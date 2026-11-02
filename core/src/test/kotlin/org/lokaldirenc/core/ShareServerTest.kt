package org.lokaldirenc.core

import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareServerTest {
    private val dir: File = Files.createTempDirectory("ld-share").toFile()
    private val apk = File(dir, "gercek.apk").also { f -> f.writeBytes(ByteArray(3 * 1024 * 1024 + 7).also { Random(1).nextBytes(it) }) }
    private val secret = File(dir, "gizli.txt").also { it.writeText("gizli") }
    private val servers = ArrayList<ShareServer>()

    private fun server(durationMs: Long = 60_000, onStopped: () -> Unit = {}): ShareServer {
        val s = ShareServer(apk, "LokalDirenc.apk", "<h1>indir</h1>", InetAddress.getLoopbackAddress(), durationMs, onStopped)
        s.start()
        servers += s
        return s
    }

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop() }
    }

    private fun open(url: String) = URI(url).toURL().openConnection() as HttpURLConnection

    /** Yolu olduğu gibi gönderir: istemci kitaplığı ../ dizilerini temizleyemesin. */
    private fun raw(s: ShareServer, line: String): String {
        val uri = URI(s.url)
        Socket(uri.host, uri.port).use { socket ->
            socket.getOutputStream().write("$line\r\nHost: x\r\n\r\n".toByteArray())
            socket.soTimeout = 3000
            return String(socket.getInputStream().readBytes(), Charsets.ISO_8859_1)
        }
    }

    @Test
    fun servesPageAndExactApk() {
        val s = server()
        assertEquals("<h1>indir</h1>", open(s.url).inputStream.readBytes().decodeToString())
        val c = open(s.url + "LokalDirenc.apk")
        assertEquals(ShareServer.APK_TYPE, c.contentType)
        assertEquals(apk.length(), c.contentLengthLong)
        assertTrue(c.getHeaderField("Content-Disposition").contains("LokalDirenc.apk"))
        assertContentEquals(apk.readBytes(), c.inputStream.readBytes())
        assertEquals(1, s.downloads)
    }

    @Test
    fun urlHasRandomTokenAndLoopbackBinding() {
        val a = server()
        val b = server()
        assertFalse(a.url == b.url)
        assertTrue(a.url.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun anythingElseIsRefused() {
        val s = server()
        val base = URI(s.url).path
        for (path in listOf("/", "/LokalDirenc.apk", "/yanlis/LokalDirenc.apk", "${base}gercek.apk", "${base}gizli.txt", "$base../gizli.txt", "$base..%2fgizli.txt", "$base../../../../etc/passwd")) {
            val reply = raw(s, "GET $path HTTP/1.1")
            assertTrue(reply.startsWith("HTTP/1.1 404"), "$path -> ${reply.take(30)}")
            assertFalse(reply.contains("gizli\r") || reply.endsWith("gizli"), path)
        }
        assertEquals(0, s.downloads)
    }

    @Test
    fun onlyGetAndHeadAreAllowed() {
        val s = server()
        val path = URI(s.url).path
        assertTrue(raw(s, "POST ${path}LokalDirenc.apk HTTP/1.1").startsWith("HTTP/1.1 405"))
        assertTrue(raw(s, "PUT $path HTTP/1.1").startsWith("HTTP/1.1 405"))
        assertTrue(raw(s, "cop").startsWith("HTTP/1.1 400"))
        val head = raw(s, "HEAD ${path}LokalDirenc.apk HTTP/1.1")
        assertTrue(head.startsWith("HTTP/1.1 200") && head.endsWith("\r\n\r\n"), "HEAD gövde göndermemeli")
        assertEquals(0, s.downloads)
    }

    @Test
    fun oversizedHeaderIsDropped() {
        val s = server()
        val uri = URI(s.url)
        Socket(uri.host, uri.port).use { socket ->
            runCatching { socket.getOutputStream().write(ByteArray(32 * 1024) { 'a'.code.toByte() }) }
            socket.soTimeout = 3000
            assertEquals(0, runCatching { socket.getInputStream().readBytes().size }.getOrDefault(0))
        }
    }

    @Test
    fun stopsOnDemandAndAfterTimeLimit() {
        val manual = server()
        val manualUrl = manual.url
        manual.stop()
        assertFailsWith<ConnectException> { open(manualUrl).inputStream.readBytes() }

        var stopped = false
        val timed = server(durationMs = 150) { stopped = true }
        val url = timed.url
        Thread.sleep(600)
        assertTrue(stopped)
        assertFailsWith<ConnectException> { open(url).inputStream.readBytes() }
    }
}
