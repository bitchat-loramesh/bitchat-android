package com.bitchat.android.meshtastic

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data

class MeshtasticGattManager(context: Context) : BleManager(context) {

    private val TAG = "MeshtasticGattManager"

    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null

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
            }

            // Connexion is valid only if these characteristics are supported
            val isSupported = toRadioChar != null && fromRadioChar != null
            if (!isSupported) Log.e(TAG, "The device doesn't support the required characteristics")
            return isSupported
        }

        // Initialisation : Subscribe to notifications (incoming messages)
        override fun initialize() {
            // Meshtastic needs a larger MTU (Maximum Transmission Unit) for Protobufs
            requestMtu(512).enqueue()

            // We configure the callback to read data coming from FROMRADIO port
            setNotificationCallback(fromRadioChar).with { _, data ->
                handleIncomingData(data)
            }

            // We really activate notifications on the device
            enableNotifications(fromRadioChar).enqueue()
            Log.i(TAG, "Notifications FROMRADIO activated.")
        }

        override fun onServicesInvalidated() {
            toRadioChar = null
            fromRadioChar = null
        }
    }

    // Curating incoming data (Meshtastic -> BitChat)
    private fun handleIncomingData(data: Data) {
        val bytes = data.value ?: return
        Log.d(TAG, "Raw data received : ${bytes.size} octets")

        // Use our convertor to decode the incoming data
        val bitchatPayload = MeshtasticConverter.toBitchat(bytes)

        if (bitchatPayload != null) {
            Log.i(TAG, "Paquet ATAK décodé avec succès ! Transmission à BitChat.")
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

        Log.d(TAG, "Message envoyé dans la file d'attente BLE (${meshtasticData.size} octets)")
    }
}