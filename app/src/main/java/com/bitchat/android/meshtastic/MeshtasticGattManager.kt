package com.bitchat.android.meshtastic

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import kotlin.random.Random
import org.meshtastic.proto.ToRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.android.ble.ktx.suspend

class MeshtasticGattManager(context: Context) : BleManager(context) {

    private val TAG = "MeshtasticGattManager"

    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null
    private val supervisorJob = SupervisorJob()
    private val gattScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private val drainMutex = Mutex()

    var onMessageReceived: ((ByteArray) -> Unit)? = null

    override fun getGattCallback(): BleManagerGattCallback = MeshtasticManagerGattCallback()

    private inner class MeshtasticManagerGattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(MeshtasticConverter.MESHTASTIC_SERVICE_UUID)
            if (service != null) {
                toRadioChar = service.getCharacteristic(MeshtasticConverter.MESHTASTIC_TORADIO_UUID)
                fromRadioChar = service.getCharacteristic(MeshtasticConverter.MESHTASTIC_FROMRADIO_UUID)
                fromNumChar = service.getCharacteristic(MeshtasticConverter.MESHTASTIC_FROMNUM_UUID)
            }
            val isSupported = toRadioChar != null && fromRadioChar != null && fromNumChar != null
            if (!isSupported) Log.e(TAG, "Device does not support the required characteristics")
            return isSupported
        }

        override fun initialize() {
            gattScope.launch {
                try {
                    requestMtu(512).suspend()

                    // Register the notification callback before enabling CCC so no
                    // notification can fire between callback registration and subscription.
                    setNotificationCallback(fromNumChar).with { _, _ ->
                        Log.d(TAG, "🔔 FROMNUM notification received.")
                        gattScope.launch { drainRadioBuffer() }
                    }

                    val configId = Random.nextInt(1, Int.MAX_VALUE)
                    val toRadioBytes = ToRadio(want_config_id = configId).encode()
                    writeCharacteristic(
                        toRadioChar,
                        toRadioBytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ).suspend()
                    Log.i(TAG, "📤 Handshake sent (want_config_id: $configId)")

                    Log.i(TAG, "⏳ Draining initial configuration...")
                    drainRadioBuffer()

                    delay(500)

                    enableNotifications(fromNumChar).suspend()
                    Log.i(TAG, "✅ Handshake complete, FROMNUM notifications active.")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ BLE handshake error", e)
                }
            }
        }

        /**
         * Reads FROMRADIO in a loop until the radio sends an empty packet.
         * The drainMutex ensures this loop is never running more than once
         * at a time, preventing concurrent reads that would cause missed packets.
         */
        private suspend fun drainRadioBuffer() {
            drainMutex.withLock {
                var packetCount = 0
                while (true) {
                    try {
                        val data = readCharacteristic(fromRadioChar).suspend()
                        val bytes = data.value
                        if (bytes != null && bytes.isNotEmpty()) {
                            packetCount++
                            handleIncomingData(data)
                        } else {
                            Log.d(TAG, "📭 FROMRADIO buffer drained ($packetCount packets).")
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in FROMRADIO read loop", e)
                        break
                    }
                }
            }
        }

        override fun onServicesInvalidated() {
            toRadioChar = null
            fromRadioChar = null
            fromNumChar = null
            supervisorJob.cancelChildren()
        }
    }

    private fun handleIncomingData(data: Data) {
        val bytes = data.value ?: return
        Log.d(TAG, "Raw packet received: ${bytes.size} bytes")
        val bitchatPayload = MeshtasticConverter.toBitchat(bytes)
        if (bitchatPayload != null) {
            Log.i(TAG, "ATAK packet decoded, forwarding to BitChat.")
            onMessageReceived?.invoke(bitchatPayload)
        }
    }

    fun sendToMesh(bitchatPayload: ByteArray) {
        val targetChar = toRadioChar ?: run {
            Log.e(TAG, "Cannot send: ToRadio characteristic not available.")
            return
        }
        val meshtasticData = MeshtasticConverter.toMeshtasticData(bitchatPayload)
        if (meshtasticData.isEmpty()) return
        writeCharacteristic(targetChar, meshtasticData, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .split()
            .enqueue()
        Log.d(TAG, "Message queued for BLE send (${meshtasticData.size} bytes)")
    }
}
