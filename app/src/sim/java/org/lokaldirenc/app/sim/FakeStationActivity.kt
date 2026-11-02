package org.lokaldirenc.app.sim

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Bundle
import android.os.ParcelUuid
import org.lokaldirenc.app.App
import org.lokaldirenc.app.LoraLink
import org.lokaldirenc.core.LoraCodec
import org.lokaldirenc.core.SosBeacon
import java.util.concurrent.ConcurrentHashMap

/**
 * Yalnız "sim" derlemesinde: donanım olmadan LoraLink'i denemek için bu telefon, ESP32 ile aynı
 * GATT hizmetini sunan sahte bir istasyon olur. Radyo yerine çerçeveleri kendi düğümüne verir.
 * Gerçek ESP32'nin davranışını kanıtlamaz; yalnız telefon tarafının GATT akışını sınar.
 */
@SuppressLint("MissingPermission")
class FakeStationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Station.start(application as App, intent.getBooleanExtra("sos", false))
        finish()
    }

    private object Station {
        var server: BluetoothGattServer? = null
        val clients = ConcurrentHashMap.newKeySet<BluetoothDevice>()
        lateinit var rx: BluetoothGattCharacteristic

        fun notifyAll(frame: ByteArray) {
            val s = server ?: return
            clients.forEach { s.notifyCharacteristicChanged(it, rx, false, frame) }
        }

        fun start(app: App, sos: Boolean) {
            val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            if (server != null) {
                if (sos) notifyAll(byteArrayOf(2, LoraCodec.KIND_SOS) + SosBeacon.encode(4242, 63) + byteArrayOf(-77))
                return
            }
            val service = BluetoothGattService(LoraLink.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val tx = BluetoothGattCharacteristic(LoraLink.TX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
            rx = BluetoothGattCharacteristic(LoraLink.RX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
            rx.addDescriptor(BluetoothGattDescriptor(LoraLink.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
            service.addCharacteristic(tx)
            service.addCharacteristic(rx)
            server = manager.openGattServer(app, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    if (newState != BluetoothProfile.STATE_CONNECTED) clients.remove(device)
                }

                override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic, prepared: Boolean, needResponse: Boolean, offset: Int, value: ByteArray) {
                    if (needResponse) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    app.node.value?.receiveLoraFrame(value)
                }

                override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor, prepared: Boolean, needResponse: Boolean, offset: Int, value: ByteArray) {
                    clients.add(device)
                    if (needResponse) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            })
            server?.addService(service)
            app.node.value?.loraSink = { notifyAll(it) }
            manager.adapter.bluetoothLeAdvertiser.startAdvertising(
                AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(),
                AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(LoraLink.SERVICE)).build(),
                object : AdvertiseCallback() {},
            )
        }
    }
}
