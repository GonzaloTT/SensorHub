package com.gonzalo.sensorhub.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

@SuppressLint("MissingPermission")
class BleClient(private val context: Context, private val packet: (ByteArray) -> Unit) {
    companion object {
        val SERVICE = UUID.fromString("e89ba400-b5f4-49bc-a83c-76c54ae84001")
        val METRIC = UUID.fromString("e89ba400-b5f4-49bc-a83c-76c54ae84002")
        val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val permissions get() = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    val state = MutableStateFlow("Desconectado")
    val devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    private var scan: ScanCallback? = null
    private var link: BluetoothGatt? = null
    private var previous: BluetoothDevice? = null

    fun ready(): Boolean {
        val reason = when {
            adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> "BLE no disponible"
            permissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } -> "Permisos pendientes"
            adapter?.isEnabled != true -> "Bluetooth desactivado"
            else -> return true
        }
        close(reason)
        return false
    }

    fun search() {
        if (!ready()) return
        close("Escaneando")
        devices.value = emptyList()
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                if (scan === this) devices.value = (devices.value + result.device).distinct()
            }
            override fun onScanFailed(code: Int) {
                if (scan === this) close("Error de búsqueda " + code)
            }
        }
        scan = callback
        val scanner = adapter?.bluetoothLeScanner ?: return close("Escáner no disponible")
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        main.postDelayed({ if (scan === callback) close("Búsqueda terminada") }, 10000)
    }

    fun connect(device: BluetoothDevice? = previous) {
        if (!ready()) return
        if (device == null) return search()
        close("Conectando")
        previous = device
        link = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
        val active = link
        main.postDelayed({ if (link === active && state.value == "Conectando") close("Tiempo de conexión agotado") }, 15000)
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (link !== g) return@post
                if (!ready()) return@post
                if (status != 0 || newState != BluetoothProfile.STATE_CONNECTED || !g.discoverServices())
                    close("Desconectado (GATT " + status + ")")
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (link !== g) return@post
                if (!ready()) return@post
                val ch = g.getService(SERVICE)?.getCharacteristic(METRIC)
                val descriptor = ch?.getDescriptor(CCCD)
                if (status != 0 || ch == null || descriptor == null || !g.setCharacteristicNotification(ch,true))
                    return@post close("Servicio de telemetría no disponible")
                val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = enable
                    g.writeDescriptor(descriptor)
                }
                if (!started) close("Falló la suscripción")
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { if (link === g) {
                if (status == 0) state.value = "Conectado" else close("Suscripción rechazada")
            } }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            accept(g, ch, ch.value.clone())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            accept(g,ch,value)
        }
        private fun accept(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (link === g && ch.uuid == METRIC) packet(value) }
        }
    }
    fun close(message: String = "Desconectado") {
        val callback = scan
        scan = null
        val g = link
        link = null
        try {
            if (callback != null) adapter?.bluetoothLeScanner?.stopScan(callback)
            g?.disconnect()
        } catch (_: SecurityException) {
            state.value = "Permisos pendientes"
            return
        } finally {
            try { g?.close() } catch (_: SecurityException) {}
        }
        state.value = message
    }
}
