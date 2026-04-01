package com.bitchat.android.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
@SuppressLint("MissingPermission")
class MeshtasticBleManager(private val context: Context) {

    private var gattManager: MeshtasticGattManager? = null

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner

    // Bluetooth state
    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()
    // Discovered devices
    private val _discoveredDevices = MutableStateFlow<List<MeshtasticDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<MeshtasticDevice>> = _discoveredDevices.asStateFlow()
    // Diffusion channel for incoming messages
    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 50)
    val incomingMessages: SharedFlow<ByteArray> = _incomingMessages.asSharedFlow()
    // Callback for scan results
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown Device"

            _discoveredDevices.update { currentList ->
                val existingDeviceIndex = currentList.indexOfFirst { it.identifier == device.address }
                val newDevice = MeshtasticDevice(
                    device = device,
                    name = deviceName,
                    isConnected = false,
                    rssi = result.rssi
                )

                if (existingDeviceIndex >= 0) {
                    // Update existing device
                    val newList = currentList.toMutableList()
                    newList[existingDeviceIndex] = newDevice.copy(isConnected = currentList[existingDeviceIndex].isConnected)
                    newList
                } else {
                    // Add new device
                    currentList + newDevice
                }
            }
        }
    }

    fun startScan() {
        if (bluetoothAdapter?.isEnabled != true || scanner == null) {
            _isBluetoothEnabled.value = false
            return
        }
        _isBluetoothEnabled.value = true

        // Fetch devices that are already paired to the phone
        val pairedDevices = bluetoothAdapter.bondedDevices
        val meshtasticPaired = pairedDevices?.filter { device ->
            val name = device.name ?: ""
            // Usual keywords for proto devices
            name.contains("Mesh", ignoreCase = true) ||
                    name.contains("Node", ignoreCase = true) ||
                    name.contains("LI", ignoreCase = false)
        }?.map {
            MeshtasticDevice(device = it, name = it.name ?: "Paired Node", isConnected = false)
        } ?: emptyList()

        // Update the list with paired devices and connected devices
        _discoveredDevices.update { currentList ->
            val connected = currentList.filter { it.isConnected }
            (connected + meshtasticPaired).distinctBy { it.identifier }
        }

        // scan for new devices
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MeshtasticConverter.MESHTASTIC_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    fun connectToMeshtastic(device: BluetoothDevice) {
        // For some devices, we need to use createBond() to fill security requirements (PIN code...)
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Log.i("MeshtasticBleManager", " Asking for Android PIN code...")
            device.createBond()
        }
        // Disconnect previous device if there is one
        gattManager?.disconnect()?.enqueue()

        // Instantiate new manager
        gattManager = MeshtasticGattManager(context).apply {
            // Listen to the connection state
            connectionObserver = object : no.nordicsemi.android.ble.observer.ConnectionObserver {
                override fun onDeviceConnecting(device: BluetoothDevice) {}
                override fun onDeviceConnected(device: BluetoothDevice) {}
                override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                    updateDeviceConnectionState(device.address, false)
                }
                override fun onDeviceReady(device: BluetoothDevice) {
                    // Device is connected and services are discovered
                    updateDeviceConnectionState(device.address, true)
                }
                override fun onDeviceDisconnecting(device: BluetoothDevice) {}
                override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                    updateDeviceConnectionState(device.address, false)
                }
            }

            // Message receival
            onMessageReceived = { payload ->
                Log.i("MeshtasticBleManager", "Transmitting payload to ViewModel (${payload.size} octets)")
                // tryEmit pushes the message in the SharedFlow in asynchronous way
                _incomingMessages.tryEmit(payload)
            }
        }

        // Start connection
        gattManager?.connect(device)
            ?.retry(3, 100) // Tries 3 times in cas of failure with a 100ms delay between each try
            ?.enqueue()
    }
    fun disconnectFromMeshtastic(device: BluetoothDevice) {
        gattManager?.disconnect()?.enqueue()
        gattManager = null
        updateDeviceConnectionState(device.address, false)
    }

    private fun updateDeviceConnectionState(address: String, isConnected: Boolean) {
        _discoveredDevices.update { list ->
            list.map { if (it.identifier == address) it.copy(isConnected = isConnected) else it }
        }
    }
    fun sendMessage(payload: ByteArray) {
        if (gattManager == null) {
            Log.e("MeshtasticBleManager", "Impossible d'envoyer : aucun appareil connecté.")
            return
        }
        gattManager?.sendToMesh(payload)
    }
}
object MeshtasticBleService {
    private var instance: MeshtasticBleManager? = null

    fun getInstance(context: Context): MeshtasticBleManager {
        if (instance == null) {
            // We use applicationContext to avoid leaking the Activity's memory
            instance = MeshtasticBleManager(context.applicationContext)
        }
        return instance!!
    }
}