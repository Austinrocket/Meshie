package com.meshie4LoRa.radio

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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import android.bluetooth.BluetoothAdapter
import java.io.IOException
import java.util.UUID

private const val TAG = "MeshChan.Ble"

data class FoundDevice(val address: String, val name: String?, val rssi: Int, val bonded: Boolean)

enum class LinkState { DISCONNECTED, BONDING, CONNECTING, CONNECTED }

/** Meshtastic phone API over BLE: write ToRadio, drain FromRadio on FromNum notify. */
@SuppressLint("MissingPermission")
class BleRadioTransport(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val OP_TIMEOUT_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 5_000L
    }

    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    val state: StateFlow<LinkState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _fromRadio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val fromRadio: SharedFlow<ByteArray> = _fromRadio

    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connectDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var pendingOp: CompletableDeferred<ByteArray>? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var targetAddress: String? = null
    private var connectJob: Job? = null

    private val opMutex = Mutex()
    private val drainMutex = Mutex()

    init {
        // Turning Bluetooth off doesn't always deliver a GATT disconnect; without this we'd think we're still connected.
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                Log.i(TAG, "Bluetooth adapter state $s")
                if (s == BluetoothAdapter.STATE_TURNING_OFF || s == BluetoothAdapter.STATE_OFF) {
                    connectDeferred?.completeExceptionally(IOException("Bluetooth turned off"))
                    pendingOp?.completeExceptionally(IOException("Bluetooth turned off"))
                    closeGatt()
                    _state.value = LinkState.DISCONNECTED
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
    }

    fun scan(): Flow<FoundDevice> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner ?: run { close(IOException("Bluetooth is off")); return@callbackFlow }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                trySend(FoundDevice(d.address, d.name ?: result.scanRecord?.deviceName, result.rssi, d.bondState == BluetoothDevice.BOND_BONDED))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("BLE scan failed: $errorCode"))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    fun connect(address: String) {
        targetAddress = address
        connectJob?.cancel()
        connectJob = scope.launch { connectLoop(address) }
    }

    fun disconnect() {
        targetAddress = null
        connectJob?.cancel()
        connectJob = null
        closeGatt()
        _state.value = LinkState.DISCONNECTED
    }

    suspend fun send(bytes: ByteArray) {
        val c = toRadio ?: throw IOException("Not connected")
        gattOp { it.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS }
        drain()
    }

    private suspend fun connectLoop(address: String) {
        while (scope.isActive && targetAddress == address) {
            if (adapter?.isEnabled != true) {
                _lastError.value = "Bluetooth is off"
                _state.value = LinkState.DISCONNECTED
                delay(RECONNECT_DELAY_MS)
                continue
            }
            try {
                connectOnce(address)
                _lastError.value = null
                _state.first { it == LinkState.DISCONNECTED }
            } catch (e: TimeoutCancellationException) {
                // A timeout is a failed attempt, not a cancellation of the loop.
                Log.w(TAG, "Connection attempt timed out")
                _lastError.value = "Radio not answering (out of range or off?)"
                closeGatt()
                _state.value = LinkState.DISCONNECTED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt failed", e)
                _lastError.value = e.message ?: e.javaClass.simpleName
                closeGatt()
                _state.value = LinkState.DISCONNECTED
            }
            delay(RECONNECT_DELAY_MS)
        }
    }

    private suspend fun connectOnce(address: String) {
        val device = adapter.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            // Bond state reads NONE briefly after Bluetooth comes back on; don't start a pairing prompt for that.
            delay(3_000)
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _state.value = LinkState.BONDING
            ensureBonded(device)
        }
        _state.value = LinkState.CONNECTING
        val connected = CompletableDeferred<Unit>()
        connectDeferred = connected
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(20_000) { connected.await() }

        gattOp { it.requestMtu(512) }
        gattOp { it.discoverServices() }
        val g = gatt ?: throw IOException("GATT closed")
        val service = g.getService(SERVICE_UUID) ?: throw IOException("Meshtastic service not found")
        val fromNum = service.getCharacteristic(FROM_NUM_UUID) ?: throw IOException("FromNum missing")
        toRadio = service.getCharacteristic(TO_RADIO_UUID) ?: throw IOException("ToRadio missing")
        fromRadioChar = service.getCharacteristic(FROM_RADIO_UUID) ?: throw IOException("FromRadio missing")

        g.setCharacteristicNotification(fromNum, true)
        val cccd = fromNum.getDescriptor(CCCD_UUID) ?: throw IOException("FromNum CCCD missing")
        gattOp { it.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS }

        Log.i(TAG, "Connected to $address")
        _state.value = LinkState.CONNECTED
    }

    private suspend fun ensureBonded(device: BluetoothDevice) {
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> done.complete(false)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return
            if (!device.createBond()) throw IOException("Could not start pairing")
            val ok = withTimeout(120_000) { done.await() }
            if (!ok) throw IOException("Pairing failed or was cancelled")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private suspend fun drain() = drainMutex.withLock {
        val c = fromRadioChar ?: return@withLock
        while (true) {
            val bytes = gattOp { it.readCharacteristic(c) }
            if (bytes.isEmpty()) break
            _fromRadio.emit(bytes)
        }
    }

    private suspend fun gattOp(start: (BluetoothGatt) -> Boolean): ByteArray = opMutex.withLock {
        val g = gatt ?: throw IOException("Not connected")
        val deferred = CompletableDeferred<ByteArray>()
        pendingOp = deferred
        try {
            if (!start(g)) throw IOException("GATT operation rejected")
            withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() } ?: throw IOException("GATT operation timed out")
        } finally {
            pendingOp = null
        }
    }

    private fun closeGatt() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        toRadio = null
        fromRadioChar = null
    }

    private fun completeOp(status: Int, value: ByteArray = ByteArray(0)) {
        val op = pendingOp ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) op.complete(value)
        else op.completeExceptionally(IOException("GATT status $status"))
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state $newState (status $status)")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connectDeferred?.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val err = IOException("Disconnected (status $status)")
                    connectDeferred?.completeExceptionally(err)
                    pendingOp?.completeExceptionally(err)
                    g.close()
                    if (g == gatt) {
                        gatt = null
                        toRadio = null
                        fromRadioChar = null
                    }
                    _state.value = LinkState.DISCONNECTED
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU $mtu")
            completeOp(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = completeOp(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = completeOp(status)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = completeOp(status)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            completeOp(status, value)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == FROM_NUM_UUID) {
                scope.launch {
                    try {
                        drain()
                    } catch (e: Exception) {
                        Log.w(TAG, "Drain failed", e)
                    }
                }
            }
        }
    }
}
