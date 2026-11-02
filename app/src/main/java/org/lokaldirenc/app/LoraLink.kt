package org.lokaldirenc.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import org.lokaldirenc.core.LoraCodec
import org.lokaldirenc.core.SosBeacon
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Telefon ile ESP32 + LoRa cihazı arasındaki Bluetooth köprüsü. Çerçeveler zaten şifreli ve
 * cihaz ağ kodunu bilmez: bu yüzden eşleştirme gerekmez, yakındaki biri bağlansa içerik okuyamaz.
 * Telefonlar LoRa radyosu taşımadığı için uzak yayın ancak bu cihaz üzerinden olur.
 */
@SuppressLint("MissingPermission")
class LoraLink(private val context: Context, private val app: App) {
    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED }

    class Found(val device: BluetoothDevice, val rssi: Int)

    @Volatile
    var state = State.IDLE
        private set

    @Volatile
    var connectedName: String? = null
        private set

    private val found = ConcurrentHashMap<String, Found>()
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val queue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var writing = false

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    fun devices(): List<Found> = found.values.sortedByDescending { it.rssi }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            found[result.device.address] = Found(result.device, result.rssi)
            bump()
        }
    }

    private fun bump() {
        app.revision.intValue++
    }

    fun scan() {
        if (state != State.IDLE) return
        found.clear()
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        state = State.SCANNING
        bump()
    }

    private fun stopScan() {
        adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner?.let { runCatching { it.stopScan(scanCallback) } }
    }

    fun connect(found: Found) {
        stopScan()
        state = State.CONNECTING
        connectedName = found.device.address.takeLast(5)
        bump()
        gatt = found.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        app.node.value?.loraSink = null
        queue.clear()
        writing = false
        tx = null
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        connectedName = null
        state = State.IDLE
        bump()
    }

    private fun send(frame: ByteArray) {
        queue.add(frame)
        pump()
    }

    @Synchronized
    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val c = tx ?: return
        val frame = queue.poll() ?: return
        writing = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.value = frame
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(c)
            }
        }
        if (!ok) writing = false
    }

    private fun onFrame(frame: ByteArray) {
        if (frame.size >= 2 && frame[1] == LoraCodec.KIND_SOS) {
            SosBeacon.decodeLoraFrame(frame)?.let { (signal, rssi) -> app.sosListener.addFromLora(signal.session, signal.batteryPercent, rssi) }
            bump()
        } else {
            app.node.value?.receiveLoraFrame(frame)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            val rx = service?.getCharacteristic(RX)
            tx = service?.getCharacteristic(TX)
            if (rx == null || tx == null) return disconnect()
            g.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(CCCD) ?: return disconnect()
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return disconnect()
            app.node.value?.loraSink = { send(it) }
            state = State.CONNECTED
            bump()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing = false
            pump()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onFrame(value)
        }

        @Deprecated("Android 13 öncesi")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { onFrame(it) }
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("4c442d4c-4f52-4100-8000-000000000001")
        val TX: UUID = UUID.fromString("4c442d4c-4f52-4100-8000-000000000002")
        val RX: UUID = UUID.fromString("4c442d4c-4f52-4100-8000-000000000003")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MTU = 247
    }
}
