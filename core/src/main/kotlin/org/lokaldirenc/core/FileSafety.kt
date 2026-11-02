package org.lokaldirenc.core

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Dosya güvenlik denetimleri (Bölüm 9.1). Doğru hash, dosyanın zararsız olduğunu kanıtlamaz. */
object FileSafety {
    class Verdict(val detectedType: String, val warnings: List<String>, val quarantine: Boolean)

    private const val MAX_NAME = 120
    private val RESERVED = Regex("^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?$", RegexOption.IGNORE_CASE)

    private val RISKY_EXT = setOf(
        "apk", "apks", "xapk", "aab", "dex", "jar", "exe", "msi", "com", "scr", "dll", "bat", "cmd", "ps1", "vbs",
        "vbe", "js", "jse", "wsf", "hta", "lnk", "reg", "sh", "bash", "py", "pl", "rb", "php", "elf", "so",
        "run", "app", "deb", "rpm", "html", "htm", "svg",
    )
    private val RISKY_TYPES = setOf("apk", "dex", "jar", "exe", "elf", "macho", "script")

    private val EXT_FAMILY = mapOf(
        "png" to "png", "jpg" to "jpeg", "jpeg" to "jpeg", "gif" to "gif", "webp" to "webp", "pdf" to "pdf",
        "zip" to "zip", "mp4" to "mp4", "m4v" to "mp4", "mov" to "mp4", "m4a" to "mp4", "mp3" to "mp3",
        "gz" to "gzip", "7z" to "7z", "rar" to "rar", "docx" to "zip", "xlsx" to "zip", "pptx" to "zip",
        "odt" to "zip", "epub" to "zip", "apk" to "apk", "jar" to "jar", "exe" to "exe", "dll" to "exe",
        "txt" to "text", "md" to "text", "csv" to "text", "json" to "text", "log" to "text",
    )

    const val ZIP_MAX_ENTRIES = 10_000
    const val ZIP_MAX_TOTAL = 8L * 1024 * 1024 * 1024
    const val ZIP_MAX_DEPTH = 3
    private const val NESTED_READ_LIMIT = 256L * 1024 * 1024

    /**
     * Dosya adı güvenilmeyen veridir. Yol bileşenleri, denetim ve yön karakterleri,
     * Windows'a özgü ayrılmış adlar ve akış ayracı temizlenir. Sonuç daima tek bir ad bileşenidir.
     */
    fun sanitizeName(raw: String): String {
        val base = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = base.filter { c ->
            !c.isISOControl() && c !in "<>:\"|?*" && c.code !in 0x200B..0x200F && c.code !in 0x202A..0x202E &&
                c.code !in 0x2066..0x2069 && c != '﻿'
        }.trim().trim('.', ' ')
        var name = if (cleaned.isEmpty() || cleaned == "..") "dosya" else cleaned
        if (RESERVED.matches(name)) name = "_$name"
        if (name.length > MAX_NAME) {
            val ext = name.substringAfterLast('.', "").take(16)
            name = if (ext.isEmpty()) name.take(MAX_NAME) else name.take(MAX_NAME - ext.length - 1) + "." + ext
        }
        return name
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isRiskyName(name: String): Boolean = extension(name) in RISKY_EXT

    /** İmza baytlarına göre tür. Eşleşme yoksa metin/ikili ayrımı yapılır. */
    fun sniff(head: ByteArray): String {
        fun at(vararg b: Int, offset: Int = 0) =
            head.size >= offset + b.size && b.indices.all { head[offset + it] == b[it].toByte() }
        return when {
            at(0x89, 0x50, 0x4E, 0x47) -> "png"
            at(0xFF, 0xD8, 0xFF) -> "jpeg"
            at(0x47, 0x49, 0x46, 0x38) -> "gif"
            at(0x52, 0x49, 0x46, 0x46) && at(0x57, 0x45, 0x42, 0x50, offset = 8) -> "webp"
            at(0x25, 0x50, 0x44, 0x46) -> "pdf"
            at(0x50, 0x4B, 0x03, 0x04) || at(0x50, 0x4B, 0x05, 0x06) -> "zip"
            at(0x1F, 0x8B) -> "gzip"
            at(0x37, 0x7A, 0xBC, 0xAF) -> "7z"
            at(0x52, 0x61, 0x72, 0x21) -> "rar"
            at(0x66, 0x74, 0x79, 0x70, offset = 4) -> "mp4"
            at(0x49, 0x44, 0x33) || at(0xFF, 0xFB) -> "mp3"
            at(0x4D, 0x5A) -> "exe"
            at(0x7F, 0x45, 0x4C, 0x46) -> "elf"
            at(0xFE, 0xED, 0xFA, 0xCE) || at(0xFE, 0xED, 0xFA, 0xCF) ||
                at(0xCF, 0xFA, 0xED, 0xFE) || at(0xCE, 0xFA, 0xED, 0xFE) -> "macho"
            at(0x64, 0x65, 0x78, 0x0A) -> "dex"
            at(0x23, 0x21) -> "script"
            head.isEmpty() -> "empty"
            head.all { it >= 0x09 || it < 0 } -> "text"
            else -> "binary"
        }
    }

    fun readHead(file: File, size: Int = 512): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(size)
        var total = 0
        while (total < size) {
            val n = input.read(buffer, total, size - total)
            if (n < 0) break
            total += n
        }
        buffer.copyOf(total)
    }

