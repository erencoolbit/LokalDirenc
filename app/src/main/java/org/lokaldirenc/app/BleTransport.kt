package org.lokaldirenc.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import org.lokaldirenc.core.BleAdvert
import org.lokaldirenc.core.Node
import org.lokaldirenc.core.WatchdogLink
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Altyapı gerektirmeyen mesh taşıması. Ortak Wi-Fi olmadan yakın üyeler birbirini dönen
 * şifreli BLE reklamıyla bulur, L2CAP üzerinden Noise oturumu açar ve zarfları eşitler.
 * Sistem soru sormaz: "insecure" kanal Bluetooth eşleştirmesi istemez, gizlilik ve üyelik
 * Noise ile sağlanır. Yalnız mesh zarfları taşınır, dosya aktarımı IP bağlantısı ister.
 *
 * Reklamda sabit kimlik yoktur: belirteç 10 dakikada bir değişir, üye olmayan için rastgele
 * görünür. Biçim için bkz. BleAdvert; iOS'un yayınlayabildiği tek hizmet kimliğine sığar.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context, private val onChange: () -> Unit) {
    enum class Status { OFF, RUNNING, UNSUPPORTED_ANDROID, NO_ADAPTER, ADAPTER_OFF, NO_PERMISSION, NO_ADVERTISING }

    @Volatile
    var status = Status.OFF
        private set

    /** Bu oturumda başarıyla tamamlanan Bluetooth eşitlemesi. */
    @Volatile
    var completedSyncs = 0
        private set

    @Volatile
    var nearby = 0
        private set

    private class NetLink(val server: BluetoothServerSocket, val psm: Int) {
        var advertiser: AdvertiseCallback? = null
        var token: ByteArray? = null
    }

    private var node: Node? = null
    private var adapter: BluetoothAdapter? = null
    private var timer: ScheduledExecutorService? = null
    private val links = ConcurrentHashMap<String, NetLink>()
    private val lastSync = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val seenDevices = ConcurrentHashMap<String, Long>()
    private val dialer = Executors.newSingleThreadExecutor { r -> Thread(r, "ld-ble-dial").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /**
     * İki cihaz birbirini aynı anda ararsa bağlantılar çakışıp zaman aşımına düşüyor.
     * Reklamdaki bu rastgele etiketi büyük olan arar, diğeri bekler. Kimlik taşımaz,
     * her başlatmada değişir.
     */
    private val roleTag = ByteArray(4).also { SecureRandom().nextBytes(it) }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
    }

    fun missingPermissions(): List<String> = requiredPermissions().filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Synchronized
    fun start(node: Node) {
        stop()
        this.node = node
        val problem = when {
            // L2CAP akış kanalı API 29 ile geldi.
            Build.VERSION.SDK_INT < 29 -> Status.UNSUPPORTED_ANDROID
            missingPermissions().isNotEmpty() -> Status.NO_PERMISSION
            else -> null
        }
        if (problem != null) return report(problem)
        val a = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return report(Status.NO_ADAPTER)
        if (!a.isEnabled) return report(Status.ADAPTER_OFF)
        if (!a.isMultipleAdvertisementSupported || a.bluetoothLeAdvertiser == null) return report(Status.NO_ADVERTISING)
        adapter = a
        a.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ld-ble-tick").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { reconcile() } }, 0, TICK_SECONDS, TimeUnit.SECONDS)
        }
        report(Status.RUNNING)
    }

    @Synchronized
    fun stop() {
        timer?.shutdownNow()
        timer = null
        adapter?.let { a ->
            if (a.isEnabled) {
                runCatching { a.bluetoothLeScanner?.stopScan(scanCallback) }
                links.values.forEach { l -> l.advertiser?.let { cb -> runCatching { a.bluetoothLeAdvertiser?.stopAdvertising(cb) } } }
            }
        }
        links.values.forEach { runCatching { it.server.close() } }
        links.clear()
        adapter = null
        nearby = 0
        node = null
        status = Status.OFF
    }

    private fun report(s: Status) {
        status = s
        onChange()
    }

    private fun epochNow() = System.currentTimeMillis() / EPOCH_MS

    /** Ağları, reklamları ve dönen belirteci güncel tutar. */
    @Synchronized
    private fun reconcile() {
        val n = node ?: return
        val a = adapter ?: return
        val ids = n.networks().map { it.id }.toSet()
        links.keys.filter { it !in ids }.forEach { id ->
            links.remove(id)?.let { l ->
                l.advertiser?.let { cb -> runCatching { a.bluetoothLeAdvertiser?.stopAdvertising(cb) } }
                runCatching { l.server.close() }
            }
        }
        for (id in ids) {
            val link = links[id] ?: openServer(a, n, id) ?: continue
            advertise(a, n, id, link)
        }
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        seenDevices.values.removeIf { it < cutoff }
        val count = seenDevices.size
        if (count != nearby) {
            nearby = count
            onChange()
        }
    }

    private fun openServer(a: BluetoothAdapter, n: Node, id: String): NetLink? {
        val server = try {
            a.listenUsingInsecureL2capChannel()
        } catch (_: IOException) {
            return null
        }
        val link = NetLink(server, server.psm)
        links[id] = link
        thread(name = "ld-ble-accept", isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "ld-ble-session", isDaemon = true) {
                    n.acceptLink(id, linkOf(socket))
                    completedSyncs++
                    onChange()
                }
            }
        }
        return link
    }

    private fun linkOf(socket: BluetoothSocket) = WatchdogLink(socket.inputStream, socket.outputStream) { socket.close() }

    private fun advertise(a: BluetoothAdapter, n: Node, id: String, link: NetLink) {
        val epoch = epochNow()
        val token = n.bleTokens(id, epoch, 0).singleOrNull() ?: return
        // Dönem veya ağ sırrı değişince belirteç değişir.
        if (link.token?.contentEquals(token) == true) return
        val le = a.bluetoothLeAdvertiser ?: return
        link.advertiser?.let { runCatching { le.stopAdvertising(it) } }
        val advert = BleAdvert.encode(token, link.psm, ByteBuffer.wrap(roleTag).int)
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                // Sistem reklam yuvası dolu olabilir: bir sonraki turda yeniden denenir.
                link.token = null
            }
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(uuidOf(advert)))
            .build()
        link.advertiser = callback
        link.token = token
        le.startAdvertising(settings, data, callback)
    }

    private fun uuidOf(token: ByteArray) = ByteBuffer.wrap(token).let { UUID(it.long, it.long) }

    private fun handle(result: ScanResult) {
        val n = node ?: return
        val uuids = result.scanRecord?.serviceUuids ?: return
        for (uuid in uuids) {
            val raw = ByteBuffer.allocate(BleAdvert.SIZE).putLong(uuid.uuid.mostSignificantBits).putLong(uuid.uuid.leastSignificantBits).array()
            val advert = BleAdvert.decode(raw) ?: continue
            val (network, keyIndex) = networkFor(n, advert.token) ?: continue
            seenDevices[result.device.address] = System.currentTimeMillis()
            // Karşı taraf hâlâ eski sırdaysa rol beklenmez: yeni sırrı ona bu taraf ulaştırır,
            // çünkü o bizim yeni reklamımızı tanıyamaz.
            if (keyIndex > 0 || Integer.toUnsignedLong(ByteBuffer.wrap(roleTag).int) > advert.role) maybeDial(n, network, result, advert.psm, keyIndex)
        }
    }

    /** Tarama saniyede çok sonuç verebilir: belirteçler dönem ve anahtar değişince hesaplanır. */
    private fun networkFor(n: Node, token: ByteArray): Pair<String, Int>? {
        val epoch = epochNow()
        val ids = n.networks().map { it.id }
        val current = ids.map { id -> n.bleTokens(id, epoch, 0).firstOrNull()?.toList() }
        val cached = tokenCache
        val table = if (cached != null && cached.epoch == epoch && cached.networks == ids && cached.current == current) {
            cached.tokens
        } else {
            ids.flatMap { id -> n.bleTokensIndexed(id, epoch, EPOCH_TOLERANCE).map { (t, index) -> t.toList() to (id to index) } }.toMap().also {
                tokenCache = TokenCache(epoch, ids, current, it)
            }
        }
        return table[token.toList()]
    }

    private class TokenCache(val epoch: Long, val networks: List<String>, val current: List<List<Byte>?>, val tokens: Map<List<Byte>, Pair<String, Int>>)

    @Volatile
    private var tokenCache: TokenCache? = null

    private fun maybeDial(n: Node, network: String, result: ScanResult, psm: Int, keyIndex: Int) {
        val device = result.device
        // BLE adresi de zamanla döner, bu kayıt kalıcı bir kimlik sayılmaz ve diske yazılmaz.
        val key = "$network|${device.address}"
        val version = n.meshVersion(network)
        val time = System.currentTimeMillis()
        val last = lastSync[key]
        if (last != null && last.first == version && time - last.second < RESYNC_MS) return
        if (last != null && time - last.second < MIN_GAP_MS) return
        if (!pending.add(key)) return
        lastSync[key] = version to time
        dialer.execute {
            try {
                val socket = device.createInsecureL2capChannel(psm)
                socket.connect()
                n.syncOverLink(network, linkOf(socket), keyIndex)
                completedSyncs++
                onChange()
            } catch (e: Exception) {
                // Karşı taraf uzaklaşmış olabilir: bir sonraki reklamda yeniden denenir.
                Log.i(TAG, "eşitleme olmadı: ${e.javaClass.simpleName}")
            } finally {
                pending.remove(key)
            }
        }
    }

    private companion object {
        const val EPOCH_MS = 10 * 60 * 1000L
        const val EPOCH_TOLERANCE = 3
        const val TICK_SECONDS = 20L
        const val PEER_TTL_MS = 60_000L
        // Bekleyen taraftaki yeni mesaj en geç bu sürede karşıya geçer.
        const val RESYNC_MS = 30_000L
        const val MIN_GAP_MS = 10_000L
        const val TAG = "LokalDirencBle"
    }
}
