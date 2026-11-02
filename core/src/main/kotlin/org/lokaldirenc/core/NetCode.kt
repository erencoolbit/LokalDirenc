package org.lokaldirenc.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Yönetimsiz ağ kodu (Bölüm 6.3): 26 karakter Base32 = 130 bit rastgelelik.
 * Elle girişte yazım hatası sessizce başka bir ağa götürmesin diye sona 2 karakter
 * sağlama eklenir. Sağlama Network ID ve anahtar türetmeye girmez.
 */
object NetCode {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BODY = 26
    private const val QR_PREFIX = "ld://join?v=1&c="

    fun generate(random: SecureRandom = SecureRandom()): String {
        val body = String(CharArray(BODY) { ALPHABET[random.nextInt(ALPHABET.length)] })
        return body + checksum(body)
    }

    /** Boşluk, tire ve küçük harfe izin verir. Geçersiz kodda [IllegalArgumentException] atar. */
    fun normalize(input: String): String {
        val clean = input.filterNot { it == '-' || it.isWhitespace() }
            .map { if (it == 'ı' || it == 'i') 'I' else it.uppercaseChar() }
            .joinToString("")
        require(clean.length == BODY + 2) { "Ağ kodu ${BODY + 2} karakter olmalı" }
        require(clean.all { it in ALPHABET }) { "Ağ kodunda geçersiz karakter var" }
        require(checksum(clean.take(BODY)) == clean.takeLast(2)) { "Ağ kodu hatalı yazılmış" }
        return clean
    }

    fun format(code: String): String = code.chunked(4).joinToString("-")

    fun qrPayload(code: String): String = QR_PREFIX + code

    /** QR içeriğinden veya elle yazılmış metinden kodu çıkarır. */
    fun parse(text: String): String = normalize(text.trim().removePrefix(QR_PREFIX))

    /** Network ID = Hash("LD-NET-v1-U" + ağ kodu). Kamuya açık sayılır, koddan geri dönülemez. */
    fun networkId(code: String): ByteArray = sha256("LD-NET-v1-U".toByteArray() + body(code).toByteArray())

    /** Noise el sıkışmasının ön paylaşımlı anahtarı. */
    fun psk(code: String): ByteArray = derive(code, "LD-PSK-v1")

    /** Keşif paketlerini yalnız üyelerin okuyabilmesini sağlayan anahtar. */
    fun discoveryKey(code: String): ByteArray = derive(code, "LD-DISC-v1")

    /** LoRa çerçevelerini yalnız üyelerin açabilmesini sağlayan anahtar. İstasyonda bulunmaz. */
    fun loraKey(code: String): ByteArray = derive(code, "LD-LORA-v1")

    /**
     * BLE reklamındaki 128 bitlik dönen belirteç. Üye olmayan için rastgele veridir, dönemler
     * arasında bağlanamaz. Dönem cihaz saatinden gelir; bu yüzden yakın dönemler de kabul edilir.
     */
    fun bleToken(discoveryKey: ByteArray, epoch: Long): ByteArray {
        val mac = org.bouncycastle.crypto.macs.HMac(SHA256Digest())
        mac.init(org.bouncycastle.crypto.params.KeyParameter(discoveryKey))
        val message = "LD-BLE-v1|$epoch".toByteArray()
        mac.update(message, 0, message.size)
        return ByteArray(32).also { mac.doFinal(it, 0) }.copyOf(16)
    }

    private fun body(code: String) = code.take(BODY)

    private fun derive(code: String, info: String): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(body(code).toByteArray(), null, info.toByteArray()))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, 32) }
    }

    private fun checksum(body: String): String {
        val digest = sha256("LD-CHK-v1".toByteArray() + body.toByteArray())
        val bits = ((digest[0].toInt() and 0xFF) shl 2) or ((digest[1].toInt() and 0xFF) ushr 6)
        return "${ALPHABET[bits ushr 5]}${ALPHABET[bits and 31]}"
    }
}

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun String.unhex(): ByteArray {
    require(length % 2 == 0) { "hex uzunluğu" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Kısa ve okunabilir gösterim: parmak izi ve doğrulama kodu için. */
internal fun base32(data: ByteArray, chars: Int): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val out = StringBuilder()
    var buffer = 0
    var bits = 0
    for (b in data) {
        buffer = (buffer shl 8) or (b.toInt() and 0xFF)
        bits += 8
        while (bits >= 5 && out.length < chars) {
            bits -= 5
            out.append(alphabet[(buffer ushr bits) and 31])
        }
        buffer = buffer and 0xFF
        if (out.length == chars) break
    }
    return out.toString()
}
