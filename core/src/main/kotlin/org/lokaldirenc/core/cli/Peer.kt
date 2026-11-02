package org.lokaldirenc.core.cli

import org.lokaldirenc.core.Node
import org.lokaldirenc.core.NodeConfig
import org.lokaldirenc.core.StateStore
import org.lokaldirenc.core.TransferStatus
import java.io.File
import java.net.InetSocketAddress

/**
 * Geliştirici aracı: emülatörle uçtan uca denemede karşı cihaz rolünü oynar.
 * Doğrulama kodlarını otomatik onaylar ve durumu ŞİFRESİZ yazar; gerçek kullanım için değildir.
 *
 *   peer --dir D --code KOD [--beacon-to host:port] [--send dosya --to host:port]
 */
private class PlainStore(private val file: File) : StateStore {
    override fun load() = file.takeIf { it.exists() }?.readBytes()
    override fun save(data: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(data)
    }
    override fun destroy() {
        file.delete()
    }
    override val protection = "YOK (geliştirici aracı)"
}

fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0].removePrefix("--") to it[1] }
    fun address(s: String) = s.split(":").let { InetSocketAddress(it[0], it[1].toInt()) }
    val dir = File(opts.getValue("dir"))
    val node = Node(
        dir, PlainStore(File(dir, "state.json")),
        NodeConfig(
            discoveryPort = opts["discovery-port"]?.toInt() ?: 47474,
            extraBeaconTargets = opts["beacon-to"]?.split(",")?.map(::address) ?: emptyList(),
        ),
    )
    node.setNick(opts["nick"] ?: "masaustu")
    node.start()
    val net = node.joinNetwork(opts.getValue("code"), "deneme")
    println("HAZIR port=${net.port}")

    val seen = HashMap<String, String>()
    val printed = HashSet<String>()
    node.onChange = {
        synchronized(printed) {
            for (m in node.meshMessages(net.id)) {
                if (printed.add(m.id)) println("MESAJ atlama=${m.hops} ozel=${m.direct} giden=${m.outgoing} kimden=${m.fromNick}: ${m.text}")
            }
            for (c in node.meshContacts(net.id)) {
                if (printed.add("kisi" + c.id)) println("KISI ${c.nick} kanitli=${c.proven}")
            }
        }
        for (t in node.transfers()) {
            if (t.needsPairing) {
                println("KOD ${t.sas}")
                node.confirmPairing(t.id, true)
            }
            if (t.needsAccept) node.respondOffer(t.id, true)
            val line = "${t.status} ${t.fileName} ${t.chunksDone}/${t.chunksTotal} kod=${t.sas} hata=${t.error} karantina=${t.quarantined} uyarilar=${t.warnings}"
            val important = t.status != TransferStatus.TRANSFERRING || t.chunksDone == t.chunksTotal
            if (seen[t.id] != line && important) {
                seen[t.id] = line
                println(line)
            }
        }
        System.out.flush()
    }
    opts["send"]?.let { path ->
        val to = address(opts.getValue("to"))
        val source = File(path)
        val copy = File(node.outbox, source.name).also { source.copyTo(it, overwrite = true) }
        node.send(net.id, to.hostString, to.port, null, copy, source.name)
    }
    // Karşılıklı kişi öğrenildikten sonra: --say herkese, --dm "ad:metin" özel.
    opts["say"]?.let { text ->
        Thread.sleep(opts["say-after"]?.toLong() ?: 8000)
        println("GONDERILDI ${node.sendPublicMessage(net.id, text)}")
    }
    opts["dm"]?.let { spec ->
        val (nick, text) = spec.split(":", limit = 2)
        while (node.meshContacts(net.id).none { it.nick == nick }) Thread.sleep(500)
        println("OZEL GONDERILDI ${node.sendDirectMessage(net.id, node.meshContacts(net.id).first { it.nick == nick }.id, text)}")
    }
    Thread.currentThread().join()
}
