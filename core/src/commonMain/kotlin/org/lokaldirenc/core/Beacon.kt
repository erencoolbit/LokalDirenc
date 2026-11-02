package org.lokaldirenc.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Payload(val port: Int, val fp: String, val nick: String)

    fun seal(key: ByteArray, payload: Payload): ByteArray {
        val body = json.encodeToString(payload).encodeToByteArray()
        require(body.size <= PLAIN - 2) { "yayın çok büyük" }
        val plain = ByteArray(PLAIN)
        ByteWriter(2).short(body.size).toByteArray().copyInto(plain)
        body.copyInto(plain, 2)
        val nonce = platformCrypto.randomBytes(NONCE)
        return nonce + platformCrypto.aeadEncrypt(key, nonce, ByteArray(0), plain)
    }

    /** Bu ağa ait olmayan veya bozuk pakette null döner. */
    fun open(key: ByteArray, packet: ByteArray, length: Int): Payload? {
        if (length != PACKET) return null
        val plain = platformCrypto.aeadDecrypt(key, packet.copyOfRange(0, NONCE), ByteArray(0), packet.copyOfRange(NONCE, PACKET)) ?: return null
        val size = ByteReader(plain).short()
        if (size !in 1..PLAIN - 2) return null
        return runCatching { json.decodeFromString<Payload>(plain.decodeToString(2, 2 + size)) }.getOrNull()
            ?.takeIf { it.port in 1..65535 && it.fp.length == 64 }
    }
}
