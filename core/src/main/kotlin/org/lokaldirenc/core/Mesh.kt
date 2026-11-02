package org.lokaldirenc.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

/** Ağ üzerinde taşınan zarf. Taşıyan cihaz bunu doğrular, saklar ve iletir. */
@Serializable
data class MeshWire(
    val kind: String,
    val issuedAt: Long,
    val body: String,
    val author: String = "",
    val sig: String = "",
    val hops: Int,
)

@Serializable
data class MeshContact(
    val id: String,
    val box: String,
    val nick: String,
    val issuedAt: Long,
    /** Kutu anahtarının sahipliği kanıtlandı: doğrudan bağlantıda veya mühürlü mesajda. */
    val proven: Boolean = false,
)

@Serializable
data class MeshMessage(
    val id: String,
    val direct: Boolean,
    val fromId: String,
    val fromNick: String,
    val toId: String? = null,
    val text: String,
    val at: Long,
    val hops: Int,
    val outgoing: Boolean,
)

@Serializable
data class MeshStored(val id: String, val wire: MeshWire, val receivedAt: Long)

@Serializable
data class MeshPersist(
    val envelopes: List<MeshStored> = emptyList(),
    val seen: List<String> = emptyList(),
    val contacts: List<MeshContact> = emptyList(),
    val messages: List<MeshMessage> = emptyList(),
)

/**
 * Taşı-ve-ilet mesh: her cihaz zarfları saklar ve karşılaştığı üyelere iletir. Ağ içinde
 * yönlendirme tablosu yoktur; sınırlı atlamalı salgın yayılımdır. Bu yüzden teslimat
 * garanti değildir ve onay alınmaz.
 *
 * Herkese açık mesaj ve kimlik duyurusu Ed25519 ile imzalıdır. Ağ kodunu bilen her üye
 * bunları okuyabilir. Özel mesaj Noise_X ile mühürlüdür: alıcı, gönderen ve içerik ara
 * cihazlara görünmez; alıcı her mührü açmayı dener.
 */
class MeshEngine(private val isBlocked: (String) -> Boolean, private val now: () -> Long = System::currentTimeMillis) {
    private class Identity(networkId: ByteArray, edPrivate: ByteArray, val boxPriv: ByteArray) {
        val networkId = networkId
        val ed = Ed25519PrivateKeyParameters(edPrivate, 0)
        val edPub = ed.generatePublicKey().encoded
        val id = sha256(edPub).hex()
        val boxPub = Noise.publicOf(boxPriv)
    }

    private class Net(val identity: Identity, persisted: MeshPersist?) {
        val envelopes = LinkedHashMap<String, MeshStored>().apply { persisted?.envelopes?.forEach { put(it.id, it) } }
        val seen = LinkedHashSet(persisted?.seen ?: emptyList())
        val contacts = HashMap<String, MeshContact>().apply { persisted?.contacts?.forEach { put(it.id, it) } }
        val messages = ArrayList(persisted?.messages ?: emptyList())
        var version = 0L
        var selfPresence: MeshWire? = null
        var ownNick = ""
    }

    @Serializable
    private data class Presence(val box: String, val nick: String)

    @Serializable
    private data class PublicBody(val text: String)

    /** Gönderenin imzalı duyurusu içeride: alıcı, kutu anahtarının sahibini böyle kanıtlar. */
    @Serializable
    private data class DirectBody(val presence: MeshWire, val text: String)

    private val nets = HashMap<String, Net>()
    private var persisted: Map<String, MeshPersist> = emptyMap()

    @Volatile
    var dirty = false
        private set

    @Synchronized
    fun load(state: Map<String, MeshPersist>) {
        persisted = state
    }

    @Synchronized
    fun register(networkId: String, networkIdBytes: ByteArray, edPrivate: ByteArray, boxPrivate: ByteArray, nick: String) {
        val net = Net(Identity(networkIdBytes, edPrivate, boxPrivate), persisted[networkId])
        nets[networkId] = net
        announce(networkId, nick)
    }

