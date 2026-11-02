package org.lokaldirenc.core

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * LoRa için sıkı çerçeve biçimi. Bir LoRa paketi küçük ve yayın süresi kısıtlıdır, bu yüzden
 * mesh eşitlemesi konuşulmaz: zarf ikili kodlanır, parçalanır ve tek yönlü yayınlanır.
 * Teslim onayı ve yeniden gönderim yoktur.
 *
 * İstasyon ağ kodunu bilmez. Gördüğü şey yalnız şudur: [ttl][tür][şifreli veri].
 * Çalınsa bile içinden anahtar çıkmaz; yalnız TTL'ye ve çerçevenin özetine bakarak aktarır.
 */
@OptIn(ExperimentalEncodingApi::class)
object LoraCodec {
    const val MAX_FRAME = 200
    const val KIND_MESH: Byte = 1
    const val KIND_SOS: Byte = 2
    const val DEFAULT_TTL: Byte = 3
    private const val HEADER = 2
    private const val NONCE = 12
    private const val TAG = 16
    private const val FRAGMENT_HEADER = 6
    const val MAX_FRAGMENT_DATA = MAX_FRAME - HEADER - NONCE - TAG - FRAGMENT_HEADER
    const val MAX_FRAGMENTS = 8

    private val kinds = listOf(MeshEngine.KIND_PRESENCE, MeshEngine.KIND_PUBLIC, MeshEngine.KIND_DIRECT)

    /** Aynı imza doğrulansın diye zarf birebir geri kurulabilir biçimde kodlanır. */
    fun encodeWire(w: MeshWire): ByteArray {
        val kind = kinds.indexOf(w.kind)
        require(kind >= 0) { "bilinmeyen zarf türü" }
        val out = ByteWriter().byte(kind).long(w.issuedAt).byte(w.hops)
        if (w.kind != MeshEngine.KIND_DIRECT) {
            out.bytes(w.author.unhex()).bytes(w.sig.unhex())
        }
        return out.bytes(Base64.decode(w.body)).toByteArray()
    }

    fun decodeWire(data: ByteArray): MeshWire? = runCatching {
        val reader = ByteReader(data)
        val kind = kinds[reader.byte()]
        val issuedAt = reader.long()
        val hops = reader.byte()
        var author = ""
        var sig = ""
        if (kind != MeshEngine.KIND_DIRECT) {
            author = reader.bytes(32).hex()
            sig = reader.bytes(64).hex()
        }
        MeshWire(kind, issuedAt, Base64.encode(reader.bytes(reader.remaining)), author, sig, hops)
    }.getOrNull()

    /** Sığmıyorsa boş liste döner. */
    fun seal(key: ByteArray, wire: MeshWire, ttl: Byte = DEFAULT_TTL): List<ByteArray> {
        val pieces = encodeWire(wire).toList().chunked(MAX_FRAGMENT_DATA)
        if (pieces.size > MAX_FRAGMENTS) return emptyList()
        val messageId = platformCrypto.randomBytes(4)
        return pieces.mapIndexed { i, piece ->
            val plain = ByteWriter(FRAGMENT_HEADER + piece.size)
                .bytes(messageId).byte(i).byte(pieces.size).bytes(piece.toByteArray()).toByteArray()
            val nonce = platformCrypto.randomBytes(NONCE)
            byteArrayOf(ttl, KIND_MESH) + nonce + platformCrypto.aeadEncrypt(key, nonce, ByteArray(0), plain)
        }
    }

    class Fragment(val messageId: Int, val index: Int, val total: Int, val data: ByteArray)

    /** Bu ağa ait değilse, bozuksa veya başka türdeyse null. TTL kimlik doğrulamaya girmez, istasyon azaltır. */
    fun open(key: ByteArray, frame: ByteArray): Fragment? {
        if (frame.size !in HEADER + NONCE + TAG + FRAGMENT_HEADER + 1..MAX_FRAME || frame[1] != KIND_MESH) return null
        val nonce = frame.copyOfRange(HEADER, HEADER + NONCE)
        val plain = platformCrypto.aeadDecrypt(key, nonce, ByteArray(0), frame.copyOfRange(HEADER + NONCE, frame.size)) ?: return null
        val reader = ByteReader(plain)
        val id = reader.int()
        val index = reader.byte()
        val total = reader.byte()
        if (total !in 1..MAX_FRAGMENTS || index >= total) return null
        return Fragment(id, index, total, reader.bytes(reader.remaining))
    }
}

