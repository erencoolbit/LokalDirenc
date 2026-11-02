package org.lokaldirenc.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Şifreli oturumun altındaki bayt akışı: TCP, Bluetooth L2CAP veya başka bir taşıma. */
interface Link : AutoCloseable {
    val input: InputStream
    val output: OutputStream

    /** Bu süre içinde veri gelmezse okuma başarısız olmalı. */
    fun setTimeout(ms: Int)
}

class SocketLink(private val socket: Socket) : Link {
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()

    override fun setTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * Okuma zaman aşımı olmayan akışlar için (Bluetooth soketi gibi): süre dolarsa bağlantıyı
 * kapatır, bekleyen okuma da böylece hata alır.
 */
class WatchdogLink(rawInput: InputStream, override val output: OutputStream, private val closer: () -> Unit) : Link {
    @Volatile
    private var timeoutMs = 30_000

    override fun setTimeout(ms: Int) {
        timeoutMs = ms
    }

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val alarm = TIMER.schedule({ this@WatchdogLink.close() }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            try {
                return rawInput.read(b, off, len)
            } catch (e: IOException) {
                throw IOException("bağlantı kapandı", e)
            } finally {
                alarm.cancel(false)
            }
        }
    }

    override fun close() {
        runCatching { closer() }
    }

    private companion object {
        val TIMER = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ld-link-timer").apply { isDaemon = true } }
    }
}
