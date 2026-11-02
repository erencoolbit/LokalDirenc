package org.lokaldirenc.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer

const val PROTOCOL_VERSION = 1
const val PURPOSE_TRANSFER = "transfer"
const val PURPOSE_MESH = "mesh"

/** Protokol sürümü prologue içinde: farklı sürüm veya farklı ağ el sıkışmayı tamamlayamaz. */
private fun prologue(networkId: ByteArray) = "LD-PROTO-$PROTOCOL_VERSION|".toByteArray() + networkId

@Serializable
sealed class Msg {
    @Serializable @SerialName("hello")
    data class Hello(val proto: Int, val nick: String, val purpose: String = PURPOSE_TRANSFER) : Msg()

    /** Mesh eşitlemesi: iletilebilir zarf kimlikleri ve gönderenin kendi duyurusu. */
    @Serializable @SerialName("mesh_inv")
    data class MeshInv(val ids: List<String>, val self: MeshWire? = null) : Msg()

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

sealed class Incoming {
    data class Control(val msg: Msg) : Incoming()
    class Chunk(val index: Int, val data: ByteArray) : Incoming()
}

class ProtocolException(message: String) : Exception(message)

/** Noise oturumu üzerinde çerçeveli mesajlaşma. Düz metin yolu yoktur. */
class SecureChannel internal constructor(
    private val link: Link,
    private val session: Noise.Result,
) : AutoCloseable {
    private val input = DataInputStream(link.input.buffered())
    private val output = DataOutputStream(link.output.buffered())

    /** Karşı tarafın ağ içi takma kimliği: statik anahtarın özeti. */
    val remoteFingerprint: String = sha256(session.remoteStatic).hex()

    /**
     * İki ekranda karşılaştırılacak kısa kod. El sıkışma özetinden türer, bu yüzden araya
     * giren biri iki tarafta aynı kodu üretemez. 40 bit: 6 haneli koddan farklı olarak
     * el sıkışma sırasında anahtar deneyerek tutturmak pratik değildir.
     */
    val sas: String = base32(sha256("LD-SAS-v1".toByteArray() + session.handshakeHash), 8)
        .chunked(4).joinToString("-")

    fun setTimeout(ms: Int) {
        link.setTimeout(ms)
    }

    private fun writeMessage(body: ByteArray) {
        synchronized(output) {
            var offset = 0
            do {
                val len = minOf(body.size - offset, MAX_PLAIN_FRAGMENT)
                val last = offset + len == body.size
                val plain = ByteArray(len + 1)
                plain[0] = if (last) 1 else 0
                System.arraycopy(body, offset, plain, 1, len)
                val cipher = session.send.encrypt(plain)
                output.writeShort(cipher.size)
                output.write(cipher)
                offset += len
            } while (offset < body.size)
            output.flush()
        }
    }

    private fun readMessage(): ByteArray {
        val assembled = ByteArrayOutputStream()
        while (true) {
            val size = input.readUnsignedShort()
            val cipher = ByteArray(size).also { input.readFully(it) }
            val plain = session.recv.decrypt(cipher)
            if (plain.isEmpty()) throw ProtocolException("boş çerçeve")
            assembled.write(plain, 1, plain.size - 1)
            if (assembled.size() > MAX_MESSAGE) throw ProtocolException("mesaj çok büyük")
            if (plain[0] == 1.toByte()) return assembled.toByteArray()
        }
    }

    fun send(msg: Msg) {
        writeMessage(byteArrayOf(KIND_CONTROL) + JSON.encodeToString<Msg>(msg).toByteArray())
    }

    fun sendChunk(index: Int, data: ByteArray, length: Int = data.size) {
        val buf = ByteBuffer.allocate(5 + length)
        buf.put(KIND_CHUNK).putInt(index).put(data, 0, length)
        writeMessage(buf.array())
    }

    fun receive(): Incoming {
        val body = readMessage()
        if (body.isEmpty()) throw ProtocolException("boş mesaj")
        return when (body[0]) {
            KIND_CONTROL -> Incoming.Control(
                try {
                    JSON.decodeFromString<Msg>(String(body, 1, body.size - 1))
                } catch (_: IllegalArgumentException) {
                    throw ProtocolException("geçersiz mesaj")
                },
            )
            KIND_CHUNK -> {
                if (body.size < 5) throw ProtocolException("geçersiz parça")
                val index = ByteBuffer.wrap(body, 1, 4).int
                Incoming.Chunk(index, body.copyOfRange(5, body.size))
            }
            else -> throw ProtocolException("bilinmeyen mesaj türü")
        }
    }

    fun receiveControl(): Msg = when (val m = receive()) {
        is Incoming.Control -> m.msg
        is Incoming.Chunk -> throw ProtocolException("beklenmeyen parça")
    }

    override fun close() {
        link.close()
    }

    companion object {
        private const val KIND_CONTROL: Byte = 1
        private const val KIND_CHUNK: Byte = 2
        private const val MAX_PLAIN_FRAGMENT = 65535 - 16 - 1
        private const val MAX_MESSAGE = 16 * 1024 * 1024
        internal val JSON = Json { classDiscriminator = "t"; ignoreUnknownKeys = true }

        /** Kodu bilmeyen veya sürümü uyuşmayan düğümde [NoiseException] atar. */
        fun handshake(socket: Socket, initiator: Boolean, staticPriv: ByteArray, psk: ByteArray, networkId: ByteArray): SecureChannel =
            handshake(SocketLink(socket), initiator, staticPriv, psk, networkId)

        fun handshake(
            link: Link,
            initiator: Boolean,
            staticPriv: ByteArray,
            psk: ByteArray,
            networkId: ByteArray,
        ): SecureChannel {
            val hs = Noise.Handshake(initiator, staticPriv, psk, prologue(networkId))
            val input = DataInputStream(link.input)
            val output = DataOutputStream(link.output)
            fun send() {
                val m = hs.write()
                output.writeShort(m.size)
                output.write(m)
                output.flush()
            }
            fun recv() {
                val size = input.readUnsignedShort()
                if (size > MAX_HANDSHAKE) throw NoiseException("el sıkışma mesajı çok büyük")
                hs.read(ByteArray(size).also { input.readFully(it) })
            }
            if (initiator) {
                send(); recv(); send()
            } else {
                recv(); send(); recv()
            }
            return SecureChannel(link, hs.split())
        }

        private const val MAX_HANDSHAKE = 256
    }
}
