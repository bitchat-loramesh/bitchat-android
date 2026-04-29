package com.bitchat.android.meshtastic

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary format for HELLO / HELLOBACK payloads exchanged over LoRa.
 *
 * The payload is the body of a BitchatPacket(type=MTT_HELLO or MTT_HELLOBACK).
 * It carries the sender's known peer list so that every node in the LoRa mesh
 * can discover bitchat peers that are beyond direct BLE range.
 *
 * ── Header ─────────────────────────────────────────────────────────────
 *  Hello ID    2B len + N bytes   Bitchat peer ID of the sender
 *  Radio Name  2B len + N bytes   Name of the sender's Meshtastic node
 *  LastHeard   8B uint64 (BE)     Timestamp of last received message (ms epoch)
 *  Peer Count  2B uint16 (BE)     Number of peer entries that follow
 *
 * ── Per-peer entry (repeated Peer Count times) ─────────────────────────
 *  Peer ID     2B len + N bytes   Bitchat peer ID
 *  Nickname    2B len + N bytes   Display name
 *  Flags       1B                 Bit 0 = isConnected, Bit 1 = nicknameKnown, 2-7 reserved
 *  Noise Key   1B presence + 32B  Curve25519 public key (presence=1) or absent (presence=0)
 *  Signing Key 1B presence + 32B  Ed25519 public key (presence=1) or absent (presence=0)
 *  LastSeen    8B uint64 (BE)     Timestamp of last message from this peer (ms epoch)
 */
data class HelloPayload(
    val helloID: String,
    val radioName: String,
    val lastHeard: Long,
    val peers: List<HelloPeerEntry>
) {
    data class HelloPeerEntry(
        val peerID: String,
        val nickname: String,
        val isConnected: Boolean,
        val nicknameKnown: Boolean,
        val noiseKey: ByteArray?,    // 32 bytes or null
        val signingKey: ByteArray?,  // 32 bytes or null
        val lastSeen: Long
    )

    // ── Flags bit positions ──────────────────────────────────────────────
    object Flags {
        const val IS_CONNECTED: Int   = 0
        const val NICKNAME_KNOWN: Int = 1
    }

    // ── Serialization ────────────────────────────────────────────────────

    fun encode(): ByteArray {
        // Pre-calculate buffer capacity to avoid reallocations
        val headerSize = 2 + helloID.toByteArray().size +
                         2 + radioName.toByteArray().size +
                         8 + 2  // lastHeard + peerCount

        val peersSize = peers.sumOf { p ->
            val pidBytes = p.peerID.toByteArray()
            val nickBytes = p.nickname.toByteArray()
            2 + pidBytes.size +   // peerID
            2 + nickBytes.size +  // nickname
            1 +                   // flags
            1 + (if (p.noiseKey != null) 32 else 0) +
            1 + (if (p.signingKey != null) 32 else 0) +
            8                     // lastSeen
        }

        val buf = ByteBuffer.allocate(headerSize + peersSize)
            .apply { order(ByteOrder.BIG_ENDIAN) }

        // Header
        buf.writeShortPrefixedString(helloID)
        buf.writeShortPrefixedString(radioName)
        buf.putLong(lastHeard)
        buf.putShort(peers.size.toShort())

        // Peer entries
        for (peer in peers) {
            buf.writeShortPrefixedString(peer.peerID)
            buf.writeShortPrefixedString(peer.nickname)

            var flags = 0
            if (peer.isConnected)    flags = flags or (1 shl Flags.IS_CONNECTED)
            if (peer.nicknameKnown)  flags = flags or (1 shl Flags.NICKNAME_KNOWN)
            buf.put(flags.toByte())

            if (peer.noiseKey != null && peer.noiseKey.size == 32) {
                buf.put(1)
                buf.put(peer.noiseKey)
            } else {
                buf.put(0)
            }

            if (peer.signingKey != null && peer.signingKey.size == 32) {
                buf.put(1)
                buf.put(peer.signingKey)
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

    // ── Deserialization ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "HelloPayload"
        private const val NOISE_KEY_SIZE = 32
        private const val SIGNING_KEY_SIZE = 32

        fun decode(data: ByteArray): HelloPayload? {
            return try {
                val buf = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                // Header
                val helloID    = buf.readShortPrefixedString() ?: return null
                val radioName  = buf.readShortPrefixedString() ?: return null
                val lastHeard  = buf.getLong()
                val peerCount  = buf.getShort().toUShort().toInt()

                // Peers
                val peers = mutableListOf<HelloPeerEntry>()
                repeat(peerCount) {
                    val peerID   = buf.readShortPrefixedString() ?: return null
                    val nickname = buf.readShortPrefixedString() ?: return null
                    val flags    = buf.get().toInt() and 0xFF

                    val isConnected   = (flags shr Flags.IS_CONNECTED)   and 1 == 1
                    val nicknameKnown = (flags shr Flags.NICKNAME_KNOWN) and 1 == 1

                    val noiseKey = if (buf.get().toInt() and 0xFF == 1) {
                        ByteArray(NOISE_KEY_SIZE).also { buf.get(it) }
                    } else null

                    val signingKey = if (buf.get().toInt() and 0xFF == 1) {
                        ByteArray(SIGNING_KEY_SIZE).also { buf.get(it) }
                    } else null

                    val lastSeen = buf.getLong()

                    peers.add(HelloPeerEntry(peerID, nickname, isConnected, nicknameKnown,
                        noiseKey, signingKey, lastSeen))
                }

                HelloPayload(helloID, radioName, lastHeard, peers)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode HelloPayload: ${e.message}")
                null
            }
        }

        // ── ByteBuffer extensions (local to this companion) ───────────────

        private fun ByteBuffer.writeShortPrefixedString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            putShort(bytes.size.toShort())
            put(bytes)
        }

        private fun ByteBuffer.readShortPrefixedString(): String? {
            if (remaining() < 2) return null
            val len = getShort().toUShort().toInt()
            if (len < 0 || remaining() < len) return null
            val bytes = ByteArray(len)
            get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
