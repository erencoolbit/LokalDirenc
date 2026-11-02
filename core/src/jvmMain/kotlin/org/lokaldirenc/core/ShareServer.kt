package org.lokaldirenc.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Uygulamayı henüz kurmamış birine kurulum paketini verir (Bölüm 14.8). Karşı tarafta Noise
 * olmadığı için bağlantı şifresizdir: bu yüzden yalnız herkese açık kurulum paketi içindir,
 * kullanıcı dosyası için kullanılmamalıdır.
 *
 * Yüzey bilerek dar: tek dosya, rastgele adres, tek arayüz, süre sınırı. İstek gövdesi
 * okunmaz, kayıt tutulmaz. Rastgele adres gizlilik sağlamaz, yalnız ağ taramasıyla
 * rastlantısal erişimi önler.
 */
class ShareServer(
    private val file: File,
    private val fileName: String,
    private val page: String,
    address: InetAddress,
    private val durationMs: Long = 10 * 60 * 1000L,
    private val onStopped: () -> Unit = {},
) {
    private val server = ServerSocket(0, 16, address)
    private val slots = Semaphore(MAX_CLIENTS)
    private val token = base32(ByteArray(16).also { SecureRandom().nextBytes(it) }, 16).lowercase()
    private val started = AtomicInteger(0)
    private val deadline = System.currentTimeMillis() + durationMs

    @Volatile
    private var running = true

    val url: String = "http://${address.hostAddress}:${server.localPort}/$token/"

    /** İndirmesi başlayan istek sayısı; tamamlandığını kanıtlamaz. */
    val downloads: Int get() = started.get()

    val remainingMs: Long get() = (deadline - System.currentTimeMillis()).coerceAtLeast(0)

    fun start() {
        thread(name = "ld-share-accept", isDaemon = true) {
            while (running) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                if (!slots.tryAcquire()) {
                    runCatching { socket.close() }
                    continue
                }
                thread(name = "ld-share-client", isDaemon = true) {
                    try {
                        socket.use { serve(it) }
                    } catch (_: IOException) {
                        // Karşı taraf koptu: kayıt tutulmaz.
                    } finally {
                        slots.release()
                    }
                }
            }
        }
        thread(name = "ld-share-timer", isDaemon = true) {
            try {
                Thread.sleep(durationMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            stop()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { server.close() }
        onStopped()
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val request = readRequestLine(socket.getInputStream()) ?: return
        val out = socket.getOutputStream().buffered()
        val parts = request.split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return respond(out, "400 Bad Request", "text/plain", "Hatalı istek".toByteArray())
        val (method, path) = parts
        if (method != "GET" && method != "HEAD") return respond(out, "405 Method Not Allowed", "text/plain", "İzin verilmiyor".toByteArray())
        val head = method == "HEAD"
        when (path) {
            "/$token/" -> respond(out, "200 OK", "text/html; charset=utf-8", page.toByteArray(), head)
            "/$token/$fileName" -> sendFile(out, head)
            else -> respond(out, "404 Not Found", "text/plain", "Bulunamadı".toByteArray(), head)
        }
    }

    private fun sendFile(out: OutputStream, head: Boolean) {
        writeHeaders(
            out, "200 OK", APK_TYPE, file.length(),
            "Content-Disposition: attachment; filename=\"$fileName\"\r\n",
        )
        if (!head) {
            started.incrementAndGet()
            file.inputStream().use { it.copyTo(out) }
        }
        out.flush()
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray, head: Boolean = false) {
        writeHeaders(out, status, type, body.size.toLong(), "")
        if (!head) out.write(body)
        out.flush()
    }

    private fun writeHeaders(out: OutputStream, status: String, type: String, length: Long, extra: String) {
        val headers = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: $length\r\n" +
            "X-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n" +
            extra + "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
    }

    /** Yalnız ilk satır gerekir. Başlıklar okunup atılır, boyutu sınırlıdır. */
    private fun readRequestLine(input: InputStream): String? {
        val all = StringBuilder()
        var total = 0
        // Bayt bayt gönderen istemci yuvayı süresiz tutamasın.
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (total < MAX_HEADER) {
            if (System.currentTimeMillis() > deadline) return null
            val b = input.read()
            if (b < 0) return null
            all.append(b.toChar())
            total++
            if (all.endsWith("\r\n\r\n") || all.endsWith("\n\n")) return all.lineSequence().first().trim()
        }
        return null
    }

    companion object {
        private const val MAX_CLIENTS = 4
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_HEADER = 8 * 1024
        const val APK_TYPE = "application/vnd.android.package-archive"
    }
}
