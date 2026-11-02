package org.lokaldirenc.core

private const val HEX = "0123456789abcdef"

internal fun ByteArray.hex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 15])
    }
    return out.toString()
}

/** Geçersiz girdide [IllegalArgumentException] atar. */
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

/** Ağ bayt sırasıyla (büyük uçlu) yazar. */
internal class ByteWriter(capacity: Int = 64) {
    private var buffer = ByteArray(capacity)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun byte(v: Int): ByteWriter {
        ensure(1)
        buffer[size++] = v.toByte()
        return this
    }

    fun short(v: Int): ByteWriter = byte(v ushr 8).byte(v)

    fun int(v: Int): ByteWriter = byte(v ushr 24).byte(v ushr 16).byte(v ushr 8).byte(v)

    fun long(v: Long): ByteWriter = int((v ushr 32).toInt()).int(v.toInt())

    fun bytes(v: ByteArray): ByteWriter {
        ensure(v.size)
        v.copyInto(buffer, size)
        size += v.size
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** Ağ bayt sırasıyla okur. Yetersiz veride [IndexOutOfBoundsException] atar. */
internal class ByteReader(private val data: ByteArray, private var position: Int = 0) {
    val remaining get() = data.size - position

    fun byte(): Int {
        if (position >= data.size) throw IndexOutOfBoundsException("veri bitti")
        return data[position++].toInt() and 0xFF
    }

    fun short(): Int = (byte() shl 8) or byte()

    fun int(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()

    fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xFFFFFFFFL)

    fun bytes(size: Int): ByteArray {
        if (size < 0 || position + size > data.size) throw IndexOutOfBoundsException("veri bitti")
        return data.copyOfRange(position, position + size).also { position += size }
    }
}

/** Uzunluk önekli alanlar: alan sınırı kaydırılarak aynı imza başka anlama getirilemez. */
internal object Canon {
    fun fields(vararg values: String): ByteArray {
        val out = ByteWriter()
        for (v in values) {
            val b = v.encodeToByteArray()
            out.int(b.size).bytes(b)
        }
        return out.toByteArray()
    }
}
