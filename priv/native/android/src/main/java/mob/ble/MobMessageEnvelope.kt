package mob.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Kotlin mirror of the existing M14 MessageEnvelope wire shape.
 *
 * This intentionally validates only the current v1 format used by the
 * Elixir `Mob.Ble.MessageEnvelope` module. It does not
 * interpret payload bytes, route messages, fragment, encrypt, or mutate
 * peer state.
 */
object MobMessageEnvelope {
    const val CURRENT_VERSION = 1
    const val MAX_TTL = 16
    const val MAX_PEER_ID_SIZE = 32
    const val MAX_PAYLOAD_TYPE_SIZE = 16
    const val MAX_PAYLOAD_SIZE = 4096

    data class Decoded(
        val messageId: ByteArray,
        val senderPeerId: String,
        val recipientPeerId: String?,
        val createdAtMs: Long,
        val ttl: Int,
        val payloadType: String,
        val payload: ByteArray,
        val capabilityRequirements: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return messageId.contentEquals(other.messageId) &&
                senderPeerId == other.senderPeerId &&
                recipientPeerId == other.recipientPeerId &&
                createdAtMs == other.createdAtMs &&
                ttl == other.ttl &&
                payloadType == other.payloadType &&
                payload.contentEquals(other.payload) &&
                capabilityRequirements == other.capabilityRequirements
        }