    /** [name] temizlenmiş addır. Dosya açılmaz, çalıştırılmaz, arşiv diske çıkarılmaz. */
    fun inspect(file: File, name: String, maxUnpacked: Long = ZIP_MAX_TOTAL): Verdict {
        val warnings = ArrayList<String>()
        var type = sniff(readHead(file))
        var quarantine = false

        if (type == "zip") {
            val zip = inspectZip(file, maxUnpacked)
            type = zip.refinedType
            warnings += zip.warnings
            quarantine = quarantine || zip.warnings.isNotEmpty()
        }

        val ext = extension(name)
        if (ext in RISKY_EXT) {
            warnings += "Riskli dosya uzantısı: .$ext"
            quarantine = true
        }
        if (type in RISKY_TYPES) {
            warnings += "İçerik çalıştırılabilir görünüyor: $type"
            quarantine = true
        }
        val expected = EXT_FAMILY[ext]
        if (expected != null && expected != type && !(expected == "text" && type == "empty")) {
            warnings += "Uzantı (.$ext) ile içerik ($type) uyuşmuyor"
            quarantine = true
        }
        return Verdict(type, warnings, quarantine)
    }

    private class ZipReport(val refinedType: String, val warnings: List<String>)

    private class Budget(val maxTotal: Long) {
        var entries = 0
        var total = 0L
        var inflateLeft = NESTED_READ_LIMIT
        var nestedRisky = false
    }

    private fun inspectZip(file: File, maxTotal: Long): ZipReport {
        val warnings = LinkedHashSet<String>()
        val budget = Budget(maxTotal)
        var refined = "zip"
        try {
            ZipFile(file).use { zf ->
                val names = HashSet<String>()
                val iterator = zf.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    names += entry.name
                    if (!account(entry, budget, warnings)) break
                    if (isNestedArchive(entry.name)) {
                        zf.getInputStream(entry).use { scanNested(it, 1, budget, warnings) }
                    }
                }
                refined = when {
                    "AndroidManifest.xml" in names && names.any { it.endsWith(".dex") } -> "apk"
                    "META-INF/MANIFEST.MF" in names && names.any { it.endsWith(".class") } -> "jar"
                    else -> "zip"
                }
            }
        } catch (_: java.io.IOException) {
            warnings += "Arşiv okunamadı veya bozuk"
        }
        return ZipReport(refined, warnings.toList())
    }

    private fun account(entry: ZipEntry, b: Budget, warnings: MutableSet<String>): Boolean {
        b.entries++
        if (entry.size > 0) b.total += entry.size
        if (entry.name.contains("..") || entry.name.startsWith("/") || entry.name.contains('\\')) {
            warnings += "Arşivde dizin dışına çıkan yol var"
        }
        if (isRiskyEntry(entry.name) && !b.nestedRisky) {
            b.nestedRisky = true
            warnings += "Arşivde çalıştırılabilir dosya var"
        }
        if (b.entries > ZIP_MAX_ENTRIES) {
            warnings += "Arşivde çok fazla dosya var (>$ZIP_MAX_ENTRIES)"
            return false
        }
        if (b.total > b.maxTotal) {
            warnings += "Arşivin açılmış boyutu sınırı aşıyor (zip bomb riski)"
            return false
        }
        return true
    }

    /** Gerçekten açılan bayt sayısı bütçeyle sınırlıdır; beyan edilen boyutlara güvenilmez. */
    private fun scanNested(stream: InputStream, depth: Int, b: Budget, warnings: MutableSet<String>) {
        if (depth >= ZIP_MAX_DEPTH) {
            warnings += "Arşiv çok derin iç içe"
            return
        }
        try {
            val zin = ZipInputStream(stream)
            while (true) {
                val entry = zin.nextEntry ?: break
                if (!account(entry, b, warnings)) return
                val metered = MeteredStream(zin, b)
                if (isNestedArchive(entry.name)) scanNested(metered, depth + 1, b, warnings)
                metered.drain()
                if (b.inflateLeft <= 0) {
                    warnings += "İç içe arşiv denetim sınırını aştı (zip bomb riski)"
                    return
                }
            }
        } catch (_: java.io.IOException) {
            warnings += "İç içe arşiv okunamadı"
        }
    }

    private class MeteredStream(private val base: InputStream, private val budget: Budget) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (budget.inflateLeft <= 0) return -1
            val n = base.read(b, off, minOf(len.toLong(), budget.inflateLeft).toInt())
            if (n > 0) budget.inflateLeft -= n
            return n
        }

        fun drain() {
            val buffer = ByteArray(16 * 1024)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }
    }

    private fun isNestedArchive(name: String) = name.lowercase().let { it.endsWith(".zip") || it.endsWith(".jar") }

    private fun isRiskyEntry(name: String) = extension(name.substringAfterLast('/')) in RISKY_EXT - setOf("html", "htm", "svg", "js", "bin", "so")
}
