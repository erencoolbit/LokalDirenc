package org.lokaldirenc.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import org.lokaldirenc.core.SosBeacon
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

/** SOS servisinin arayüzün okuduğu durumu. */
object SosState {
    @Volatile var active = false
    @Volatile var advertising = false
    @Volatile var longRange = false
    @Volatile var whistling = false
    @Volatile var startedAt = 0L
}

/**
 * Yardım sinyali: Bluetooth yayını ve aralıklı düdük. Ekran kapalıyken de sürsün diye ön plan
 * servisidir ve yalnız kullanıcı başlatır. Ağ kodu, kimlik veya konum taşımaz.
 * Pil ömrü henüz ölçülmedi.
 */
@SuppressLint("MissingPermission")
class SosService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousAlarmVolume = -1
    private var whistle: AudioTrack? = null
    private val session = SecureRandom().nextInt()

    private val legacyCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            SosState.advertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            SosState.advertising = false
        }
    }

    private val codedCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            SosState.longRange = status == ADVERTISE_SUCCESS
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            stopAdvertising()
            startAdvertising()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val blast = object : Runnable {
        override fun run() {
            whistle?.let { track ->
                runCatching {
                    track.stop()
                    track.reloadStaticData()
                    track.play()
                }
            }
            handler.postDelayed(this, WHISTLE_EVERY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (SosState.active) return START_NOT_STICKY
        val app = application as App
        val canAdvertise = app.bluetooth.missingPermissions().isEmpty()
        startInForeground(canAdvertise)
        SosState.active = true
        SosState.startedAt = System.currentTimeMillis()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lokaldirenc:sos").apply {
                setReferenceCounted(false)
                acquire()
            }
        if (canAdvertise) refresh.run()
        if (intent?.getBooleanExtra(EXTRA_WHISTLE, true) != false) startWhistle()
        return START_NOT_STICKY
    }

    private fun startInForeground(canAdvertise: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Yardım sinyali", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SosService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle("Yardım sinyali yayınlanıyor")
            .setContentText("Pil tüketir. Durdurmak için dokunun.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            // İzin yoksa yalnız düdük çalışır; connectedDevice türü Bluetooth izni ister.
            val type = if (canAdvertise) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIFICATION, notification, type)
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }

    private fun batteryPercent() = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun startAdvertising() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val le = adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
        if (le == null) {
            SosState.advertising = false
            SosState.longRange = false
            return
        }
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(UUID.fromString(SosBeacon.SERVICE_UUID)), SosBeacon.encode(session, batteryPercent()))
            .build()
        le.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build(),
            data, legacyCallback,
        )
        // Uzun menzil her telefonda yoktur; normal yayın her durumda sürer.
        if (adapter.isLeCodedPhySupported && adapter.isLeExtendedAdvertisingSupported) {
            le.startAdvertisingSet(
                AdvertisingSetParameters.Builder()
                    .setLegacyMode(false)
                    .setConnectable(false)
                    .setScannable(false)
                    .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build(),
                data, null, null, null, codedCallback,
            )
        }
    }

    private fun stopAdvertising() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (!adapter.isEnabled) return
        val le = adapter.bluetoothLeAdvertiser ?: return
        runCatching { le.stopAdvertising(legacyCallback) }
        runCatching { le.stopAdvertisingSet(codedCallback) }
    }

    /** Kısa, keskin üçlü ses. Sürekli çalmaz: pil gider, kurtarma ekibi de sessizlik anlarında dinler. */
    private fun startWhistle() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val samples = whistleSamples()
        whistle = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .apply { write(samples, 0, samples.size) }
        SosState.whistling = true
        blast.run()
    }

    private fun whistleSamples(): ShortArray {
        val beep = SAMPLE_RATE * 4 / 10
        val gap = SAMPLE_RATE * 2 / 10
        val out = ShortArray((beep + gap) * 3)
        for (n in 0 until 3) {
            val offset = n * (beep + gap)
            for (i in 0 until beep) {
                out[offset + i] = (sin(2 * PI * WHISTLE_HZ * i / SAMPLE_RATE) * Short.MAX_VALUE * 0.95).toInt().toShort()
            }
        }
        return out
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAdvertising()
        whistle?.let { runCatching { it.stop() }; it.release() }
        whistle = null
        if (previousAlarmVolume >= 0) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        SosState.active = false
        SosState.advertising = false
        SosState.longRange = false
        SosState.whistling = false
        (application as App).revision.intValue++
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.lokaldirenc.app.SOS_STOP"
        const val EXTRA_WHISTLE = "whistle"
        private const val CHANNEL = "sos"
        private const val NOTIFICATION = 7
        private const val REFRESH_MS = 5 * 60 * 1000L
        private const val WHISTLE_EVERY_MS = 30_000L
        private const val SAMPLE_RATE = 44_100
        private const val WHISTLE_HZ = 3150.0
    }
}

/** Yakındaki yardım çağrılarını dinler. Çağrılar doğrulanamaz: herkes taklit edebilir. */
@SuppressLint("MissingPermission")
class SosListener(private val context: Context) {
    class Heard(val session: Int, val battery: Int, val rssi: Int, val lastSeen: Long, val viaLora: Boolean)

    private val heard = ConcurrentHashMap<Int, Heard>()
    private var scanning = false
    private val uuid = ParcelUuid(UUID.fromString(SosBeacon.SERVICE_UUID))

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
    }

    private fun handle(result: ScanResult) {
        val signal = SosBeacon.decode(result.scanRecord?.getServiceData(uuid)) ?: return
        heard[signal.session] = Heard(signal.session, signal.batteryPercent, result.rssi, System.currentTimeMillis(), false)
    }

    fun addFromLora(session: Int, battery: Int, rssi: Int) {
        heard[session] = Heard(session, battery, rssi, System.currentTimeMillis(), true)
    }

    fun current(): List<Heard> {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        heard.values.removeIf { it.lastSeen < cutoff }
        return heard.values.sortedByDescending { it.rssi }
    }

    /** Yalnız izin varsa ve Bluetooth açıksa başlar. */
    fun start() {
        if (scanning) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val scanner = adapter.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (adapter.isLeExtendedAdvertisingSupported) {
            settings.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        scanner.startScan(null, settings.build(), callback)
        scanning = true
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (adapter.isEnabled) runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
    }

    private companion object {
        const val KEEP_MS = 5 * 60 * 1000L
    }
}
