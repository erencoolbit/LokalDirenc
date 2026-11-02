package org.lokaldirenc.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Yönetilen ağa katılma bilgisi. Ağ sırrı bağlantı anahtarlarını üretir, bu yüzden bu bağlantı
 * kimde varsa ağa bağlanabilir. Açık ve karma ağda QR ile paylaşılır; davetli ağda yalnız
 * davetin içinde gider. Sır, yönetici olduğunu kanıtlamaz (Bölüm 6.2).
 */
@Serializable
data class ManagedJoin(
    val networkId: String,
    val secret: String,
    val name: String,
    /** Yalnız katılmadan önce göstermek için; asıl kural imzalı zincirden okunur. */
    val modeHint: EntryMode,
    val ticket: InviteTicket? = null,
    val inviteSecret: String? = null,
)

/** Yöneticiler arasında elden gezen imza paketi: ağ henüz yokken ilk olay böyle imzalanır. */
@Serializable
data class GenesisBundle(val event: GovernanceEvent, val secret: String, val name: String)

/** Onay bekleyen karar veya davet. Eşik dolunca uygulanır. */
@Serializable
data class Proposal(val event: GovernanceEvent? = null, val ticket: InviteTicket? = null) {
    val key: String get() = event?.hash() ?: "invite:${ticket?.inviteId}"
    val signatures: List<GovernanceSignature> get() = event?.signatures ?: ticket?.signatures ?: emptyList()
}

@OptIn(ExperimentalEncodingApi::class)
object ManagedLinks {
    private const val JOIN = "ld://managed?d="
    private const val BUNDLE = "ld://sign?d="
    private val json = Json { ignoreUnknownKeys = true }
    private val base64 = Base64.UrlSafe

    fun encode(join: ManagedJoin): String = JOIN + base64.encode(json.encodeToString(ManagedJoin.serializer(), join).encodeToByteArray())

    fun encode(bundle: GenesisBundle): String = BUNDLE + base64.encode(json.encodeToString(GenesisBundle.serializer(), bundle).encodeToByteArray())

    /** Yapıştırırken araya giren boşluk ve satır sonları paketi bozmasın. */
    private fun clean(text: String) = text.filterNot { it.isWhitespace() }

    fun isJoin(text: String) = clean(text).startsWith(JOIN)

    fun isBundle(text: String) = clean(text).startsWith(BUNDLE)

    /** Bozuk veya sahte bağlantıda null. İçerik güvenilmeyen veridir, sonra doğrulanır. */
    fun parseJoin(text: String): ManagedJoin? = runCatching {
        json.decodeFromString(ManagedJoin.serializer(), base64.decode(clean(text).removePrefix(JOIN)).decodeToString())
    }.getOrNull()?.takeIf { it.networkId.length == 64 && it.secret.length == 64 && runCatching { it.networkId.unhex(); it.secret.unhex() }.isSuccess }

    fun parseBundle(text: String): GenesisBundle? = runCatching {
        json.decodeFromString(GenesisBundle.serializer(), base64.decode(clean(text).removePrefix(BUNDLE)).decodeToString())
    }.getOrNull()?.takeIf { it.secret.length == 64 && runCatching { it.secret.unhex() }.isSuccess }
}
