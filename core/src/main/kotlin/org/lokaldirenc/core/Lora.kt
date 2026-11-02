package org.lokaldirenc.core

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

/**
 * LoRa için sıkı çerçeve biçimi. Bir LoRa paketi küçük ve yayın süresi kısıtlıdır, bu yüzden
 * mesh eşitlemesi konuşulmaz: zarf ikili kodlanır, parçalanır ve tek yönlü yayınlanır.
 * Teslim onayı ve yeniden gönderim yoktur.
 *
 * İstasyon ağ kodunu bilmez. Gördüğü şey yalnız şudur: [ttl][tür][şifreli veri].
 * Çalınsa bile içinden anahtar çıkmaz; yalnız TTL'ye ve çerçevenin özetine bakarak aktarır.
 */
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
    private val random = SecureRandom()

    private val kinds = listOf(MeshEngine.KIND_PRESENCE, MeshEngine.KIND_PUBLIC, MeshEngine.KIND_DIRECT)

    /** Aynı imza doğrulansın diye zarf birebir geri kurulabilir biçimde kodlanır. */
    fun encodeWire(w: MeshWire): ByteArray {
        val out = ByteArrayOutputStream()
        val kind = kinds.indexOf(w.kind)
        require(kind >= 0) { "bilinmeyen zarf türü" }
        out.write(kind)
        out.write(ByteBuffer.allocate(8).putLong(w.issuedAt).array())
        out.write(w.hops)
        if (w.kind != MeshEngine.KIND_DIRECT) {
            out.write(w.author.unhex())
            out.write(w.sig.unhex())
        }
        out.write(Base64.getDecoder().decode(w.body))
        return out.toByteArray()
    }

    fun decodeWire(data: ByteArray): MeshWire? = runCatching {
        val buffer = ByteBuffer.wrap(data)
        val kind = kinds[buffer.get().toInt()]
        val issuedAt = buffer.long
        val hops = buffer.get().toInt()
        var author = ""
        var sig = ""
        if (kind != MeshEngine.KIND_DIRECT) {
            author = ByteArray(32).also { buffer.get(it) }.hex()
            sig = ByteArray(64).also { buffer.get(it) }.hex()
        }
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
        MeshWire(kind, issuedAt, Base64.getEncoder().encodeToString(body), author, sig, hops)
    }.getOrNull()

    /** Sığmıyorsa boş liste döner. */
    fun seal(key: ByteArray, wire: MeshWire, ttl: Byte = DEFAULT_TTL): List<ByteArray> {
        val data = encodeWire(wire)
        val pieces = data.toList().chunked(MAX_FRAGMENT_DATA)
        if (pieces.size > MAX_FRAGMENTS) return emptyList()
        val messageId = ByteArray(4).also { random.nextBytes(it) }
        return pieces.mapIndexed { i, piece ->
            val plain = ByteBuffer.allocate(FRAGMENT_HEADER + piece.size)
                .put(messageId).put(i.toByte()).put(pieces.size.toByte()).put(piece.toByteArray()).array()
            val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
            val c = ChaCha20Poly1305().apply { init(true, AEADParameters(KeyParameter(key), TAG * 8, nonce)) }
            val out = ByteArray(HEADER + NONCE + plain.size + TAG)
            out[0] = ttl
            out[1] = KIND_MESH
            System.arraycopy(nonce, 0, out, HEADER, NONCE)
            val len = c.processBytes(plain, 0, plain.size, out, HEADER + NONCE)
            c.doFinal(out, HEADER + NONCE + len)
            out
        }
    }

    class Fragment(val messageId: Int, val index: Int, val total: Int, val data: ByteArray)

    /** Bu ağa ait değilse, bozuksa veya başka türdeyse null. TTL kimlik doğrulamaya girmez, istasyon azaltır. */
    fun open(key: ByteArray, frame: ByteArray): Fragment? {
        if (frame.size !in HEADER + NONCE + TAG + FRAGMENT_HEADER + 1..MAX_FRAME || frame[1] != KIND_MESH) return null
        val c = ChaCha20Poly1305().apply { init(false, AEADParameters(KeyParameter(key), TAG * 8, frame.copyOfRange(HEADER, HEADER + NONCE))) }
        val plain = ByteArray(frame.size - HEADER - NONCE - TAG)
        try {
            val len = c.processBytes(frame, HEADER + NONCE, frame.size - HEADER - NONCE, plain, 0)
            c.doFinal(plain, len)
        } catch (_: InvalidCipherTextException) {
            return null
        }
        val buffer = ByteBuffer.wrap(plain)
        val id = buffer.int
        val index = buffer.get().toInt() and 0xFF
        val total = buffer.get().toInt() and 0xFF
        if (total !in 1..MAX_FRAGMENTS || index >= total) return null
        return Fragment(id, index, total, ByteArray(buffer.remaining()).also { buffer.get(it) })
    }
}