/** Parçaları toplar. Kayıp parça için yeniden isteme yoktur: mesaj süre dolunca düşer. */
class LoraReassembler(private val maxPending: Int = 32, private val ttlMs: Long = 10 * 60 * 1000L, private val now: () -> Long = ::currentTimeMillis) {
    private class Pending(val total: Int, val startedAt: Long) {
        val parts = HashMap<Int, ByteArray>()
    }

    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val pending = LinkedHashMap<String, Pending>()

    fun add(scope: String, f: LoraCodec.Fragment): ByteArray? = kotlinx.atomicfu.locks.synchronized(lock) {
        val time = now()
        pending.values.removeAll { time - it.startedAt > ttlMs }
        val key = "$scope|${f.messageId}"
        val p = pending.getOrPut(key) { Pending(f.total, time) }
        if (p.total != f.total) return null
        if (f.index !in p.parts) p.parts[f.index] = f.data
        if (p.parts.size == p.total) {
            pending.remove(key)
            val out = ByteWriter()
            (0 until p.total).forEach { out.bytes(p.parts.getValue(it)) }
            return out.toByteArray()
        }
        if (pending.size > maxPending) pending.remove(pending.keys.first())
        null
    }

    fun pendingCount() = kotlinx.atomicfu.locks.synchronized(lock) { pending.size }
}

/**
 * Afet SOS yayını. Ağ kodundan bağımsız ve herkese açıktır: enkazdaki biri için kimlik
 * doğrulaması beklenemez, bu yüzden taklit edilebilir. Konum veya kişi bilgisi taşımaz;
 * oturum numarası her başlatmada değişir.
 *
 * Bluetooth'ta tek 128 bitlik hizmet kimliği olarak yayınlanır: [sabit önek 10][sos verisi 6].
 * Sabit önek, SOS'un duyulabilmesi için "sabit kimlik yok" ilkesinin bilinçli istisnasıdır.
 */
object SosBeacon {
    val UUID_PREFIX: ByteArray = "4c442d534f5340008000".unhex()
    private const val VERSION = 1
    const val SIZE = 6
    const val UUID_SIZE = 16

    data class Signal(val session: Int, val batteryPercent: Int)

    fun encode(session: Int, batteryPercent: Int): ByteArray =
        ByteWriter(SIZE).byte(VERSION).int(session).byte(batteryPercent.coerceIn(0, 100)).toByteArray()

    fun decode(data: ByteArray?): Signal? {
        if (data == null || data.size != SIZE || data[0].toInt() != VERSION) return null
        val reader = ByteReader(data, 1)
        val session = reader.int()
        val battery = reader.byte()
        if (battery !in 0..100) return null
        return Signal(session, battery)
    }

    /** Reklamda yayınlanacak 128 bitlik hizmet kimliği. */
    fun encodeUuid(session: Int, batteryPercent: Int): ByteArray = UUID_PREFIX + encode(session, batteryPercent)

    fun decodeUuid(data: ByteArray): Signal? {
        if (data.size != UUID_SIZE || !data.copyOfRange(0, UUID_PREFIX.size).contentEquals(UUID_PREFIX)) return null
        return decode(data.copyOfRange(UUID_PREFIX.size, UUID_SIZE))
    }

    /** İstasyonun LoRa'ya aktardığı SOS: [ttl][tür][sos verisi][duyan istasyonun RSSI'ı]. */
    fun decodeLoraFrame(frame: ByteArray): Pair<Signal, Int>? {
        if (frame.size != 2 + SIZE + 1 || frame[1] != LoraCodec.KIND_SOS) return null
        val signal = decode(frame.copyOfRange(2, 2 + SIZE)) ?: return null
        return signal to frame[2 + SIZE].toInt()
    }
}
