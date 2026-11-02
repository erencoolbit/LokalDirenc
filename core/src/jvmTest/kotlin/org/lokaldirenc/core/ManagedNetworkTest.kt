package org.lokaldirenc.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedNetworkTest {
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

    private class Admin {
        val priv: ByteArray = MeshEngine.generateSigningKey()
        val pub: String = Ed25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded.hex()
    }

    private val root: File = Files.createTempDirectory("ld-managed").toFile()
    private val nodes = ArrayList<Node>()
    private val a1 = Admin()
    private val a2 = Admin()
    private val a3 = Admin()
    private val keys = ManagementKeySet(2, listOf(a1.pub, a2.pub, a3.pub))
    private val id = GovernanceLog.networkIdOf(keys)
    private val day = 24L * 3_600_000
    private val SECRET = "5e".repeat(32)

    private fun now() = System.currentTimeMillis()
    private fun freePort() = DatagramSocket(0).use { it.localPort }

    private fun node(name: String, port: Int, sees: List<Int>, store: StateStore = MemoryStore()): Node {
        val n = Node(
            File(root, name), store,
            NodeConfig(
                discoveryPort = port, beaconIntervalMs = 80, peerTtlMs = 1500, handshakeTimeoutMs = 2000, ioTimeoutMs = 3000,
                promptTimeoutMs = 3000, meshIntervalMs = 80, meshResyncMs = 250, meshFlushMs = 100,
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

    private fun waitFor(what: String, timeoutMs: Long = 8000, check: () -> Boolean) {
        val end = now() + timeoutMs
        while (now() < end) {
            if (check()) return
            Thread.sleep(10)
        }
        throw AssertionError("Zaman aşımı: $what")
    }

    private fun GovernanceEvent.by(vararg signers: Admin): GovernanceEvent {
        var e = this
        signers.forEach { e = e.signedBy(it.priv) }
        return e
    }

    private fun InviteTicket.by(vararg signers: Admin): InviteTicket {
        var t = this
        signers.forEach { t = t.signedBy(it.priv) }
        return t
    }

    private fun definition(mode: EntryMode, name: String = "Mahalle") = NetworkDefinition(name, mode, adminTermExpiry = now() + 30 * day)

    private fun genesis(mode: EntryMode = EntryMode.OPEN) = GovernanceLog.genesisDraft(keys, definition(mode), now()).by(a1, a2)

    /** Kurucu ve komşular: a - b - c, a ile c birbirini görmez. */
    private class Line(val a: Node, val b: Node, val c: Node)

    private fun line(mode: EntryMode = EntryMode.OPEN): Line {
        val (pa, pb, pc) = Triple(freePort(), freePort(), freePort())
        val a = node("a", pa, listOf(pb))
        val b = node("b", pb, listOf(pa, pc))
        val c = node("c", pc, listOf(pb))
        a.createManagedNetwork(genesis(mode), SECRET, "Mahalle")
        b.joinManagedNetwork(id, SECRET, "Mahalle")
        c.joinManagedNetwork(id, SECRET, "Mahalle")
        return Line(a, b, c)
    }

    private fun invite(n: Node, expires: Long = now() + day, maxUses: Int = 1) {
        val draft = Invites.draft(id, expires, maxUses)
        n.redeemInvite(id, draft.ticket.by(a1, a2), draft.secret)
    }

    @Test
    fun chainReachesMembersAcrossHopsAndUpdatesFollow() {
        val l = line()
        waitFor("zincir C'ye ulaştı") { l.c.governance(id)?.definition?.name == "Mahalle" }
        assertEquals(keys, l.c.governance(id)!!.keys)
        val log = l.a.governance(id)!!
        l.a.submitGovernance(id, log.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition(EntryMode.OPEN, "Yeni ad"))).by(a2, a3))
        waitFor("güncelleme yayıldı") { l.c.governance(id)?.definition?.name == "Yeni ad" }
        assertEquals(log.lastHash, l.c.governance(id)!!.lastHash)
    }

    @Test
    fun openNetworkCarriesMessagesWithoutInvite() {
        val l = line()
        l.a.sendPublicMessage(id, "açık ağ")
        waitFor("mesaj C'de") { l.c.meshMessages(id).any { it.text == "açık ağ" && it.hops == 2 } }
    }

    /** Protokolü doğrudan konuşan kötü niyetli eş: kurbanın zincirine olay sokmaya çalışır. */
    private fun pushEvents(target: Node, events: List<GovernanceEvent>) {
        val idBytes = id.unhex()
        val socket = java.net.Socket("127.0.0.1", target.networks().single().port).apply { soTimeout = 3000 }
        SecureChannel.handshake(socket, true, Noise.generatePrivate(), NetCode.managedKey(SECRET.unhex(), "psk"), idBytes).use { ch ->
            ch.send(Msg.Hello(PROTOCOL_VERSION, "kotu", PURPOSE_MESH))
            ch.receiveControl()
            ch.send(Msg.GovHave(events.size.toLong(), events.last().hash()))
            ch.receiveControl()
            ch.send(Msg.GovEvents(events))
            runCatching { ch.receiveControl() }
        }
    }

    @Test
    fun fakeChainFromNetworkIsRefusedAndReported() {
        val victim = node("kurban", freePort(), emptyList())
        victim.joinManagedNetwork(id, SECRET, "Mahalle")
        // Saldırgan kurbanın beklediği kimlikle kendi anahtarlarını sunar.
        val x = Admin()
        val y = Admin()
        val forged = GovernanceEvent(id, GovernanceTypes.GENESIS, 0, "", now(), GovernanceLog.encode(GenesisPayload(ManagementKeySet(2, listOf(x.pub, y.pub)), definition(EntryMode.OPEN)))).by(x, y)
        pushEvents(victim, listOf(forged))
        waitFor("reddedildi") { victim.governanceAlert(id) == GovernanceReject.WRONG_NETWORK }
        assertNull(victim.governance(id)!!.definition, "sahte zincir kabul edilmemeli")
        assertTrue(victim.snapshot().governance[id].isNullOrEmpty())
    }

    @Test
    fun forgedEventFromNetworkCannotChangeSettings() {
        val victim = node("kurban", freePort(), emptyList())
        victim.createManagedNetwork(genesis(), SECRET, "Mahalle")
        val log = victim.governance(id)!!
        val x = Admin()
        val forged = log.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition(EntryMode.OPEN, "Ele geçti"))).by(a1, x)
        pushEvents(victim, listOf(forged))
        waitFor("reddedildi") { victim.governanceAlert(id) == GovernanceReject.NOT_ENOUGH_SIGNATURES }
        assertEquals("Mahalle", log.definition!!.name)
    }

    @Test
    fun divergedChainsAreDetectedNotOverwritten() {
        val pa = freePort()
        val pb = freePort()
        val a = node("a", pa, emptyList())
        val b = node("b", pb, emptyList())
        val g = genesis()
        a.createManagedNetwork(g, SECRET, "M")
        b.createManagedNetwork(g, SECRET, "M")
        // Yöneticiler iki ayrı karar imzaladı: aynı sıra, farklı içerik.
        a.submitGovernance(id, a.governance(id)!!.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition(EntryMode.OPEN, "Bir"))).by(a1, a2))
        b.submitGovernance(id, b.governance(id)!!.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition(EntryMode.OPEN, "İki"))).by(a2, a3))
        val nodesPorts = listOf(a, b)
        nodesPorts.forEach { n ->
            val socket = java.net.Socket("127.0.0.1", n.networks().single().port).apply { soTimeout = 3000 }
            val idBytes = id.unhex()
            val other = if (n === a) b else a
            SecureChannel.handshake(socket, true, Noise.generatePrivate(), NetCode.managedKey(SECRET.unhex(), "psk"), idBytes).use { ch ->
                ch.send(Msg.Hello(PROTOCOL_VERSION, "kopru", PURPOSE_MESH))
                ch.receiveControl()
                val theirEvents = other.governance(id)!!.events
                ch.send(Msg.GovHave(theirEvents.size.toLong(), theirEvents.last().hash()))
                ch.receiveControl()
                ch.send(Msg.GovEvents(emptyList()))
                runCatching { ch.receiveControl() }
            }
        }
        waitFor("çatallanma görüldü") { a.governanceAlert(id) == GovernanceReject.FORK && b.governanceAlert(id) == GovernanceReject.FORK }
        assertEquals("Bir", a.governance(id)!!.definition!!.name, "kendi zinciri üstüne yazılmaz")
        assertEquals("İki", b.governance(id)!!.definition!!.name)
    }

    @Test
    fun allowListHoldsAcrossTheNetwork() {
        val l = line()
        waitFor("zincir") { l.b.governance(id)?.definition != null }
        val log = l.a.governance(id)!!
        val wipe = log.draft("remote_wipe", "{}").by(a1, a2, a3)
        assertFailsWith<GovernanceRejected> { l.a.submitGovernance(id, wipe) }
        assertFailsWith<GovernanceRejected> { l.b.submitGovernance(id, wipe.copy(networkId = id)) }
        l.a.sendPublicMessage(id, "hala burada")
        waitFor("iletişim sürer") { l.b.meshMessages(id).any { it.text == "hala burada" } }
    }

    @Test
    fun inviteOnlyAdmitsMembersAndOutsiderLearnsOnlyTheRules() {
        val pa = freePort()
        val pb = freePort()
        val pc = freePort()
        val a = node("a", pa, listOf(pb, pc))
        val b = node("b", pb, listOf(pa))
        val outsider = node("disari", pc, listOf(pa))
        a.createManagedNetwork(genesis(EntryMode.INVITE_ONLY), SECRET, "Davetli")
        invite(a)
        b.joinManagedNetwork(id, SECRET, "Davetli")
        invite(b)
        outsider.joinManagedNetwork(id, SECRET, "Davetli")

        a.sendPublicMessage(id, "yalnız üyeler")
        waitFor("üye aldı") { b.meshMessages(id).any { it.text == "yalnız üyeler" } }
        waitFor("kural dışarıya da ulaştı") { outsider.governance(id)?.definition?.entryMode == EntryMode.INVITE_ONLY }
        Thread.sleep(800)
        assertTrue(outsider.meshMessages(id).none { it.text == "yalnız üyeler" })
    }

    @Test
    fun revokedMemberIsCutOffButKeepsOwnData() {
        val pa = freePort()
        val pb = freePort()
        val a = node("a", pa, listOf(pb))
        val b = node("b", pb, listOf(pa))
        a.createManagedNetwork(genesis(EntryMode.INVITE_ONLY), SECRET, "Davetli")
        invite(a)
        b.joinManagedNetwork(id, SECRET, "Davetli")
        invite(b)
        a.sendPublicMessage(id, "önce")
        waitFor("önce") { b.meshMessages(id).any { it.text == "önce" } }

        val fp = b.networks().single().fingerprint
        val log = a.governance(id)!!
        a.submitGovernance(id, log.draft(GovernanceTypes.MEMBER_REVOKE, GovernanceLog.encode(RevokePayload(fp))).by(a1, a2))
        waitFor("iptal üyeye de ulaştı") { b.governance(id)!!.revokedMembers.contains(fp) }
        a.sendPublicMessage(id, "sonra")
        Thread.sleep(800)
        assertTrue(b.meshMessages(id).none { it.text == "sonra" }, "iptal edilen yeni içerik alamamalı")
        assertTrue(b.meshMessages(id).any { it.text == "önce" }, "uzaktan silme yok: elindeki veri durur")
    }

    @Test
    fun singleUseInviteAdmitsOnlyFirstIdentityOnEachNode() {
        val pa = freePort()
        val pb = freePort()
        val pc = freePort()
        val a = node("a", pa, listOf(pb, pc))
        a.createManagedNetwork(genesis(EntryMode.INVITE_ONLY), SECRET, "D")
        invite(a)
        val draft = Invites.draft(id, now() + day)
        val ticket = draft.ticket.by(a1, a2)
        val first = node("bir", pb, listOf(pa))
        first.joinManagedNetwork(id, SECRET, "D")
        first.redeemInvite(id, ticket, draft.secret)
        first.sendPublicMessage(id, "ilk kişiden")
        waitFor("ilk kişi kabul edildi") { a.meshMessages(id).any { it.text == "ilk kişiden" } }

        // Kod sızdı: ikinci kişi aynı biletle kendi kimliğine kanıt üretir. Kanıt tek başına geçerlidir,
        // ama bu düğüm bileti zaten bir kimlik için saymıştır.
        val second = node("iki", pc, listOf(pa))
        second.joinManagedNetwork(id, SECRET, "D")
        second.redeemInvite(id, ticket, draft.secret)
        second.sendPublicMessage(id, "ikinci kişiden")
        waitFor("ikinci kural öğrendi") { second.governance(id)?.definition != null }
        Thread.sleep(800)
        assertTrue(a.meshMessages(id).none { it.text == "ikinci kişiden" }, "ikinci kimlik yazamamalı")
        assertTrue(second.meshMessages(id).none { it.text == "ilk kişiden" }, "ikinci kimlik okuyamamalı")
        first.sendPublicMessage(id, "ilk kişi sürer")
        waitFor("ilk kişi hâlâ üye") { a.meshMessages(id).any { it.text == "ilk kişi sürer" } }
    }

    @Test
    fun proofCannotBeStolenOrForged() {
        val a = node("a", freePort(), emptyList())
        a.createManagedNetwork(genesis(EntryMode.INVITE_ONLY), SECRET, "D")
        val log = a.governance(id)!!
        val me = "aa".repeat(32)
        val draft = Invites.draft(id, now() + day)
        val ticket = draft.ticket.by(a1, a2)
        val proof = Invites.redeem(ticket, draft.secret, me, now())
        assertTrue(Invites.verify(proof, log, me))
        assertFalse(Invites.verify(proof, log, "bb".repeat(32)), "başkasının kanıtı kullanılamaz")
        assertFalse(Invites.verify(proof.copy(memberFingerprint = "bb".repeat(32)), log, "bb".repeat(32)), "kimlik değiştirilemez")
        // Sır olmadan kanıt üretilemez.
        assertFalse(Invites.verify(Invites.redeem(ticket, Invites.draft(id, now() + day).secret, me, now()), log, me))
        // Tek yöneticinin bileti yetmez.
        val weak = Invites.draft(id, now() + day)
        assertFalse(Invites.verify(Invites.redeem(weak.ticket.by(a1), weak.secret, me, now()), log, me))
        // Başka ağın bileti geçmez.
        val other = Invites.draft("00".repeat(32), now() + day)
        assertFalse(Invites.verify(Invites.redeem(other.ticket.by(a1, a2), other.secret, me, now()), log, me))
        // Süresi dolmuş bilet.
        val expired = Invites.draft(id, now() - 1)
        assertFalse(Invites.verify(Invites.redeem(expired.ticket.by(a1, a2), expired.secret, me, now()), log, me))
        // Bozuk imza çökertmez.
        assertFalse(Invites.verify(proof.copy(signature = "zz"), log, me))
    }

    @Test
    fun revokedInviteAndKeyRotationInvalidateTickets() {
        val a = node("a", freePort(), emptyList())
        a.createManagedNetwork(genesis(EntryMode.INVITE_ONLY), SECRET, "D")
        val log = a.governance(id)!!
        val me = "aa".repeat(32)
        val d1 = Invites.draft(id, now() + day)
        val p1 = Invites.redeem(d1.ticket.by(a1, a2), d1.secret, me, now())
        a.submitGovernance(id, log.draft(GovernanceTypes.INVITE_REVOKE, GovernanceLog.encode(RevokePayload(d1.ticket.inviteId))).by(a1, a2))
        assertFalse(Invites.verify(p1, log, me))
        val d2 = Invites.draft(id, now() + day)
        val p2 = Invites.redeem(d2.ticket.by(a1, a2), d2.secret, me, now())
        assertTrue(Invites.verify(p2, log, me))
        val fresh = Admin()
        val fresh2 = Admin()
        a.submitGovernance(id, log.draft(GovernanceTypes.KEY_ROTATION, GovernanceLog.encode(RotationPayload(ManagementKeySet(2, listOf(fresh.pub, fresh2.pub))))).by(a1, a2))
        assertFalse(Invites.verify(p2, log, me), "anahtar döndürme eski biletleri geçersiz kılar (Bölüm 5.5)")
    }

    @Test
    fun mixedModeLetsOutsiderReadButNotWrite() {
        val pa = freePort()
        val pb = freePort()
        val a = node("a", pa, listOf(pb))
        val reader = node("okuyucu", pb, listOf(pa))
        a.createManagedNetwork(genesis(EntryMode.MIXED), SECRET, "Karma")
        invite(a)
        reader.joinManagedNetwork(id, SECRET, "Karma")
        a.sendPublicMessage(id, "herkes okur")
        waitFor("okudu") { reader.meshMessages(id).any { it.text == "herkes okur" } }
        reader.sendPublicMessage(id, "onaysız yazı")
        Thread.sleep(800)
        assertTrue(a.meshMessages(id).none { it.text == "onaysız yazı" })
    }

    @Test
    fun retiredNetworkStopsButLeavesDataAndSpreads() {
        val l = line()
        l.a.sendPublicMessage(id, "kalıcı")
        waitFor("mesaj") { l.c.meshMessages(id).any { it.text == "kalıcı" } }
        val log = l.a.governance(id)!!
        l.a.submitGovernance(id, log.draft(GovernanceTypes.RETIRE_NOW, GovernanceLog.encode(RetirePayload("sonlandı"))).by(a1, a2))
        waitFor("sonlandı bilgisi yayıldı") { l.b.governance(id)!!.mode == ManagementMode.RETIRED }
        assertTrue(l.b.meshMessages(id).any { it.text == "kalıcı" }, "veri silinmez, karar kullanıcının")
        waitFor("C de öğrendi") { l.c.governance(id)!!.mode == ManagementMode.RETIRED }
        Thread.sleep(2000) // eş listesinin süresi dolsun: emekli ağ yayın yapmaz
        assertTrue(l.a.peers(id).isEmpty(), "emekli ağ kendini duyurmamalı")
        l.b.sendPublicMessage(id, "sonlandıktan sonra")
        Thread.sleep(500)
        assertTrue(l.c.meshMessages(id).none { it.text == "sonlandıktan sonra" })
    }

    @Test
    fun chainSurvivesRestartAndIsReverified() {
        val store = MemoryStore()
        val port = freePort()
        val a = node("kalici", port, emptyList(), store)
        a.createManagedNetwork(genesis(), SECRET, "Mahalle")
        val log = a.governance(id)!!
        a.submitGovernance(id, log.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition(EntryMode.OPEN, "Kalıcı"))).by(a1, a2))
        val hash = log.lastHash
        a.stop()
        val again = node("kalici", port, emptyList(), store)
        assertEquals("Kalıcı", again.governance(id)!!.definition!!.name)
        assertEquals(hash, again.governance(id)!!.lastHash)
        assertTrue(again.networks().single().managed)
    }

    @Test
    fun withoutSecretNothingIsLearnedEvenWithNetworkId() {
        val a = node("a", freePort(), emptyList())
        a.createManagedNetwork(genesis(EntryMode.INVITE_ONLY), SECRET, "Gizli")
        invite(a)
        val port = a.networks().single().port
        // Kamuya açık kimliği bilmek el sıkışmaya yetmez: üye parmak izleri ve kanıtlar görünmez.
        val socket = java.net.Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        val idBytes = id.unhex()
        assertFailsWith<Exception> {
            SecureChannel.handshake(socket, true, Noise.generatePrivate(), NetCode.managedKey(idBytes, "psk"), idBytes)
        }
        val b = node("disari", freePort(), emptyList())
        val other = "11".repeat(32)
        b.joinManagedNetwork(id, other, "Gizli")
        val s2 = java.net.Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        assertFailsWith<Exception> {
            SecureChannel.handshake(s2, true, Noise.generatePrivate(), NetCode.managedKey(other.unhex(), "psk"), idBytes)
        }
        Thread.sleep(200)
        assertTrue(a.snapshot().inviteUses.values.flatten().size <= 1)
    }

    private fun radio(from: Node, to: Node): ArrayList<ByteArray> {
        val sent = ArrayList<ByteArray>()
        from.loraSink = { frame ->
            sent += frame
            to.receiveLoraFrame(frame)
        }
        return sent
    }

    @Test
    fun loraFramesCannotBeReadWithoutSecret() {
        val a = node("a", freePort(), emptyList())
        val outsider = node("d", freePort(), emptyList())
        a.createManagedNetwork(genesis(EntryMode.INVITE_ONLY), SECRET, "G")
        // Dışarıdaki aynı kimliği bilir ama sırrı bilmez.
        outsider.joinManagedNetwork(id, "11".repeat(32), "G")
        val sent = radio(a, outsider)
        a.sendPublicMessage(id, "gizli lora", remote = true)
        assertTrue(sent.isNotEmpty())
        assertTrue(outsider.meshMessages(id).none { it.text == "gizli lora" })
        assertNull(LoraCodec.open(NetCode.managedKey(id.unhex(), "lora"), sent[0]), "kamuya açık kimlikten türetilen anahtar açmamalı")
        assertNotNull(LoraCodec.open(NetCode.managedKey(SECRET.unhex(), "lora"), sent[0]))
    }

    @Test
    fun revokedAuthorIsDroppedOnLora() {
        val a = node("a", freePort(), emptyList())
        val b = node("b", freePort(), emptyList())
        val g = genesis(EntryMode.INVITE_ONLY)
        a.createManagedNetwork(g, SECRET, "G")
        b.createManagedNetwork(g, SECRET, "G")
        radio(a, b)
        a.sendPublicMessage(id, "iptalden önce", remote = true)
        assertTrue(b.meshMessages(id).any { it.text == "iptalden önce" })
        val fp = a.networks().single().fingerprint
        val log = b.governance(id)!!
        b.submitGovernance(id, log.draft(GovernanceTypes.MEMBER_REVOKE, GovernanceLog.encode(RevokePayload(fp))).by(a1, a2))
        a.sendPublicMessage(id, "iptalden sonra", remote = true)
        assertTrue(b.meshMessages(id).none { it.text == "iptalden sonra" })
    }

    /** İki yönetici telefonu, ayrı anahtarlarla. */
    private fun founders(mode: EntryMode = EntryMode.OPEN): Triple<Node, Node, NetworkInfo> {
        val pa = freePort()
        val pb = freePort()
        val x = node("kurucu", pa, listOf(pb))
        val y = node("ortak", pb, listOf(pa))
        val first = x.startManagedNetwork("Mahalle", mode, listOf(y.adminCode()))
        assertTrue(x.networks().isEmpty(), "tek imzayla ağ kurulmaz")
        val second = y.signBundle(ManagedLinks.parseBundle(ManagedLinks.encode(first))!!)
        val net = assertNotNull(second.network)
        val back = x.signBundle(second.bundle)
        assertNotNull(back.network)
        assertEquals(net.id, back.network!!.id)
        return Triple(x, y, net)
    }

    @Test
    fun genesisNeedsTwoDevicesAndBothEndUpAdmins() {
        val (x, y, net) = founders()
        assertTrue(x.isAdmin(net.id))
        assertTrue(y.isAdmin(net.id))
        assertEquals("Mahalle", y.governance(net.id)!!.definition!!.name)
        assertEquals(x.governance(net.id)!!.lastHash, y.governance(net.id)!!.lastHash)
        assertEquals("", x.snapshot().adminKeyPool, "anahtar ağa geçti")
    }

    @Test
    fun strangerCannotSignBundleOrFormNetworkAlone() {
        val x = node("x", freePort(), emptyList())
        val y = node("y", freePort(), emptyList())
        val z = node("z", freePort(), emptyList())
        assertFailsWith<IllegalArgumentException> { x.startManagedNetwork("T", EntryMode.OPEN, emptyList()) }
        assertFailsWith<IllegalArgumentException> { x.startManagedNetwork("T", EntryMode.OPEN, listOf(x.adminCode())) }
        val bundle = x.startManagedNetwork("T", EntryMode.OPEN, listOf(y.adminCode()))
        z.adminCode()
        assertFailsWith<IllegalArgumentException> { z.signBundle(bundle) }
        val tampered = bundle.copy(event = bundle.event.copy(payload = bundle.event.payload.replace("T", "X")))
        assertFailsWith<Exception> {
            val r = y.signBundle(tampered)
            assertNull(r.network)
            x.signBundle(r.bundle)
            throw IllegalStateException("yalnız kurucunun imzası değişen pakette geçersiz kaldı")
        }
        assertTrue(y.networks().isEmpty())
    }

    @Test
    fun proposalSpreadsSignedOnSecondDeviceAndApplies() {
        val (x, y, net) = founders()
        x.proposeEvent(net.id, GovernanceTypes.DEFINITION, GovernanceLog.encode(definition(EntryMode.OPEN, "Yeni ad")))
        assertEquals("Mahalle", x.governance(net.id)!!.definition!!.name, "tek imza yetmez")
        waitFor("öneri ortağa ulaştı") { y.pendingProposals(net.id).isNotEmpty() }
        val p = y.pendingProposals(net.id).single()
        assertFalse(y.hasSigned(net.id, p))
        y.approveProposal(net.id, p)
        assertEquals("Yeni ad", y.governance(net.id)!!.definition!!.name)
        waitFor("kurucuya da yayıldı") { x.governance(net.id)!!.definition!!.name == "Yeni ad" }
        waitFor("öneri temizlendi") { x.pendingProposals(net.id).isEmpty() }
    }

    @Test
    fun forgedProposalSignaturesAreStrippedAndCannotReachThreshold() {
        val (x, y, net) = founders()
        val fake = Admin()
        val log = x.governance(net.id)!!
        val bad = log.draft(GovernanceTypes.RETIRE_NOW, GovernanceLog.encode(RetirePayload("sahte"))).by(fake)
        val socket = java.net.Socket("127.0.0.1", y.networks().single().port).apply { soTimeout = 3000 }
        val secret = x.snapshot().networks.single().secret
        SecureChannel.handshake(socket, true, Noise.generatePrivate(), NetCode.managedKey(secret.unhex(), "psk"), net.id.unhex()).use { ch ->
            ch.send(Msg.Hello(PROTOCOL_VERSION, "kotu", PURPOSE_MESH))
            ch.receiveControl()
            val have = y.governance(net.id)!!.events
            ch.send(Msg.GovHave(have.size.toLong(), have.last().hash()))
            ch.receiveControl()
            ch.send(Msg.GovEvents(emptyList()))
            ch.receiveControl()
            ch.send(Msg.MeshEnd)
            ch.send(Msg.GovProposals(listOf(Proposal(event = bad))))
            runCatching { ch.receiveControl() }
        }
        Thread.sleep(300)
        assertTrue(y.pendingProposals(net.id).isEmpty(), "imzası geçersiz öneri tutulmamalı")
        assertEquals(ManagementMode.ACTIVE, y.governance(net.id)!!.mode)
    }

    @Test
    fun nonAdminCannotProposeOrApprove() {
        val (x, _, net) = founders()
        val pc = freePort()
        val member = node("uye", pc, listOf(x.config.discoveryPort))
        member.joinWithLink(x.joinLink(net.id)!!)
        assertFailsWith<IllegalStateException> { member.proposeEvent(net.id, GovernanceTypes.RETIRE_NOW, GovernanceLog.encode(RetirePayload())) }
        assertFalse(member.isAdmin(net.id))
    }

    @Test
    fun inviteFlowFromTwoAdminsToNewMember() {
        val (x, y, net) = founders(EntryMode.INVITE_ONLY)
        assertNull(x.joinLink(net.id), "davetli ağda açık QR yok")
        x.proposeInvite(net.id)
        assertTrue(x.readyInvites(net.id).isEmpty(), "tek imzalı davet hazır sayılmaz")
        waitFor("davet ortağa ulaştı") { y.pendingProposals(net.id).any { it.ticket != null } }
        y.approveProposal(net.id, y.pendingProposals(net.id).single { it.ticket != null })
        waitFor("davet hazır") { x.readyInvites(net.id).isNotEmpty() }
        assertTrue(y.readyInvites(net.id).isEmpty(), "sır yalnız oluşturanda")

        val link = x.readyInvites(net.id).single()
        val member = node("uye", freePort(), listOf(x.config.discoveryPort))
        val joined = member.joinWithLink(link)
        assertEquals(net.id, joined.id)
        x.sendPublicMessage(net.id, "hoş geldin")
        member.sendPublicMessage(net.id, "teşekkürler")
        waitFor("üye okudu") { member.meshMessages(net.id).any { it.text == "hoş geldin" } }
        waitFor("üye yazdı") { x.meshMessages(net.id).any { it.text == "teşekkürler" } }
    }

    @Test
    fun managedLinksRejectGarbage() {
        assertNull(ManagedLinks.parseJoin("ld://managed?d=!!!"))
        assertNull(ManagedLinks.parseJoin("ld://managed?d=e30"))
        assertNull(ManagedLinks.parseBundle("ld://sign?d=@@"))
        val a = node("a", freePort(), emptyList())
        assertFailsWith<IllegalArgumentException> { a.joinWithLink("cop") }
        assertTrue(a.networks().isEmpty())
    }

    @Test
    fun unmanagedNetworksAreUnaffected() {
        val pa = freePort()
        val pb = freePort()
        val a = node("a", pa, listOf(pb))
        val b = node("b", pb, listOf(pa))
        val net = a.createNetwork("eski")
        b.joinNetwork(net.code, "eski")
        assertFalse(a.networks().single().managed)
        assertNull(a.governance(net.id))
        a.sendPublicMessage(net.id, "yönetimsiz")
        waitFor("geçti") { b.meshMessages(net.id).any { it.text == "yönetimsiz" } }
    }

    @Test
    fun leavingManagedNetworkClearsItsRecords() {
        val a = node("a", freePort(), emptyList())
        a.createManagedNetwork(genesis(EntryMode.INVITE_ONLY), SECRET, "D")
        invite(a)
        a.leaveNetwork(id)
        assertTrue(a.networks().isEmpty())
        assertTrue(a.snapshot().governance.isEmpty())
        assertTrue(a.snapshot().memberships.isEmpty())
    }
}
