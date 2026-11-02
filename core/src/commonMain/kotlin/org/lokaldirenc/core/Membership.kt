package org.lokaldirenc.core

import kotlinx.serialization.Serializable

/**
 * Davet bileti (Bölüm 5.5, 6.2). Yöneticiler eşik imzayla bir kez imzalar ve kodu paylaşır;
 * kodu kullanan kişi çevrimiçi yönetici gerekmeden kendi kimliğine bağlı üyelik kanıtı üretir.
 * Herkes bu kanıtı sırrı bilmeden doğrulayabilir.
 *
 * Sunucusuz sistemde "tek kullanım" kesin olarak zorlanamaz: aynı kodu kullanan iki kişi aynı
 * ana kadar birbirinden habersiz kabul edilebilir. Her düğüm ilk gördüğünü kabul eder, sonrakini
 * reddeder. Süre de cihaz saatine dayanır. Anahtar döndürme, eski biletleri geçersiz kılar (Bölüm 5.5).
 */
@Serializable
data class InviteTicket(
    val networkId: String,
    val inviteId: String,
    val inviteKey: String,
    val expiresAt: Long,
    val maxUses: Int = 1,
    val signatures: List<GovernanceSignature> = emptyList(),
) {
    fun signedBytes(): ByteArray = Canon.fields("LD-INVITE-v1", networkId, inviteId, inviteKey, expiresAt.toString(), maxUses.toString())

    fun signedBy(privateKey: ByteArray): InviteTicket {
        val public = platformCrypto.ed25519PublicOf(privateKey).hex()
        val signature = platformCrypto.ed25519Sign(privateKey, signedBytes()).hex()
        return copy(signatures = signatures.filter { it.key != public } + GovernanceSignature(public, signature))
    }

    fun validSignerCount(set: ManagementKeySet): Int {
        val message = signedBytes()
        return signatures.map { it.key }.distinct().count { key ->
            key in set.keys && signatures.any { s -> s.key == key && verifyHex(key, s.sig, message) }
        }
    }
}

/** Bileti bir kimliğe bağlar: başkası aynı kanıtı kendi anahtarı için kullanamaz. */
@Serializable
data class MembershipProof(val ticket: InviteTicket, val memberFingerprint: String, val issuedAt: Long, val signature: String)

@Serializable
data class RevokePayload(val value: String)

object Invites {
    const val DEFAULT_LIFETIME_MS = 24L * 3_600_000

    class Draft(val ticket: InviteTicket, val secret: String)

    /** Sır yalnız davet edilene verilir. Bilet imzalanmak üzere yöneticilere gider. */
    fun draft(networkId: String, expiresAt: Long, maxUses: Int = 1): Draft {
        val secret = platformCrypto.ed25519GeneratePrivate()
        val inviteId = platformCrypto.randomBytes(16).hex()
        val ticket = InviteTicket(networkId, inviteId, platformCrypto.ed25519PublicOf(secret).hex(), expiresAt, maxUses)
        return Draft(ticket, secret.hex())
    }

    private fun proofBytes(ticket: InviteTicket, memberFingerprint: String, issuedAt: Long) =
        Canon.fields("LD-MEMBER-v1", ticket.networkId, ticket.inviteId, memberFingerprint, issuedAt.toString())

    fun redeem(ticket: InviteTicket, secret: String, memberFingerprint: String, issuedAt: Long): MembershipProof =
        MembershipProof(ticket, memberFingerprint, issuedAt, platformCrypto.ed25519Sign(secret.unhex(), proofBytes(ticket, memberFingerprint, issuedAt)).hex())

    private fun adminBytes(networkId: String, memberFingerprint: String) = Canon.fields("LD-ADMIN-MEMBER-v1", networkId, memberFingerprint)

    /** Yönetici kendine davet yazmak zorunda kalmasın: anahtarıyla kendi kimliğini imzalar. */
    fun adminProof(privateKey: ByteArray, networkId: String, memberFingerprint: String) = GovernanceSignature(
        platformCrypto.ed25519PublicOf(privateKey).hex(),
        platformCrypto.ed25519Sign(privateKey, adminBytes(networkId, memberFingerprint)).hex(),
    )

    fun verifyAdmin(proof: GovernanceSignature, log: GovernanceLog, fingerprint: String): Boolean {
        val keys = log.keys ?: return false
        return proof.key in keys.keys && verifyHex(proof.key, proof.sig, adminBytes(log.networkId, fingerprint))
    }

    /** Kullanım sayısı ayrı denetlenir, çünkü her düğümün kendi gördüklerine bağlıdır. */
    fun verify(proof: MembershipProof, log: GovernanceLog, expectedFingerprint: String): Boolean {
        val ticket = proof.ticket
        val keys = log.keys ?: return false
        if (ticket.networkId != log.networkId || proof.memberFingerprint != expectedFingerprint) return false
        if (ticket.maxUses < 1 || proof.issuedAt > ticket.expiresAt) return false
        if (ticket.inviteId in log.revokedInvites || proof.memberFingerprint in log.revokedMembers) return false
        if (ticket.validSignerCount(keys) < keys.threshold) return false
        return verifyHex(ticket.inviteKey, proof.signature, proofBytes(ticket, proof.memberFingerprint, proof.issuedAt))
    }
}

internal fun verifyHex(key: String, signature: String, message: ByteArray): Boolean = try {
    platformCrypto.ed25519Verify(key.unhex(), signature.unhex(), message)
} catch (_: IllegalArgumentException) {
    false
}
