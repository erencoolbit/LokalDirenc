package org.lokaldirenc.core

import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RotationAndWitnessTest {
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

    private val root: File = Files.createTempDirectory("ld-rot").toFile()
    private val nodes = ArrayList<Node>()

    private fun freePort() = DatagramSocket(0).use { it.localPort }

    private fun node(name: String, port: Int = freePort(), sees: List<Int> = emptyList(), grace: Long = 60_000, store: StateStore = MemoryStore()): Node {
        val n = Node(
            File(root, name), store,
            NodeConfig(
                discoveryPort = port, beaconIntervalMs = 80, peerTtlMs = 1500, handshakeTimeoutMs = 1500, ioTimeoutMs = 3000,
                promptTimeoutMs = 3000, meshIntervalMs = 80, meshResyncMs = 250, meshFlushMs = 100, secretGraceMs = grace,
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

    private fun waitFor(what: String, timeoutMs: Long = 9000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(10)
        }
        throw AssertionError("Zaman aşımı: $what")
    }

    private class Group(val x: Node, val y: Node, val px: Int, val py: Int, val id: String)

    /** İki yönetici birbirini görür. */
    private fun founders(mode: EntryMode = EntryMode.OPEN, grace: Long = 60_000, extra: List<Int> = emptyList()): Group {
        val px = freePort()
        val py = freePort()
        val x = node("x", px, listOf(py) + extra, grace)
        val y = node("y", py, listOf(px) + extra, grace)
        val first = x.startManagedNetwork("Mahalle", mode, listOf(y.adminCode()))
        val second = y.signBundle(first)
        val net = assertNotNull(second.network)
        x.signBundle(second.bundle)
        return Group(x, y, px, py, net.id)
    }

    private fun Node.secret(id: String) = snapshot().networks.single { it.networkId == id }.secret

    private fun approveAll(admin: Node, id: String) {
        waitFor("öneri ulaştı") { admin.pendingProposals(id).any { it.event != null } }
        admin.approveProposal(id, admin.pendingProposals(id).first { it.event != null })
    }

    private fun radio(from: Node, to: Node) {
        from.loraSink = { to.receiveLoraFrame(it) }
    }

    @Test
    fun secretRotationReachesMembersAndBothAdmins() {
        val g = founders()
        val pm = freePort()
        val member = node("uye", pm, listOf(g.px), grace = 60_000)
        member.joinWithLink(g.x.joinLink(g.id)!!)
        waitFor("yönetici üyeyi tanıdı") { g.x.meshContacts(g.id).any { it.nick == "uye" } }
        val old = g.x.secret(g.id)

        assertTrue(g.x.proposeSecretRotation(g.id) >= 3)
        assertEquals(old, g.x.secret(g.id), "tek imzayla sır değişmez")
        approveAll(g.y, g.id)

        waitFor("hepsi aldı") { g.x.secret(g.id) != old && g.y.secret(g.id) != old && member.secret(g.id) != old }
        assertEquals(g.x.secret(g.id), g.y.secret(g.id))
        assertEquals(g.x.secret(g.id), member.secret(g.id))
        assertFalse(member.secretMissed(g.id))
        assertEquals(1, member.governance(g.id)!!.secretEpoch)

        g.x.sendPublicMessage(g.id, "yeni sırla")
        waitFor("iletişim sürer") { member.meshMessages(g.id).any { it.text == "yeni sırla" } }
    }

    @Test
    fun revokedMemberMissesNewSecretAndCannotReadLora() {
        val g = founders()
        val pm = freePort()
        val revoked = node("iptal", pm, listOf(g.px))
        revoked.joinWithLink(g.x.joinLink(g.id)!!)
        waitFor("tanındı") { g.x.meshContacts(g.id).any { it.nick == "iptal" } }
        val contact = g.x.meshContacts(g.id).single { it.nick == "iptal" }
        val old = g.x.secret(g.id)

        g.x.proposeEvent(g.id, GovernanceTypes.MEMBER_REVOKE, GovernanceLog.encode(RevokePayload(g.x.contactFingerprint(contact))))
        approveAll(g.y, g.id)
        waitFor("iptal x'e döndü") { g.x.governance(g.id)!!.revokedMembers.isNotEmpty() }
        waitFor("öneri temiz") { g.y.pendingProposals(g.id).isEmpty() }
        g.x.proposeSecretRotation(g.id)
        approveAll(g.y, g.id)
        waitFor("x yeni sırda") { g.x.secret(g.id) != old }
        waitFor("iptal edilen olayı gördü") { revoked.governance(g.id)!!.secretEpoch == 1 }

        assertEquals(old, revoked.secret(g.id), "zinciri görür ama sırrı açamaz")
        assertTrue(revoked.secretMissed(g.id), "arayüz bunu söyleyebilmeli")
        radio(g.x, revoked)
        g.x.sendPublicMessage(g.id, "gizli lora", remote = true)
        assertTrue(revoked.meshMessages(g.id).none { it.text == "gizli lora" }, "eski sır yeni çerçeveyi açmamalı")
    }

    @Test
    fun forgedRotationCannotPlantSecretOrSkipEpoch() {
        val g = founders()
        val log = g.x.governance(g.id)!!
        val fake = MeshEngine.generateSigningKey()
        assertFailsWith<GovernanceRejected> {
            g.x.submitGovernance(g.id, log.draft(GovernanceTypes.SECRET_ROTATE, GovernanceLog.encode(SecretRotation(1, listOf("aa")))).signedBy(fake))
        }
        // İmzası geçerli ama dönemi atlayan olay.
        val keys = g.x.snapshot().adminKeys.getValue(g.id).unhex()
        val keys2 = g.y.snapshot().adminKeys.getValue(g.id).unhex()
        assertFailsWith<GovernanceRejected> {
            g.x.submitGovernance(g.id, log.draft(GovernanceTypes.SECRET_ROTATE, GovernanceLog.encode(SecretRotation(5, listOf("aa")))).signedBy(keys).signedBy(keys2))
        }
        assertEquals(0, log.secretEpoch)
        // Mühür bu cihaza ait değilse sır değişmez, cihaz durumu bilir.
        val old = g.x.secret(g.id)
        g.x.submitGovernance(g.id, log.draft(GovernanceTypes.SECRET_ROTATE, GovernanceLog.encode(SecretRotation(1, listOf("00".repeat(120))))).signedBy(keys).signedBy(keys2))
        assertEquals(old, g.x.secret(g.id))
        assertTrue(g.x.secretMissed(g.id))
    }

    private fun canHandshake(target: Node, id: String, secret: String): Boolean = try {
        val s = Socket("127.0.0.1", target.networks().single().port).apply { soTimeout = 2000 }
        SecureChannel.handshake(s, true, Noise.generatePrivate(), NetCode.managedKey(secret.unhex(), "psk"), id.unhex()).close()
        true
    } catch (_: Exception) {
        false
    }

    @Test
    fun oldSecretWorksOnlyDuringGrace() {
        val g = founders(grace = 800)
        val old = g.x.secret(g.id)
        g.x.proposeSecretRotation(g.id)
        approveAll(g.y, g.id)
        waitFor("x yeni sırda") { g.x.secret(g.id) != old }
        assertTrue(canHandshake(g.x, g.id, old), "geçiş süresinde eski sır yeni sırrı almaya yarar")
        assertTrue(canHandshake(g.x, g.id, g.x.secret(g.id)))
        Thread.sleep(1000)
        assertFalse(canHandshake(g.x, g.id, old), "süre dolunca eski sır geçersiz")
        assertTrue(canHandshake(g.x, g.id, g.x.secret(g.id)))
    }

    @Test
    fun newSecretSurvivesRestart() {
        val store = MemoryStore()
        val px = freePort()
        val py = freePort()
        val x = node("kalici", px, listOf(py), store = store)
        val y = node("y", py, listOf(px))
        val second = y.signBundle(x.startManagedNetwork("M", EntryMode.OPEN, listOf(y.adminCode())))
        val id = second.network!!.id
        x.signBundle(second.bundle)
        val old = x.secret(id)
        x.proposeSecretRotation(id)
        approveAll(y, id)
        waitFor("yeni sır") { x.secret(id) != old }
        val fresh = x.secret(id)
        x.stop()
        val again = node("kalici", px, listOf(py), store = store)
        assertEquals(fresh, again.secret(id))
        assertEquals(1, again.governance(id)!!.secretEpoch)
        assertNotEquals(old, again.secret(id))
    }

    @Test
    fun adminCanBeReplacedAndOldKeyLosesPower() {
        val g = founders()
        val pw = freePort()
        val w = node("yeni", pw, listOf(g.px, g.py))
        w.joinWithLink(g.x.joinLink(g.id)!!)
        val code = w.adminCode()
        assertFalse(w.isAdmin(g.id))

        // y çıkarılıyor, w giriyor. Eski setin eşiği onaylar.
        g.x.proposeKeyRotation(g.id, listOf(g.x.ownAdminCode(g.id)!!, code))
        approveAll(g.y, g.id)
        waitFor("yeni yönetici") { w.isAdmin(g.id) }
        assertEquals(g.x.governance(g.id)!!.keys, w.governance(g.id)!!.keys)
        assertEquals(g.id, w.governance(g.id)!!.networkId, "Network ID değişmez")
        waitFor("eski yönetici yetkisini kaybetti") { !g.y.isAdmin(g.id) }

        val before = g.x.governance(g.id)!!.definition!!.name
        val definition = g.x.governance(g.id)!!.definition!!
        g.y.snapshot().adminKeys[g.id]?.let { stolen ->
            val log = g.y.governance(g.id)!!
            assertFailsWith<GovernanceRejected> {
                g.y.submitGovernance(g.id, log.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition.copy(name = "Ele geçti"))).signedBy(stolen.unhex()).signedBy(MeshEngine.generateSigningKey()))
            }
        }
        assertEquals(before, g.x.governance(g.id)!!.definition!!.name)

        // Yeni set birlikte karar alabiliyor.
        w.proposeEvent(g.id, GovernanceTypes.DEFINITION, GovernanceLog.encode(definition.copy(name = "Yeni yönetim")))
        approveAll(g.x, g.id)
        waitFor("karar yayıldı") { g.y.governance(g.id)!!.definition!!.name == "Yeni yönetim" }
    }

    @Test
    fun keyRotationRejectsBadSets() {
        val g = founders()
        assertFailsWith<IllegalArgumentException> { g.x.proposeKeyRotation(g.id, listOf(g.x.ownAdminCode(g.id)!!)) }
        assertFailsWith<IllegalArgumentException> { g.x.proposeKeyRotation(g.id, listOf("cop", "cop2")) }
    }

    @Test
    fun knownMembersSurviveKeyRotationButOldTicketsDoNotAdmitNewPeople() {
        val pm = freePort()
        val pn = freePort()
        val g = founders(EntryMode.INVITE_ONLY, extra = listOf(pm, pn))
        g.x.proposeInvite(g.id)
        waitFor("davet ulaştı") { g.y.pendingProposals(g.id).any { it.ticket != null } }
        g.y.approveProposal(g.id, g.y.pendingProposals(g.id).first { it.ticket != null })
        waitFor("davet hazır") { g.x.readyInvites(g.id).isNotEmpty() }
        val member = node("uye", pm, listOf(g.px))
        member.joinWithLink(g.x.readyInvites(g.id).single())
        member.sendPublicMessage(g.id, "önce")
        waitFor("üye kabul") { g.x.meshMessages(g.id).any { it.text == "önce" } }

        val w = node("w", freePort(), emptyList())
        g.x.proposeKeyRotation(g.id, listOf(g.x.ownAdminCode(g.id)!!, g.y.ownAdminCode(g.id)!!, w.adminCode()))
        approveAll(g.y, g.id)
        waitFor("değişim uygulandı") { g.x.governance(g.id)!!.keys!!.keys.size == 3 }
        member.sendPublicMessage(g.id, "sonra")
        waitFor("tanınan üye sürer") { g.x.meshMessages(g.id).any { it.text == "sonra" } }

        // Eski anahtarlarla imzalı, kullanılmamış bilet yeni kişiyi sokmamalı.
        val log = g.x.governance(g.id)!!
        val draft = Invites.draft(g.id, System.currentTimeMillis() + 100000)
        val outdated = draft.ticket.signedBy(MeshEngine.generateSigningKey()).signedBy(MeshEngine.generateSigningKey())
        assertFalse(Invites.verify(Invites.redeem(outdated, draft.secret, "ab".repeat(32), System.currentTimeMillis()), log, "ab".repeat(32)))
    }

    @Test
    fun witnessesAccumulateAcrossNetworkAndUseMedian() {
        val pm = freePort()
        val g = founders(extra = listOf(pm))
        val member = node("uye", pm, listOf(g.px, g.py))
        member.joinWithLink(g.x.joinLink(g.id)!!)
        val memberId = member.meshOwnId(g.id)!!
        waitFor("iki tanık") { g.x.witnessSummary(g.id, memberId, 2).witnesses >= 2 }
        assertNotNull(g.x.witnessSummary(g.id, memberId, 2).medianObservedAt)
        assertNull(g.x.witnessSummary(g.id, memberId, 3).medianObservedAt, "K tanık olmadan süre ölçülmez")
    }

    @Test
    fun oneDeviceClockCannotMoveMedian() {
        val code = NetCode.generate()
        val netBytes = NetCode.networkId(code)
        val id = netBytes.hex()
        val base = 1_800_000_000_000L
        val day = 86_400_000L
        val observer = MeshEngine({ false }, { base })
        observer.register(id, netBytes, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "gozlem", witnessing = true)
        val subject = MeshEngine({ false }, { base })
        subject.register(id, netBytes, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "uye", witnessing = true)
        val subjectId = subject.ownId(id)!!
        val presence = subject.selfPresence(id)!!
        // Saatini 12 saat geriye almış tek tanık ile dürüst iki tanık. Daha büyük sapma zaten reddedilir.
        listOf(base - day / 2, base, base).forEachIndexed { i, clock ->
            val w = MeshEngine({ false }, { clock })
            w.register(id, netBytes, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "t$i", witnessing = true)
            w.ingest(id, presence)
            w.outgoing(id, w.inventory(id)).filter { it.kind == MeshEngine.KIND_WITNESS }.forEach { observer.ingest(id, it) }
        }
        val summary = observer.witnessSummary(id, subjectId, 3)
        assertEquals(3, summary.witnesses)
        assertEquals(base, summary.medianObservedAt, "tek saat medyanı oynatamaz")
    }

    @Test
    fun selfWitnessAndUnmanagedAreIgnored() {
        val code = NetCode.generate()
        val netBytes = NetCode.networkId(code)
        val id = netBytes.hex()
        val a = MeshEngine({ false })
        a.register(id, netBytes, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "a", witnessing = true)
        val b = MeshEngine({ false })
        b.register(id, netBytes, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "b", witnessing = false)
        b.ingest(id, a.selfPresence(id)!!)
        assertTrue(b.outgoing(id, b.inventory(id)).none { it.kind == MeshEngine.KIND_WITNESS }, "yönetimsiz ağda tanıklık yok")
        assertEquals(0, a.witnessSummary(id, a.ownId(id)!!, 1).witnesses, "kendi kendine tanık olunmaz")
    }

    @Test
    fun forgedWitnessIsRejected() {
        val code = NetCode.generate()
        val netBytes = NetCode.networkId(code)
        val id = netBytes.hex()
        val a = MeshEngine({ false })
        a.register(id, netBytes, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "a", witnessing = true)
        val w = MeshEngine({ false })
        w.register(id, netBytes, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "w", witnessing = true)
        val sub = MeshEngine({ false })
        sub.register(id, netBytes, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "s", witnessing = true)
        w.ingest(id, sub.selfPresence(id)!!)
        val real = w.outgoing(id, w.inventory(id)).first { it.kind == MeshEngine.KIND_WITNESS }
        assertFalse(a.ingest(id, real.copy(issuedAt = real.issuedAt - 1000)), "tarih değiştirilemez")
        assertTrue(a.ingest(id, real))
    }
}
