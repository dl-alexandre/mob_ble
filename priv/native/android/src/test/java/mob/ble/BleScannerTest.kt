package mob.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScannerTest {
    private class RecordingFetchHook : MobBeaconFetchCoordinatorHook {
        val beacons = mutableListOf<BleEvent.ReceivedMessageBeacon>()
        val serviceAdvertisements = mutableListOf<String>()
        val messageHashes = mutableListOf<ByteArray?>()

        override fun onLegacyBeacon(beacon: BleEvent.ReceivedMessageBeacon) {
            beacons.add(beacon)
        }

        override fun onFetchServiceAdvertisement(
            deviceId: String,
            messageIdHash: ByteArray?,
            rssi: Int,
            advertisement: ByteArray
        ) {
            serviceAdvertisements.add(deviceId)
            messageHashes.add(messageIdHash)
        }
    }

    private fun envelope(): ByteArray = MobMessageEnvelope.buildV1(
        messageId = ByteArray(16) { it.toByte() },
        senderPeerId = "mob-alpha",
        recipientPeerId = "mob-beta",
        createdAtMs = 1_700_000_000_000L,
        ttl = 1,
        payloadType = "TX",
        payload = "hi".toByteArray()
    )

    private fun scanRecord(payload: ByteArray): ByteArray {
        val manufacturerLength = payload.size + 3
        return byteArrayOf(
            2,
            0x01,
            0x06,
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

    @Test fun `message advertisement scan result emits canonical ReceivedMessage`() {
        val sink = InMemoryEventSink()
        val scanner = BleScanner(adapter = null, sink = sink)
        val envelope = envelope()

        scanner.handleScanFields(
            deviceId = "AA:BB:CC:DD:EE:01",
            rssi = -61,
            advertisement = scanRecord(envelope),
            observedAtMs = 12_345L
        )

        assertEquals(1, sink.events.size)
        val event = sink.events.single()
        assertTrue(event is BleEvent.ReceivedMessage)

        val received = event as BleEvent.ReceivedMessage
        assertEquals(ByteArray(16) { it.toByte() }.toList(), received.messageId.toList())
        assertEquals("mob-alpha", received.senderPeerId)
        assertEquals("mob-beta", received.recipientPeerId)
        assertEquals("AA:BB:CC:DD:EE:01", received.receivedDeviceId)
        assertEquals("device_discovered", received.rawTransportMetadata.sourceEvent)
        assertEquals(envelope.toList(), received.envelope.toList())
    }

    @Test fun `ordinary advertisements keep sighting classification`() {
        val sink = InMemoryEventSink()
        val scanner = BleScanner(adapter = null, sink = sink)
        val advertisement = byteArrayOf(2, 0x01, 0x06)

        scanner.handleScanFields("AA:BB", -70, advertisement, 1L)
        scanner.handleScanFields("AA:BB", -71, advertisement, 2L)

        assertTrue(sink.events[0] is BleEvent.DeviceDiscovered)
        assertTrue(sink.events[1] is BleEvent.AdvertisementReceived)
    }

    @Test fun `legacy beacon scan result is offered to fetch coordinator`() {
        val sink = InMemoryEventSink()
        val hook = RecordingFetchHook()
        val scanner = BleScanner(adapter = null, sink = sink, fetchCoordinator = hook)

        scanner.handleScanFields(
            deviceId = "AA:BB:CC:DD:EE:02",
            rssi = -62,
            advertisement = scanRecord(legacyBeacon()),
            observedAtMs = 12_346L
        )

        assertEquals(1, sink.events.size)
        assertTrue(sink.events.single() is BleEvent.ReceivedMessageBeacon)
        assertEquals(1, hook.beacons.size)
        assertEquals("AA:BB:CC:DD:EE:02", hook.beacons.single().receivedDeviceId)
    }

    @Test fun `fetch service advertisement is offered to fetch coordinator`() {
        val sink = InMemoryEventSink()
        val hook = RecordingFetchHook()
        val scanner = BleScanner(adapter = null, sink = sink, fetchCoordinator = hook)
        val localName = "mx0102030405060708".toByteArray()
        val advertisement = byteArrayOf(2, 0x01, 0x06, (localName.size + 1).toByte(), 0x09) + localName

        scanner.handleScanFields(
            deviceId = "AA:BB:CC:DD:EE:03",
            rssi = -63,
            advertisement = advertisement,
            observedAtMs = 12_347L,
            serviceUuids = listOf(MobFetchGatt.SERVICE_UUID.toString())
        )

        assertEquals(1, sink.events.size)
        assertTrue(sink.events.single() is BleEvent.DeviceDiscovered)
        assertEquals(listOf("AA:BB:CC:DD:EE:03"), hook.serviceAdvertisements)
        assertEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).toList(), hook.messageHashes.single()?.toList())
    }

    @Test fun `fetch service parsed device name is offered to fetch coordinator`() {
        val sink = InMemoryEventSink()
        val hook = RecordingFetchHook()
        val scanner = BleScanner(adapter = null, sink = sink, fetchCoordinator = hook)
        val advertisement = byteArrayOf(2, 0x01, 0x06)

        scanner.handleScanFields(
            deviceId = "AA:BB:CC:DD:EE:04",
            rssi = -64,
            advertisement = advertisement,
            observedAtMs = 12_348L,
            serviceUuids = listOf(MobFetchGatt.SERVICE_UUID.toString()),
            localName = "mx1112131415161718"
        )

        assertEquals(1, sink.events.size)
        assertTrue(sink.events.single() is BleEvent.DeviceDiscovered)
        assertEquals(listOf("AA:BB:CC:DD:EE:04"), hook.serviceAdvertisements)
        assertEquals(byteArrayOf(0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18).toList(), hook.messageHashes.single()?.toList())
    }

    @Test fun `malformed tagged message advertisement emits decode error`() {
        val sink = InMemoryEventSink()
        val scanner = BleScanner(adapter = null, sink = sink)
        val badPayload = byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 1, 0, 1, 2, 3)

        scanner.handleScanFields("AA:BB", -70, scanRecord(badPayload), 1L)

        assertEquals(1, sink.events.size)
        val event = sink.events.single()
        assertTrue(event is BleEvent.Error)
        assertTrue((event as BleEvent.Error).detail.contains("message_advertisement_decode_error"))
    }
}
