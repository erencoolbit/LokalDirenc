package org.lokaldirenc.core

import kotlinx.serialization.Serializable

/** Yönetilen ağın M-of-N yönetim anahtar seti (Bölüm 11.3). Tek anahtarlı yönetici yoktur. */
@Serializable
data class ManagementKeySet(val threshold: Int, val keys: List<String>) {
    /** Sıradan bağımsız: aynı set her cihazda aynı Network ID'yi verir. */
    fun canonical(): ByteArray = Canon.fields(threshold.toString(), *keys.sorted().toTypedArray())

    fun isValid(): Boolean =
        threshold >= MIN_THRESHOLD && keys.size >= threshold && keys.toSet().size == keys.size &&
            keys.all { it.length == 64 && runCatching { it.unhex() }.isSuccess }

    /** QR'da gösterilen parmak izi (Bölüm 10.1). */
    fun fingerprint(): String = sha256(canonical()).hex()

    companion object {
        const val MIN_THRESHOLD = 2
    }
}

@Serializable
enum class EntryMode { OPEN, MIXED, INVITE_ONLY }

/** İmzalı ağ tanımı (Bölüm 6.1). */
@Serializable
data class NetworkDefinition(
    val name: String,
    val entryMode: EntryMode = EntryMode.OPEN,
    val governanceEnabled: Boolean = false,
    val adminTermExpiry: Long,
)

/**
 * Eylem izin listesi (Bölüm 11.4). Yetki, burada yazan eylemler kadardır: üyenin verisini
 * silme, cihaza komut gönderme veya içeriğe erişme gibi bir tür bilerek yoktur. Değiştirilmiş
 * bir istemcide eşik imzayla imzalanmış olsa bile resmi istemci bilinmeyen türü geçersiz sayar.
 */
object GovernanceTypes {
    const val GENESIS = "genesis"
    const val DEFINITION = "definition"
    const val KEY_ROTATION = "key_rotation"
    const val TERM_RENEW = "term_renew"
    const val RETIRE_ANNOUNCE = "retire_announce"
    const val RETIRE_CANCEL = "retire_cancel"
    const val RETIRE_NOW = "retire_now"
    val ALLOWED = setOf(GENESIS, DEFINITION, KEY_ROTATION, TERM_RENEW, RETIRE_ANNOUNCE, RETIRE_CANCEL, RETIRE_NOW)
}

@Serializable
data class GovernanceSignature(val key: String, val sig: String)

/** İmzalı yönetim olayı (Bölüm 16, SignedEvent). İmzalar özetin dışındadır, eklenebilir. */
@Serializable
data class GovernanceEvent(
    val networkId: String,
    val type: String,
    val sequence: Long,
    val prevHash: String,
    val issuedAt: Long,
    val payload: String,
    val signatures: List<GovernanceSignature> = emptyList(),
) {
    fun signedBytes(): ByteArray = Canon.fields("LD-GOV-v1", networkId, type, sequence.toString(), prevHash, issuedAt.toString(), payload)

    fun hash(): String = sha256(signedBytes()).hex()

    /** Her yönetici kendi cihazında ayrı imzalar; imzalar sonra birleştirilir. */
    fun signedBy(privateKey: ByteArray): GovernanceEvent {
        val public = platformCrypto.ed25519PublicOf(privateKey).hex()
        val signature = platformCrypto.ed25519Sign(privateKey, signedBytes()).hex()
        return copy(signatures = signatures.filter { it.key != public } + GovernanceSignature(public, signature))
    }

    /** Sette olan, farklı ve geçerli imza sayısı. Aynı anahtarın iki imzası tek sayılır. */
    fun validSignerCount(set: ManagementKeySet): Int {
        val message = signedBytes()
        return signatures.map { it.key }.distinct().count { key ->
            key in set.keys && signatures.any { s -> s.key == key && verify(key, s.sig, message) }
        }
    }

    private fun verify(key: String, sig: String, message: ByteArray): Boolean = try {
        platformCrypto.ed25519Verify(key.unhex(), sig.unhex(), message)
    } catch (_: IllegalArgumentException) {
        false
    }
}

@Serializable
data class GenesisPayload(val keys: ManagementKeySet, val definition: NetworkDefinition)

@Serializable
data class RotationPayload(val keys: ManagementKeySet)

@Serializable
data class TermPayload(val adminTermExpiry: Long)

@Serializable
data class RetirePayload(val reason: String = "")

enum class GovernanceReject {
    WRONG_NETWORK, NOT_ALLOWED, BAD_SEQUENCE, FORK, BROKEN_CHAIN, NOT_ENOUGH_SIGNATURES,
    BAD_PAYLOAD, BAD_TERM, TERM_EXPIRED, RETIRED, CLOCK, NOTHING_TO_CANCEL,
}

class GovernanceRejected(val reason: GovernanceReject) : Exception(reason.name)

