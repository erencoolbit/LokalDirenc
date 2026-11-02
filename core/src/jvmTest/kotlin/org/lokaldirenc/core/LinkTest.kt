package org.lokaldirenc.core

import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkTest {
    private class MemoryStore : StateStore {
        var data: ByteArray? = null
        override fun load() = data
        override fun save(data: ByteArray) {
            this.data = data
        }
        override fun destroy() {
            data = null
        }
        override val protection = "test"
    }

    private val root: File = Files.createTempDirectory("ld-link").toFile()
    private val nodes = ArrayList<Node>()

    private fun node(name: String): Node {
        // Keşif hedefi yok: bu düğümlerin tek yolu test edilen bağlantıdır.
        val port = java.net.DatagramSocket(0).use { it.localPort }
        val n = Node(File(root, name), MemoryStore(), NodeConfig(discoveryPort = port, meshFlushMs = 100, handshakeTimeoutMs = 2000, ioTimeoutMs = 2000))
        n.setNick(name)
        n.start()
        nodes += n
        return n
    }

    @AfterTest
    fun cleanup() {
        nodes.forEach { it.stop() }
    }

    /** Bluetooth soketi gibi: akış var, TCP yok, okuma zaman aşımı yok. */
    private fun pipePair(): Pair<Link, Link> {
        val aIn = PipedInputStream(1024 * 1024)
        val bIn = PipedInputStream(1024 * 1024)
        val aOut = PipedOutputStream(bIn)
        val bOut = PipedOutputStream(aIn)
        // Gerçek bağlantı gibi: bir uç kapanınca iki uçtaki okuma, yolda olan veriyi bitirip sonlanır.
        val closeAll = {
            listOf(aOut, bOut).forEach { s -> runCatching { s.close() } }
        }
        return WatchdogLink(aIn, aOut, closeAll) to WatchdogLink(bIn, bOut, closeAll)
    }

    private fun link(a: Node, b: Node, id: String) {
        val (la, lb) = pipePair()
        val server = thread { b.acceptLink(id, lb) }
        a.syncOverLink(id, la)
        server.join(5000)
    }

    @Test
    fun meshSyncsOverNonTcpLinkInBothDirections() {
        val a = node("a")
        val b = node("b")
        val net = a.createNetwork("bt")
        b.joinNetwork(net.code, "bt")
        assertTrue(a.peers(net.id).isEmpty(), "IP keşfi olmamalı")
        a.sendPublicMessage(net.id, "a'dan")
        b.sendPublicMessage(net.id, "b'den")
        link(a, b, net.id)
        assertTrue(b.meshMessages(net.id).any { it.text == "a'dan" && it.hops == 1 })
        assertTrue(a.meshMessages(net.id).any { it.text == "b'den" && it.hops == 1 })
        assertTrue(a.meshContacts(net.id).single { it.nick == "b" }.proven, "bağlantı kutu anahtarını kanıtlar")
    }

    @Test
    fun relayAcrossTwoLinksCarriesSealedMessage() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        val net = a.createNetwork("bt")
        b.joinNetwork(net.code, "bt")
        c.joinNetwork(net.code, "bt")
        link(b, c, net.id)
        link(a, b, net.id)
        val target = a.meshContacts(net.id).single { it.nick == "c" }
        assertTrue(a.sendDirectMessage(net.id, target.id, "bluetooth gizli"))
        link(a, b, net.id)
        link(b, c, net.id)
        assertTrue(c.meshMessages(net.id).any { it.text == "bluetooth gizli" && it.hops == 2 })
        assertTrue(b.meshMessages(net.id).none { it.text == "bluetooth gizli" })
    }

    @Test
    fun linkWithWrongCodeFailsAndLeavesNothing() {
        val a = node("a")
        val b = node("b")
        val netA = a.createNetwork("bir")
        val netB = b.createNetwork("iki")
        a.sendPublicMessage(netA.id, "gizli")
        val (la, lb) = pipePair()
        val server = thread { b.acceptLink(netB.id, lb) }
        assertFailsWith<Exception> { a.syncOverLink(netA.id, la) }
        server.join(5000)
        assertTrue(b.meshMessages(netB.id).isEmpty())
        assertTrue(b.meshContacts(netB.id).isEmpty())
    }

    @Test
    fun silentPeerTimesOutInsteadOfHanging() {
        val a = node("a")
        val net = a.createNetwork("bt")
        val (la, _) = pipePair()
        val started = System.currentTimeMillis()
        assertFailsWith<Exception> { a.syncOverLink(net.id, la) }
        assertTrue(System.currentTimeMillis() - started < 6000, "zaman aşımı çalışmalı")
    }

    @Test
    fun watchdogClosesOnSilence() {
        val (la, _) = pipePair()
        la.setTimeout(100)
        val started = System.currentTimeMillis()
        val result = runCatching { la.input.read() }.getOrDefault(-1)
        assertEquals(-1, result, "kapanan bağlantıdan veri gelmemeli")
        assertTrue(System.currentTimeMillis() - started < 3000)
    }

    @Test
    fun bleTokenRotatesAndIsBoundToNetwork() {
        val keyA = NetCode.discoveryKey(NetCode.generate())
        val keyB = NetCode.discoveryKey(NetCode.generate())
        val token = NetCode.bleToken(keyA, 100)
        assertEquals(BleAdvert.TOKEN, token.size)
        assertContentEquals(token, NetCode.bleToken(keyA, 100))
        assertFalse(token.contentEquals(NetCode.bleToken(keyA, 101)), "dönem değişince değişmeli")
        assertFalse(token.contentEquals(NetCode.bleToken(keyB, 100)), "ağa bağlı olmalı")
    }

    @Test
    fun bleAdvertFitsOneServiceUuid() {
        val token = NetCode.bleToken(NetCode.discoveryKey(NetCode.generate()), 7)
        val raw = BleAdvert.encode(token, 0x0081, -2)
        assertEquals(16, raw.size)
        val back = BleAdvert.decode(raw)!!
        assertContentEquals(token, back.token)
        assertEquals(0x0081, back.psm)
        assertEquals(0xFFFFFFFEL, back.role)
        assertEquals(null, BleAdvert.decode(ByteArray(15)))
    }

    @Test
    fun nodeOffersTokensAroundCurrentEpoch() {
        val a = node("a")
        val net = a.createNetwork("bt")
        val tokens = a.bleTokens(net.id, 500, 3)
        assertEquals(7, tokens.size)
        assertContentEquals(NetCode.bleToken(NetCode.discoveryKey(net.code), 500), tokens[3])
        assertTrue(a.bleTokens("yok", 500, 3).isEmpty())
    }
}
