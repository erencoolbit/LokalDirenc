package org.lokaldirenc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NoiseVectorTest {
    private fun bytes(hex: String) = hex.unhex()

    /** Vektörler bağımsız bir Noise uygulamasından üretildi: tools/gen_noise_vectors.py */
    @Test
    fun matchesIndependentImplementationByteForByte() {
        val text = javaClass.getResource("/noise_xxpsk0_vectors.json")!!.readText()
        val vectors = Json.parseToJsonElement(text).jsonArray
        assertEquals(3, vectors.size)
        for (element in vectors) {
            val v = element.jsonObject
            fun s(k: String) = bytes(v.getValue(k).jsonPrimitive.content)
            val i = Noise.Handshake(true, s("staticI"), s("psk"), s("prologue"), s("ephemeralI"))
            val r = Noise.Handshake(false, s("staticR"), s("psk"), s("prologue"), s("ephemeralR"))
            val payloads = v.getValue("payloads").jsonArray.map { bytes(it.jsonPrimitive.content) }
            val messages = v.getValue("messages").jsonArray.map { bytes(it.jsonPrimitive.content) }
            for (n in messages.indices) {
                val (w, rd) = if (n % 2 == 0) i to r else r to i
                val out = w.write(payloads[n])
                assertContentEquals(messages[n], out, "mesaj $n")
                assertContentEquals(payloads[n], rd.read(out), "yük $n")
            }
            val a = i.split()
            val b = r.split()
            assertContentEquals(s("handshakeHash"), a.handshakeHash)
            assertContentEquals(s("handshakeHash"), b.handshakeHash)
            assertContentEquals(Noise.publicOf(s("staticR")), a.remoteStatic)
            assertContentEquals(Noise.publicOf(s("staticI")), b.remoteStatic)
            for (entry in v.getValue("transport").jsonArray) {
                val t = entry.jsonObject
                val plain = bytes(t.getValue("plain").jsonPrimitive.content)
                val cipher = bytes(t.getValue("cipher").jsonPrimitive.content)
                val (sender, receiver) = if (t.getValue("from").jsonPrimitive.content == "i") a to b else b to a
                assertContentEquals(cipher, sender.send.encrypt(plain))
                assertContentEquals(plain, receiver.recv.decrypt(cipher))
            }
        }
    }

    @Test
    fun sealMatchesIndependentImplementationByteForByte() {
        val text = javaClass.getResource("/noise_x_vectors.json")!!.readText()
        val vectors = Json.parseToJsonElement(text).jsonArray
        assertEquals(3, vectors.size)
        for (element in vectors) {
            val v = element.jsonObject
            fun s(k: String) = bytes(v.getValue(k).jsonPrimitive.content)
            assertContentEquals(s("sealed"), Noise.seal(s("recipientPub"), s("senderPriv"), s("prologue"), s("plain"), s("ephemeral")))
            val opened = Noise.open(s("recipientPriv"), s("prologue"), s("sealed"))
            assertContentEquals(s("plain"), opened.plain)
            assertContentEquals(Noise.publicOf(s("senderPriv")), opened.senderPub)
        }
    }

    @Test
    fun sealOpensOnlyForRecipientAndPrologue() {
        val sender = Noise.generatePrivate()
        val recipient = Noise.generatePrivate()
        val sealed = Noise.seal(Noise.publicOf(recipient), sender, byteArrayOf(1), "gizli".toByteArray())
        assertFailsWith<NoiseException> { Noise.open(Noise.generatePrivate(), byteArrayOf(1), sealed) }
        assertFailsWith<NoiseException> { Noise.open(recipient, byteArrayOf(2), sealed) }
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertFailsWith<NoiseException> { Noise.open(recipient, byteArrayOf(1), sealed) }
    }

    @Test
    fun wrongPskIsRejectedAtFirstMessage() {
        val i = Noise.Handshake(true, Noise.generatePrivate(), ByteArray(32) { 1 }, ByteArray(0))
        val r = Noise.Handshake(false, Noise.generatePrivate(), ByteArray(32) { 2 }, ByteArray(0))
        assertFailsWith<NoiseException> { r.read(i.write()) }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val psk = ByteArray(32) { 7 }
        val i = Noise.Handshake(true, Noise.generatePrivate(), psk, ByteArray(0))
        val r = Noise.Handshake(false, Noise.generatePrivate(), psk, ByteArray(0))
        val m = i.write()
        m[m.size - 1] = (m[m.size - 1] + 1).toByte()
        assertFailsWith<NoiseException> { r.read(m) }
    }
}