enum class ManagementMode {
    /** Yönetici yetkisi geçerli. */
    ACTIVE,

    /** Yetki süresi doldu: aktarım ve içerik çalışır, ayar değişikliği ve federasyon durur (Bölüm 5.1). */
    SUSPENDED,

    /** Bu ağ kimliği sonlandı. Üyelerin verisi silinmez, karar kullanıcıya sorulur (Bölüm 11.6). */
    RETIRED,
}

/**
 * Bir yönetilen ağın doğrulanmış olay zinciri. Yalnız imzaları ve sırayı denetler; ağ iletişimi
 * bu sınıfın işi değildir. Yönetici iletişimi durduramaz, kimsenin verisini silemez: bu sınıfta
 * böyle bir yetki olmadığı gibi, emekli edilen ağda bile veriye dokunulmaz.
 *
 * Süreler cihaz saatine dayanır. Bekleme süresi olayın beyan ettiği zamandan değil, bu cihazın
 * olayı ilk gördüğü andan sayılır: böylece geçmiş tarihli olayla bekleme atlanamaz.
 */
class GovernanceLog private constructor(val networkId: String, private val now: () -> Long) {
    var keys: ManagementKeySet? = null
        private set
    var definition: NetworkDefinition? = null
        private set
    var lastHash = ""
        private set
    var nextSequence = 0L
        private set

    /** Yalnız bekleyen emeklilikte dolu: bu cihazda geçerli olacağı an. */
    var retireEffectiveAt: Long? = null
        private set

    private var retiredNow = false
    private val applied = HashMap<Long, String>()

    val mode: ManagementMode
        get() {
            val time = now()
            if (retiredNow || retireEffectiveAt?.let { time >= it } == true) return ManagementMode.RETIRED
            val expiry = definition?.adminTermExpiry ?: return ManagementMode.SUSPENDED
            return if (time >= expiry) ManagementMode.SUSPENDED else ManagementMode.ACTIVE
        }

    /** Olayı doğrular ve uygular; geçersizse [GovernanceRejected] atar ve durum değişmez. */
    fun apply(e: GovernanceEvent) {
        fun fail(reason: GovernanceReject): Nothing = throw GovernanceRejected(reason)
        if (e.networkId != networkId) fail(GovernanceReject.WRONG_NETWORK)
        if (e.type !in GovernanceTypes.ALLOWED) fail(GovernanceReject.NOT_ALLOWED)
        // Aynı sırada farklı olay: yöneticilerin çatallaması. Görünür kılınır, üstüne yazılmaz (Bölüm 17).
        applied[e.sequence]?.let { if (it != e.hash()) fail(GovernanceReject.FORK) else fail(GovernanceReject.BAD_SEQUENCE) }
        if (e.sequence != nextSequence) fail(GovernanceReject.BAD_SEQUENCE)
        if (e.prevHash != lastHash) fail(GovernanceReject.BROKEN_CHAIN)
        val time = now()
        if (e.issuedAt > time + MAX_FUTURE_MS) fail(GovernanceReject.CLOCK)
        if (mode == ManagementMode.RETIRED) fail(GovernanceReject.RETIRED)

        if (e.sequence == 0L) {
            if (e.type != GovernanceTypes.GENESIS) fail(GovernanceReject.NOT_ALLOWED)
            val p = parse<GenesisPayload>(e.payload) ?: fail(GovernanceReject.BAD_PAYLOAD)
            if (!p.keys.isValid()) fail(GovernanceReject.BAD_PAYLOAD)
            // Kendi kendini doğrulayan kimlik: sahte ağ bu kimliği taşıyan geçerli bir set üretemez.
            if (networkIdOf(p.keys) != networkId) fail(GovernanceReject.WRONG_NETWORK)
            if (e.validSignerCount(p.keys) < p.keys.threshold) fail(GovernanceReject.NOT_ENOUGH_SIGNATURES)
            if (!termAcceptable(p.definition.adminTermExpiry, e.issuedAt)) fail(GovernanceReject.BAD_TERM)
            keys = p.keys
            definition = p.definition.copy(name = cleanName(p.definition.name))
        } else {
            if (e.type == GovernanceTypes.GENESIS) fail(GovernanceReject.NOT_ALLOWED)
            val current = keys ?: fail(GovernanceReject.BAD_SEQUENCE)
            if (e.validSignerCount(current) < current.threshold) fail(GovernanceReject.NOT_ENOUGH_SIGNATURES)
            // Askıdayken ayarlar durur. Yenileme, anahtar değişimi ve sonlandırma açık kalır,
            // yoksa süresini kaçıran topluluk ağını geri alamaz, çalınan anahtarı da değiştiremez.
            val suspended = mode == ManagementMode.SUSPENDED
            when (e.type) {
                GovernanceTypes.DEFINITION -> {
                    if (suspended) fail(GovernanceReject.TERM_EXPIRED)
                    val d = parse<NetworkDefinition>(e.payload) ?: fail(GovernanceReject.BAD_PAYLOAD)
                    if (!termAcceptable(d.adminTermExpiry, e.issuedAt)) fail(GovernanceReject.BAD_TERM)
                    definition = d.copy(name = cleanName(d.name))
                }
                GovernanceTypes.TERM_RENEW -> {
                    val t = parse<TermPayload>(e.payload) ?: fail(GovernanceReject.BAD_PAYLOAD)
                    if (!termAcceptable(t.adminTermExpiry, e.issuedAt)) fail(GovernanceReject.BAD_TERM)
                    definition = definition?.copy(adminTermExpiry = t.adminTermExpiry)
                }
                GovernanceTypes.KEY_ROTATION -> {
                    val r = parse<RotationPayload>(e.payload) ?: fail(GovernanceReject.BAD_PAYLOAD)
                    if (!r.keys.isValid()) fail(GovernanceReject.BAD_PAYLOAD)
                    keys = r.keys
                }
                GovernanceTypes.RETIRE_ANNOUNCE -> {
                    parse<RetirePayload>(e.payload) ?: fail(GovernanceReject.BAD_PAYLOAD)
                    retireEffectiveAt = time + RETIRE_WAIT_MS
                }
                GovernanceTypes.RETIRE_CANCEL -> {
                    if (retireEffectiveAt == null) fail(GovernanceReject.NOTHING_TO_CANCEL)
                    retireEffectiveAt = null
                }
                GovernanceTypes.RETIRE_NOW -> {
                    parse<RetirePayload>(e.payload) ?: fail(GovernanceReject.BAD_PAYLOAD)
                    retiredNow = true
                }
                else -> fail(GovernanceReject.NOT_ALLOWED)
            }
        }
        applied[e.sequence] = e.hash()
        lastHash = e.hash()
        nextSequence = e.sequence + 1
    }

