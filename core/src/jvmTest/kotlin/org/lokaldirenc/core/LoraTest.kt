package org.lokaldirenc.core

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoraTest {
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

    private val root: File = Files.createTempDirectory("ld-lora").toFile()
    private val nodes = ArrayList<Node>()

    /** Keşif hedefi yok: bu düğümlerin tek yolu LoRa çerçeveleridir. */
    private fun node(name: String): Node {
        val port = java.net.DatagramSocket(0).use { it.localPort }
        val n = Node(File(root, name), MemoryStore(), NodeConfig(discoveryPort = port))
        n.setNick(name)
        n.start()
        nodes += n
        return n
    }

    @AfterTest
    fun cleanup() {
        nodes.forEach { it.stop() }
    }

    private fun radio(from: Node, to: Node, drop: (Int) -> Boolean = { false }): ArrayList<ByteArray> {
        val sent = ArrayList<ByteArray>()
        from.loraSink = { frame ->
            val index = sent.size
            sent += frame
            if (!drop(index)) to.receiveLoraFrame(frame)
        }
        return sent
    }

    private fun wire(kind: String = MeshEngine.KIND_PUBLIC, body: ByteArray = ByteArray(100) { it.toByte() }) = MeshWire(
        kind, 1_700_000_000_000, java.util.Base64.getEncoder().encodeToString(body),
        if (kind == MeshEngine.KIND_DIRECT) "" else "ab".repeat(32),
        if (kind == MeshEngine.KIND_DIRECT) "" else "cd".repeat(64),
        5,
    )

    private val key = NetCode.loraKey(NetCode.generate())

    @Test
    fun wireSurvivesCompactEncodingExactly() {
        for (w in listOf(wire(), wire(MeshEngine.KIND_PRESENCE), wire(MeshEngine.KIND_DIRECT))) {
            assertEquals(w, LoraCodec.decodeWire(LoraCodec.encodeWire(w)))
        }
        assertNull(LoraCodec.decodeWire(byteArrayOf(9, 1, 2)))
        assertNull(LoraCodec.decodeWire(ByteArray(0)))
    }

    @Test
    fun framesFitAndOpenOnlyForMembers() {
        val frames = LoraCodec.seal(key, wire())
        assertTrue(frames.all { it.size <= LoraCodec.MAX_FRAME })
        assertEquals(LoraCodec.DEFAULT_TTL, frames[0][0])
        val first = frames[0]
        assertNotNull(LoraCodec.open(key, first))
        assertNull(LoraCodec.open(NetCode.loraKey(NetCode.generate()), first))
        val tampered = first.copyOf().also { it[30] = (it[30] + 1).toByte() }
        assertNull(LoraCodec.open(key, tampered))
        // Station decrements TTL: that must not break authentication.
        assertNotNull(LoraCodec.open(key, first.copyOf().also { it[0] = 1 }))
        assertNull(LoraCodec.open(key, ByteArray(10)))
    }

    @Test
    fun oversizedMessageIsNotSentAsPartialFrames() {
        val body = ByteArray(LoraCodec.MAX_FRAGMENT_DATA * (LoraCodec.MAX_FRAGMENTS + 1))
        assertTrue(LoraCodec.seal(key, wire(body = body)).isEmpty())
    }

    @Test
    fun reassemblesOutOfOrderAndIgnoresDuplicates() {
        val w = wire(body = ByteArray(500) { it.toByte() })
        val frames = LoraCodec.seal(key, w).reversed()
        assertTrue(frames.size > 1)
        val r = LoraReassembler()
        var result: ByteArray? = null
        for (f in frames.dropLast(1)) {
            assertNull(r.add("n", LoraCodec.open(key, f)!!))
            assertNull(r.add("n", LoraCodec.open(key, f)!!), "yineleme tamamlamamalı")
        }
        result = r.add("n", LoraCodec.open(key, frames.last())!!)
        assertEquals(w, LoraCodec.decodeWire(result!!))
    }

    @Test
    fun incompleteMessagesExpireAndPendingIsBounded() {
        var clock = 0L
        val r = LoraReassembler(maxPending = 3, ttlMs = 1000) { clock }
        repeat(10) {
            val f = LoraCodec.seal(key, wire(body = ByteArray(500))).first()
            assertNull(r.add("n", LoraCodec.open(key, f)!!))
        }
        assertTrue(r.pendingCount() <= 3)
        clock = 5000
        val f = LoraCodec.seal(key, wire(body = ByteArray(500))).first()
        r.add("n", LoraCodec.open(key, f)!!)
        assertEquals(1, r.pendingCount())
    }

    @Test
    fun publicAndDirectCrossLoraWithoutAnyOtherLink() {
        val a = node("a")
        val b = node("b")
        val net = a.createNetwork("uzak")
        b.joinNetwork(net.code, "uzak")
        val sent = radio(a, b)
        radio(b, a)
        assertTrue(a.sendPublicMessage(net.id, "uzaktan selam", remote = true))
        val got = b.meshMessages(net.id).single { it.text == "uzaktan selam" }
        assertEquals(a.meshOwnId(net.id), got.fromId, "imza köprüden sonra da doğrulanmalı")
        assertTrue(got.hops >= 1)
        println("LoRa: herkese açık + duyuru = ${sent.size} çerçeve")
        assertTrue(sent.all { it.size <= LoraCodec.MAX_FRAME })

        // Duyuru sayesinde B, A'yı tanır ve özel yanıt verebilir.
        val contact = b.meshContacts(net.id).single { it.nick == "a" }
        val before = a.meshMessages(net.id).size
        assertTrue(b.sendDirectMessage(net.id, contact.id, "özel yanıt", remote = true))
        assertEquals(before + 1, a.meshMessages(net.id).size)
        assertEquals("özel yanıt", a.meshMessages(net.id).last().text)
    }

    @Test
    fun localMessagesStayOffLora() {
        val a = node("a")
        val b = node("b")
        val net = a.createNetwork("uzak")
        b.joinNetwork(net.code, "uzak")
        val sent = radio(a, b)
        a.sendPublicMessage(net.id, "yerel sohbet")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun lostFragmentLosesMessageWithoutCorruption() {
        val a = node("a")
        val b = node("b")
        val net = a.createNetwork("uzak")
        b.joinNetwork(net.code, "uzak")
        radio(a, b) { it == 1 } // duyurunun ikinci parçası kayboldu
        a.sendPublicMessage(net.id, "kayıplı", remote = true)
        assertTrue(b.meshContacts(net.id).none { it.nick == "a" }, "eksik duyuru kabul edilmemeli")
    }

    @Test
    fun otherNetworkCannotReadFrames() {
        val a = node("a")
        val outsider = node("o")
        val net = a.createNetwork("bir")
        val other = outsider.createNetwork("iki")
        radio(a, outsider)
        a.sendPublicMessage(net.id, "gizli", remote = true)
        assertTrue(outsider.meshMessages(other.id).none { it.text == "gizli" })
    }

    @Test
    fun budgetLimitsFramesButMessageStillGoesLocal() {
        val a = node("a")
        val b = node("b")
        val net = a.createNetwork("uzak")
        b.joinNetwork(net.code, "uzak")
        val sent = radio(a, b)
        repeat(Node.LORA_MAX_FRAMES + 5) { a.sendPublicMessage(net.id, "m$it", remote = true) }
        assertTrue(sent.size <= Node.LORA_MAX_FRAMES, "gönderilen ${sent.size}")
        assertEquals(Node.LORA_MAX_FRAMES + 5, a.meshMessages(net.id).size, "yerelde hepsi kalmalı")
        assertTrue(a.loraBudgetLeft() >= 0)
    }

    @Test
    fun noDeviceMeansLocalOnly() {
        val a = node("a")
        val net = a.createNetwork("uzak")
        assertTrue(a.sendPublicMessage(net.id, "cihaz yok", remote = true))
        assertEquals(Node.LORA_MAX_FRAMES, a.loraBudgetLeft())
    }

    @Test
    fun sosBeaconRoundTripAndRejectsGarbage() {
        val data = SosBeacon.encode(123456, 87)
        assertEquals(SosBeacon.SIZE, data.size)
        assertEquals(SosBeacon.Signal(123456, 87), SosBeacon.decode(data))
        assertEquals(100, SosBeacon.decode(SosBeacon.encode(1, 250))!!.batteryPercent)
        assertNull(SosBeacon.decode(null))
        assertNull(SosBeacon.decode(ByteArray(3)))
        assertNull(SosBeacon.decode(data.copyOf().also { it[0] = 9 }))
        assertNull(SosBeacon.decode(data.copyOf().also { it[5] = 101 }))
        val uuid = SosBeacon.encodeUuid(123456, 87)
        assertEquals(SosBeacon.UUID_SIZE, uuid.size)
        assertEquals(SosBeacon.Signal(123456, 87), SosBeacon.decodeUuid(uuid))
        assertNull(SosBeacon.decodeUuid(ByteArray(16)), "başka hizmet kimliği SOS sayılmamalı")
    }

    @Test
    fun sosLoraFrameDecodes() {
        val frame = byteArrayOf(2, LoraCodec.KIND_SOS) + SosBeacon.encode(77, 40) + byteArrayOf(-71)
        val (signal, rssi) = SosBeacon.decodeLoraFrame(frame)!!
        assertEquals(77, signal.session)
        assertEquals(-71, rssi)
        assertNull(SosBeacon.decodeLoraFrame(frame.copyOf(5)))
    }
}
