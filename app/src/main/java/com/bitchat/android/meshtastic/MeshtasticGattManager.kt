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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.ktx.suspend

class MeshtasticGattManager(context: Context) : BleManager(context) {

    private val TAG = "MeshtasticGattManager"

    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null

    // Callback to inform the app that a Bitchat message has been received
    var onMessageReceived: ((ByteArray) -> Unit)? = null

    override fun getGattCallback(): BleManagerGattCallback {
        return MeshtasticManagerGattCallback()
    }

    private inner class MeshtasticManagerGattCallback : BleManagerGattCallback() {

        // Once connected, verify that services are available
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(MeshtasticConverter.MESHTASTIC_SERVICE_UUID)
            if (service != null) {
                toRadioChar = service.getCharacteristic(MeshtasticConverter.MESHTASTIC_TORADIO_UUID)
                fromRadioChar = service.getCharacteristic(MeshtasticConverter.MESHTASTIC_FROMRADIO_UUID)
                fromNumChar = service.getCharacteristic(MeshtasticConverter.MESHTASTIC_FROMNUM_UUID)
            }

            // Connexion is valid only if these characteristics are supported
            val isSupported = toRadioChar != null && fromRadioChar != null && fromNumChar != null
            if (!isSupported) Log.e(TAG, "The device doesn't support the required characteristics")
            return isSupported
        }

        // Initialisation : Subscribe to notifications (incoming messages)
        override fun initialize() {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    requestMtu(512).suspend()

                    // Configuration of callback pfor when new messages arrive
                    setNotificationCallback(fromNumChar).with { _, _ ->
                        Log.d(TAG, "🔔 Notification FROMNUM.")
                        // We start a coroutine to be able to loop without blocking the BLE thread
                        CoroutineScope(Dispatchers.IO).launch {
                            drainRadioBuffer()
                        }
                    }

                    // Sending Handshake
                    val configId = Random.nextInt(1, Int.MAX_VALUE)
                    val toRadioBytes = ToRadio(want_config_id = configId).encode()
                    writeCharacteristic(toRadioChar, toRadioBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
                    Log.i(TAG, "📤 Handshake sent (want_config_id: $configId)")

                    // We free the initial queue (Configuration, Nodes, etc.)
                    Log.i(TAG, "⏳ Downloading initial configuration...")
                    drainRadioBuffer()

                    delay(500)

                    enableNotifications(fromNumChar).suspend()
                    Log.i(TAG, "✅ Handshake done, now listening !")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error while BLE handshake", e)
                }
            }
        }

        /**
         * Reads FROMRADIO port until the radio sends an empty packet.
         * Essential to respect the Meshtastic protocol.
         */
        private suspend fun drainRadioBuffer() {
            var isEmpty = false
            var packetCount = 0

            while (!isEmpty) {
                try {
                    val data = readCharacteristic(fromRadioChar).suspend()
                    val bytes = data.value

                    if (bytes != null && bytes.isNotEmpty()) {
                        packetCount++
                        handleIncomingData(data)
                    } else {
                        isEmpty = true
                        Log.d(TAG, "📭 FROMRADIO buffer freed ($packetCount packets read this time).")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during reading loop", e)
                    isEmpty = true
                }
            }
        }

        override fun onServicesInvalidated() {
            toRadioChar = null
            fromRadioChar = null
            fromNumChar = null
        }
    }

    // Curating incoming data (Meshtastic -> BitChat)
    private fun handleIncomingData(data: Data) {
        val bytes = data.value ?: return
        Log.d(TAG, "Raw data received : ${bytes.size} octets")

        // Use our convertor to decode the incoming data
        val bitchatPayload = MeshtasticConverter.toBitchat(bytes)

        if (bitchatPayload != null) {
            Log.i(TAG, "ATAK packet successfully decoded ! Transmission to BitChat.")
            onMessageReceived?.invoke(bitchatPayload)
        }
    }

    // Sending data (BitChat -> Meshtastic)
    fun sendToMesh(bitchatPayload: ByteArray) {
        val targetChar = toRadioChar ?: run {
            Log.e(TAG, "Impossible to send : ToRadio characteristics weren't found.")
            return
        }

        // Conversion of BitChat message to ToRadio Protobuf
        val meshtasticData = MeshtasticConverter.toMeshtasticData(bitchatPayload)
        if (meshtasticData.isEmpty()) return

        // Send via Nordic BLE
        // "split()" is crucial : if the Protobuf packet is larger than the MTU,
        // Nordic will automatically split into smaller chunks that the bLE can handle.
        writeCharacteristic(targetChar, meshtasticData, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .split()
            .enqueue()

        Log.d(TAG, "Message sent to BLE queue (${meshtasticData.size} octets)")
    }
}