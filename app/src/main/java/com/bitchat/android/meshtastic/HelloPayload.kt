package com.bitchat.android.meshtastic

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary format for HELLO / HELLOBACK payloads exchanged over LoRa.
 *
 * Format is 1compatible with the iOS implementation.
 *
 *  === Header ===
 *  Hello ID    2B uint16 (BE)     Random session identifier (echoed in HELLOBACK)
 *  Radio Name  2B len + N bytes   Name of the sender's Meshtastic node
 *  Peer Count  2B uint16 (BE)     Number of peer entries that follow
 *
 * === Per-peer entry (repeated Peer Count times) ===
 *  Peer ID     2B len + N bytes   Bitchat peer ID
 *  Nickname    2B len + N bytes   Display name
 *  Flags       1B                 Bit 0 = isConnected, Bit 1 = isVerifiedNickname, 2-7 reserved
 *  Noise Key   1B presence + 32B  Curve25519 public key (presence=1) or absent (presence=0)
 *  Signing Key 1B presence + 32B  Ed25519 public key (presence=1) or absent (presence=0)
 *  LastSeen    8B uint64 (BE)     Unix timestamp in milliseconds
 */
data class HelloPayload(
    val helloId: UShort,      // 2-byte random session ID (echoed in HELLOBACK)
    val radioName: String,
    val peers: List<HelloPeerEntry>
) {
    data class HelloPeerEntry(
        val peerID: String,
        val nickname: String,
        val isConnected: Boolean,
        val isVerifiedNickname: Boolean,  // bit 1 (matches iOS "isVerifiedNickname")
        val noiseKey: ByteArray?,         // 32 bytes or null
        val signingKey: ByteArray?,       // 32 bytes or null
        val lastSeen: Long                // ms epoch
    )

    // Serialization

    fun encode(): ByteArray {
        val headerSize = 2 +                          // helloId
                         2 + radioName.toByteArray().size +  // radioName
                         2                            // peerCount

        val peersSize = peers.sumOf { p ->
            val pidBytes = p.peerID.toByteArray()
            val nickBytes = p.nickname.toByteArray()
            2 + pidBytes.size +
            2 + nickBytes.size +
            1 +                                       // flags
            1 + (if (p.noiseKey != null) 32 else 0) +
            1 + (if (p.signingKey != null) 32 else 0) +
            8                                         // lastSeen
        }

        val buf = ByteBuffer.allocate(headerSize + peersSize)
            .apply { order(ByteOrder.BIG_ENDIAN) }

        // Header
        buf.putShort(helloId.toShort())
        buf.writeShortPrefixedString(radioName)
        buf.putShort(peers.size.toShort())

        // Peer entries
        for (peer in peers) {
            buf.writeShortPrefixedString(peer.peerID)
            buf.writeShortPrefixedString(peer.nickname)

            var flags = 0
            if (peer.isConnected)        flags = flags or 0x01
            if (peer.isVerifiedNickname) flags = flags or 0x02
            buf.put(flags.toByte())

            if (peer.noiseKey != null && peer.noiseKey.size == 32) {
                buf.put(1); buf.put(peer.noiseKey)
            } else {
                buf.put(0)
            }

            if (peer.signingKey != null && peer.signingKey.size == 32) {
                buf.put(1); buf.put(peer.signingKey)
            } else {
                buf.put(0)
            }

            buf.putLong(peer.lastSeen)
        }

        val result = ByteArray(buf.position())
        buf.rewind()
        buf.get(result)
        return result
    }

    //  Deserialization

    companion object {
        private const val TAG = "HelloPayload"
        private const val KEY_SIZE = 32

        fun decode(data: ByteArray): HelloPayload? {
            return try {
                val buf = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                // Header
                val helloId   = buf.getShort().toUShort()
                val radioName = buf.readShortPrefixedString() ?: return null
                val peerCount = buf.getShort().toUShort().toInt()

                // Peers
                val peers = mutableListOf<HelloPeerEntry>()
                repeat(peerCount) {
                    val peerID   = buf.readShortPrefixedString() ?: return null
                    val nickname = buf.readShortPrefixedString() ?: return null
                    val flags    = buf.get().toInt() and 0xFF

                    val isConnected        = (flags and 0x01) != 0
                    val isVerifiedNickname = (flags and 0x02) != 0

                    val noiseKey = if (buf.get().toInt() and 0xFF == 1) {
                        ByteArray(KEY_SIZE).also { buf.get(it) }
                    } else null

                    val signingKey = if (buf.get().toInt() and 0xFF == 1) {
                        ByteArray(KEY_SIZE).also { buf.get(it) }
                    } else null

                    val lastSeen = buf.getLong()

                    peers.add(HelloPeerEntry(peerID, nickname, isConnected, isVerifiedNickname,
                        noiseKey, signingKey, lastSeen))
                }

                HelloPayload(helloId, radioName, peers)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode HelloPayload: ${e.message}")
                null
            }
        }

        // ByteBuffer extensions (local to this companion)

        private fun ByteBuffer.writeShortPrefixedString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            putShort(bytes.size.toShort())
            put(bytes)
        }

        private fun ByteBuffer.readShortPrefixedString(): String? {
            if (remaining() < 2) return null
            val len = getShort().toUShort().toInt()
            if (remaining() < len) return null
            val bytes = ByteArray(len)
            get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
