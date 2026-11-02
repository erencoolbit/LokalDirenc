package org.lokaldirenc.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Yerel ağ yayını. Ağ dışından bakan için her paket aynı boyda rastgele veridir:
 * sabit kimlik, ağ kimliği veya sihirli bayt taşımaz (Bölüm 10).
 *
 * Kayıttan oynatma bir saldırganın listeye sahte adres koymasını sağlayabilir, ama
 * o adres el sıkışmayı tamamlayamaz ve parmak izi eşleşmez. Bu yüzden zaman damgası
 * (cihaz saati güvenilir değil) kullanılmaz.
 */
internal object Beacon {
    private const val NONCE = 12
    private const val TAG = 16
    private const val PLAIN = 240
    const val PACKET = NONCE + PLAIN + TAG
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Payload(val port: Int, val fp: String, val nick: String)

    fun seal(key: ByteArray, payload: Payload): ByteArray {
        val body = json.encodeToString(payload).toByteArray()
        require(body.size <= PLAIN - 2) { "yayın çok büyük" }
        val plain = ByteBuffer.allocate(PLAIN).putShort(body.size.toShort()).put(body).array()
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val c = ChaCha20Poly1305().apply { init(true, AEADParameters(KeyParameter(key), TAG * 8, nonce)) }
        val out = ByteArray(PACKET)
        System.arraycopy(nonce, 0, out, 0, NONCE)
        val len = c.processBytes(plain, 0, plain.size, out, NONCE)
        c.doFinal(out, NONCE + len)
        return out
    }

    /** Bu ağa ait olmayan veya bozuk pakette null döner. */
    fun open(key: ByteArray, packet: ByteArray, length: Int): Payload? {
        if (length != PACKET) return null
        val c = ChaCha20Poly1305().apply {
            init(false, AEADParameters(KeyParameter(key), TAG * 8, packet.copyOfRange(0, NONCE)))
        }
        val plain = ByteArray(PLAIN)
        try {
            val len = c.processBytes(packet, NONCE, PLAIN + TAG, plain, 0)
            c.doFinal(plain, len)
        } catch (_: InvalidCipherTextException) {
            return null
        }
        val size = ByteBuffer.wrap(plain).short.toInt()
        if (size !in 1..PLAIN - 2) return null
        return runCatching { json.decodeFromString<Payload>(String(plain, 2, size)) }.getOrNull()
            ?.takeIf { it.port in 1..65535 && it.fp.length == 64 }
    }
}