    @Synchronized
    fun remove(networkId: String) {
        nets.remove(networkId)
        dirty = true
    }

    @Synchronized
    fun announce(networkId: String, nick: String) {
        val net = nets[networkId] ?: return
        net.ownNick = nick
        val body = JSON.encodeToString(Presence(net.identity.boxPub.hex(), nick)).toByteArray()
        val wire = signed(net, KIND_PRESENCE, body)
        net.selfPresence = wire
        ingest(networkId, wire)
    }

    @Synchronized
    fun presenceAge(networkId: String): Long = now() - (nets[networkId]?.selfPresence?.issuedAt ?: 0)

    private fun signed(net: Net, kind: String, body: ByteArray): MeshWire {
        val issuedAt = now()
        val encoded = Base64.getEncoder().encodeToString(body)
        val signer = Ed25519Signer()
        signer.init(true, net.identity.ed)
        val message = canonical(net.identity.networkId, kind, issuedAt, encoded)
        signer.update(message, 0, message.size)
        return MeshWire(kind, issuedAt, encoded, net.identity.edPub.hex(), signer.generateSignature().hex(), MAX_HOPS)
    }

    private fun canonical(networkId: ByteArray, kind: String, issuedAt: Long, body: String): ByteArray =
        "LD-MESH-1|".toByteArray() + networkId + "|$kind|$issuedAt|$body".toByteArray()

    private fun envelopeId(net: Net, w: MeshWire): String =
        sha256(canonical(net.identity.networkId, w.kind, w.issuedAt, w.body) + w.author.toByteArray() + w.sig.toByteArray()).hex()

