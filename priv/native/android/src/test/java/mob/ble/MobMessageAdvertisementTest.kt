package mob.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobMessageAdvertisementTest {

    private fun envelope(): ByteArray = MobMessageEnvelope.buildV1(
        messageId = ByteArray(16) { if (it == 15) 1 else 0 },
        senderPeerId = "mob-alpha",
        recipientPeerId = "mob-beta",
        createdAtMs = 1_700_000_000_000L,
        ttl = 1,
        payloadType = "TX",
        payload = "hi".toByteArray()
    )

    private fun scanRecord(payload: ByteArray): ByteArray {
        return byteArrayOf(2, 0x01, 0x06) + manufacturerStructure(payload)
    }

    private fun manufacturerStructure(payload: ByteArray): ByteArray {
        val manufacturerLength = payload.size + 3
        return byteArrayOf(
            manufacturerLength.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte()
        ) + payload
    }

    private fun legacyBeacon(): ByteArray {
        val parsed = MobMessageEnvelope.parse(envelope())
        require(parsed is MobMessageEnvelope.ParseResult.Ok)
        return BleDispatcher.legacyBeaconPayload(parsed.envelope)
    }

    @Test fun `message advertisement becomes canonical received message`() {
        val envelope = envelope()
        val result = MobMessageAdvertisement.decodeScanRecord(
            advertisement = scanRecord(envelope),
            deviceId = "AA:BB:CC",
            rssi = -61,
            observedAtMs = 12345L,
            sourceEvent = "advertisement_received"
        )

        assertTrue(result is MobMessageAdvertisement.DecodeResult.Received)
        val event = (result as MobMessageAdvertisement.DecodeResult.Received).event

        assertEquals(ByteArray(16) { if (it == 15) 1 else 0 }.toList(), event.messageId.toList())
        assertEquals("mob-alpha", event.senderPeerId)
        assertEquals("mob-beta", event.recipientPeerId)
        assertEquals("AA:BB:CC", event.receivedDeviceId)
        assertEquals(12345L, event.receivedAt)
        assertEquals(-61, event.rssi)
        assertEquals(envelope.toList(), event.envelope.toList())
        assertEquals(envelope.toList(), event.rawTransportMetadata.messagePayload.toList())
        assertEquals((byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + envelope).toList(), event.rawTransportMetadata.manufacturerData.toList())
        assertEquals(65535, event.rawTransportMetadata.companyIdentifier)
        assertEquals(255, event.rawTransportMetadata.adType)
        assertEquals("advertisement_received", event.rawTransportMetadata.sourceEvent)
        assertEquals("received_message", event.toJsonObject().getString("event"))
    }

    @Test fun `ordinary advertisements are ignored`() {
        val result = MobMessageAdvertisement.decodeScanRecord(
            advertisement = byteArrayOf(2, 0x01, 0x06),
            deviceId = "AA:BB",
            rssi = -70,
            observedAtMs = 0L,
            sourceEvent = "device_discovered"
        )

        assertEquals(MobMessageAdvertisement.DecodeResult.NotMessageAdvertisement, result)
    }

    @Test fun `legacy message beacon becomes canonical beacon event`() {
        val beacon = legacyBeacon()
        val result = MobMessageAdvertisement.decodeScanRecord(
            advertisement = scanRecord(beacon),
            deviceId = "AA:BB:CC",
            rssi = -62,
            observedAtMs = 45678L,
            sourceEvent = "advertisement_received"
        )

        assertTrue(result is MobMessageAdvertisement.DecodeResult.ReceivedBeacon)
        val event = (result as MobMessageAdvertisement.DecodeResult.ReceivedBeacon).event

        assertEquals("received_message_beacon", event.toJsonObject().getString("event"))
        assertEquals(1, event.beaconVersion)
        assertEquals(1, event.envelopeVersion)
        assertEquals("TX", event.payloadKind)
        assertEquals(8, event.messageIdHash.size)
        assertEquals(8, event.senderPeerIdHash.size)
        assertEquals(beacon.toList(), event.rawTransportMetadata.beaconPayload.toList())
        assertEquals((byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + beacon).toList(), event.rawTransportMetadata.manufacturerData.toList())
    }

    @Test fun `malformed tagged message advertisement becomes tagged error`() {
        val badPayload = byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 1, 0, 1, 2, 3)
        val result = MobMessageAdvertisement.decodeScanRecord(
            advertisement = scanRecord(badPayload),
            deviceId = "AA:BB",
            rssi = -70,
            observedAtMs = 0L,
            sourceEvent = "advertisement_received"
        )

        assertTrue(result is MobMessageAdvertisement.DecodeResult.Error)
        val event = (result as MobMessageAdvertisement.DecodeResult.Error).event

        assertEquals(BleEvent.Companion.ErrorKind.UNKNOWN, event.kind)
        assertEquals("AA:BB", event.deviceId)
        assertTrue(event.detail.contains("message_advertisement_decode_error"))
        assertTrue(event.detail.contains("truncated_envelope"))
    }

    @Test fun `truncated tagged message advertisement structure becomes tagged error`() {
        val badPayload = byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 1, 0, 1, 2, 3)
        val complete = scanRecord(badPayload)
        val truncated = complete.copyOf(complete.size - 1)
        val result = MobMessageAdvertisement.decodeScanRecord(
            advertisement = truncated,
            deviceId = "AA:BB",
            rssi = -70,
            observedAtMs = 0L,
            sourceEvent = "advertisement_received"
        )

        assertTrue(result is MobMessageAdvertisement.DecodeResult.Error)
        val event = (result as MobMessageAdvertisement.DecodeResult.Error).event

        assertEquals(BleEvent.Companion.ErrorKind.UNKNOWN, event.kind)
        assertEquals("AA:BB", event.deviceId)
        assertTrue(event.detail.contains("message_advertisement_decode_error"))
        assertTrue(event.detail.contains("truncated_ad_structure"))
    }

    @Test fun `first valid message structure wins over later truncated message structure`() {
        val envelope = envelope()
        val badPayload = byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 1, 0, 1, 2, 3)
        val truncated = manufacturerStructure(badPayload).copyOf(8)
        val advertisement = byteArrayOf(2, 0x01, 0x06) + manufacturerStructure(envelope) + truncated
        val result = MobMessageAdvertisement.decodeScanRecord(
            advertisement = advertisement,
            deviceId = "AA:BB:CC",
            rssi = -61,
            observedAtMs = 12345L,
            sourceEvent = "advertisement_received"
        )

        assertTrue(result is MobMessageAdvertisement.DecodeResult.Received)
        val event = (result as MobMessageAdvertisement.DecodeResult.Received).event

        assertEquals(envelope.toList(), event.envelope.toList())
        assertEquals(advertisement.toList(), event.rawTransportMetadata.advertisement.toList())
    }
}