/** Parçaları toplar. Kayıp parça için yeniden isteme yoktur: mesaj süre dolunca düşer. */
class LoraReassembler(private val maxPending: Int = 32, private val ttlMs: Long = 10 * 60 * 1000L, private val now: () -> Long = System::currentTimeMillis) {
    private class Pending(val total: Int, val startedAt: Long) {
        val parts = HashMap<Int, ByteArray>()
    }

    private val pending = LinkedHashMap<String, Pending>()

    @Synchronized
    fun add(scope: String, f: LoraCodec.Fragment): ByteArray? {
        val time = now()
        pending.values.removeIf { time - it.startedAt > ttlMs }
        val key = "$scope|${f.messageId}"
        val p = pending.getOrPut(key) { Pending(f.total, time) }
        if (p.total != f.total) return null
        p.parts.putIfAbsent(f.index, f.data)
        if (p.parts.size == p.total) {
            pending.remove(key)
            val out = ByteArrayOutputStream()
            (0 until p.total).forEach { out.write(p.parts.getValue(it)) }
            return out.toByteArray()
        }
        if (pending.size > maxPending) pending.remove(pending.keys.first())
        return null
    }

    @Synchronized
    fun pendingCount() = pending.size
}

/**
 * Afet SOS yayını. Ağ kodundan bağımsız ve herkese açıktır: enkazdaki biri için kimlik
 * doğrulaması beklenemez, bu yüzden taklit edilebilir. Konum veya kişi bilgisi taşımaz;
 * oturum numarası her başlatmada değişir.
 */
object SosBeacon {
    /** Sabit ve herkese açık tanımlayıcı: SOS'un duyulabilmesi için bilinçli istisna. */
    const val SERVICE_UUID = "4c442d53-4f53-4000-8000-000000000001"
    private const val VERSION: Byte = 1
    const val SIZE = 6

    data class Signal(val session: Int, val batteryPercent: Int)

    fun encode(session: Int, batteryPercent: Int): ByteArray =
        ByteBuffer.allocate(SIZE).put(VERSION).putInt(session).put(batteryPercent.coerceIn(0, 100).toByte()).array()

    fun decode(data: ByteArray?): Signal? {
        if (data == null || data.size != SIZE || data[0] != VERSION) return null
        val buffer = ByteBuffer.wrap(data, 1, SIZE - 1)
        val session = buffer.int
        val battery = buffer.get().toInt()
        if (battery !in 0..100) return null
        return Signal(session, battery)
    }

    /** İstasyonun LoRa'ya aktardığı SOS: [ttl][tür][sos verisi][duyan istasyonun RSSI'ı]. */
    fun decodeLoraFrame(frame: ByteArray): Pair<Signal, Int>? {
        if (frame.size != 2 + SIZE + 1 || frame[1] != LoraCodec.KIND_SOS) return null
        val signal = decode(frame.copyOfRange(2, 2 + SIZE)) ?: return null
        return signal to frame[2 + SIZE].toInt()
    }
}