    private fun verified(net: Net, w: MeshWire): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(w.author.unhex(), 0))
        val message = canonical(net.identity.networkId, w.kind, w.issuedAt, w.body)
        signer.update(message, 0, message.size)
        signer.verifySignature(w.sig.unhex())
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IndexOutOfBoundsException) {
        false
    }

    private fun decode(w: MeshWire): ByteArray? = runCatching { Base64.getDecoder().decode(w.body) }.getOrNull()

    private fun cleanText(text: String) =
        text.filter { (!it.isISOControl() || it == '\n') && it.code !in 0x202A..0x202E && it.code !in 0x2066..0x2069 }.trim().take(MAX_TEXT)

    private fun cleanNick(nick: String) =
        nick.filter { !it.isISOControl() && it.code !in 0x202A..0x202E && it.code !in 0x2066..0x2069 }.trim().take(32)

    /** Gelen zarfı doğrular ve saklar. Yeni ve geçerliyse true. Geçersiz zarf iletilmez. */
    @Synchronized
    fun ingest(networkId: String, w: MeshWire): Boolean {
        val net = nets[networkId] ?: return false
        val time = now()
        if (w.body.length > MAX_BODY || w.hops !in 0..MAX_HOPS) return false
        // Saat güvenilmez, bu yalnız bir tutarlılık sınırıdır: çok eski zarflar geri dönmesin.
        if (w.issuedAt < time - MAX_AGE_MS || w.issuedAt > time + MAX_FUTURE_MS) return false
        val id = envelopeId(net, w)
        if (id in net.seen) return false
        val accepted = when (w.kind) {
            KIND_PRESENCE -> verified(net, w) && acceptPresence(net, w)
            KIND_PUBLIC -> verified(net, w) && acceptPublic(net, id, w)
            KIND_DIRECT -> {
                if (w.author.isNotEmpty() || w.sig.isNotEmpty()) false else {
                    tryOpenDirect(net, id, w)
                    true
                }
            }
            else -> false
        }
        if (!accepted) return false
        remember(net, id)
        // Engellenen içerik gösterilmez ve başkasına aktarılmaz (Bölüm 13.1).
        if (w.author.isNotEmpty() && isBlocked(sha256(w.author.unhex()).hex())) return false
        if (w.kind == KIND_PRESENCE) {
            net.envelopes.values.removeIf { it.wire.kind == KIND_PRESENCE && it.wire.author == w.author }
        }
        net.envelopes[id] = MeshStored(id, w, time)
        enforceQuota(net, w)
        net.version++
        dirty = true
        return true
    }

    private fun remember(net: Net, id: String) {
        net.seen.add(id)
        val extra = net.seen.size - MAX_SEEN
        if (extra > 0) net.seen.take(extra).forEach { net.seen.remove(it) }
    }

    private fun enforceQuota(net: Net, newest: MeshWire) {
        // Yazar kotası: tek bir üye herkesin deposunu dolduramasın. Mühürlü zarfların yazarı
        // görünmediği için onlara ayrı toplam kota uygulanır.
        val sameSource = net.envelopes.values.filter { it.wire.author == newest.author && it.wire.kind == newest.kind }
        val limit = if (newest.kind == KIND_DIRECT) MAX_DIRECT_STORED else MAX_PER_AUTHOR
        sameSource.take((sameSource.size - limit).coerceAtLeast(0)).forEach { net.envelopes.remove(it.id) }
        val overflow = net.envelopes.size - MAX_STORED
        if (overflow > 0) net.envelopes.keys.take(overflow).forEach { net.envelopes.remove(it) }
    }

    private fun acceptPresence(net: Net, w: MeshWire): Boolean {
        val p = parsePresence(w) ?: return false
        val id = sha256(w.author.unhex()).hex()
        val existing = net.contacts[id]
        if (id != net.identity.id && (existing == null || existing.issuedAt < w.issuedAt)) {
            // Yeni bir kutu anahtarı duyurulursa sahiplik kanıtı yeniden gerekir.
            val proven = existing?.proven == true && existing.box == p.box
            if (existing == null && net.contacts.size >= MAX_CONTACTS) {
                // Sahte kimlik seliyle liste şişmesin: önce kanıtsız en eskisi gider, kanıtlılar korunur.
                val oldest = net.contacts.values.filter { !it.proven }.minByOrNull { it.issuedAt } ?: return true
                net.contacts.remove(oldest.id)
            }
            net.contacts[id] = MeshContact(id, p.box, cleanNick(p.nick), w.issuedAt, proven)
        }
        return true
    }

    private fun parsePresence(w: MeshWire): Presence? {
        if (w.kind != KIND_PRESENCE) return null
        val raw = decode(w) ?: return null
        val p = runCatching { JSON.decodeFromString<Presence>(String(raw)) }.getOrNull() ?: return null
        if (p.box.length != 64 || runCatching { p.box.unhex() }.isFailure) return null
        return p
    }

    private fun acceptPublic(net: Net, id: String, w: MeshWire): Boolean {
        val raw = decode(w) ?: return false
        val body = runCatching { JSON.decodeFromString<PublicBody>(String(raw)) }.getOrNull() ?: return false
        val text = cleanText(body.text)
        if (text.isEmpty()) return false
        val from = sha256(w.author.unhex()).hex()
        if (isBlocked(from)) return true
        val mine = from == net.identity.id
        net.messages += MeshMessage(id, false, from, net.contacts[from]?.nick ?: "", null, text, now(), MAX_HOPS - w.hops, mine)
        trimMessages(net)
        return true
    }

    private fun tryOpenDirect(net: Net, id: String, w: MeshWire) {
        val sealed = decode(w) ?: return
        val opened = try {
            Noise.open(net.identity.boxPriv, sealPrologue(net), sealed)
        } catch (_: NoiseException) {
            return
        }
        val plain = unpad(opened.plain) ?: return
        val body = runCatching { JSON.decodeFromString<DirectBody>(String(plain)) }.getOrNull() ?: return
        if (!verified(net, body.presence)) return
        val presence = parsePresence(body.presence) ?: return
        // Gönderen anahtarı Noise ile kanıtlı; imzalı duyuruda aynı anahtar yazmalı.
        if (presence.box != opened.senderPub.hex()) return
        val from = sha256(body.presence.author.unhex()).hex()
        val existing = net.contacts[from]
        val nick = cleanNick(presence.nick)
        if (existing == null || existing.issuedAt <= body.presence.issuedAt) {
            net.contacts[from] = MeshContact(from, presence.box, nick, body.presence.issuedAt, true)
        } else if (existing.box == presence.box) {
            net.contacts[from] = existing.copy(proven = true)
        }
        val text = cleanText(body.text)
        if (text.isEmpty() || isBlocked(from)) return
        net.messages += MeshMessage(id, true, from, net.contacts[from]?.nick ?: nick, net.identity.id, text, now(), MAX_HOPS - w.hops, false)
        trimMessages(net)
        dirty = true
    }

    private fun sealPrologue(net: Net) = "LD-MESH-1|".toByteArray() + net.identity.networkId

    private fun trimMessages(net: Net) {
        val extra = net.messages.size - MAX_MESSAGES
        if (extra > 0) net.messages.subList(0, extra).clear()
    }

    /** Boyut sızmasın diye düz metin 512 baytın katına doldurulur. */
    private fun pad(data: ByteArray): ByteArray {
        val total = ((data.size + 2 + PAD_UNIT - 1) / PAD_UNIT) * PAD_UNIT
        return ByteBuffer.allocate(total).putShort(data.size.toShort()).put(data).array()
    }

    private fun unpad(data: ByteArray): ByteArray? {
        if (data.size < 2) return null
        val size = ByteBuffer.wrap(data).short.toInt() and 0xFFFF
        if (size > data.size - 2) return null
        return data.copyOfRange(2, 2 + size)
    }

    fun sendPublic(networkId: String, text: String): Boolean = publishPublic(networkId, text) != null

    fun sendDirect(networkId: String, contactId: String, text: String): Boolean = publishDirect(networkId, contactId, text) != null

    /** Gönderilen zarfı döner; LoRa gibi başka bir yoldan da yayınlanabilsin diye. */
    @Synchronized
    fun publishPublic(networkId: String, text: String): MeshWire? {
        val net = nets[networkId] ?: return null
        val clean = cleanText(text)
        if (clean.isEmpty()) return null
        val wire = signed(net, KIND_PUBLIC, JSON.encodeToString(PublicBody(clean)).toByteArray())
        return wire.takeIf { ingest(networkId, it) }
    }

    @Synchronized
    fun publishDirect(networkId: String, contactId: String, text: String): MeshWire? {
        val net = nets[networkId] ?: return null
        val contact = net.contacts[contactId] ?: return null
        val clean = cleanText(text)
        val presence = net.selfPresence
        if (clean.isEmpty() || presence == null) return null
        val plain = JSON.encodeToString(DirectBody(presence, clean)).toByteArray()
        val sealed = Noise.seal(contact.box.unhex(), net.identity.boxPriv, sealPrologue(net), pad(plain))
        // Karıştırılmış zaman: sıra ve gönderim anı ara cihazlara daha az şey söylesin.
        val issuedAt = now() - RANDOM.nextInt(JITTER_MS)
        val wire = MeshWire(KIND_DIRECT, issuedAt, Base64.getEncoder().encodeToString(sealed), hops = MAX_HOPS)
        if (!ingest(networkId, wire)) return null
        net.messages += MeshMessage(envelopeId(net, wire), true, net.identity.id, net.ownNick, contactId, clean, now(), 0, true)
        trimMessages(net)
        dirty = true
        return wire
    }

    /**
     * Eşitleme oturumunda karşı tarafın kendi duyurusu. Noise statik anahtarı duyurudaki
     * kutu anahtarıyla eşleşiyorsa anahtarın sahibi kanıtlanmıştır. Başkasının kutu anahtarını
     * kendi duyurusuna yazan üye, "doğrulandı" etiketini böylece alamaz.
     */
    @Synchronized
    fun acceptLinkPresence(networkId: String, w: MeshWire?, linkFingerprint: String) {
        val net = nets[networkId] ?: return
        if (w == null || !verified(net, w)) return
        val p = parsePresence(w) ?: return
        ingest(networkId, w)
        if (sha256(p.box.unhex()).hex() != linkFingerprint) return
        val id = sha256(w.author.unhex()).hex()
        net.contacts[id]?.takeIf { it.box == p.box && !it.proven }?.let {
            net.contacts[id] = it.copy(proven = true)
            dirty = true
            net.version++
        }
    }

    /** İletilebilir zarfların kimlikleri. */
    @Synchronized
    fun inventory(networkId: String): List<String> =
        nets[networkId]?.envelopes?.values?.filter { it.wire.hops > 0 }?.map { it.id } ?: emptyList()

    /** Karşı tarafın istediği zarflar; atlama hakkı bir azaltılmış olarak. */
    @Synchronized
    fun outgoing(networkId: String, ids: Collection<String>): List<MeshWire> {
        val net = nets[networkId] ?: return emptyList()
        return ids.mapNotNull { id -> net.envelopes[id]?.wire?.takeIf { it.hops > 0 }?.let { it.copy(hops = it.hops - 1) } }
    }

    @Synchronized
    fun selfPresence(networkId: String): MeshWire? = nets[networkId]?.selfPresence

    @Synchronized
    fun version(networkId: String): Long = nets[networkId]?.version ?: 0

    @Synchronized
    fun contacts(networkId: String): List<MeshContact> = nets[networkId]?.contacts?.values?.sortedBy { it.nick } ?: emptyList()

    @Synchronized
    fun messages(networkId: String): List<MeshMessage> {
        val net = nets[networkId] ?: return emptyList()
        return net.messages.map { m -> if (m.fromNick.isEmpty()) m.copy(fromNick = net.contacts[m.fromId]?.nick ?: "") else m }
    }

    @Synchronized
    fun carriedCount(networkId: String): Int = nets[networkId]?.envelopes?.values?.count { it.wire.kind != KIND_PRESENCE } ?: 0

    @Synchronized
    fun ownId(networkId: String): String? = nets[networkId]?.identity?.id

    /** Engellenen yazarın saklanan içeriği ve görünen mesajları kaldırılır. */
    @Synchronized
    fun dropAuthor(id: String) {
        for (net in nets.values) {
            net.envelopes.values.removeIf { it.wire.author.isNotEmpty() && sha256(it.wire.author.unhex()).hex() == id }
            net.messages.removeIf { it.fromId == id }
            net.version++
        }
        dirty = true
    }

    @Synchronized
    fun purge(retentionMs: Long) {
        val time = now()
        for (net in nets.values) {
            net.envelopes.values.removeIf { time - it.receivedAt > MAX_AGE_MS || time - it.wire.issuedAt > MAX_AGE_MS }
            net.messages.removeIf { time - it.at > retentionMs }
        }
        dirty = true
    }

    @Synchronized
    fun export(): Map<String, MeshPersist> {
        dirty = false
        return nets.mapValues { (_, n) ->
            MeshPersist(n.envelopes.values.toList(), n.seen.toList(), n.contacts.values.toList(), n.messages.toList())
        }
    }

    companion object {
        const val MAX_HOPS = 8
        const val MAX_TEXT = 1000
        const val MAX_AGE_MS = 72L * 3_600_000
        private const val MAX_FUTURE_MS = 24L * 3_600_000
        private const val MAX_BODY = 8 * 1024
        const val MAX_STORED = 500
        const val MAX_PER_AUTHOR = 100
        const val MAX_DIRECT_STORED = 300
        const val MAX_CONTACTS = 1000
        private const val MAX_SEEN = 3000
        private const val MAX_MESSAGES = 500
        private const val PAD_UNIT = 512
        private const val JITTER_MS = 60_000
        const val KIND_PRESENCE = "presence"
        const val KIND_PUBLIC = "public"
        const val KIND_DIRECT = "direct"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val RANDOM = SecureRandom()

        fun generateSigningKey(): ByteArray = Ed25519PrivateKeyParameters(SecureRandom()).encoded
    }
}
