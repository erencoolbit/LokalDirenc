package org.lokaldirenc.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSafetyTest {
    private val dir: File = Files.createTempDirectory("ld-safety").toFile()

    private fun file(name: String, data: ByteArray) = File(dir, name).also { it.writeBytes(data) }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (n, d) ->
                z.putNextEntry(ZipEntry(n))
                z.write(d)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun sanitizeRemovesTraversalAndDangerousCharacters() {
        assertEquals("passwd", FileSafety.sanitizeName("../../etc/passwd"))
        assertEquals("evil.exe", FileSafety.sanitizeName("..\\..\\Windows\\evil.exe"))
        assertEquals("dosya", FileSafety.sanitizeName(".."))
        assertEquals("dosya", FileSafety.sanitizeName(""))
        assertEquals("dosya", FileSafety.sanitizeName("///"))
        assertEquals("_CON.txt", FileSafety.sanitizeName("CON.txt"))
        assertEquals("a.txtstream", FileSafety.sanitizeName("a.txt:stream"))
        assertEquals("rapor.pdf", FileSafety.sanitizeName("rapor.pdf\u0000"))
        // Sağdan sola geçersiz kılma: "xcod.exe" adını "exe.docx" gibi gösterir.
        assertEquals("xcod.exe", FileSafety.sanitizeName("‮xcod.exe"))
        assertTrue(FileSafety.sanitizeName("a".repeat(500) + ".pdf").let { it.length <= 120 && it.endsWith(".pdf") })
    }

    @Test
    fun sniffsByContentNotExtension() {
        assertEquals("png", FileSafety.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals("exe", FileSafety.sniff("MZ..".toByteArray()))
        assertEquals("elf", FileSafety.sniff(byteArrayOf(0x7F, 0x45, 0x4C, 0x46)))
        assertEquals("script", FileSafety.sniff("#!/bin/sh\n".toByteArray()))
        assertEquals("text", FileSafety.sniff("merhaba dünya\n".toByteArray()))
        assertEquals("empty", FileSafety.sniff(ByteArray(0)))
    }

    @Test
    fun executableDisguisedAsImageIsQuarantined() {
        val v = FileSafety.inspect(file("tatil.jpg", "MZ fake pe".toByteArray()), "tatil.jpg")
        assertEquals("exe", v.detectedType)
        assertTrue(v.quarantine)
        assertTrue(v.warnings.any { "uyuşmuyor" in it })
    }

    @Test
    fun plainTextIsClean() {
        val v = FileSafety.inspect(file("not.txt", "merhaba".toByteArray()), "not.txt")
        assertFalse(v.quarantine)
        assertTrue(v.warnings.isEmpty())
    }

    @Test
    fun riskyExtensionIsQuarantinedEvenWithHarmlessContent() {
        assertTrue(FileSafety.inspect(file("a.apk", "metin".toByteArray()), "a.apk").quarantine)
        assertTrue(FileSafety.inspect(file("b.PS1", "metin".toByteArray()), "b.PS1").quarantine)
    }

    @Test
    fun apkHiddenInZipExtensionIsDetected() {
        val data = zip(mapOf("AndroidManifest.xml" to ByteArray(4), "classes.dex" to ByteArray(4)))
        val v = FileSafety.inspect(file("belge.zip", data), "belge.zip")
        assertEquals("apk", v.detectedType)
        assertTrue(v.quarantine)
    }

    @Test
    fun cleanZipPasses() {
        val v = FileSafety.inspect(file("foto.zip", zip(mapOf("a.txt" to "x".toByteArray()))), "foto.zip")
        assertFalse(v.quarantine)
    }

    @Test
    fun zipSlipAndExecutableEntriesAreFlagged() {
        val v = FileSafety.inspect(file("s.zip", zip(mapOf("../../x.txt" to ByteArray(1)))), "s.zip")
        assertTrue(v.warnings.any { "dizin dışına" in it })
        val w = FileSafety.inspect(file("e.zip", zip(mapOf("kur.exe" to ByteArray(1)))), "e.zip")
        assertTrue(w.warnings.any { "çalıştırılabilir" in it })
    }

    @Test
    fun zipBombBySizeIsFlagged() {
        val v = FileSafety.inspect(file("b.zip", zip(mapOf("x" to ByteArray(2 * 1024 * 1024)))), "b.zip", maxUnpacked = 1024 * 1024)
        assertTrue(v.quarantine)
        assertTrue(v.warnings.any { "zip bomb" in it })
    }

    @Test
    fun tooManyEntriesAreFlagged() {
        val entries = (0..FileSafety.ZIP_MAX_ENTRIES).associate { "f$it" to ByteArray(0) }
        assertTrue(FileSafety.inspect(file("n.zip", zip(entries)), "n.zip").warnings.any { "çok fazla" in it })
    }

    @Test
    fun deepNestingIsFlagged() {
        var data = zip(mapOf("son.txt" to ByteArray(1)))
        repeat(FileSafety.ZIP_MAX_DEPTH + 1) { data = zip(mapOf("ic.zip" to data)) }
        val v = FileSafety.inspect(file("d.zip", data), "d.zip")
        assertTrue(v.warnings.any { "derin" in it })
    }

    @Test
    fun corruptZipIsQuarantined() {
        val v = FileSafety.inspect(file("c.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)), "c.zip")
        assertTrue(v.quarantine)
    }
}
