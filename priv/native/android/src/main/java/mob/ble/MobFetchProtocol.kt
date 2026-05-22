package mob.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object MobFetchProtocol {
    private val REQUEST_MAGIC = byteArrayOf('M'.code.toByte(), 'F'.code.toByte(), 'Q'.code.toByte())
    private val RESPONSE_MAGIC = byteArrayOf('M'.code.toByte(), 'F'.code.toByte(), 'R'.code.toByte())
    const val VERSION = 1
    const val STATUS_OK = 0
    const val STATUS_NOT_FOUND = 1
    const val STATUS_INVALID_REQUEST = 2

    fun statusName(status: Int): String =
        when (status) {
            STATUS_OK -> "ok"
            STATUS_NOT_FOUND -> "not_found"
            STATUS_INVALID_REQUEST -> "invalid_request"
            else -> "unknown"
        }

    data class Request(
        val requestId: String,
        val messageIdHash: ByteArray,
        val requesterPeerId: String?
    )

    data class Response(
        val requestId: String,
        val messageIdHash: ByteArray,
        val status: Int,
        val envelope: ByteArray?,
        val reason: String?
    )

    fun encodeRequest(request: Request): ByteArray {
        require(request.requestId.isNotEmpty())
        require(request.messageIdHash.size == 8)
        val requestId = request.requestId.toByteArray(StandardCharsets.UTF_8)
        val requester = request.requesterPeerId?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        require(requestId.size <= 255)
        require(requester.size <= 255)

        return ByteArrayOutputStream().use { out ->
            out.write(REQUEST_MAGIC)
            out.write(VERSION)
            out.write(requestId.size)
            out.write(requestId)
            out.write(request.messageIdHash)
            out.write(requester.size)
            out.write(requester)
            out.toByteArray()
        }
    }

    fun decodeRequest(bytes: ByteArray): Request? {
        if (bytes.size < 3 + 1 + 1 + 8 + 1) return null
        if (!bytes.copyOfRange(0, 3).contentEquals(REQUEST_MAGIC)) return null
        if ((bytes[3].toInt() and 0xFF) != VERSION) return null
        var offset = 4
        val requestIdLength = bytes[offset].toInt() and 0xFF
        offset += 1
        if (offset + requestIdLength + 8 + 1 > bytes.size) return null
        val requestId = String(bytes, offset, requestIdLength, StandardCharsets.UTF_8)
        offset += requestIdLength
        val hash = bytes.copyOfRange(offset, offset + 8)
        offset += 8
        val requesterLength = bytes[offset].toInt() and 0xFF
        offset += 1
        if (offset + requesterLength > bytes.size) return null
        val requester = if (requesterLength == 0) null else String(bytes, offset, requesterLength, StandardCharsets.UTF_8)
        if (requestId.isEmpty()) return null
        return Request(requestId, hash, requester)
    }

    fun encodeResponse(response: Response): ByteArray {
        require(response.requestId.isNotEmpty())
        require(response.messageIdHash.size == 8)
        val requestId = response.requestId.toByteArray(StandardCharsets.UTF_8)
        val payload = response.envelope ?: response.reason?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        require(requestId.size <= 255)
        require(payload.size <= 65535)

        return ByteArrayOutputStream().use { out ->
            out.write(RESPONSE_MAGIC)
            out.write(VERSION)
            out.write(response.status)
            out.write(requestId.size)
            out.write(requestId)
            out.write(response.messageIdHash)
            out.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(payload.size.toShort()).array())
            out.write(payload)
            out.toByteArray()
        }
    }

    fun decodeResponse(bytes: ByteArray): Response? {
        if (bytes.size < 3 + 1 + 1 + 1 + 8 + 2) return null
        if (!bytes.copyOfRange(0, 3).contentEquals(RESPONSE_MAGIC)) return null
        if ((bytes[3].toInt() and 0xFF) != VERSION) return null
        val status = bytes[4].toInt() and 0xFF
        if (status !in STATUS_OK..STATUS_INVALID_REQUEST) return null
        var offset = 5
        val requestIdLength = bytes[offset].toInt() and 0xFF
        offset += 1
        if (offset + requestIdLength + 8 + 2 > bytes.size) return null
        val requestId = String(bytes, offset, requestIdLength, StandardCharsets.UTF_8)
        offset += requestIdLength
        val hash = bytes.copyOfRange(offset, offset + 8)
        offset += 8
        val payloadLength = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        offset += 2
        if (offset + payloadLength > bytes.size) return null
        val payload = bytes.copyOfRange(offset, offset + payloadLength)
        if (requestId.isEmpty()) return null
        return if (status == STATUS_OK) {
            Response(requestId, hash, status, payload, null)
        } else {
            Response(requestId, hash, status, null, String(payload, StandardCharsets.UTF_8))
        }
    }
}
