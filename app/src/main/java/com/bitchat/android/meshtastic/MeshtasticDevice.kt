package com.bitchat.android.meshtastic

import android.bluetooth.BluetoothDevice

data class MeshtasticDevice(
    val device: BluetoothDevice,
    val name: String,
    val isConnected: Boolean = false,
    val rssi: Int = 0
) {
    // Mac adress serves as unique identifier
    val identifier: String get() = device.address
}