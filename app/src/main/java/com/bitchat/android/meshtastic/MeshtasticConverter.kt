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

    // 0xFFFFFFFF = broadcast to all nodes on the channel
    private const val BROADCAST_ADDR = -1

    // Match Meshtastic's default hop limit so packets are relayed across the mesh.
    // 0 = direct neighbor only (no relay); 3 = standard Meshtastic default.
    private const val DEFAULT_HOP_LIMIT = 3

    // MARK: - Protobuf encoding

    /**
     * Converts a pre-encoded BitchatPacket byte array to a TAKPacket.
     *
     * The iOS bitchat-loramesh implementation always places the full BitchatPacket
     * binary encoding in TAKPacket.detail. We mirror that behaviour so that
     * messages are decodable on both platforms.
     * GeoChat.chat is intentionally NOT used for outgoing bitchat messages —
     * it would lose all bitchat metadata (senderID, TTL, type, compression).
     */
    fun toAttakProtocol(encodedBitchatPacket: ByteArray): TAKPacket? {
        return try {
            Log.d(TAG, "📡 Wrapping ${encodedBitchatPacket.size} bytes into TAKPacket.detail")
            TAKPacket(detail = encodedBitchatPacket.toByteString())
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
                    // hop_limit = 0 means "direct neighbor only, no relay".
                    // Setting 3 matches Meshtastic's default and ensures packets
                    // are forwarded across multi-hop meshes.
                    hop_limit = DEFAULT_HOP_LIMIT,
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
     * Serialize a bitchat message payload to a BLE-ready byte array
     */
    fun toMeshtasticData(messagePayload: ByteArray): ByteArray {
        val toRadio = toMeshtastic(messagePayload) ?: return ByteArray(0)

        return try {
            val bin = toRadio.encode()
            Log.d(TAG, "📡 Serialized ToRadio to ${bin.size} bytes for BLE write")
            bin
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize ToRadio", e)
            ByteArray(0)
        }
    }

    /**
     * Decodes a raw BLE FROMRADIO byte array and returns the bitchat payload if the
     * packet is an ATAK_PLUGIN message. Returns null for every other packet type
     * (config, nodeinfo, position, etc.) which are silently ignored.
     *
     * Logs every mesh packet received with its portnum and sender node ID so that
     * the full receive pipeline can be verified without a Meshtastic serial console.
     */
    fun toBitchat(radioData: ByteArray): ByteArray? {
        return try {
            val fromRadio = FromRadio.ADAPTER.decode(radioData)

            val meshPacket = fromRadio.packet ?: run {
                // Non-packet FromRadio variants (config, nodeinfo, rebooted, etc.)
                // arrive during the initial drain; log them only at DEBUG level.
                Log.d(TAG, "📥 FromRadio (non-packet): ${fromRadioVariantName(fromRadio)}")
                return null
            }

            // Log every mesh packet so we can verify port numbers and routing
            // without needing a physical serial console on the radio.
            val portnum = meshPacket.decoded?.portnum
            val fromNode = meshPacket.from.toUInt()
            val toNode = meshPacket.to.toUInt()
            Log.i(TAG, "📥 MeshPacket from=0x${fromNode.toString(16)} to=0x${toNode.toString(16)} portnum=$portnum hops=${meshPacket.hop_start - meshPacket.hop_limit}")

            if ((meshPacket.encrypted?.size ?: 0) > 0 && meshPacket.decoded == null) {
                Log.w(TAG, "📥 Packet is encrypted and undecoded — channel key mismatch?")
                return null
            }

            val decodedData = meshPacket.decoded ?: run {
                Log.d(TAG, "📥 MeshPacket has no decoded payload")
                return null
            }

            if (decodedData.portnum != PortNum.ATAK_PLUGIN) {
                Log.d(TAG, "📥 Ignoring portnum=${decodedData.portnum} (not ATAK_PLUGIN)")
                return null
            }

            val atakPayload = decodedData.payload.toByteArray()
            Log.i(TAG, "📥 ATAK_PLUGIN packet received (${atakPayload.size} bytes)")

            try {
                val tak = TAKPacket.ADAPTER.decode(atakPayload)

                when {
                    tak.chat != null -> {
                        Log.d(TAG, "📥 TAKPacket.chat: '${tak.chat.message.take(50)}'")
                        tak.chat.message.toByteArray(Charsets.UTF_8)
                    }
                    tak.detail != null -> {
                        Log.d(TAG, "📥 TAKPacket.detail: ${tak.detail.size} bytes")
                        tak.detail.toByteArray()
                    }
                    tak.pli != null -> {
                        Log.d(TAG, "📥 TAKPacket.pli (position) — ignored for chat")
                        null
                    }
                    else -> {
                        Log.w(TAG, "📥 TAKPacket has no recognised payload variant")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "📥 Could not parse ATAK payload as TAKPacket, returning raw bytes", e)
                atakPayload
            }

        } catch (e: Exception) {
            Log.w(TAG, "📥 Could not decode FromRadio: ${e.message}")
            null
        }
    }

    /** Human-readable description of a non-packet FromRadio variant for logging. */
    private fun fromRadioVariantName(fromRadio: FromRadio): String = when {
        fromRadio.my_info != null -> "my_info"
        fromRadio.node_info != null -> "node_info(${fromRadio.node_info.num.toUInt().toString(16)})"
        fromRadio.config != null -> "config"
        fromRadio.moduleConfig != null -> "module_config"
        fromRadio.channel != null -> "channel(${fromRadio.channel.index})"
        fromRadio.config_complete_id != 0 -> "config_complete(id=${fromRadio.config_complete_id})"
        fromRadio.rebooted == true -> "rebooted"
        fromRadio.metadata != null -> "metadata"
        fromRadio.mqttClientProxyMessage != null -> "mqtt_proxy"
        fromRadio.clientNotification != null -> "client_notification"
        else -> "unknown"
    }
}
