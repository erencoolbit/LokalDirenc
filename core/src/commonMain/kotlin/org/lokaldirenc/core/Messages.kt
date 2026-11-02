package org.lokaldirenc.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1
const val PURPOSE_TRANSFER = "transfer"
const val PURPOSE_MESH = "mesh"

@Serializable
sealed class Msg {
    @Serializable @SerialName("hello")
    data class Hello(val proto: Int, val nick: String, val purpose: String = PURPOSE_TRANSFER) : Msg()

    /** Mesh eşitlemesi: iletilebilir zarf kimlikleri ve gönderenin kendi duyurusu. */
    @Serializable @SerialName("mesh_inv")
    data class MeshInv(val ids: List<String>, val self: MeshWire? = null, val digest: String = "") : Msg()

    @Serializable @SerialName("mesh_push")
    data class MeshPush(val items: List<MeshWire>) : Msg()

    @Serializable @SerialName("mesh_end")
    data object MeshEnd : Msg()

    /** Bu taraf doğrulama kodunu onayladı veya karşı tarafı zaten tanıyor. */
    @Serializable @SerialName("pair_ok")
    data object PairOk : Msg()

    @Serializable @SerialName("offer")
    data class Offer(
        val name: String,
        val size: Long,
        val type: String,
        val fileHash: String,
        val chunkSize: Int,
        val chunkHashes: List<String>,
        val root: String,
    ) : Msg()

    @Serializable @SerialName("accept")
    data class Accept(val have: Int) : Msg()

    @Serializable @SerialName("reject")
    data class Reject(val reason: String) : Msg()

    @Serializable @SerialName("get")
    data class Get(val indices: List<Int>) : Msg()

    @Serializable @SerialName("done")
    data object Done : Msg()

    @Serializable @SerialName("cancel")
    data class Cancel(val reason: String) : Msg()
}