        override fun hashCode(): Int {
            var result = messageId.contentHashCode()
            result = 31 * result + senderPeerId.hashCode()
            result = 31 * result + (recipientPeerId?.hashCode() ?: 0)
            result = 31 * result + createdAtMs.hashCode()
            result = 31 * result + ttl
            result = 31 * result + payloadType.hashCode()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + capabilityRequirements
            return result
        }
    }

    sealed class ParseResult {
        data class Ok(val envelope: Decoded) : ParseResult()
        data class Error(val reason: String) : ParseResult()
    }

    fun validate(bytes: ByteArray): String? {
        if (bytes.size < 2 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'X'.code.toByte()) {
            return "missing_magic"
        }

        var offset = 2
        val version = readByte(bytes, offset) ?: return "truncated_envelope"
        offset += 1
        if (version == 0) return "invalid_envelope_version"
        if (version != CURRENT_VERSION) return "unsupported_envelope_version"

        val flags = readByte(bytes, offset) ?: return "truncated_envelope"
        offset += 1
        if (flags != 0) return "invalid_flags"

        if (bytes.size < offset + 16) return "truncated_envelope"
        offset += 16

        if (bytes.size < offset + 8) return "truncated_envelope"
        offset += 8

        val ttl = readByte(bytes, offset) ?: return "truncated_envelope"
        offset += 1
        if (ttl < 0 || ttl > MAX_TTL) return "invalid_ttl"

        val sender = readLengthPrefixed(bytes, offset) ?: return "invalid_sender_peer_id"
        offset = sender.nextOffset
        if (sender.length !in 1..MAX_PEER_ID_SIZE) return "invalid_sender_peer_id"

        val recipient = readLengthPrefixed(bytes, offset) ?: return "invalid_recipient_peer_id"
        offset = recipient.nextOffset
        if (recipient.length != 0 && recipient.length !in 1..MAX_PEER_ID_SIZE) {
            return "invalid_recipient_peer_id"
        }

        val payloadType = readLengthPrefixed(bytes, offset) ?: return "invalid_payload_type"
        offset = payloadType.nextOffset
        if (payloadType.length !in 1..MAX_PAYLOAD_TYPE_SIZE) return "invalid_payload_type"

        val caps = readByte(bytes, offset) ?: return "truncated_envelope"
        offset += 1
        if (caps !in 0..255) return "invalid_capability_requirements"

        val payload = readLengthPrefixed16(bytes, offset) ?: return "truncated_envelope"
        if (payload.length > MAX_PAYLOAD_SIZE) return "payload_too_large"

        return null
    }

    fun parse(bytes: ByteArray): ParseResult {
        validate(bytes)?.let { return ParseResult.Error(it) }

        var offset = 4
        val messageId = bytes.copyOfRange(offset, offset + 16)
        offset += 16

        val createdAtMs = ByteBuffer.wrap(bytes, offset, 8)
            .order(ByteOrder.BIG_ENDIAN)
            .long
        offset += 8

        val ttl = bytes[offset].toInt() and 0xFF
        offset += 1

        val sender = readLengthPrefixed(bytes, offset)!!
        val senderStart = offset + 1
        val senderPeerId = String(bytes, senderStart, sender.length, StandardCharsets.UTF_8)
        offset = sender.nextOffset

        val recipient = readLengthPrefixed(bytes, offset)!!
        val recipientStart = offset + 1
        val recipientPeerId = if (recipient.length == 0) {
            null
        } else {
            String(bytes, recipientStart, recipient.length, StandardCharsets.UTF_8)
        }
        offset = recipient.nextOffset

        val type = readLengthPrefixed(bytes, offset)!!
        val typeStart = offset + 1
        val payloadType = String(bytes, typeStart, type.length, StandardCharsets.UTF_8)
        offset = type.nextOffset

        val capabilityRequirements = bytes[offset].toInt() and 0xFF
        offset += 1

        val payload = readLengthPrefixed16(bytes, offset)!!
        val payloadStart = offset + 2

        return ParseResult.Ok(
            Decoded(
                messageId = messageId,
                senderPeerId = senderPeerId,
                recipientPeerId = recipientPeerId,
                createdAtMs = createdAtMs,
                ttl = ttl,
                payloadType = payloadType,
                payload = bytes.copyOfRange(payloadStart, payload.nextOffset),
                capabilityRequirements = capabilityRequirements
            )
        )
    }

    fun buildV1(
        messageId: ByteArray,
        senderPeerId: String,
        recipientPeerId: String?,
        createdAtMs: Long,
        ttl: Int,
        payloadType: String,
        payload: ByteArray,
        capabilityRequirements: Int = 0
    ): ByteArray {
        require(messageId.size == 16) { "messageId must be 16 bytes" }
        require(ttl in 0..MAX_TTL) { "ttl out of range" }
        require(capabilityRequirements in 0..255) { "capabilityRequirements out of range" }

        val sender = senderPeerId.toByteArray(StandardCharsets.UTF_8)
        val recipient = recipientPeerId?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val type = payloadType.toByteArray(StandardCharsets.UTF_8)

        require(sender.size in 1..MAX_PEER_ID_SIZE) { "senderPeerId out of range" }
        require(recipient.isEmpty() || recipient.size in 1..MAX_PEER_ID_SIZE) {
            "recipientPeerId out of range"
        }
        require(type.size in 1..MAX_PAYLOAD_TYPE_SIZE) { "payloadType out of range" }
        require(payload.size <= MAX_PAYLOAD_SIZE) { "payload too large" }

        return ByteArrayOutputStream().use { out ->
            out.write(byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), CURRENT_VERSION.toByte(), 0))
            out.write(messageId)
            out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(createdAtMs).array())
            out.write(ttl)
            out.write(sender.size)
            out.write(sender)
            out.write(recipient.size)
            out.write(recipient)
            out.write(type.size)
            out.write(type)
            out.write(capabilityRequirements)
            out.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(payload.size.toShort()).array())
            out.write(payload)
            out.toByteArray()
        }
    }

    private data class LengthPrefixed(val length: Int, val nextOffset: Int)

    private fun readByte(bytes: ByteArray, offset: Int): Int? =
        if (offset < bytes.size) bytes[offset].toInt() and 0xFF else null

    private fun readLengthPrefixed(bytes: ByteArray, offset: Int): LengthPrefixed? {
        val length = readByte(bytes, offset) ?: return null
        val start = offset + 1
        val end = start + length
        if (end > bytes.size) return null
        return LengthPrefixed(length = length, nextOffset = end)
    }

    private fun readLengthPrefixed16(bytes: ByteArray, offset: Int): LengthPrefixed? {
        if (offset + 2 > bytes.size) return null
        val length = ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
        val start = offset + 2
        val end = start + length
        if (end > bytes.size) return null
        return LengthPrefixed(length = length, nextOffset = end)
    }
}