    private fun termAcceptable(expiry: Long, issuedAt: Long) = expiry > issuedAt && expiry - issuedAt <= MAX_TERM_MS

    private fun cleanName(name: String) =
        name.filter { !it.isISOControl() && it.code !in 0x202A..0x202E && it.code !in 0x2066..0x2069 }.trim().take(40)

    /** Sıradaki olayın imzasız taslağı. */
    fun draft(type: String, payload: String, issuedAt: Long = now()): GovernanceEvent =
        GovernanceEvent(networkId, type, nextSequence, lastHash, issuedAt, payload)

    companion object {
        const val RETIRE_WAIT_MS = 72L * 3_600_000
        const val DEFAULT_TERM_MS = 183L * 24 * 3_600_000

        /** Süresiz yöneticiyi önler. Belgedeki öneri 6 ay; yenilenebilir üst sınır 1 yıl. */
        const val MAX_TERM_MS = 366L * 24 * 3_600_000
        private const val MAX_FUTURE_MS = 24L * 3_600_000
        private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }

        /** Network ID = Hash("LD-NET-v1" + ilk yönetim anahtar seti) (Bölüm 6.1). */
        fun networkIdOf(keys: ManagementKeySet): String = sha256("LD-NET-v1".encodeToByteArray() + keys.canonical()).hex()

        fun encode(value: GenesisPayload) = JSON.encodeToString(GenesisPayload.serializer(), value)
        fun encode(value: NetworkDefinition) = JSON.encodeToString(NetworkDefinition.serializer(), value)
        fun encode(value: RotationPayload) = JSON.encodeToString(RotationPayload.serializer(), value)
        fun encode(value: TermPayload) = JSON.encodeToString(TermPayload.serializer(), value)
        fun encode(value: RetirePayload) = JSON.encodeToString(RetirePayload.serializer(), value)

        private inline fun <reified T> parse(text: String): T? =
            runCatching { JSON.decodeFromString<T>(text) }.getOrNull()

        /** Yeni ağın ilk olayının taslağı. Yöneticiler imzalar, sonra [bootstrap] ile doğrulanır. */
        fun genesisDraft(keys: ManagementKeySet, definition: NetworkDefinition, issuedAt: Long): GovernanceEvent =
            GovernanceEvent(networkIdOf(keys), GovernanceTypes.GENESIS, 0, "", issuedAt, encode(GenesisPayload(keys, definition)))

        /**
         * Olay zincirini baştan doğrular. [expectedNetworkId] güvenilir kaynaktan, QR'dan gelir;
         * zinciri veren düğüme güvenilmez (Bölüm 10.1).
         */
        fun bootstrap(expectedNetworkId: String, events: List<GovernanceEvent>, now: () -> Long = ::currentTimeMillis): GovernanceLog {
            val log = GovernanceLog(expectedNetworkId, now)
            events.forEach(log::apply)
            if (log.keys == null) throw GovernanceRejected(GovernanceReject.BAD_SEQUENCE)
            return log
        }
    }
}
