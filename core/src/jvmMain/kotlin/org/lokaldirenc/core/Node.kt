package org.lokaldirenc.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Durum deposu platforma aittir: Android'de Keystore ile şifreli (Platform Adapter). */
interface StateStore {
    fun load(): ByteArray?
    fun save(data: ByteArray)

    /** Anahtarı ve veriyi yok eder (panik silme). */
    fun destroy()

    /** Arayüzde dürüstçe gösterilecek koruma açıklaması. */
    val protection: String
}

data class NodeConfig(
    val discoveryPort: Int = 47474,
    val beaconIntervalMs: Long = 3000,
    val peerTtlMs: Long = 12000,
    val chunkSize: Int = 256 * 1024,
    val maxFileSize: Long = 2L * 1024 * 1024 * 1024,
    val promptTimeoutMs: Long = 120_000,
    val ioTimeoutMs: Int = 30_000,
    val handshakeTimeoutMs: Int = 10_000,
    val maxSessions: Int = 8,
    /** Yayına ek olarak doğrudan gönderilecek hedefler (emülatör ve testler için). */
    val extraBeaconTargets: List<InetSocketAddress> = emptyList(),
    val meshIntervalMs: Long = 3000,
    /** Yeni zarf olmasa da eşlerle yeniden karşılaştırma aralığı. */
    val meshResyncMs: Long = 30_000,
    /** Tüm durum her yazımda yeniden şifrelenir; sık yazmak pil harcar. Kapanışta da yazılır. */
    val meshFlushMs: Long = 10_000,
)

enum class TransferStatus { PREPARING, CONNECTING, PAIRING, AWAIT_ACCEPT, WAITING_PEER, TRANSFERRING, VERIFYING, DONE, REJECTED, FAILED }

val TransferStatus.isFinal get() = this == TransferStatus.DONE || this == TransferStatus.REJECTED || this == TransferStatus.FAILED

@Serializable
data class Transfer(
    val id: String,
    val networkId: String,
    val outgoing: Boolean,
    val peerFp: String = "",
    val peerNick: String = "",
    val fileName: String,
    val size: Long = 0,
    val fileHash: String = "",
    val status: TransferStatus,
    val chunksDone: Int = 0,
    val chunksTotal: Int = 0,
    val resumedChunks: Int = 0,
    val warnings: List<String> = emptyList(),
    val detectedType: String = "",
    val quarantined: Boolean = false,
    val storedPath: String? = null,
    val sourcePath: String? = null,
    val peerHost: String? = null,
    val peerPort: Int = 0,
    val error: String? = null,
    val createdAt: Long,
    @Transient val sas: String? = null,
    @Transient val needsPairing: Boolean = false,
    @Transient val needsAccept: Boolean = false,
)

data class Peer(val networkId: String, val host: String, val port: Int, val fingerprint: String?, val nick: String, val trusted: Boolean, val lastSeen: Long)

@Serializable
data class StoredNetwork(val code: String, val name: String, val staticKey: String, val createdAt: Long, val meshKey: String = "")

@Serializable
data class TrustedPeer(val networkId: String, val fingerprint: String, val nick: String)

@Serializable
data class NodeState(
    val nick: String = "",
    val networks: List<StoredNetwork> = emptyList(),
    val trusted: List<TrustedPeer> = emptyList(),
    val blockedPeers: List<String> = emptyList(),
    val blockedHashes: List<String> = emptyList(),
    val history: List<Transfer> = emptyList(),
    val retentionHours: Int = 24 * 7,
    val mesh: Map<String, MeshPersist> = emptyMap(),
    val bluetoothEnabled: Boolean = false,
)

data class NetworkInfo(val id: String, val code: String, val name: String, val fingerprint: String, val port: Int)

/**
 * Bir cihazın yönetimsiz ağ düğümü: keşif, el sıkışma, aktarım ve yerel durum.
 * Hiçbir dış sunucuya bağlanmaz. Kayıtlarda dosya içeriği, ağ kodu ve anahtar bulunmaz.
 */
