package mob.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobBeaconFetchCoordinatorTest {
    private class FakeFetchClient : MobBeaconFetchClient {
        var deviceAddress: String? = null
        var request: MobFetchProtocol.Request? = null
        var startCount = 0

        override fun fetchOnce(deviceAddress: String, request: MobFetchProtocol.Request): Boolean {
            this.deviceAddress = deviceAddress
            this.request = request
            startCount += 1
            return true
        }

        override fun stopClient() {}
    }

    private fun envelope(): ByteArray = MobMessageEnvelope.buildV1(
        messageId = ByteArray(16) { it.toByte() },
        senderPeerId = "mob-ios",
        recipientPeerId = null,
        createdAtMs = 1_700_000_000_000L,
        ttl = 1,
        payloadType = "TX",
        payload = "hello".toByteArray()
    )

    private fun beaconFor(envelope: ByteArray): BleEvent.ReceivedMessageBeacon {
        val parsed = MobMessageEnvelope.parse(envelope)
        require(parsed is MobMessageEnvelope.ParseResult.Ok)
        val beaconPayload = BleDispatcher.legacyBeaconPayload(parsed.envelope)
        return BleEvent.ReceivedMessageBeacon(
            beaconVersion = 1,
            envelopeVersion = 1,
            payloadKind = "TX",
            messageIdHash = beaconPayload.copyOfRange(6, 14),
            senderPeerIdHash = beaconPayload.copyOfRange(14, 22),
            receivedDeviceId = "beacon-device",
            receivedAt = 12_345L,
            rssi = -62,
            rawTransportMetadata = BleEvent.ReceivedMessageBeacon.RawTransportMetadata(
                transport = "ble_advertisement",
                sourceEvent = "advertisement_received",
                receivedDeviceId = "beacon-device",
                advertisement = byteArrayOf(1, 2, 3),
                beaconPayload = beaconPayload,
                manufacturerData = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + beaconPayload,
                companyIdentifier = BleDispatcher.MOB_COMPANY_IDENTIFIER,
                adType = MobMessageAdvertisement.MANUFACTURER_SPECIFIC_DATA_AD_TYPE
            )
        )
    }

    @Test fun `service advertisement after beacon starts one fetch request`() {
        val sink = InMemoryEventSink()
        val client = FakeFetchClient()
        lateinit var listener: MobFetchGatt.ClientListener
        val envelope = envelope()
        val coordinator = MobBeaconFetchCoordinator(
            context = null,
            adapter = null,
            sink = sink,
            requesterPeerId = "mob-android",
            nowMs = { 100L },
            fetchClientFactory = {
                listener = it
                client
            }
        )

        coordinator.onLegacyBeacon(beaconFor(envelope))
        coordinator.onFetchServiceAdvertisement("AA:BB:CC")
        coordinator.onFetchServiceAdvertisement("AA:BB:CC")

        assertEquals(1, client.startCount)
        assertEquals("AA:BB:CC", client.deviceAddress)
        assertNotNull(client.request)
        assertEquals("mob-android", client.request?.requesterPeerId)

        listener.onFetchComplete("AA:BB:CC", client.request!!, envelope)

        assertEquals(1, sink.events.size)
        val event = sink.events.single()
        assertTrue(event is BleEvent.ReceivedMessage)
        val received = event as BleEvent.ReceivedMessage
        assertEquals("mob-ios", received.senderPeerId)
        assertEquals("AA:BB:CC", received.receivedDeviceId)
        assertEquals("ble_android_gatt_fetch", received.rawTransportMetadata.transport)
        assertEquals("gatt_fetch_response", received.rawTransportMetadata.sourceEvent)
        assertEquals(envelope.toList(), received.envelope.toList())
    }

    @Test fun `service advertisement hash can start fetch without prior beacon sighting`() {
        val sink = InMemoryEventSink()
        val client = FakeFetchClient()
        val coordinator = MobBeaconFetchCoordinator(
            context = null,
            adapter = null,
            sink = sink,
            nowMs = { 200L },
            fetchClientFactory = { client }
        )

        coordinator.onFetchServiceAdvertisement(
            deviceId = "AA:BB:CC",
            messageIdHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            rssi = -48,
            advertisement = byteArrayOf(2, 0x01, 0x06)
        )

        assertEquals(1, client.startCount)
        assertEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).toList(), client.request?.messageIdHash?.toList())
    }
}
