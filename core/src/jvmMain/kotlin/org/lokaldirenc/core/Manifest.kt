package org.lokaldirenc.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Dosyanın parça özetleri, Merkle kökü ve tam özeti (Bölüm 9, adım 6). */
internal class Manifest(val fileHash: String, val chunkSize: Int, val chunkHashes: List<String>, val root: String) {
    companion object {
        fun build(file: File, chunkSize: Int): Manifest {
            val whole = MessageDigest.getInstance("SHA-256")
            val hashes = ArrayList<String>()
            val buffer = ByteArray(chunkSize)
            file.inputStream().use { input ->
                while (true) {
                    val len = input.readNBytesCompat(buffer)
                    if (len == 0) break
                    whole.update(buffer, 0, len)
                    hashes.add(leaf(buffer, len).hex())
                }
            }
            return Manifest(whole.digest().hex(), chunkSize, hashes, merkleRoot(hashes))
        }

        fun leaf(data: ByteArray, length: Int = data.size): ByteArray {
            val d = MessageDigest.getInstance("SHA-256")
            d.update(0)
            d.update(data, 0, length)
            return d.digest()
        }

        fun merkleRoot(leaves: List<String>): String {
            if (leaves.isEmpty()) return sha256(byteArrayOf(0)).hex()
            var level = leaves.map { it.unhex() }
            while (level.size > 1) {
                level = level.chunked(2).map { pair ->
                    if (pair.size == 2) sha256(byteArrayOf(1) + pair[0] + pair[1]) else pair[0]
                }
            }
            return level[0].hex()
        }

        fun chunkCount(size: Long, chunkSize: Int): Long = (size + chunkSize - 1) / chunkSize

        fun fileHash(file: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val len = input.read(buffer)
                    if (len < 0) break
                    d.update(buffer, 0, len)
                }
            }
            return d.digest().hex()
        }

        fun readChunk(raf: RandomAccessFile, index: Int, chunkSize: Int, buffer: ByteArray): Int {
            raf.seek(index.toLong() * chunkSize)
            var total = 0
            while (total < chunkSize) {
                val n = raf.read(buffer, total, chunkSize - total)
                if (n < 0) break
                total += n
            }
            return total
        }

        private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val n = read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            return total
        }
    }
}
