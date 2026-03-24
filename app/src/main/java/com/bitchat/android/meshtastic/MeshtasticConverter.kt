package com.bitchat.android.meshtastic

import android.util.Log
import java.util.UUID
import okio.ByteString.Companion.toByteString

import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Data
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.TAKPacket
import org.meshtastic.proto.GeoChat

object MeshtasticConverter {
    private const val TAG = "MeshtasticConverter"

    // MARK: - Constants

    // UUIDs Bluetooth translated from IOS version
    val MESHTASTIC_SERVICE_UUID: UUID = UUID.fromString("6BA1B218-15A8-461F-9FA8-5DCAE273EAFD")
    val MESHTASTIC_TORADIO_UUID: UUID = UUID.fromString("F75C76D2-129E-4DAD-A1DD-7866124401E7")
    val MESHTASTIC_FROMRADIO_UUID: UUID = UUID.fromString("2C55E69E-4993-11ED-B878-0242AC120002")
    val MESHTASTIC_FROMNUM_UUID: UUID = UUID.fromString("ED9DA18C-A800-4F66-A670-AA7547E34453")
    val MESHTASTIC_LOGRADIO_UUID: UUID = UUID.fromString("5a3d6e49-06e6-4423-9944-e9de8cdf9547")

    private const val BROADCAST_ADDR = -1

    // MARK: - Protobuf encoding

    /**
     * Converts a bitchat message payload to a TAKPacket
     */
    fun toAttakProtocol(messagePayload: ByteArray): TAKPacket? {
        return try {
            val text = String(messagePayload, Charsets.UTF_8)

            if (text.isNotBlank()) {
                // We try to interpret the payload as a text message
                Log.d(TAG, "\uD83D\uDCE1 Created TAKPacket with text message: '${text.take(50)}...'")
                TAKPacket(
                    chat = GeoChat(message = text)
                )
            } else {
                // It's not text, we send the raw payload as binary data
                Log.d(TAG, "\uD83D\uDCE1 Created TAKPacket with ${messagePayload.size} bytes of binary data")
                TAKPacket(
                    detail = messagePayload.toByteString()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TAKPacket", e)
            null
        }
    }

    /**
     * Converts a bitchat message payload to a ToRadio message
     */
    fun toMeshtastic(messagePayload: ByteArray): ToRadio? {
        val tak = toAttakProtocol(messagePayload) ?: run {
            Log.e(TAG, "Failed to convert message to TAKPacket")
            return null
        }

        return try {
            val takBytes = tak.encode()

            ToRadio(
                packet = MeshPacket(
                    to = BROADCAST_ADDR,
                    want_ack = false,
                    decoded = Data(
                        portnum = PortNum.ATAK_PLUGIN,
                        payload = takBytes.toByteString()
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build ToRadio", e)
            null
        }
    }

    /**
     * Serialize a bitchat message payload to a BLE-ready message
     */
    fun toMeshtasticData(messagePayload: ByteArray): ByteArray {
        val toRadio = toMeshtastic(messagePayload) ?: return ByteArray(0)

        return try {
            val bin = toRadio.encode()
            Log.d(TAG, "\uD83D\uDCE1 Serialized ToRadio to ${bin.size} bytes for BLE write")
            bin
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize ToRadio", e)
            ByteArray(0)
        }
    }

    /**
     * Unpacks a BLE-ready message to a bitchat message payload
     */
    fun toBitchat(radioData: ByteArray): ByteArray? {
        return try {
            val fromRadio = FromRadio.ADAPTER.decode(radioData)
            val meshPacket = fromRadio.packet ?: run {
                Log.d(TAG, "\uD83D\uDCE1 FromRadio has no packet field")
                return null
            }

            val decodedData = meshPacket.decoded ?: run {
                Log.d(TAG, "\uD83D\uDCE1 FromRadio packet has no decoded data")
                return null
            }

            Log.d(TAG, "\uD83D\uDCE1 FromRadio portnum: ${decodedData.portnum}")

            // Check if the portnum is ATAK_PLUGIN
            if (decodedData.portnum == PortNum.ATAK_PLUGIN) {
                val atakPayload = decodedData.payload.toByteArray()
                Log.i(TAG, "\uD83D\uDCE1 Received ATAK packet (${atakPayload.size} bytes)")

                try {
                    val tak = TAKPacket.ADAPTER.decode(atakPayload)

                    // Extract the payload variant
                    when {
                        tak.detail != null -> {
                            Log.d(TAG, "\uD83D\uDCE1 Extracted detail bytes (${tak.detail.size} bytes)")
                            return tak.detail.toByteArray()
                        }
                        tak.chat != null -> {
                            Log.d(TAG, "\uD83D\uDCE1 Extracted chat message: '${tak.chat.message.take(50)}...'")
                            return tak.chat.message.toByteArray(Charsets.UTF_8)
                        }
                        tak.pli != null -> {
                            Log.d(TAG, "\uD83D\uDCE1 Received PLI (position) packet - ignored for chat")
                            return null
                        }
                        else -> {
                            Log.w(TAG, "\uD83D\uDCE1 TAKPacket has no recognized payload variant")
                            return null
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "\uD83D\uDCE1 Could not parse ATAK payload as TAKPacket", e)
                    // Fallback : we return the raw payload
                    return atakPayload
                }
            } else {
                Log.d(TAG, "\uD83D\uDCE1 Ignoring non-ATAK packet")
                return null
            }

        } catch (e: Exception) {
            Log.w(TAG, "\uD83D\uDCE1 Could not parse as FromRadio, trying raw TAKPacket fallback...", e)

            // Fallback for debugging
            try {
                val tak = TAKPacket.ADAPTER.decode(radioData)
                when {
                    tak.detail != null -> return tak.detail.toByteArray()
                    tak.chat != null -> return tak.chat.message.toByteArray(Charsets.UTF_8)
                    else -> return null
                }
            } catch (fallbackEx: Exception) {
                return null
            }
        }
    }
}
