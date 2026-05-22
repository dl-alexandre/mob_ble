package mob.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobFetchProtocolTest {
    @Test
    fun requestRoundTripPreservesCanonicalFields() {
        val request = MobFetchProtocol.Request(
            requestId = "fetch-1",
            messageIdHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            requesterPeerId = "mob-beta"
        )

        val decoded = MobFetchProtocol.decodeRequest(MobFetchProtocol.encodeRequest(request))!!

        assertEquals("fetch-1", decoded.requestId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), decoded.messageIdHash)
        assertEquals("mob-beta", decoded.requesterPeerId)
    }

    @Test
    fun responseRoundTripPreservesEnvelope() {
        val envelope = MobMessageEnvelope.buildV1(
            messageId = ByteArray(16) { it.toByte() },
            senderPeerId = "mob-alpha",
            recipientPeerId = "mob-beta",
            createdAtMs = 1_700_000_000_000L,
            ttl = 1,
            payloadType = "TX",
            payload = "hi".toByteArray()
        )
        val response = MobFetchProtocol.Response(
            requestId = "fetch-1",
            messageIdHash = MobFetchGatt.messageIdHash(ByteArray(16) { it.toByte() }),
            status = MobFetchProtocol.STATUS_OK,
            envelope = envelope,
            reason = null
        )

        val decoded = MobFetchProtocol.decodeResponse(MobFetchProtocol.encodeResponse(response))!!

        assertEquals("fetch-1", decoded.requestId)
        assertEquals(MobFetchProtocol.STATUS_OK, decoded.status)
        assertArrayEquals(response.messageIdHash, decoded.messageIdHash)
        assertArrayEquals(envelope, decoded.envelope)
        assertNull(decoded.reason)
    }

    @Test
    fun responseRoundTripPreservesFailureReason() {
        val response = MobFetchProtocol.Response(
            requestId = "fetch-1",
            messageIdHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            status = MobFetchProtocol.STATUS_NOT_FOUND,
            envelope = null,
            reason = "not_found"
        )

        val decoded = MobFetchProtocol.decodeResponse(MobFetchProtocol.encodeResponse(response))!!

        assertEquals(MobFetchProtocol.STATUS_NOT_FOUND, decoded.status)
        assertEquals("not_found", decoded.reason)
        assertNull(decoded.envelope)
    }

    @Test
    fun malformedMessagesAreRejected() {
        assertNull(MobFetchProtocol.decodeRequest(byteArrayOf('M'.code.toByte())))
        assertNull(MobFetchProtocol.decodeResponse(byteArrayOf('M'.code.toByte())))
    }
}
