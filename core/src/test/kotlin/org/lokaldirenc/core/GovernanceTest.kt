package org.lokaldirenc.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GovernanceTest {
    private var clock = 1_800_000_000_000L
    private val day = 24L * 3_600_000

    private class Admin {
        val priv: ByteArray = MeshEngine.generateSigningKey()
        val pub: String = Ed25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded.hex()
    }

    private val a = Admin()
    private val b = Admin()
    private val c = Admin()
    private val keys = ManagementKeySet(2, listOf(a.pub, b.pub, c.pub))
    private val id = GovernanceLog.networkIdOf(keys)

    private fun definition(name: String = "Mahalle", term: Long = 30 * day) = NetworkDefinition(name, adminTermExpiry = clock + term)

    private fun genesis(vararg signers: Admin): GovernanceEvent {
        var e = GovernanceLog.genesisDraft(keys, definition(), clock)
        signers.forEach { e = e.signedBy(it.priv) }
        return e
    }

    private fun log() = GovernanceLog.bootstrap(id, listOf(genesis(a, b))) { clock }

    private fun GovernanceEvent.by(vararg signers: Admin): GovernanceEvent {
        var e = this
        signers.forEach { e = e.signedBy(it.priv) }
        return e
    }

    private fun rejected(expected: GovernanceReject, block: () -> Unit) {
        assertEquals(expected, assertFailsWith<GovernanceRejected> { block() }.reason)
    }

    @Test
    fun networkIdIsSelfCertifyingAndOrderIndependent() {
        val reordered = ManagementKeySet(2, listOf(c.pub, a.pub, b.pub))
        assertEquals(id, GovernanceLog.networkIdOf(reordered))
        assertNotEquals(id, GovernanceLog.networkIdOf(ManagementKeySet(3, keys.keys)), "eşik kimliğe dahil")
        assertNotEquals(NetCode.networkId(NetCode.generate()).hex(), id)
    }

    @Test
    fun keySetRulesRejectSingleAdminAndDuplicates() {
        assertFalse(ManagementKeySet(1, listOf(a.pub)).isValid(), "tek anahtarlı yönetici yok")
        assertFalse(ManagementKeySet(2, listOf(a.pub, a.pub)).isValid(), "aynı anahtar iki kez sayılmaz")
        assertFalse(ManagementKeySet(3, listOf(a.pub, b.pub)).isValid())
        assertFalse(ManagementKeySet(2, listOf(a.pub, "zz")).isValid())
        assertTrue(keys.isValid())
    }

    @Test
    fun validGenesisBootstraps() {
        val l = log()
        assertEquals(ManagementMode.ACTIVE, l.mode)
        assertEquals("Mahalle", l.definition!!.name)
        assertEquals(1, l.nextSequence)
    }

    @Test
    fun fakeNetworkCannotClaimAnotherNetworkId() {
        // Saldırgan kendi anahtarlarıyla kurban ağın kimliğini taşımaya çalışır.
        val x = Admin()
        val y = Admin()
        val fake = ManagementKeySet(2, listOf(x.pub, y.pub))
        val forged = GovernanceEvent(id, GovernanceTypes.GENESIS, 0, "", clock, GovernanceLog.encode(GenesisPayload(fake, definition()))).by(x, y)
        rejected(GovernanceReject.WRONG_NETWORK) { GovernanceLog.bootstrap(id, listOf(forged)) { clock } }
        // Kendi kimliğiyle kurarsa geçerli ama QR'daki kimlikle eşleşmez.
        val own = GovernanceLog.genesisDraft(fake, definition(), clock).by(x, y)
        rejected(GovernanceReject.WRONG_NETWORK) { GovernanceLog.bootstrap(id, listOf(own)) { clock } }
    }

    @Test
    fun oneKeyAloneCannotAct() {
        rejected(GovernanceReject.NOT_ENOUGH_SIGNATURES) { GovernanceLog.bootstrap(id, listOf(genesis(a))) { clock } }
        val l = log()
        val rename = l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("Ele geçirildi")))
        rejected(GovernanceReject.NOT_ENOUGH_SIGNATURES) { l.apply(rename.by(a)) }
        val twice = rename.by(a).let { it.copy(signatures = it.signatures + it.signatures) }
        rejected(GovernanceReject.NOT_ENOUGH_SIGNATURES) { l.apply(twice) }
        assertEquals("Mahalle", l.definition!!.name)
        l.apply(rename.by(a, c))
        assertEquals("Ele geçirildi", l.definition!!.name)
    }

    @Test
    fun outsiderOrForgedSignaturesDoNotCount() {
        val l = log()
        val outsider = Admin()
        val e = l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("X")))
        rejected(GovernanceReject.NOT_ENOUGH_SIGNATURES) { l.apply(e.by(a, outsider)) }
        val forged = e.by(a).let { it.copy(signatures = it.signatures + GovernanceSignature(b.pub, "00".repeat(64))) }
        rejected(GovernanceReject.NOT_ENOUGH_SIGNATURES) { l.apply(forged) }
        val garbage = e.by(a).let { it.copy(signatures = it.signatures + GovernanceSignature(b.pub, "zz")) }
        rejected(GovernanceReject.NOT_ENOUGH_SIGNATURES) { l.apply(garbage) }
    }

    @Test
    fun signatureDoesNotCarryOverToAlteredEvent() {
        val l = log()
        val e = l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("Doğru"))).by(a, b)
        val altered = e.copy(payload = GovernanceLog.encode(definition("Değiştirilmiş")))
        rejected(GovernanceReject.NOT_ENOUGH_SIGNATURES) { l.apply(altered) }
    }

    @Test
    fun allowListRejectsPowersThatDoNotExistEvenWithAllSignatures() {
        val l = log()
        for (type in listOf("delete_user", "remote_wipe", "read_messages", "force_update", "ban_everyone", "")) {
            rejected(GovernanceReject.NOT_ALLOWED) { l.apply(l.draft(type, "{}").by(a, b, c)) }
        }
        rejected(GovernanceReject.NOT_ALLOWED) { l.apply(l.draft(GovernanceTypes.GENESIS, "{}").by(a, b, c)) }
        assertEquals(1, l.nextSequence)
    }

    @Test
    fun chainRejectsReplaySkipAndBrokenLink() {
        val l = log()
        val first = l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("Bir"))).by(a, b)
        l.apply(first)
        rejected(GovernanceReject.BAD_SEQUENCE) { l.apply(first) }
        val skip = l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition())).copy(sequence = 5).by(a, b)
        rejected(GovernanceReject.BAD_SEQUENCE) { l.apply(skip) }
        val broken = l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition())).copy(prevHash = "00".repeat(32)).by(a, b)
        rejected(GovernanceReject.BROKEN_CHAIN) { l.apply(broken) }
    }

    @Test
    fun forkByAdminsIsDetected() {
        val l = log()
        val base = l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("Bir")))
        val other = l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("İki")))
        l.apply(base.by(a, b))
        rejected(GovernanceReject.FORK) { l.apply(other.by(b, c)) }
        assertEquals("Bir", l.definition!!.name)
    }

    @Test
    fun rotationKeepsIdAndOldKeysStopWorking() {
        val l = log()
        val d = Admin()
        val e = Admin()
        val next = ManagementKeySet(2, listOf(d.pub, e.pub, c.pub))
        l.apply(l.draft(GovernanceTypes.KEY_ROTATION, GovernanceLog.encode(RotationPayload(next))).by(a, b))
        assertEquals(id, l.networkId, "kimlik değişmez")
        rejected(GovernanceReject.NOT_ENOUGH_SIGNATURES) {
            l.apply(l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("Eski"))).by(a, b))
        }
        l.apply(l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("Yeni"))).by(d, e))
        assertEquals("Yeni", l.definition!!.name)
        rejected(GovernanceReject.BAD_PAYLOAD) {
            l.apply(l.draft(GovernanceTypes.KEY_ROTATION, GovernanceLog.encode(RotationPayload(ManagementKeySet(1, listOf(d.pub))))).by(d, e))
        }
    }

    @Test
    fun chainReplaysIdenticallyOnAnotherDevice() {
        val l = log()
        val events = ArrayList<GovernanceEvent>().apply { add(genesis(a, b)) }
        fun push(e: GovernanceEvent) {
            l.apply(e)
            events += e
        }
        val d = Admin()
        push(l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("Bir"))).by(a, b))
        push(l.draft(GovernanceTypes.KEY_ROTATION, GovernanceLog.encode(RotationPayload(ManagementKeySet(2, listOf(b.pub, c.pub, d.pub))))).by(a, c))
        push(l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("İki"))).by(c, d))
        val other = GovernanceLog.bootstrap(id, events) { clock }
        assertEquals(l.lastHash, other.lastHash)
        assertEquals("İki", other.definition!!.name)
        assertEquals(l.keys, other.keys)
    }

    @Test
    fun expiredTermSuspendsSettingsButAllowsRecovery() {
        val l = log()
        clock += 31 * day
        assertEquals(ManagementMode.SUSPENDED, l.mode)
        rejected(GovernanceReject.TERM_EXPIRED) {
            l.apply(l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition("Askıda"))).by(a, b))
        }
        // Çalınan anahtar askıdayken de değiştirilebilmeli.
        val d = Admin()
        l.apply(l.draft(GovernanceTypes.KEY_ROTATION, GovernanceLog.encode(RotationPayload(ManagementKeySet(2, listOf(a.pub, b.pub, d.pub))))).by(a, b))
        l.apply(l.draft(GovernanceTypes.TERM_RENEW, GovernanceLog.encode(TermPayload(clock + 30 * day))).by(a, b))
        assertEquals(ManagementMode.ACTIVE, l.mode)
    }

    @Test
    fun termCannotBeEndlessOrInPast() {
        val l = log()
        rejected(GovernanceReject.BAD_TERM) {
            l.apply(l.draft(GovernanceTypes.TERM_RENEW, GovernanceLog.encode(TermPayload(clock + 10 * 365 * day))).by(a, b))
        }
        rejected(GovernanceReject.BAD_TERM) {
            l.apply(l.draft(GovernanceTypes.TERM_RENEW, GovernanceLog.encode(TermPayload(clock - day))).by(a, b))
        }
        val endless = GovernanceLog.genesisDraft(keys, NetworkDefinition("X", adminTermExpiry = clock + 10 * 365 * day), clock).by(a, b)
        rejected(GovernanceReject.BAD_TERM) { GovernanceLog.bootstrap(id, listOf(endless)) { clock } }
    }

    @Test
    fun retirementWaitsAndCanBeCancelled() {
        val l = log()
        l.apply(l.draft(GovernanceTypes.RETIRE_ANNOUNCE, GovernanceLog.encode(RetirePayload("taşınıyoruz"))).by(a, b))
        clock += 71 * 3_600_000
        assertEquals(ManagementMode.ACTIVE, l.mode, "72 saat dolmadan sonlanmaz")
        l.apply(l.draft(GovernanceTypes.RETIRE_CANCEL, "{}").by(b, c))
        clock += 2 * 3_600_000
        assertEquals(ManagementMode.ACTIVE, l.mode)
        rejected(GovernanceReject.NOTHING_TO_CANCEL) { l.apply(l.draft(GovernanceTypes.RETIRE_CANCEL, "{}").by(b, c)) }
    }

    @Test
    fun backdatedRetirementCannotSkipWait() {
        val l = log()
        val old = l.draft(GovernanceTypes.RETIRE_ANNOUNCE, GovernanceLog.encode(RetirePayload()), issuedAt = clock - 30 * day).by(a, b)
        l.apply(old)
        assertEquals(ManagementMode.ACTIVE, l.mode, "bekleme bu cihazın gördüğü andan sayılır")
        clock += GovernanceLog.RETIRE_WAIT_MS
        assertEquals(ManagementMode.RETIRED, l.mode)
    }

    @Test
    fun retiredNetworkIsFinal() {
        val l = log()
        l.apply(l.draft(GovernanceTypes.RETIRE_NOW, GovernanceLog.encode(RetirePayload("anahtar çalındı"))).by(a, b))
        assertEquals(ManagementMode.RETIRED, l.mode)
        rejected(GovernanceReject.RETIRED) {
            l.apply(l.draft(GovernanceTypes.TERM_RENEW, GovernanceLog.encode(TermPayload(clock + day))).by(a, b, c))
        }
    }

    @Test
    fun farFutureEventAndBadPayloadAndWrongNetworkAreRejected() {
        val l = log()
        rejected(GovernanceReject.CLOCK) {
            l.apply(l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition()), issuedAt = clock + 2 * day).by(a, b))
        }
        rejected(GovernanceReject.BAD_PAYLOAD) { l.apply(l.draft(GovernanceTypes.DEFINITION, "cop").by(a, b)) }
        rejected(GovernanceReject.BAD_PAYLOAD) { l.apply(l.draft(GovernanceTypes.DEFINITION, """{"name":"x","adminTermExpiry":1,"yetki":"sinirsiz"}""").by(a, b)) }
        val elsewhere = l.draft(GovernanceTypes.DEFINITION, GovernanceLog.encode(definition())).copy(networkId = "00".repeat(32)).by(a, b)
        rejected(GovernanceReject.WRONG_NETWORK) { l.apply(elsewhere) }
    }

    @Test
    fun fieldBoundariesCannotBeShifted() {
        assertFalse(Canon.fields("ab", "c").contentEquals(Canon.fields("a", "bc")))
    }

    @Test
    fun displayNameIsCleaned() {
        val e = GovernanceLog.genesisDraft(keys, NetworkDefinition("‮adın\u0000 ağı" + "x".repeat(100), adminTermExpiry = clock + day), clock).by(a, b)
        val name = GovernanceLog.bootstrap(id, listOf(e)) { clock }.definition!!.name
        assertTrue(name.length <= 40 && name.startsWith("adın"))
        assertFalse(name.any { it.isISOControl() || it == '‮' })
    }
}
