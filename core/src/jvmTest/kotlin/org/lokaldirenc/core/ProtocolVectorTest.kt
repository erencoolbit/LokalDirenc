package org.lokaldirenc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Baytlar Kotlin kodundan bağımsız bir Python uygulamasıyla üretildi
 * (tools/gen_protocol_vectors.py). iOS gibi yeni bir uygulama da aynı sınavdan geçmelidir.
 */
class ProtocolVectorTest {
    private val root = Json.parseToJsonElement(javaClass.getResource("/protocol_vectors.json")!!.readText()).jsonObject

    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.b(key: String) = s(key).unhex()
    private val networks = root.getValue("networks").jsonArray.map { it.jsonObject }

    private fun wireOf(o: JsonObject) = MeshWire(o.s("kind"), o.getValue("issuedAt").jsonPrimitive.long, o.s("body"), o.s("author"), o.s("sig"), o.getValue("hops").jsonPrimitive.int)

    @Test
    fun codeChecksumAndKeysMatch() {
        for (n in networks) {
            assertEquals(n.s("code"), NetCode.normalize(n.s("code")), "sağlama")
            assertContentEquals(n.b("networkId"), NetCode.networkId(n.s("code")))
            assertContentEquals(n.b("psk"), NetCode.psk(n.s("code")))
            assertContentEquals(n.b("discoveryKey"), NetCode.discoveryKey(n.s("code")))
            assertContentEquals(n.b("loraKey"), NetCode.loraKey(n.s("code")))
        }
    }

    @Test
    fun bleTokenAndAdvertMatch() {
        for (n in networks) {
            val token = NetCode.bleToken(n.b("discoveryKey"), n.getValue("bleEpoch").jsonPrimitive.long)
            assertContentEquals(n.b("bleToken"), token)
            assertContentEquals(n.b("bleAdvert"), BleAdvert.encode(token, 0x0081, 0xDEADBEEF.toInt()))
            val decoded = BleAdvert.decode(n.b("bleAdvert"))!!
            assertEquals(0x0081, decoded.psm)
            assertEquals(0xDEADBEEFL, decoded.role)
        }
    }

    @Test
    fun foreignBeaconOpens() {
        for (n in networks) {
            val packet = n.b("beacon")
            val p = assertNotNull(Beacon.open(n.b("discoveryKey"), packet, packet.size))
            assertEquals(n.getValue("beaconPort").jsonPrimitive.int, p.port)
            assertEquals(n.s("beaconFp"), p.fp)
            assertEquals(n.s("beaconNick"), p.nick)
        }
    }

    @Test
    fun foreignLoraFrameOpensAndWireMatches() {
        for (n in networks) {
            val wire = wireOf(n.getValue("wire").jsonObject)
            assertContentEquals(n.b("compactWire"), LoraCodec.encodeWire(wire))
            val fragment = assertNotNull(LoraCodec.open(n.b("loraKey"), n.b("loraFrame")))
            assertEquals(1, fragment.total)
            assertEquals(wire, LoraCodec.decodeWire(fragment.data))
        }
    }

    @Test
    fun foreignSignedEnvelopeIsAccepted() {
        for (n in networks) {
            val wire = wireOf(n.getValue("wire").jsonObject)
            val id = n.s("networkId")
            val engine = MeshEngine({ false }, { wire.issuedAt })
            engine.register(id, n.b("networkId"), MeshEngine.generateSigningKey(), Noise.generatePrivate(), "kotlin")
            assertTrue(engine.ingest(id, wire), "Python imzası doğrulanmalı")
            assertEquals("merhaba", engine.messages(id).single().text)
            assertTrue(!engine.ingest(id, wire.copy(body = wire.body.replaceFirst('e', 'f'))), "değişen zarf reddedilmeli")
        }
    }

    @Test
    fun governanceMatches() {
        val g = root.getValue("governance").jsonObject
        val set = ManagementKeySet(g.getValue("threshold").jsonPrimitive.int, g.getValue("keys").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(g.s("networkId"), GovernanceLog.networkIdOf(set))
        assertEquals(g.s("fingerprint"), set.fingerprint())
        val e = g.getValue("event").jsonObject
        val event = GovernanceEvent(
            e.s("networkId"), e.s("type"), e.getValue("sequence").jsonPrimitive.long, e.s("prevHash"),
            e.getValue("issuedAt").jsonPrimitive.long, e.s("payload"),
            e.getValue("signatures").jsonArray.map { GovernanceSignature(it.jsonObject.s("key"), it.jsonObject.s("sig")) },
        )
        assertEquals(g.s("eventHash"), event.hash())
        val now = g.getValue("now").jsonPrimitive.long
        val log = GovernanceLog.bootstrap(g.s("networkId"), listOf(event)) { now }
        assertEquals("Mahalle", log.definition!!.name)
    }

    @Test
    fun merkleAndSosAndCanonMatch() {
        val m = root.getValue("merkle").jsonObject
        val chunks = m.getValue("chunks").jsonArray.map { it.jsonPrimitive.content.unhex() }
        val leaves = chunks.map { Manifest.leaf(it).hex() }
        assertEquals(m.getValue("leaves").jsonArray.map { it.jsonPrimitive.content }, leaves)
        assertEquals(m.s("root"), Manifest.merkleRoot(leaves))

        val sos = root.getValue("sos").jsonObject
        val uuid = SosBeacon.encodeUuid(sos.getValue("session").jsonPrimitive.int, sos.getValue("battery").jsonPrimitive.int)
        assertEquals(sos.s("uuid"), uuid.hex())

        val c = root.getValue("canon").jsonObject
        val values = c.getValue("values").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(c.s("bytes"), Canon.fields(*values.toTypedArray()).hex())
    }
}
