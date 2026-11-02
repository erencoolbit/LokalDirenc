package org.lokaldirenc.core

/**
 * Yönetimsiz ağ kodu (Bölüm 6.3): 26 karakter Base32 = 130 bit rastgelelik.
 * Elle girişte yazım hatası sessizce başka bir ağa götürmesin diye sona 2 karakter
 * sağlama eklenir. Sağlama Network ID ve anahtar türetmeye girmez.
 */
object NetCode {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BODY = 26
    private const val QR_PREFIX = "ld://join?v=1&c="

    fun generate(): String {
        // 32 harfli alfabe: 5 bit tam eşleşir, sapma olmaz.
        val body = platformCrypto.randomBytes(BODY).map { ALPHABET[it.toInt() and 31] }.joinToString("")
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
    fun networkId(code: String): ByteArray = sha256("LD-NET-v1-U".encodeToByteArray() + body(code).encodeToByteArray())

    /** Noise el sıkışmasının ön paylaşımlı anahtarı. */
    fun psk(code: String): ByteArray = derive(code, "LD-PSK-v1")

    /** Keşif paketlerini yalnız üyelerin okuyabilmesini sağlayan anahtar. */
    fun discoveryKey(code: String): ByteArray = derive(code, "LD-DISC-v1")

    /** LoRa çerçevelerini yalnız üyelerin açabilmesini sağlayan anahtar. İstasyonda bulunmaz. */
    fun loraKey(code: String): ByteArray = derive(code, "LD-LORA-v1")

    /**
     * BLE reklamındaki dönen belirteç (80 bit). Üye olmayan için rastgele veridir, dönemler
     * arasında bağlanamaz. Dönem cihaz saatinden gelir; bu yüzden yakın dönemler de kabul edilir.
     */
    fun bleToken(discoveryKey: ByteArray, epoch: Long): ByteArray =
        platformCrypto.hmacSha256(discoveryKey, "LD-BLE-v1|$epoch".encodeToByteArray()).copyOf(BleAdvert.TOKEN)

    private fun body(code: String) = code.take(BODY)

    private fun derive(code: String, info: String): ByteArray =
        platformCrypto.hkdfSha256(body(code).encodeToByteArray(), info.encodeToByteArray(), 32)

    private fun checksum(body: String): String {
        val digest = sha256("LD-CHK-v1".encodeToByteArray() + body.encodeToByteArray())
        val bits = ((digest[0].toInt() and 0xFF) shl 2) or ((digest[1].toInt() and 0xFF) ushr 6)
        return "${ALPHABET[bits ushr 5]}${ALPHABET[bits and 31]}"
    }
}

/**
 * Bluetooth reklamı tek bir 128 bitlik hizmet kimliğine sığar: [belirteç 10][kanal 2][rol 4].
 * iOS uygulamaları reklamda "service data" yayınlayamaz, ama hizmet kimliği yayınlayabilir;
 * Android de aynısını okur. Rol etiketi, iki cihazın aynı anda birbirini aramasını önler.
 */
object BleAdvert {
    const val TOKEN = 10
    const val SIZE = 16

    class Decoded(val token: ByteArray, val psm: Int, val role: Long)

    fun encode(token: ByteArray, psm: Int, role: Int): ByteArray {
        require(token.size == TOKEN) { "belirteç boyutu" }
        require(psm in 0..0xFFFF) { "kanal numarası" }
        return ByteWriter(SIZE).bytes(token).short(psm).int(role).toByteArray()
    }

    fun decode(data: ByteArray): Decoded? {
        if (data.size != SIZE) return null
        val reader = ByteReader(data)
        return Decoded(reader.bytes(TOKEN), reader.short(), reader.int().toLong() and 0xFFFFFFFFL)
    }
}
