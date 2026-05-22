package mob.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobMessageEnvelopeTest {

    private fun envelope(
        messageId: ByteArray = ByteArray(16) { if (it == 15) 1 else 0 },
        payload: ByteArray = "hi".toByteArray()
    ): ByteArray = MobMessageEnvelope.buildV1(
        messageId = messageId,
        senderPeerId = "mob-alpha",
        recipientPeerId = "mob-beta",
        createdAtMs = 1_700_000_000_000L,
        ttl = 1,
        payloadType = "TX",
        payload = payload
    )

    @Test fun `buildV1 emits the documented M14 magic and validates`() {
        val bytes = envelope()

        assertEquals('M'.code.toByte(), bytes[0])
        assertEquals('X'.code.toByte(), bytes[1])
        assertEquals(MobMessageEnvelope.CURRENT_VERSION.toByte(), bytes[2])
        assertNull(MobMessageEnvelope.validate(bytes))
        assertEquals(60, bytes.size)
    }

    @Test fun `known fixture bytes match Elixir M14 encoding`() {
        val bytes = envelope()
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)

        assertEquals(
            "TVgBAAAAAAAAAAAAAAAAAAAAAAEAAAGLz+VoAAELbWVzaHgtYWxwaGEKbWVzaHgtYmV0YQJUWAAAAmhp",
            b64
        )
    }

    @Test fun `parse returns the documented M14 fields`() {
        val bytes = envelope()

        val parsed = MobMessageEnvelope.parse(bytes)

        assertTrue(parsed is MobMessageEnvelope.ParseResult.Ok)
        val envelope = (parsed as MobMessageEnvelope.ParseResult.Ok).envelope
        assertEquals(ByteArray(16) { if (it == 15) 1 else 0 }.toList(), envelope.messageId.toList())
        assertEquals("mob-alpha", envelope.senderPeerId)
        assertEquals("mob-beta", envelope.recipientPeerId)
        assertEquals(1_700_000_000_000L, envelope.createdAtMs)
        assertEquals(1, envelope.ttl)
        assertEquals("TX", envelope.payloadType)
        assertEquals("hi", String(envelope.payload))
    }

    @Test fun `parse returns an error for malformed envelopes`() {
        val parsed = MobMessageEnvelope.parse("hi".toByteArray())

        assertEquals(
            MobMessageEnvelope.ParseResult.Error("missing_magic"),
            parsed
        )
    }

    @Test fun `validate rejects malformed envelopes without throwing`() {
        assertEquals("missing_magic", MobMessageEnvelope.validate("hi".toByteArray()))
        assertEquals(
            "truncated_envelope",
            MobMessageEnvelope.validate(byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 1, 0, 1))
        )
    }

    @Test fun `minimal valid M14 envelope still exceeds legacy manufacturer payload budget`() {
        val minimal = MobMessageEnvelope.buildV1(
            messageId = ByteArray(16),
            senderPeerId = "a",
            recipientPeerId = null,
            createdAtMs = 0L,
            ttl = 1,
            payloadType = "T",
            payload = ByteArray(0)
        )

        assertEquals(37, minimal.size)
        assertTrue(minimal.size > BleDispatcher.MAX_LEGACY_MANUFACTURER_PAYLOAD)
    }
}
