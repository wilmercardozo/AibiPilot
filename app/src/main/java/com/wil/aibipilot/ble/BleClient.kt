package com.wil.aibipilot.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.wil.aibipilot.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed class BleEvent {
    data class JsonMessage(val json: String) : BleEvent()
    data class RawMessage(val bytes: ByteArray) : BleEvent()
}

data class ScanDevice(val device: BluetoothDevice, val rssi: Int)

class BleClient(private val context: Context) {

    companion object {
        private const val TAG = "AibiBle"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val writeMutex = Mutex()

    @Volatile
    private var activeScanCallback: ScanCallback? = null

    // Reensamblado RX
    private val rxBuffer = java.io.ByteArrayOutputStream()
    private var rxTotal = -1

    private val scope = CoroutineScope(Dispatchers.IO)

    fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): Array<String> =
        // MIUI/HyperOS exige ACCESS_FINE_LOCATION para entregar resultados
        // de escaneo BLE incluso en Android 12+.
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            emptyArray()
        }

    fun isBluetoothEnabled(): Boolean = adapter.isEnabled

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScanDevice> = callbackFlow {
        if (!adapter.isEnabled || !hasRequiredPermissions()) {
            close()
            return@callbackFlow
        }
        // Sin filtros: en MIUI/HyperOS los ScanFilter con UUID/nombre pueden
        // no matchear; filtramos por nombre en el ViewModel.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            private var count = 0

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                count++
                // Loggear solo el primero y luego 1 de cada 30 para no saturar logcat
                if (count == 1 || count % 30 == 0) {
                    Log.d(TAG, "scan: ${result.device.name} ${result.device.address} rssi=${result.rssi} (n=$count)")
                }
                trySend(ScanDevice(result.device, result.rssi))
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { r -> trySend(ScanDevice(r.device, r.rssi)) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "scan failed: $errorCode")
            }
        }
        try {
            activeScanCallback = callback
            adapter.bluetoothLeScanner?.startScan(null, settings, callback)
            Log.d(TAG, "scan started (no filters)")
        } catch (e: Exception) {
            Log.e(TAG, "startScan exception: ${e.message}", e)
            close()
            return@callbackFlow
        }
        awaitClose {
            try {
                adapter.bluetoothLeScanner?.stopScan(callback)
            } catch (_: Exception) {
            }
            activeScanCallback = null
        }
    }.flowOn(Dispatchers.IO)

    fun stopScan() {
        try {
            activeScanCallback?.let { adapter.bluetoothLeScanner?.stopScan(it) }
        } catch (_: Exception) {
        }
        activeScanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, onEvent: (BleEvent) -> Unit, onState: (Boolean) -> Unit) {
        disconnect()
        rxBuffer.reset()
        rxTotal = -1
        Log.d(TAG, "connectGatt -> ${device.name} ${device.address}")
        val g: BluetoothGatt? = try {
            device.connectGatt(
                context, false,
                object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "conn state: $status / $newState")
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            onState(true)
                            gatt = g
                            g.requestMtu(512)
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            onState(false)
                            writeChar = null
                        }
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    Log.d(TAG, "MTU: $mtu ($status)")
                    this@BleClient.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                    g.discoverServices()
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val service = g.getService(UUID.fromString(Protocol.SERVICE_UUID))
                    val ch = service?.getCharacteristic(
                        UUID.fromString(Protocol.CHARACTERISTIC_UUID)
                    )
                    if (ch == null) {
                        Log.e(TAG, "ffe1 characteristic not found")
                        onState(false)
                        return
                    }
                    writeChar = ch
                    g.setCharacteristicNotification(ch, true)
                    val cccd = ch.getDescriptor(UUID.fromString(CCCD_UUID))
                    if (cccd != null) {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    handleIncoming(value, onEvent)
                }
            },
            BluetoothDevice.TRANSPORT_LE
        )
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt exception: ${e.message}", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onState(false) }
            return
        }
        if (g == null) {
            Log.e(TAG, "connectGatt returned null")
            android.os.Handler(android.os.Looper.getMainLooper()).post { onState(false) }
            return
        }
        this.gatt = g
    }

    private fun handleIncoming(data: ByteArray, onEvent: (BleEvent) -> Unit) {
        if (data.isEmpty()) return
        // Frame binario DD CC -> emitir crudo
        if (data.size >= 2 &&
            data[0] == Protocol.MSG_HEADER[0] && data[1] == Protocol.MSG_HEADER[1]
        ) {
            onEvent(BleEvent.RawMessage(data))
            return
        }
        // Frame JSON BB AA: leer longitud de bytes 2-3 (little endian)
        if (data.size >= 4 &&
            data[0] == Protocol.JSON_HEADER[0] && data[1] == Protocol.JSON_HEADER[1]
        ) {
            rxTotal = (data[2].toInt() and 0xFF) + ((data[3].toInt() and 0xFF) shl 8)
            rxBuffer.reset()
            rxBuffer.write(data, 4, data.size - 4)
        } else {
            rxBuffer.write(data, 0, data.size)
        }
        if (rxTotal in 1..rxBuffer.size()) {
            val json = String(rxBuffer.toByteArray(), Charsets.UTF_8)
            rxTotal = -1
            rxBuffer.reset()
            onEvent(BleEvent.JsonMessage(json))
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun write(bytes: ByteArray) {
        writeMutex.withLock {
            val ch = writeChar ?: return@withLock
            val g = gatt ?: return@withLock
            ch.writeType =
                if (bytes.size > mtu - 3) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = bytes
            if (bytes.size > mtu - 3 && mtu <= 23) {
                // split manual en chunks de 20 bytes
                var offset = 0
                while (offset < bytes.size) {
                    val chunk = bytes.copyOfRange(offset, minOf(offset + 20, bytes.size))
                    ch.value = chunk
                    if (!g.writeCharacteristic(ch)) {
                        Log.e(TAG, "write failed")
                        return@withLock
                    }
                    offset += 20
                }
            } else {
                if (!g.writeCharacteristic(ch)) {
                    Log.e(TAG, "write failed")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        writeChar = null
    }

    fun currentMtu(): Int = mtu
}