class Node(private val dir: File, private val store: StateStore, val config: NodeConfig = NodeConfig()) {
    private val lock = Any()
    private var state: NodeState = store.load()?.let { runCatching { JSON.decodeFromString<NodeState>(String(it)) }.getOrNull() } ?: NodeState()
    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val pairPrompts = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val acceptPrompts = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val runtimes = ConcurrentHashMap<String, NetRuntime>()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val activeParts = ConcurrentHashMap.newKeySet<String>()
    private val sessionSlots = Semaphore(config.maxSessions)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "ld-worker").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private var discoverySocket: DatagramSocket? = null
    private val mesh = MeshEngine({ it in state.blockedPeers })
    private val meshSyncing = ConcurrentHashMap.newKeySet<String>()
    private val meshLastSync = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val handshakes = HashMap<String, Int>()
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ld-deadline").apply { isDaemon = true } }

    /** Eş başına görülen adresler. Yalnız el sıkışmayla doğrulanan adres listede kalıcı olur. */
    private val addressSeen = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val verifiedAddress = ConcurrentHashMap<String, String>()

    @Volatile
    var onChange: () -> Unit = {}

    private val inbox get() = File(dir, "inbox")
    private val quarantine get() = File(dir, "quarantine")
    private val partial get() = File(dir, "partial")
    val outbox get() = File(dir, "outbox")

    val protection get() = store.protection

    private inner class NetRuntime(val network: StoredNetwork) {
        val id = NetCode.networkId(network.code).hex()
        val idBytes = NetCode.networkId(network.code)
        val psk = NetCode.psk(network.code)
        val discKey = NetCode.discoveryKey(network.code)
        val loraKey = NetCode.loraKey(network.code)
        val staticPriv = network.staticKey.unhex()
        val fingerprint = sha256(Noise.publicOf(staticPriv)).hex()
        val server = ServerSocket(0)

        fun start() {
            pool.execute {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    val source = socket.inetAddress?.hostAddress ?: "?"
                    if (!enterHandshake(source)) {
                        runCatching { socket.close() }
                        continue
                    }
                    pool.execute { serveIncoming(this, SocketLink(socket), source) }
                }
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        listOf(inbox, quarantine, partial, outbox).forEach { it.mkdirs() }
        // Önceki oturumda yarım kalanlar kapanıştan sonra sürdürülemez sayılır.
        synchronized(lock) {
            state = state.copy(history = state.history.map { if (it.status.isFinal) it else it.copy(status = TransferStatus.FAILED, error = "Uygulama kapandı") })
        }
        state.history.forEach { transfers[it.id] = it }
        mesh.load(state.mesh)
        if (state.networks.any { it.meshKey.isEmpty() }) {
            mutate { s -> s.copy(networks = s.networks.map { if (it.meshKey.isEmpty()) it.copy(meshKey = MeshEngine.generateSigningKey().hex()) else it }) }
        }
        state.networks.forEach { startRuntime(it) }
        purgeExpired()
        startDiscovery()
        pool.execute {
            while (running.get()) {
                sleep(60 * 60 * 1000L)
                if (running.get()) purgeExpired()
            }
        }
        pool.execute {
            while (running.get()) {
                runCatching { meshTick() }
                sleep(config.meshIntervalMs)
            }
        }
        pool.execute {
            while (running.get()) {
                sleep(config.meshFlushMs)
                if (running.get()) flushMesh()
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        flushMesh()
        runtimes.values.forEach { runCatching { it.server.close() } }
        runtimes.clear()
        discoverySocket?.close()
        pool.shutdownNow()
        deadlines.shutdownNow()
    }

    private fun startRuntime(n: StoredNetwork): NetRuntime {
        val rt = NetRuntime(n)
        runtimes[rt.id] = rt
        mesh.register(rt.id, rt.idBytes, n.meshKey.unhex(), rt.staticPriv, state.nick)
        rt.start()
        return rt
    }

    // ---- Ağ yönetimi ----

    fun createNetwork(name: String): NetworkInfo = addNetwork(NetCode.generate(), name)

    /** Geçersiz kodda [IllegalArgumentException] atar. */
    fun joinNetwork(codeOrQr: String, name: String): NetworkInfo = addNetwork(NetCode.parse(codeOrQr), name)

    private fun addNetwork(code: String, name: String): NetworkInfo {
        val id = NetCode.networkId(code).hex()
        runtimes[id]?.let { return info(it) }
        // Ağ başına ayrı takma anahtar: ağlar arası bağlanamaz (Bölüm 7.2).
        val n = StoredNetwork(code, name.take(40).ifBlank { "Ağ ${id.take(4)}" }, Noise.generatePrivate().hex(), System.currentTimeMillis(), MeshEngine.generateSigningKey().hex())
        mutate { it.copy(networks = it.networks + n) }
        val rt = startRuntime(n)
        notifyChange()
        return info(rt)
    }

    /** Ağdan çıkış her zaman serbesttir; kimlik ve bu ağın aktarım kayıtları silinir. */
    fun leaveNetwork(id: String) {
        runtimes.remove(id)?.let { runCatching { it.server.close() } }
        peers.keys.removeIf { it.startsWith("$id|") }
        transfers.values.filter { it.networkId == id }.forEach { transfers.remove(it.id) }
        mesh.remove(id)
        mutate { s ->
            s.copy(
                networks = s.networks.filter { NetCode.networkId(it.code).hex() != id },
                trusted = s.trusted.filter { it.networkId != id },
                history = s.history.filter { it.networkId != id },
                mesh = s.mesh - id,
            )
        }
        notifyChange()
    }

    private fun info(rt: NetRuntime) = NetworkInfo(rt.id, rt.network.code, rt.network.name, rt.fingerprint, rt.server.localPort)

    fun networks(): List<NetworkInfo> = runtimes.values.sortedBy { it.network.createdAt }.map { info(it) }

    fun peers(networkId: String): List<Peer> {
        val now = System.currentTimeMillis()
        val s = state
        return peers.values
            .filter { it.networkId == networkId && now - it.lastSeen <= config.peerTtlMs && it.fingerprint !in s.blockedPeers }
            .map { p -> p.copy(trusted = s.trusted.any { it.networkId == networkId && it.fingerprint == p.fingerprint }) }
            .sortedBy { it.nick }
    }

    fun transfers(): List<Transfer> = transfers.values.sortedByDescending { it.createdAt }

    fun snapshot(): NodeState = state

    fun setNick(nick: String) {
        mutate { it.copy(nick = sanitizeNick(nick)) }
        runtimes.keys.forEach { mesh.announce(it, state.nick) }
        notifyChange()
    }

    // ---- Mesh mesajlaşma ----

    /** [remote] ise bağlı LoRa cihazından da yayınlanır. Yerel sohbet LoRa'ya çıkmaz. */
    fun sendPublicMessage(networkId: String, text: String, remote: Boolean = false): Boolean {
        val wire = mesh.publishPublic(networkId, text)
        if (wire != null && remote) lastLoraFailed = !sendOverLora(networkId, wire)
        notifyChange()
        return wire != null
    }

    fun sendDirectMessage(networkId: String, contactId: String, text: String, remote: Boolean = false): Boolean {
        val wire = mesh.publishDirect(networkId, contactId, text)
        if (wire != null && remote) lastLoraFailed = !sendOverLora(networkId, wire)
        notifyChange()
        return wire != null
    }

    // ---- LoRa köprüsü ----

    /** Bağlı LoRa cihazına çerçeve yazar. Bağlı cihaz yoksa null. */
    @Volatile
    var loraSink: ((ByteArray) -> Unit)? = null

    /** Son uzak gönderim LoRa'ya çıkamadı: hak doldu veya mesaj sığmıyor. Mesaj yerelde yine yayılır. */
    @Volatile
    var lastLoraFailed = false
        private set

    private val loraReassembler = LoraReassembler()
    private val loraSentAt = ArrayDeque<Long>()
    private val loraAnnounced = ConcurrentHashMap.newKeySet<String>()

    /** Yerel hak: cihazdaki yayın süresi sınırına ek olarak kanalı tek telefon tüketmesin. */
    @Synchronized
    fun loraBudgetLeft(): Int {
        val cutoff = System.currentTimeMillis() - LORA_WINDOW_MS
        while (loraSentAt.isNotEmpty() && loraSentAt.first() < cutoff) loraSentAt.removeFirst()
        return LORA_MAX_FRAMES - loraSentAt.size
    }

    fun loraFrameCount(networkId: String, wire: MeshWire): Int =
        runtimes[networkId]?.let { LoraCodec.seal(it.loraKey, wire).size } ?: 0

    /** Hak dolmuşsa veya mesaj sığmıyorsa false. Mesaj yerel ağda yine de yayılır. */
    @Synchronized
    private fun sendOverLora(networkId: String, wire: MeshWire): Boolean {
        val sink = loraSink ?: return false
        val rt = runtimes[networkId] ?: return false
        val wires = ArrayList<MeshWire>()
        // Uzak taraf özel mesaj yanıtlayabilsin diye kimlik duyurusu oturumda bir kez gider.
        if (loraAnnounced.add(networkId)) mesh.selfPresence(networkId)?.let { wires += it }
        wires += wire
        val frames = wires.flatMap { LoraCodec.seal(rt.loraKey, it) }
        if (frames.isEmpty() || frames.size > loraBudgetLeft()) {
            loraAnnounced.remove(networkId)
            return false
        }
        val time = System.currentTimeMillis()
        frames.forEach {
            loraSentAt.addLast(time)
            sink(it)
        }
        return true
    }

    /** LoRa'dan gelen çerçeve. Bu ağların hiçbirine ait değilse yok sayılır. */
    fun receiveLoraFrame(frame: ByteArray) {
        for (rt in runtimes.values) {
            val fragment = LoraCodec.open(rt.loraKey, frame) ?: continue
            val data = loraReassembler.add(rt.id, fragment) ?: return
            val wire = LoraCodec.decodeWire(data) ?: return
            mesh.ingest(rt.id, wire.copy(hops = (wire.hops - 1).coerceAtLeast(0)))
            notifyChange()
            return
        }
    }

    fun meshMessages(networkId: String): List<MeshMessage> = mesh.messages(networkId)

    fun meshContacts(networkId: String): List<MeshContact> = mesh.contacts(networkId).filter { it.id !in state.blockedPeers }

    /** Yalnız kanıtlanmış kutu anahtarı, ilk eşleşmede kodu onaylanmış kişiye aitse doğrulanmış sayılır. */
    fun isContactVerified(networkId: String, contact: MeshContact): Boolean =
        contact.proven && state.trusted.any { it.networkId == networkId && it.fingerprint == sha256(contact.box.unhex()).hex() }

    fun meshCarried(networkId: String): Int = mesh.carriedCount(networkId)

    fun meshOwnId(networkId: String): String? = mesh.ownId(networkId)

    fun blockContact(contact: MeshContact) {
        mutate { it.copy(blockedPeers = (it.blockedPeers + contact.id + sha256(contact.box.unhex()).hex()).distinct()) }
        mesh.dropAuthor(contact.id)
        notifyChange()
    }

    // ---- Platform taşımaları (Bluetooth vb.) ----

    fun setBluetoothEnabled(enabled: Boolean) {
        mutate { it.copy(bluetoothEnabled = enabled) }
        notifyChange()
    }

    /** [epoch] etrafındaki dönemlerin belirteçleri; saat farkına tolerans için. */
    fun bleTokens(networkId: String, epoch: Long, tolerance: Int): List<ByteArray> {
        val rt = runtimes[networkId] ?: return emptyList()
        return (-tolerance..tolerance).map { NetCode.bleToken(rt.discKey, epoch + it) }
    }

    fun meshVersion(networkId: String): Long = mesh.version(networkId)

    /** Gelen bağlantıyı karşılar ve bitene kadar bekler. Bağlantıyı kapatır. */
    fun acceptLink(networkId: String, link: Link) {
        val rt = runtimes[networkId]
        if (rt == null || !enterHandshake("bluetooth")) {
            link.close()
            return
        }
        serveIncoming(rt, link, "bluetooth")
        link.close()
    }

    /** Bağlantı üzerinden mesh eşitlemesi yapar. Başarısızsa istisna atar; bağlantıyı kapatır. */
    fun syncOverLink(networkId: String, link: Link) {
        val rt = runtimes[networkId]
        if (rt == null) {
            link.close()
            return
        }
        link.setTimeout(config.handshakeTimeoutMs)
        try {
            SecureChannel.handshake(link, true, rt.staticPriv, rt.psk, rt.idBytes).use { ch ->
                if (ch.remoteFingerprint in state.blockedPeers) return
                exchangeHello(ch, PURPOSE_MESH)
                meshExchange(ch, rt, true)
            }
        } finally {
            link.close()
        }
    }

    private fun flushMesh() {
        if (!mesh.dirty) return
        val exported = mesh.export()
        mutate { it.copy(mesh = exported) }
    }

    /** Görünen her eşle, yeni zarf varsa veya uzun süredir karşılaştırılmadıysa eşitlenir. */
    private fun meshTick() {
        val time = System.currentTimeMillis()
        for (rt in runtimes.values) {
            if (mesh.presenceAge(rt.id) > PRESENCE_REFRESH_MS) mesh.announce(rt.id, state.nick)
            val version = mesh.version(rt.id)
            for (peer in peers(rt.id)) {
                val fp = peer.fingerprint ?: continue
                val peerKey = "${rt.id}|$fp"
                val seen = addressSeen[peerKey] ?: continue
                seen.values.removeIf { time - it > config.peerTtlMs }
                // Doğrulanmamış adresler de denenir: eş taşındıysa böyle bulunur, sahteyse el sıkışma düşer.
                for (address in seen.keys.take(MAX_ADDRESSES_TRIED)) {
                    val key = "$peerKey|$address"
                    val last = meshLastSync[key]
                    val unchanged = last != null && last.first == version && time - last.second < config.meshResyncMs
                    if (unchanged) continue
                    if (!meshSyncing.add(key)) continue
                    if (!sessionSlots.tryAcquire()) {
                        meshSyncing.remove(key)
                        continue
                    }
                    meshLastSync[key] = version to time
                    pool.execute {
                        try {
                            meshSyncTo(rt, peerKey, fp, address)
                        } catch (_: Exception) {
                            // Eş gitmiş olabilir veya adres sahte; bir sonraki turda yeniden denenir.
                        } finally {
                            sessionSlots.release()
                            meshSyncing.remove(key)
                        }
                    }
                }
            }
        }
    }

    private fun meshSyncTo(rt: NetRuntime, peerKey: String, fp: String, address: String) {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toInt()
        val socket = Socket()
        socket.soTimeout = config.handshakeTimeoutMs
        socket.connect(InetSocketAddress(host, port), config.handshakeTimeoutMs)
        SecureChannel.handshake(socket, true, rt.staticPriv, rt.psk, rt.idBytes).use { ch ->
            if (ch.remoteFingerprint != fp || fp in state.blockedPeers) return
            // Canlı doğrulanmış adres korunur: eş iki adresten ulaşılabilirse liste oynamasın.
            val current = verifiedAddress[peerKey]
            val currentAlive = current != null && current != address &&
                System.currentTimeMillis() - (addressSeen[peerKey]?.get(current) ?: 0) <= config.peerTtlMs
            if (!currentAlive) {
                verifiedAddress[peerKey] = address
                peers.computeIfPresent(peerKey) { _, p -> p.copy(host = host, port = port) }
            }
            exchangeHello(ch, PURPOSE_MESH)
            meshExchange(ch, rt, true)
        }
    }

    /** İki taraf sırayla gönderir, böylece TCP tamponları dolunca kilitlenme olmaz. */
    private fun meshExchange(ch: SecureChannel, rt: NetRuntime, initiator: Boolean) {
        ch.setTimeout(config.ioTimeoutMs)
        val mine = mesh.inventory(rt.id)
        // Önce yalnız özet: boşta geçen eşitleme tüm kimlik listesini taşımasın.
        val digest = sha256(mine.sorted().joinToString("").toByteArray()).hex()
        ch.send(Msg.MeshInv(emptyList(), mesh.selfPresence(rt.id), digest))
        val first = ch.receiveControl() as? Msg.MeshInv ?: throw ProtocolException("Envanter bekleniyordu")
        mesh.acceptLinkPresence(rt.id, first.self, ch.remoteFingerprint)
        if (first.digest == digest) {
            if (initiator) ch.send(Msg.MeshEnd) else runCatching { ch.receiveControl() }
            notifyChange()
            return
        }
        ch.send(Msg.MeshInv(mine))
        val theirs = ch.receiveControl() as? Msg.MeshInv ?: throw ProtocolException("Envanter bekleniyordu")
        if (theirs.ids.size > MESH_MAX_INVENTORY) throw ProtocolException("Envanter çok büyük")
        val wanted = (mine.toSet() - theirs.ids.toSet()).take(MESH_MAX_PUSH)
        fun push() {
            mesh.outgoing(rt.id, wanted).chunked(MESH_PUSH_BATCH).forEach { ch.send(Msg.MeshPush(it)) }
            ch.send(Msg.MeshEnd)
        }
        fun pull() {
            var received = 0
            while (true) {
                when (val m = ch.receiveControl()) {
                    is Msg.MeshPush -> {
                        received += m.items.size
                        if (received > MESH_MAX_PUSH) throw ProtocolException("Çok fazla zarf")
                        m.items.forEach { mesh.ingest(rt.id, it) }
                    }
                    is Msg.MeshEnd -> return
                    else -> throw ProtocolException("Beklenmeyen mesaj")
                }
            }
        }
        if (initiator) {
            push()
            pull()
            ch.send(Msg.MeshEnd)
        } else {
            pull()
            push()
            // Bluetooth'ta erken kapatmak yoldaki son paketi düşürebilir: karşı taraf onaylayana kadar bekle.
            runCatching { ch.receiveControl() }
        }
        notifyChange()
    }

    fun setRetentionHours(hours: Int) {
        mutate { it.copy(retentionHours = hours) }
        purgeExpired()
        notifyChange()
    }

    fun blockPeer(fingerprint: String) {
        mutate { it.copy(blockedPeers = (it.blockedPeers + fingerprint).distinct()) }
        notifyChange()
    }

    fun unblockPeer(fingerprint: String) {
        mutate { it.copy(blockedPeers = it.blockedPeers - fingerprint) }
        notifyChange()
    }

    fun blockHash(hash: String) {
        mutate { it.copy(blockedHashes = (it.blockedHashes + hash).distinct()) }
        notifyChange()
    }

    fun unblockHash(hash: String) {
        mutate { it.copy(blockedHashes = it.blockedHashes - hash) }
        notifyChange()
    }

    fun confirmPairing(id: String, ok: Boolean) {
        pairPrompts.remove(id)?.complete(ok)
    }

    fun respondOffer(id: String, accept: Boolean) {
        acceptPrompts.remove(id)?.complete(accept)
    }

    /** Karantinadaki dosyayı kullanıcı onayıyla normal alana taşır. Dosya yine açılmaz. */
    fun releaseFromQuarantine(id: String) {
        val t = transfers[id] ?: return
        val path = t.storedPath ?: return
        if (!t.quarantined) return
        val target = uniqueFile(inbox, t.fileName)
        if (!File(path).renameTo(target)) return
        update(id) { it.copy(storedPath = target.path, quarantined = false) }
        persistHistory(id)
    }

    /** Kaydı ve alınan dosyayı siler. */
    fun deleteTransfer(id: String) {
        val t = transfers.remove(id) ?: return
        t.storedPath?.let { File(it).delete() }
        t.sourcePath?.let { File(it).delete() }
        mutate { s -> s.copy(history = s.history.filter { it.id != id }) }
        notifyChange()
    }

    /**
     * Panik silme: önce şifreleme anahtarı yok edilir, sonra dosyalar silinir. Uçucu olmayan
     * bellekte alınan dosyaların fiziksel olarak geri getirilemeyeceği garanti edilmez.
     */
    fun panicWipe() {
        stop()
        store.destroy()
        dir.deleteRecursively()
        transfers.clear()
        peers.clear()
        state = NodeState()
    }

    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - state.retentionHours * 3_600_000L
        listOf(inbox, quarantine, partial, outbox).forEach { folder ->
            folder.listFiles()?.filter { it.lastModified() < cutoff && it.name !in activeParts }?.forEach { it.delete() }
        }
        transfers.values.filter { it.status.isFinal && it.createdAt < cutoff }.forEach { transfers.remove(it.id) }
        mesh.purge(state.retentionHours * 3_600_000L)
        mutate { s -> s.copy(history = s.history.filter { it.createdAt >= cutoff }) }
        notifyChange()
    }

    // ---- Keşif ----

    private fun startDiscovery() {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(config.discoveryPort))
        }
        discoverySocket = socket
        pool.execute { receiveBeacons(socket) }
        pool.execute {
            while (running.get()) {
                sendBeacons(socket)
                sleep(config.beaconIntervalMs)
            }
        }
    }

    private fun receiveBeacons(socket: DatagramSocket) {
        val buffer = ByteArray(Beacon.PACKET + 1)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: IOException) {
                if (socket.isClosed) return
                continue
            }
            for (rt in runtimes.values) {
                val p = Beacon.open(rt.discKey, packet.data, packet.length) ?: continue
                if (p.fp == rt.fingerprint) break
                val key = "${rt.id}|${p.fp}"
                val host = packet.address.hostAddress ?: ""
                val address = "$host:${p.port}"
                val time = System.currentTimeMillis()
                val seen = addressSeen.getOrPut(key) { ConcurrentHashMap() }
                seen[address] = time
                // Keşif paketi kayıttan oynatılabilir. Doğrulanmış adres canlıyken başka adres
                // onun yerine geçmez; yeni adres ancak el sıkışmayı geçerse kabul edilir.
                val verified = verifiedAddress[key]
                if (verified != null && verified != address && time - (seen[verified] ?: 0) <= config.peerTtlMs) break
                val before = peers[key]
                peers[key] = Peer(rt.id, host, p.port, p.fp, sanitizeNick(p.nick), false, time)
                if (before == null) notifyChange()
                break
            }
        }
    }

    private fun sendBeacons(socket: DatagramSocket) {
        val targets = broadcastAddresses().map { InetSocketAddress(it, config.discoveryPort) } + config.extraBeaconTargets
        for (rt in runtimes.values) {
            val packet = Beacon.seal(rt.discKey, Beacon.Payload(rt.server.localPort, rt.fingerprint, state.nick))
            for (target in targets) {
                runCatching { socket.send(DatagramPacket(packet, packet.size, target)) }
            }
        }
    }

    /** Wi-Fi ve hotspot arayüzlerinin yayın adresleri. Donanım tanıtıcısı okunmaz. */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
            .distinct()
    }.getOrDefault(emptyList())

    /** Elle adres girişi: yayını engelleyen ağlarda kullanılır. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.broadcast != null }
            .mapNotNull { it.address.hostAddress }
    }.getOrDefault(emptyList())

    // ---- Gönderen ----

    /** [file] uygulamanın kopyasıdır; aktarım bitince silinir, yarım kalırsa [retry] için durur. */
    fun send(networkId: String, host: String, port: Int, expectedFp: String?, file: File, displayName: String): String {
        val rt = runtimes[networkId] ?: throw IllegalArgumentException("Ağ bulunamadı")
        val t = Transfer(
            id = UUID.randomUUID().toString(), networkId = networkId, outgoing = true, peerFp = expectedFp ?: "",
            fileName = FileSafety.sanitizeName(displayName), size = file.length(), status = TransferStatus.PREPARING,
            sourcePath = file.path, peerHost = host, peerPort = port, createdAt = System.currentTimeMillis(),
        )
        transfers[t.id] = t
        notifyChange()
        launchSender(rt, t, file)
        return t.id
    }

    /** Yarım kalan gönderimi yeniden başlatır; alıcı elindeki parçaları istemez. */
    fun retry(id: String) {
        val t = transfers[id] ?: return
        if (!t.outgoing || !t.status.isFinal || t.status == TransferStatus.DONE) return
        val file = t.sourcePath?.let { File(it) }?.takeIf { it.exists() } ?: return
        val rt = runtimes[t.networkId] ?: return
        // Eş adresini değiştirmiş olabilir: en son görülen adres tercih edilir.
        val latest = peers["${t.networkId}|${t.peerFp}"]
        val updated = t.copy(status = TransferStatus.PREPARING, error = null, peerHost = latest?.host ?: t.peerHost, peerPort = latest?.port ?: t.peerPort, chunksDone = 0, resumedChunks = 0)
        transfers[id] = updated
        notifyChange()
        launchSender(rt, updated, file)
    }

    private fun launchSender(rt: NetRuntime, t: Transfer, file: File) {
        pool.execute {
            sessionSlots.acquireUninterruptibly()
            try {
                runSender(rt, t, file)
            } catch (e: Exception) {
                finish(t.id, TransferStatus.FAILED, describe(e))
            } finally {
                sessionSlots.release()
            }
        }
    }

    private fun runSender(rt: NetRuntime, initial: Transfer, file: File) {
        val id = initial.id
        val manifest = Manifest.build(file, config.chunkSize)
        update(id) { it.copy(fileHash = manifest.fileHash, chunksTotal = manifest.chunkHashes.size, status = TransferStatus.CONNECTING) }
        val socket = Socket()
        socket.soTimeout = config.handshakeTimeoutMs
        socket.connect(InetSocketAddress(initial.peerHost, initial.peerPort), config.handshakeTimeoutMs)
        SecureChannel.handshake(socket, true, rt.staticPriv, rt.psk, rt.idBytes).use { ch ->
            if (initial.peerFp.isNotEmpty() && initial.peerFp != ch.remoteFingerprint) {
                throw ProtocolException("Karşı tarafın kimliği değişmiş")
            }
            if (ch.remoteFingerprint in state.blockedPeers) throw ProtocolException("Bu kişi engellenmiş")
            update(id) { it.copy(peerFp = ch.remoteFingerprint, sas = ch.sas) }
            val hello = exchangeHello(ch, PURPOSE_TRANSFER)
            update(id) { it.copy(peerNick = sanitizeNick(hello.nick)) }
            pair(ch, rt, id)

            update(id) { it.copy(status = TransferStatus.WAITING_PEER) }
            val head = FileSafety.readHead(file)
            ch.send(
                Msg.Offer(
                    transfers.getValue(id).fileName, file.length(), FileSafety.sniff(head), manifest.fileHash,
                    manifest.chunkSize, manifest.chunkHashes, manifest.root,
                ),
            )
            ch.setTimeout(config.promptTimeoutMs.toInt() + config.ioTimeoutMs)
            when (val reply = ch.receiveControl()) {
                is Msg.Reject -> {
                    file.delete()
                    finish(id, TransferStatus.REJECTED, "Alıcı reddetti")
                    return
                }
                is Msg.Accept -> update(id) { it.copy(status = TransferStatus.TRANSFERRING, resumedChunks = reply.have, chunksDone = reply.have) }
                is Msg.Cancel -> throw ProtocolException("Alıcı iptal etti")
                else -> throw ProtocolException("Beklenmeyen yanıt")
            }
            ch.setTimeout(config.ioTimeoutMs)
            serveChunks(ch, id, file, manifest)
        }
    }

    private fun serveChunks(ch: SecureChannel, id: String, file: File, manifest: Manifest) {
        val buffer = ByteArray(manifest.chunkSize)
        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                when (val m = ch.receiveControl()) {
                    is Msg.Get -> {
                        for (i in m.indices) {
                            if (i !in manifest.chunkHashes.indices) throw ProtocolException("Geçersiz parça isteği")
                            val len = Manifest.readChunk(raf, i, manifest.chunkSize, buffer)
                            ch.sendChunk(i, buffer, len)
                        }
                        update(id) { it.copy(chunksDone = minOf(it.chunksDone + m.indices.size, it.chunksTotal)) }
                    }
                    is Msg.Done -> {
                        raf.close() // Windows açık dosyayı silmez.
                        file.delete()
                        finish(id, TransferStatus.DONE, null)
                        return
                    }
                    is Msg.Cancel -> throw ProtocolException("Alıcı iptal etti")
                    else -> throw ProtocolException("Beklenmeyen mesaj")
                }
            }
        }
    }

    // ---- Alıcı ----

    /**
     * Kimliği doğrulanmamış bağlantılar oturum hakkı tüketemez: ağ kodunu bilmeyen biri boş
     * bağlantılarla hizmeti durdurmasın. Kaynak başına ve toplam sınır vardır.
     */
    private fun enterHandshake(source: String): Boolean = synchronized(handshakes) {
        val count = handshakes[source] ?: 0
        if (count >= MAX_HANDSHAKES_PER_SOURCE || handshakes.values.sum() >= MAX_HANDSHAKES) return false
        handshakes[source] = count + 1
        true
    }

    private fun leaveHandshake(source: String) = synchronized(handshakes) {
        val count = (handshakes[source] ?: 1) - 1
        if (count <= 0) handshakes.remove(source) else handshakes[source] = count
    }

    private fun serveIncoming(rt: NetRuntime, link: Link, source: String) {
        val ch = try {
            authenticate(rt, link)
        } finally {
            leaveHandshake(source)
        } ?: return
        if (!sessionSlots.tryAcquire()) {
            ch.close()
            return
        }
        try {
            runReceiver(rt, ch)
        } finally {
            sessionSlots.release()
            ch.close()
        }
    }

    /**
     * Kesin süre sınırı: okuma zaman aşımı her baytta yenilendiği için tek başına yetmez,
     * yoksa bayt bayt gönderen biri bağlantıyı dakikalarca tutabilir.
     * Kodu bilmeyen düğüme hiçbir bilgi verilmez ve kayıt tutulmaz.
     */
    private fun authenticate(rt: NetRuntime, link: Link): SecureChannel? {
        link.setTimeout(config.handshakeTimeoutMs)
        val alarm = deadlines.schedule({ link.close() }, config.handshakeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        return try {
            SecureChannel.handshake(link, false, rt.staticPriv, rt.psk, rt.idBytes)
        } catch (_: Exception) {
            link.close()
            null
        } finally {
            alarm.cancel(false)
        }
    }

    private fun runReceiver(rt: NetRuntime, ch: SecureChannel) {
        var id: String? = null
        try {
            ch.use {
                // Engellenen kişi fark edilmeden kapatılır.
                if (ch.remoteFingerprint in state.blockedPeers) return
                val hello = exchangeHello(ch, PURPOSE_TRANSFER)
                if (hello.purpose == PURPOSE_MESH) {
                    // Ağ kodu üyeliği kanıtlar; zarflar kendi imza veya mührünü taşır. Soru sorulmaz.
                    meshExchange(ch, rt, false)
                    return
                }
                val t = Transfer(
                    id = UUID.randomUUID().toString(), networkId = rt.id, outgoing = false, peerFp = ch.remoteFingerprint,
                    peerNick = sanitizeNick(hello.nick), fileName = "", status = TransferStatus.CONNECTING, sas = ch.sas,
                    createdAt = System.currentTimeMillis(),
                )
                id = t.id
                transfers[t.id] = t
                pair(ch, rt, t.id)
                ch.setTimeout(config.ioTimeoutMs)
                val offer = ch.receiveControl() as? Msg.Offer ?: throw ProtocolException("Teklif bekleniyordu")
                receiveFile(ch, rt, t.id, offer)
            }
        } catch (e: Exception) {
            id?.let { finish(it, TransferStatus.FAILED, describe(e)) }
        }
    }

    private fun validate(o: Msg.Offer) {
        val hexRe = Regex("^[0-9a-f]{64}$")
        if (!hexRe.matches(o.fileHash) || !hexRe.matches(o.root)) throw ProtocolException("Geçersiz özet")
        if (o.chunkSize !in 16 * 1024..1024 * 1024) throw ProtocolException("Geçersiz parça boyutu")
        if (o.size < 0) throw ProtocolException("Geçersiz boyut")
        if (Manifest.chunkCount(o.size, o.chunkSize) != o.chunkHashes.size.toLong()) throw ProtocolException("Parça sayısı tutmuyor")
        if (!o.chunkHashes.all { hexRe.matches(it) }) throw ProtocolException("Geçersiz parça özeti")
        if (Manifest.merkleRoot(o.chunkHashes) != o.root) throw ProtocolException("Merkle kökü tutmuyor")
    }

    private fun receiveFile(ch: SecureChannel, rt: NetRuntime, id: String, offer: Msg.Offer) {
        val name = FileSafety.sanitizeName(offer.name)
        update(id) { it.copy(fileName = name, size = offer.size, chunksTotal = offer.chunkHashes.size) }
        validate(offer)
        update(id) { it.copy(fileHash = offer.fileHash) }

        fun reject(reason: String, shown: String) {
            ch.send(Msg.Reject(reason))
            finish(id, TransferStatus.REJECTED, shown)
        }
        if (offer.fileHash in state.blockedHashes) return reject("declined", "Engelli dosya özeti")
        if (offer.size > config.maxFileSize) return reject("too_large", "Dosya boyut sınırını aşıyor")
        partial.mkdirs()
        if (partial.usableSpace < offer.size + 16 * 1024 * 1024) return reject("no_space", "Depolama alanı yetersiz")

        // Bir üye sürekli teklif açıp ekranı istemlerle dolduramasın.
        if (acceptPrompts.size >= MAX_PROMPTS) return reject("busy", "Çok fazla bekleyen istek")
        val warnings = ArrayList<String>()
        if (FileSafety.isRiskyName(name)) warnings += "Riskli dosya uzantısı: .${FileSafety.extension(name)}"
        update(id) { it.copy(status = TransferStatus.AWAIT_ACCEPT, needsAccept = true, warnings = warnings) }
        val prompt = CompletableFuture<Boolean>()
        acceptPrompts[id] = prompt
        notifyChange()
        val accepted = await(prompt)
        acceptPrompts.remove(id)
        update(id) { it.copy(needsAccept = false) }
        if (!accepted) return reject("declined", "Reddedildi")

        val partName = "${rt.id.take(16)}_${offer.fileHash}.part"
        if (!activeParts.add(partName)) return reject("busy", "Aynı dosya zaten alınıyor")
        try {
            val part = File(partial, partName)
            val have = RandomAccessFile(part, "rw").use { raf -> prepareResume(raf, offer) }
            update(id) { it.copy(status = TransferStatus.TRANSFERRING, chunksDone = have.size, resumedChunks = have.size) }
            ch.send(Msg.Accept(have.size))
            val missing = offer.chunkHashes.indices.filterNot { it in have }
            RandomAccessFile(part, "rw").use { raf -> pull(ch, id, raf, offer, missing) }

            update(id) { it.copy(status = TransferStatus.VERIFYING) }
            if (Manifest.fileHash(part) != offer.fileHash) {
                part.delete()
                ch.send(Msg.Cancel("hash"))
                throw ProtocolException("Dosya özeti tutmuyor")
            }
            place(ch, id, part, name, offer)
        } finally {
            activeParts.remove(partName)
        }
    }

    private fun prepareResume(raf: RandomAccessFile, offer: Msg.Offer): Set<Int> {
        val existing = raf.length()
        raf.setLength(offer.size)
        if (existing == 0L) return emptySet()
        val buffer = ByteArray(offer.chunkSize)
        val have = HashSet<Int>()
        for (i in offer.chunkHashes.indices) {
            val len = Manifest.readChunk(raf, i, offer.chunkSize, buffer)
            if (Manifest.leaf(buffer, len).hex() == offer.chunkHashes[i]) have += i
        }
        return have
    }

    private fun pull(ch: SecureChannel, id: String, raf: RandomAccessFile, offer: Msg.Offer, missing: List<Int>) {
        val retries = HashMap<Int, Int>()
        val queue = ArrayDeque(missing)
        while (queue.isNotEmpty()) {
            val batch = List(minOf(BATCH, queue.size)) { queue.removeFirst() }
            ch.send(Msg.Get(batch))
            for (expected in batch) {
                val chunk = ch.receive() as? Incoming.Chunk ?: throw ProtocolException("Parça bekleniyordu")
                if (chunk.index != expected) throw ProtocolException("Parça sırası bozuk")
                val expectedLen = minOf(offer.chunkSize.toLong(), offer.size - expected.toLong() * offer.chunkSize).toInt()
                val valid = chunk.data.size == expectedLen && Manifest.leaf(chunk.data).hex() == offer.chunkHashes[expected]
                if (valid) {
                    raf.seek(expected.toLong() * offer.chunkSize)
                    raf.write(chunk.data)
                    update(id) { it.copy(chunksDone = it.chunksDone + 1) }
                } else {
                    val count = (retries[expected] ?: 0) + 1
                    retries[expected] = count
                    if (count > MAX_CHUNK_RETRIES) {
                        ch.send(Msg.Cancel("bad_chunk"))
                        throw ProtocolException("Parça doğrulanamadı")
                    }
                    queue.addLast(expected)
                }
            }
        }
    }

    private fun place(ch: SecureChannel, id: String, part: File, name: String, offer: Msg.Offer) {
        val verdict = FileSafety.inspect(part, name)
        val warnings = ArrayList(verdict.warnings)
        if (offer.type != verdict.detectedType && offer.type != "zip" && verdict.detectedType != "zip") {
            warnings += "Gönderenin beyan ettiği tür (${FileSafety.sanitizeName(offer.type).take(16)}) içerikle uyuşmuyor"
        }
        val quarantined = verdict.quarantine || warnings.size > verdict.warnings.size
        val target = if (quarantined) uniqueFile(quarantine, "$name.karantina") else uniqueFile(inbox, name)
        target.parentFile?.mkdirs()
        if (!part.renameTo(target)) throw IOException("Dosya taşınamadı")
        update(id) { it.copy(storedPath = target.path, quarantined = quarantined, warnings = warnings.distinct(), detectedType = verdict.detectedType) }
        ch.send(Msg.Done)
        finish(id, TransferStatus.DONE, null)
    }

    private fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        var n = 1
        while (candidate.exists()) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            candidate = File(folder, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        // Temizleme yalnız ad bileşeni bıraktığı için bu kontrol ikinci savunma hattıdır.
        check(candidate.canonicalFile.parentFile == folder.canonicalFile) { "hedef dizin dışı" }
        return candidate
    }

    // ---- Ortak adımlar ----

    private fun exchangeHello(ch: SecureChannel, purpose: String): Msg.Hello {
        ch.send(Msg.Hello(PROTOCOL_VERSION, state.nick, purpose))
        val hello = ch.receiveControl() as? Msg.Hello ?: throw ProtocolException("Selamlaşma bekleniyordu")
        if (hello.proto != PROTOCOL_VERSION) throw ProtocolException("Protokol sürümü uyumsuz")
        return hello
    }

    /** İlk eşleşme: iki tarafta aynı kod görünüyorsa arada kimse yoktur (Bölüm 9, adım 2). */
    private fun pair(ch: SecureChannel, rt: NetRuntime, id: String) {
        val fp = ch.remoteFingerprint
        val known = state.trusted.any { it.networkId == rt.id && it.fingerprint == fp }
        if (!known) {
            if (pairPrompts.size >= MAX_PROMPTS) throw ProtocolException("Çok fazla bekleyen istek")
            update(id) { it.copy(status = TransferStatus.PAIRING, needsPairing = true) }
            val prompt = CompletableFuture<Boolean>()
            pairPrompts[id] = prompt
            notifyChange()
            val ok = await(prompt)
            pairPrompts.remove(id)
            update(id) { it.copy(needsPairing = false) }
            if (!ok) {
                ch.send(Msg.Cancel("sas"))
                throw ProtocolException("Doğrulama kodu onaylanmadı")
            }
            val nick = transfers[id]?.peerNick ?: ""
            mutate { s -> s.copy(trusted = s.trusted + TrustedPeer(rt.id, fp, nick)) }
        }
        ch.send(Msg.PairOk)
        ch.setTimeout(config.promptTimeoutMs.toInt() + config.ioTimeoutMs)
        when (ch.receiveControl()) {
            is Msg.PairOk -> Unit
            is Msg.Cancel -> throw ProtocolException("Karşı taraf doğrulama kodunu onaylamadı")
            else -> throw ProtocolException("Beklenmeyen mesaj")
        }
    }

    private fun await(prompt: CompletableFuture<Boolean>): Boolean = try {
        prompt.get(config.promptTimeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        false
    }

    private fun update(id: String, change: (Transfer) -> Transfer) {
        transfers.computeIfPresent(id) { _, t -> change(t) }
        notifyChange()
    }

    private fun finish(id: String, status: TransferStatus, error: String?) {
        update(id) { it.copy(status = status, error = error, needsPairing = false, needsAccept = false) }
        persistHistory(id)
        // Yanıt bekleyen istem varsa artık geçersizdir.
        pairPrompts.remove(id)?.complete(false)
        acceptPrompts.remove(id)?.complete(false)
    }

    private fun persistHistory(id: String) {
        val t = transfers[id] ?: return
        // Her başarısız deneme bir kayıt: sınırsız büyürse şifreli durum dosyası şişer.
        mutate { s -> s.copy(history = (s.history.filter { it.id != id } + t).takeLast(MAX_HISTORY)) }
        val finished = transfers.values.filter { it.status.isFinal }
        if (finished.size > MAX_HISTORY) {
            finished.sortedBy { it.createdAt }.take(finished.size - MAX_HISTORY).forEach { transfers.remove(it.id) }
        }
        notifyChange()
    }

    private fun mutate(change: (NodeState) -> NodeState) {
        synchronized(lock) {
            state = change(state)
            store.save(JSON.encodeToString(state).toByteArray())
        }
    }

    private fun notifyChange() {
        runCatching { onChange() }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun sanitizeNick(nick: String) = nick.filter { !it.isISOControl() && it.code !in 0x202A..0x202E && it.code !in 0x2066..0x2069 }.trim().take(32)

    private fun describe(e: Exception): String = when (e) {
        is ProtocolException -> e.message ?: "Protokol hatası"
        is NoiseException -> "Güvenli bağlantı kurulamadı (ağ kodu veya sürüm farklı olabilir)"
        is java.net.SocketTimeoutException -> "Zaman aşımı"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "Karşı cihaza ulaşılamadı"
        is java.io.EOFException, is java.net.SocketException -> "Bağlantı koptu"
        else -> "Aktarım hatası"
    }

    companion object {
        private const val BATCH = 16
        private const val MAX_CHUNK_RETRIES = 3
        private const val MESH_MAX_INVENTORY = 2000
        private const val MESH_MAX_PUSH = 1000
        private const val MESH_PUSH_BATCH = 100
        private const val PRESENCE_REFRESH_MS = 24L * 3_600_000
        private const val LORA_WINDOW_MS = 10L * 60_000
        const val LORA_MAX_FRAMES = 20
        const val MAX_HANDSHAKES = 16
        const val MAX_HANDSHAKES_PER_SOURCE = 4
        const val MAX_PROMPTS = 4
        const val MAX_HISTORY = 200
        private const val MAX_ADDRESSES_TRIED = 4
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
