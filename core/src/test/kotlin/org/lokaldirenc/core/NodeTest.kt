package org.lokaldirenc.core

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeTest {
    private class MemoryStore : StateStore {
        var data: ByteArray? = null
        var destroyed = false
        override fun load() = data
        override fun save(data: ByteArray) {
            this.data = data
        }
        override fun destroy() {
            data = null
            destroyed = true
        }
        override val protection = "test"
    }

    private val root: File = Files.createTempDirectory("ld-node").toFile()
    private val nodes = ArrayList<Node>()
    private val chunk = 16 * 1024

    // Keşif UDP'dir: Windows TCP'de boş olan portu UDP için ayırmış olabilir.
    private fun freePort() = java.net.DatagramSocket(0).use { it.localPort }

    private fun node(
        name: String,
        discovery: Int = freePort(),
        targets: List<Int> = emptyList(),
        store: StateStore = MemoryStore(),
    ): Node {
        val n = Node(
            File(root, name), store,
            NodeConfig(
                discoveryPort = discovery, beaconIntervalMs = 100, chunkSize = chunk, maxFileSize = 8L * 1024 * 1024,
                promptTimeoutMs = 3000, ioTimeoutMs = 3000, handshakeTimeoutMs = 2000,
                extraBeaconTargets = targets.map { InetSocketAddress("127.0.0.1", it) },
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

    private fun waitFor(what: String, timeoutMs: Long = 8000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(10)
        }
        throw AssertionError("Zaman aşımı: $what")
    }

    /** Eşleşme ve kabul istemlerini kullanıcı gibi onaylar. */
    private fun autoApprove(n: Node, accept: Boolean = true, pair: Boolean = true) {
        val t = Thread {
            while (!Thread.currentThread().isInterrupted) {
                for (x in n.transfers()) {
                    if (x.needsPairing) n.confirmPairing(x.id, pair)
                    if (x.needsAccept) n.respondOffer(x.id, accept)
                }
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun data(size: Int, seed: Long = 42) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun outboxFile(n: Node, name: String, content: ByteArray) =
        File(n.outbox, name).also { it.parentFile.mkdirs(); it.writeBytes(content) }

    private fun pair(): Triple<Node, Node, NetworkInfo> {
        val a = node("a")
        val b = node("b")
        val net = a.createNetwork("deneme")
        b.joinNetwork(net.code, "deneme")
        return Triple(a, b, net)
    }

    private fun port(n: Node, id: String) = n.networks().first { it.id == id }.port

    private fun finished(n: Node, id: String) = n.transfers().first { it.id == id }.status.isFinal

    @Test
    fun netCodeHasExpectedShapeAndDetectsTypos() {
        val code = NetCode.generate()
        assertEquals(28, code.length)
        assertEquals(code, NetCode.normalize(NetCode.format(code).lowercase()))
        assertEquals(code, NetCode.parse(NetCode.qrPayload(code)))
        val typo = (if (code[3] == 'A') 'B' else 'A').let { code.replaceRange(3, 4, it.toString()) }
        assertFailsWith<IllegalArgumentException> { NetCode.normalize(typo) }
        assertNotEquals(NetCode.generate(), code)
    }

    @Test
    fun networkIdPskAndDiscoveryKeyAreIndependent() {
        val code = NetCode.generate()
        val id = NetCode.networkId(code).hex()
        assertEquals(sha256("LD-NET-v1-U".toByteArray() + code.take(26).toByteArray()).hex(), id)
        assertFalse(NetCode.psk(code).contentEquals(NetCode.discoveryKey(code)))
        assertFalse(NetCode.psk(code).contentEquals(NetCode.networkId(code)))
    }

    @Test
    fun beaconIsOpaqueAndOnlyMembersCanRead() {
        val key = NetCode.discoveryKey(NetCode.generate())
        val payload = Beacon.Payload(1234, "a".repeat(64), "kişi")
        val one = Beacon.seal(key, payload)
        val two = Beacon.seal(key, payload)
        assertEquals(Beacon.PACKET, one.size)
        assertFalse(one.contentEquals(two), "paketler dönmeli")
        assertEquals(payload, Beacon.open(key, one, one.size))
        assertNull(Beacon.open(NetCode.discoveryKey(NetCode.generate()), one, one.size))
        one[20] = (one[20] + 1).toByte()
        assertNull(Beacon.open(key, one, one.size))
    }

    @Test
    fun peersDiscoverEachOtherAndIgnoreOtherNetworks() {
        val pa = freePort()
        val pb = freePort()
        val a = node("a", pa, listOf(pb))
        val b = node("b", pb, listOf(pa))
        val net = a.createNetwork("ortak")
        val other = a.createNetwork("baska")
        b.joinNetwork(net.code, "ortak")
        waitFor("keşif") { a.peers(net.id).size == 1 && b.peers(net.id).size == 1 }
        val seen = a.peers(net.id).single()
        assertEquals("b", seen.nick)
        assertEquals(port(b, net.id), seen.port)
        assertTrue(a.peers(other.id).isEmpty())
        // Başka ağın üyesi aynı yayını çözemez, kendi kendini de görmez.
        assertTrue(b.peers(other.id).isEmpty())
    }

    @Test
    fun endToEndTransferAcrossChunkBoundaries() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b)
        for ((i, size) in listOf(0, 1, chunk - 1, chunk, chunk + 1, chunk * 50 + 123).withIndex()) {
            val content = data(size, i.toLong())
            val name = "dosya$i.dat"
            val id = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, name, content), name)
            waitFor("gönderim $i") { finished(a, id) }
            assertEquals(TransferStatus.DONE, a.transfers().first { it.id == id }.status, "boyut $size")
            val received = b.transfers().first { it.fileName == name }
            assertEquals(TransferStatus.DONE, received.status)
            assertContentEquals(content, File(received.storedPath!!).readBytes())
            assertFalse(File(a.outbox, name).exists(), "gönderilen kopya silinmeli")
        }
    }

    @Test
    fun bothSidesSeeSameSasAndAskOnlyOnFirstPairing() {
        val (a, b, net) = pair()
        val prompts = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
        fun watch(n: Node, who: String) {
            n.onChange = {
                for (t in n.transfers()) {
                    if (t.needsPairing) {
                        prompts.getOrPut(who) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(t.sas!!)
                        n.confirmPairing(t.id, true)
                    }
                    if (t.needsAccept) n.respondOffer(t.id, true)
                }
            }
        }
        watch(a, "a")
        watch(b, "b")
        val first = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "1.txt", "bir".toByteArray()), "1.txt")
        waitFor("ilk") { finished(a, first) }
        assertEquals(TransferStatus.DONE, a.transfers().first { it.id == first }.status)
        assertEquals(prompts["a"], prompts["b"])
        assertEquals(1, prompts["a"]!!.size)
        assertTrue(a.snapshot().trusted.single().fingerprint.isNotEmpty())

        val second = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "2.txt", "iki".toByteArray()), "2.txt")
        waitFor("ikinci") { finished(a, second) }
        assertEquals(TransferStatus.DONE, a.transfers().first { it.id == second }.status)
        assertEquals(1, prompts["a"]!!.size, "tanınan kişiye yeniden sorulmamalı")
    }

    @Test
    fun deviceWithoutCodeCannotCompleteHandshake() {
        val b = node("b")
        val net = b.createNetwork("gizli")
        val outsider = node("disari")
        val own = outsider.createNetwork("baska")
        val id = outsider.send(own.id, "127.0.0.1", port(b, net.id), null, outboxFile(outsider, "x.txt", "gizli".toByteArray()), "x.txt")
        waitFor("başarısızlık") { finished(outsider, id) }
        assertEquals(TransferStatus.FAILED, outsider.transfers().first { it.id == id }.status)
        Thread.sleep(50)
        assertTrue(b.transfers().isEmpty(), "dışarıdan gelen deneme kayıt bırakmamalı")
    }

    @Test
    fun rejectingSasStopsBeforeAnyFileInfoIsSent() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b, pair = false)
        val id = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "gizli-ad.txt", "x".toByteArray()), "gizli-ad.txt")
        waitFor("iptal") { finished(a, id) }
        assertEquals(TransferStatus.FAILED, a.transfers().first { it.id == id }.status)
        waitFor("alıcı") { b.transfers().all { it.status.isFinal } }
        assertTrue(b.transfers().none { it.fileName.contains("gizli") })
    }

    @Test
    fun receiverCanRejectAndNothingIsStored() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b, accept = false)
        val id = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "r.txt", "x".toByteArray()), "r.txt")
        waitFor("ret") { finished(a, id) }
        assertEquals(TransferStatus.REJECTED, a.transfers().first { it.id == id }.status)
        waitFor("alıcı ret") { b.transfers().all { it.status.isFinal } }
        assertTrue(File(root, "b/inbox").listFiles().isNullOrEmpty())
    }

    @Test
    fun oversizedOfferIsRejectedWithoutPrompt() {
        val (a, b, net) = pair()
        autoApprove(a)
        var asked = false
        b.onChange = {
            for (t in b.transfers()) {
                if (t.needsPairing) b.confirmPairing(t.id, true)
                if (t.needsAccept) asked = true
            }
        }
        val big = File(a.outbox, "buyuk.bin")
        big.parentFile.mkdirs()
        big.outputStream().use { o -> repeat(9) { o.write(ByteArray(1024 * 1024)) } }
        val id = a.send(net.id, "127.0.0.1", port(b, net.id), null, big, "buyuk.bin")
        waitFor("büyük ret") { finished(a, id) }
        assertEquals(TransferStatus.REJECTED, a.transfers().first { it.id == id }.status)
        assertFalse(asked)
    }

    @Test
    fun blockedPeerAndBlockedHashAreRefused() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b)
        val content = "engellenecek".toByteArray()
        b.blockHash(sha256(content).hex())
        val first = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "h.txt", content), "h.txt")
        waitFor("özet engeli") { finished(a, first) }
        assertEquals(TransferStatus.REJECTED, a.transfers().first { it.id == first }.status)

        b.blockPeer(sha256(Noise.publicOf(a.snapshot().networks.single().staticKey.unhex())).hex())
        val before = b.transfers().size
        val second = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "p.txt", "x".toByteArray()), "p.txt")
        waitFor("kişi engeli") { finished(a, second) }
        assertEquals(TransferStatus.FAILED, a.transfers().first { it.id == second }.status)
        assertEquals(before, b.transfers().size)
    }

    @Test
    fun maliciousFileNameCannotEscapeInbox() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b)
        val id = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "n.txt", "x".toByteArray()), "n.txt")
        waitFor("gönderim") { finished(a, id) }
        // Kötü niyetli gönderen adı protokol düzeyinde yollar.
        val rogue = RogueSender(a, net, port(b, net.id))
        rogue.use {
            val content = "kaçış".toByteArray()
            it.handshakeAndPair()
            it.offer("../../../../kacis.txt", content)
            assertTrue(it.channel.receiveControl() is Msg.Accept)
            it.serve(content)
        }
        waitFor("alındı") { b.transfers().any { t -> t.status == TransferStatus.DONE && t.fileName == "kacis.txt" } }
        val t = b.transfers().first { it.fileName == "kacis.txt" }
        assertEquals(File(root, "b/inbox").canonicalPath, File(t.storedPath!!).canonicalFile.parentFile.path)
        assertFalse(File(root, "kacis.txt").exists())
    }

    @Test
    fun interruptedTransferResumesWithoutResendingVerifiedChunks() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b)
        // Önce tanışma, sonra kötü niyetli olmayan ama yarıda kopan bir gönderen.
        val hello = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "t.txt", "x".toByteArray()), "t.txt")
        waitFor("tanışma") { finished(a, hello) }

        val content = data(chunk * 40 + 5)
        RogueSender(a, net, port(b, net.id)).use {
            it.handshakeAndPair()
            it.offer("devam.bin", content)
            assertTrue(it.channel.receiveControl() is Msg.Accept)
            it.serve(content, stopAfterRequests = 2) // 32 parça verip bağlantıyı keser
        }
        waitFor("yarım kaldı") { b.transfers().any { it.fileName == "devam.bin" && it.status == TransferStatus.FAILED } }

        val id = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "devam.bin", content), "devam.bin")
        waitFor("sürdürme") { finished(a, id) }
        assertEquals(TransferStatus.DONE, a.transfers().first { it.id == id }.status)
        val resumed = a.transfers().first { it.id == id }.resumedChunks
        assertTrue(resumed >= 16, "doğrulanmış parçalar yeniden istenmemeli, sürdürülen=$resumed")
        val received = b.transfers().first { it.fileName == "devam.bin" && it.status == TransferStatus.DONE }
        assertContentEquals(content, File(received.storedPath!!).readBytes())
        assertEquals(TransferStatus.DONE, received.status)
    }

    @Test
    fun corruptChunkIsRetriedThenAccepted() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b)
        val hello = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "t.txt", "x".toByteArray()), "t.txt")
        waitFor("tanışma") { finished(a, hello) }
        val content = data(chunk * 3)
        RogueSender(a, net, port(b, net.id)).use {
            it.handshakeAndPair()
            it.offer("oz.bin", content)
            assertTrue(it.channel.receiveControl() is Msg.Accept)
            it.serve(content, corruptTimes = 2)
        }
        waitFor("alındı") { b.transfers().any { it.fileName == "oz.bin" && it.status == TransferStatus.DONE } }
        assertContentEquals(content, File(b.transfers().first { it.fileName == "oz.bin" }.storedPath!!).readBytes())
    }

    @Test
    fun persistentlyBadChunkFailsAndIsNotStored() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b)
        val hello = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "t.txt", "x".toByteArray()), "t.txt")
        waitFor("tanışma") { finished(a, hello) }
        val content = data(chunk * 2)
        RogueSender(a, net, port(b, net.id)).use {
            it.handshakeAndPair()
            it.offer("kotu.bin", content)
            assertTrue(it.channel.receiveControl() is Msg.Accept)
            it.serve(content, corruptTimes = Int.MAX_VALUE)
        }
        waitFor("başarısız") { b.transfers().any { it.fileName == "kotu.bin" && it.status == TransferStatus.FAILED } }
        assertTrue(File(root, "b/inbox").listFiles()!!.none { "kotu" in it.name })
    }

    @Test
    fun forgedManifestIsRefused() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b)
        val hello = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "t.txt", "x".toByteArray()), "t.txt")
        waitFor("tanışma") { finished(a, hello) }
        RogueSender(a, net, port(b, net.id)).use {
            it.handshakeAndPair()
            it.channel.send(Msg.Offer("x.bin", chunk.toLong(), "binary", "0".repeat(64), chunk, listOf("0".repeat(64)), "f".repeat(64)))
        }
        waitFor("reddedildi") { b.transfers().any { it.status == TransferStatus.FAILED && "Merkle" in (it.error ?: "") } }
    }

    @Test
    fun quarantineReceivesRiskyFilesAndReleaseRequiresCall() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b)
        val id = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "tatil.jpg", "MZ gizli exe".toByteArray()), "tatil.jpg")
        waitFor("gönderim") { finished(a, id) }
        val t = b.transfers().first { it.fileName == "tatil.jpg" }
        assertTrue(t.quarantined)
        assertTrue(t.storedPath!!.endsWith(".karantina"))
        assertTrue(File(t.storedPath!!).parentFile.name == "quarantine")
        assertTrue(t.warnings.isNotEmpty())
        b.releaseFromQuarantine(t.id)
        val released = b.transfers().first { it.id == t.id }
        assertFalse(released.quarantined)
        assertEquals("inbox", File(released.storedPath!!).parentFile.name)
    }

    @Test
    fun stateSurvivesRestartAndPanicWipeDestroysEverything() {
        val store = MemoryStore()
        val a = node("kalici", store = store)
        val net = a.createNetwork("ağ")
        val fp = net.fingerprint
        a.blockPeer("f".repeat(64))
        a.stop()

        val again = node("kalici", store = store)
        assertEquals(fp, again.networks().single().fingerprint, "kimlik kalıcı olmalı")
        assertEquals(net.code, again.networks().single().code)
        assertTrue("f".repeat(64) in again.snapshot().blockedPeers)
        File(again.outbox, "kalan.bin").writeBytes(ByteArray(10))

        again.panicWipe()
        assertTrue(store.destroyed)
        assertNull(store.data)
        assertFalse(File(root, "kalici").exists())
        assertTrue(again.snapshot().networks.isEmpty())
    }

    @Test
    fun separateNetworksUseSeparateIdentities() {
        val a = node("a")
        val n1 = a.createNetwork("bir")
        val n2 = a.createNetwork("iki")
        assertNotEquals(n1.fingerprint, n2.fingerprint)
    }

    @Test
    fun leavingNetworkRemovesItsIdentityAndHistory() {
        val (a, b, net) = pair()
        autoApprove(a)
        autoApprove(b)
        val id = a.send(net.id, "127.0.0.1", port(b, net.id), null, outboxFile(a, "z.txt", "x".toByteArray()), "z.txt")
        waitFor("gönderim") { finished(a, id) }
        a.leaveNetwork(net.id)
        assertTrue(a.networks().isEmpty())
        assertTrue(a.snapshot().networks.isEmpty())
        assertTrue(a.snapshot().trusted.isEmpty())
        assertTrue(a.snapshot().history.isEmpty())
    }

    /** Protokolü doğrudan konuşan kötü niyetli veya kusurlu gönderen. */
    private inner class RogueSender(owner: Node, net: NetworkInfo, port: Int) : AutoCloseable {
        private val key = owner.snapshot().networks.first { NetCode.networkId(it.code).hex() == net.id }
        val channel: SecureChannel = Socket("127.0.0.1", port).let { s ->
            s.soTimeout = 3000
            SecureChannel.handshake(s, true, key.staticKey.unhex(), NetCode.psk(net.code), NetCode.networkId(net.code))
        }

        fun handshakeAndPair() {
            channel.send(Msg.Hello(PROTOCOL_VERSION, "kotu"))
            assertTrue(channel.receiveControl() is Msg.Hello)
            channel.send(Msg.PairOk)
            assertTrue(channel.receiveControl() is Msg.PairOk)
        }

        fun offer(name: String, content: ByteArray) {
            val f = File.createTempFile("rogue", ".bin", root).also { it.writeBytes(content) }
            val m = Manifest.build(f, chunk)
            channel.send(Msg.Offer(name, content.size.toLong(), FileSafety.sniff(content.take(512).toByteArray()), m.fileHash, chunk, m.chunkHashes, m.root))
        }

        fun serve(content: ByteArray, stopAfterRequests: Int = Int.MAX_VALUE, corruptTimes: Int = 0) {
            var requests = 0
            var corrupt = corruptTimes
            while (requests < stopAfterRequests) {
                val m = try {
                    channel.receiveControl()
                } catch (_: Exception) {
                    return
                }
                when (m) {
                    is Msg.Get -> {
                        requests++
                        for (i in m.indices) {
                            val from = i * chunk
                            val piece = content.copyOfRange(from, minOf(from + chunk, content.size))
                            if (corrupt > 0) {
                                corrupt--
                                piece[0] = (piece[0] + 1).toByte()
                            }
                            channel.sendChunk(i, piece)
                        }
                    }
                    else -> return
                }
            }
        }

        override fun close() = channel.close()
    }
}
