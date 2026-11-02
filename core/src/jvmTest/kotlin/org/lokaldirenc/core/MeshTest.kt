package org.lokaldirenc.core

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshTest {
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

    private val root: File = Files.createTempDirectory("ld-mesh").toFile()
    private val nodes = ArrayList<Node>()

    // Keşif UDP'dir: Windows TCP'de boş olan portu UDP için ayırmış olabilir.
    private fun freePort() = java.net.DatagramSocket(0).use { it.localPort }

    private fun node(name: String, port: Int, sees: List<Int>, store: StateStore = MemoryStore()): Node {
        val n = Node(
            File(root, name), store,
            NodeConfig(
                discoveryPort = port, beaconIntervalMs = 80, peerTtlMs = 1500, handshakeTimeoutMs = 2000, ioTimeoutMs = 3000,
                meshIntervalMs = 80, meshResyncMs = 400, meshFlushMs = 100,
                extraBeaconTargets = sees.map { InetSocketAddress("127.0.0.1", it) },
            ),
        )
        n.setNick(name)
        n.start()
        nodes += n
        return n
    }

    @AfterTest
    fun cleanup() {
        nodes.forEach { it.stop() }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("Zaman aşımı: $what")
    }

    /** A - B - C: A ile C birbirini görmez, tek yol B'dir. */
    private class Line(val a: Node, val b: Node, val c: Node, val net: NetworkInfo)

    private fun line(): Line {
        val (pa, pb, pc) = Triple(freePort(), freePort(), freePort())
        val a = node("a", pa, listOf(pb))
        val b = node("b", pb, listOf(pa, pc))
        val c = node("c", pc, listOf(pb))
        val net = a.createNetwork("mesh")
        b.joinNetwork(net.code, "mesh")
        c.joinNetwork(net.code, "mesh")
        waitFor("komşular") { a.peers(net.id).size == 1 && c.peers(net.id).size == 1 && b.peers(net.id).size == 2 }
        return Line(a, b, c, net)
    }

    @Test
    fun publicMessageCrossesTwoHopsWithoutDirectLink() {
        val l = line()
        assertTrue(l.a.peers(l.net.id).none { it.nick == "c" }, "A, C'yi doğrudan görmemeli")
        assertTrue(l.a.sendPublicMessage(l.net.id, "herkese merhaba"))
        waitFor("C'ye ulaştı") { l.c.meshMessages(l.net.id).any { it.text == "herkese merhaba" } }
        val m = l.c.meshMessages(l.net.id).single { it.text == "herkese merhaba" }
        assertEquals(2, m.hops)
        assertEquals(1, l.b.meshMessages(l.net.id).single { it.text == "herkese merhaba" }.hops)
        assertEquals(l.a.meshOwnId(l.net.id), m.fromId)
        assertFalse(m.direct)
    }

    @Test
    fun contactsLearnedAcrossHopsAndDirectMessageIsOpaqueToRelay() {
        val l = line()
        waitFor("kişi yayıldı") { l.a.meshContacts(l.net.id).any { it.nick == "c" } }
        val contact = l.a.meshContacts(l.net.id).single { it.nick == "c" }
        assertFalse(contact.proven, "A, C ile doğrudan konuşmadı")

        assertTrue(l.a.sendDirectMessage(l.net.id, contact.id, "yalnız sana"))
        waitFor("C aldı") { l.c.meshMessages(l.net.id).any { it.text == "yalnız sana" } }
        val got = l.c.meshMessages(l.net.id).single { it.text == "yalnız sana" }
        assertTrue(got.direct)
        assertEquals(2, got.hops)
        assertEquals(l.a.meshOwnId(l.net.id), got.fromId)
        assertTrue(l.c.meshContacts(l.net.id).single { it.id == got.fromId }.proven, "mühür göndereni kanıtlar")

        // Ara cihaz taşıdı ama okuyamadı; kalıcı durumunda da metin yok.
        assertTrue(l.b.meshCarried(l.net.id) >= 1)
        assertTrue(l.b.meshMessages(l.net.id).none { it.text == "yalnız sana" })
        waitFor("yazıldı") { l.b.snapshot().mesh[l.net.id]?.envelopes?.any { it.wire.kind == MeshEngine.KIND_DIRECT } == true }
        val envelope = l.b.snapshot().mesh.getValue(l.net.id).envelopes.first { it.wire.kind == MeshEngine.KIND_DIRECT }
        assertTrue(envelope.wire.author.isEmpty() && envelope.wire.sig.isEmpty(), "yazar görünmemeli")
        assertFalse(String(Base64.getDecoder().decode(envelope.wire.body), Charsets.ISO_8859_1).contains("yalnız"))

        // Yanıt, öğrenilen kişiye gider.
        val replyTo = l.c.meshContacts(l.net.id).single { it.id == got.fromId }
        assertTrue(l.c.sendDirectMessage(l.net.id, replyTo.id, "aldım"))
        waitFor("A yanıtı aldı") { l.a.meshMessages(l.net.id).any { it.text == "aldım" && !it.outgoing } }
    }

    @Test
    fun carrierDeliversAfterAuthorHasLeft() {
        val pa = freePort()
        val pb = freePort()
        val pc = freePort()
        val a = node("a", pa, listOf(pb))
        val b = node("b", pb, listOf(pa, pc))
        val net = a.createNetwork("mesh")
        b.joinNetwork(net.code, "mesh")
        a.sendPublicMessage(net.id, "taşı beni")
        waitFor("B aldı") { b.meshMessages(net.id).any { it.text == "taşı beni" } }
        a.stop()

        val c = node("c", pc, listOf(pb))
        c.joinNetwork(net.code, "mesh")
        waitFor("C sonradan aldı") { c.meshMessages(net.id).any { it.text == "taşı beni" } }
    }

    @Test
    fun linkProvesOnlyRealOwnerOfBoxKey() {
        val pa = freePort()
        val pb = freePort()
        val a = node("a", pa, listOf(pb))
        val b = node("b", pb, listOf(pa))
        val net = a.createNetwork("mesh")
        b.joinNetwork(net.code, "mesh")
        waitFor("kanıtlandı") { a.meshContacts(net.id).any { it.nick == "b" && it.proven } }
        val contact = a.meshContacts(net.id).single { it.nick == "b" }
        assertFalse(a.isContactVerified(net.id, contact), "SAS onayı olmadan doğrulanmış sayılmamalı")
    }

    private class Harness {
        val code = NetCode.generate()
        val netId = NetCode.networkId(code)
        val id = netId.hex()
        val engine = MeshEngine({ false })
        val attackerA = MeshEngine({ false })
        val attackerB = MeshEngine({ false })

        init {
            engine.register(id, netId, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "kurban")
            attackerA.register(id, netId, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "saldirgan")
            attackerB.register(id, netId, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "diger")
        }

        fun publicWire(from: MeshEngine, text: String): MeshWire {
            from.sendPublic(id, text)
            return from.outgoing(id, from.inventory(id)).first { it.kind == MeshEngine.KIND_PUBLIC }
        }
    }

    @Test
    fun forgedOrAlteredEnvelopesAreDropped() {
        val h = Harness()
        val good = h.publicWire(h.attackerA, "özgün")
        val altered = good.copy(body = Base64.getEncoder().encodeToString("""{"text":"değişti"}""".toByteArray()))
        assertFalse(h.engine.ingest(h.id, altered), "imzasız değişiklik")
        assertFalse(h.engine.ingest(h.id, good.copy(sig = "00".repeat(64))), "sahte imza")
        assertFalse(h.engine.ingest(h.id, good.copy(sig = "zz")), "bozuk imza")
        assertFalse(h.engine.ingest(h.id, good.copy(author = "ab")), "bozuk yazar")
        assertFalse(h.engine.ingest(h.id, good.copy(kind = "yonetici")), "bilinmeyen tür")
        assertFalse(h.engine.ingest(h.id, good.copy(hops = 99)), "atlama sınırı")
        assertTrue(h.engine.messages(h.id).isEmpty())
        assertTrue(h.engine.ingest(h.id, good))
        assertFalse(h.engine.ingest(h.id, good), "yineleme")
        assertEquals(1, h.engine.messages(h.id).size)
    }

    @Test
    fun envelopeFromAnotherNetworkIsRejected() {
        val h = Harness()
        val other = NetCode.networkId(NetCode.generate())
        val foreign = MeshEngine({ false })
        foreign.register(other.hex(), other, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "yabanci")
        foreign.sendPublic(other.hex(), "başka ağ")
        val wire = foreign.outgoing(other.hex(), foreign.inventory(other.hex())).first { it.kind == MeshEngine.KIND_PUBLIC }
        assertFalse(h.engine.ingest(h.id, wire))
    }

    @Test
    fun hopLimitStopsForwarding() {
        val h = Harness()
        val wire = h.publicWire(h.attackerA, "son durak").copy(hops = 0)
        assertTrue(h.engine.ingest(h.id, wire), "alıcı yine okur")
        assertEquals(MeshEngine.MAX_HOPS, h.engine.messages(h.id).single().hops)
        assertTrue(h.engine.outgoing(h.id, h.engine.inventory(h.id)).none { it.kind == MeshEngine.KIND_PUBLIC }, "iletilmez")
    }

    @Test
    fun expiredAndFarFutureEnvelopesAreRejected() {
        var clock = 1_000_000_000_000L
        val h = Harness()
        val sender = MeshEngine({ false }, { clock })
        sender.register(h.id, h.netId, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "s")
        val receiver = MeshEngine({ false }, { clock })
        receiver.register(h.id, h.netId, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "r")
        sender.sendPublic(h.id, "zaman")
        val wire = sender.outgoing(h.id, sender.inventory(h.id)).first { it.kind == MeshEngine.KIND_PUBLIC }

        clock += MeshEngine.MAX_AGE_MS + 1
        assertFalse(receiver.ingest(h.id, wire), "çok eski")
        clock -= MeshEngine.MAX_AGE_MS + 1 + 48L * 3_600_000
        assertFalse(receiver.ingest(h.id, wire), "çok ileri")
    }

    @Test
    fun oneAuthorCannotEvictOthers() {
        val h = Harness()
        val other = h.publicWire(h.attackerB, "masum")
        assertTrue(h.engine.ingest(h.id, other))
        repeat(MeshEngine.MAX_PER_AUTHOR + 20) { i ->
            h.attackerA.sendPublic(h.id, "spam $i")
        }
        h.attackerA.outgoing(h.id, h.attackerA.inventory(h.id)).forEach { h.engine.ingest(h.id, it) }
        assertTrue(h.engine.outgoing(h.id, h.engine.inventory(h.id)).any { it.author == other.author && it.kind == MeshEngine.KIND_PUBLIC }, "masum üyenin zarfı kalmalı")
        assertTrue(h.engine.carriedCount(h.id) <= MeshEngine.MAX_PER_AUTHOR + 2)
    }

    @Test
    fun blockedAuthorIsHiddenAndNotForwarded() {
        val l = line()
        waitFor("kişi") { l.b.meshContacts(l.net.id).any { it.nick == "a" } }
        l.b.blockContact(l.b.meshContacts(l.net.id).single { it.nick == "a" })
        l.a.sendPublicMessage(l.net.id, "engelli yazar")
        Thread.sleep(1500)
        assertTrue(l.b.meshMessages(l.net.id).none { it.text == "engelli yazar" })
        assertTrue(l.c.meshMessages(l.net.id).none { it.text == "engelli yazar" }, "B iletmemeli")
    }

    @Test
    fun messagesSurviveRestartAndPanicWipeClearsThem() {
        val store = MemoryStore()
        val port = freePort()
        val a = node("kalici", port, emptyList(), store)
        val net = a.createNetwork("mesh")
        a.sendPublicMessage(net.id, "kalsın")
        val ownId = a.meshOwnId(net.id)
        a.stop()

        val again = node("kalici", port, emptyList(), store)
        assertEquals(ownId, again.meshOwnId(net.id), "mesh kimliği kalıcı")
        val kept = again.meshMessages(net.id).single { it.text == "kalsın" }
        assertTrue(kept.outgoing)
        again.panicWipe()
        assertEquals(null, store.data)
        assertTrue(again.snapshot().mesh.isEmpty())
    }

    @Test
    fun meshSyncDoesNotPromptOrCreateTransferRecords() {
        val l = line()
        l.a.sendPublicMessage(l.net.id, "soru yok")
        waitFor("yayıldı") { l.c.meshMessages(l.net.id).isNotEmpty() }
        listOf(l.a, l.b, l.c).forEach { assertTrue(it.transfers().isEmpty()) }
    }
}
