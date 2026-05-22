package mob.ble

import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BleAdvertGossipDispatcherTest {

    private class FakeRadio(
        override val isAvailable: Boolean = true,
        override val isLeExtendedAdvertisingSupported: Boolean = false,
        override val leMaximumAdvertisingDataLength: Int = 31,
        override val isMultipleAdvertisementSupported: Boolean = true
    ) : BleDispatchRadio {
        var legacyStartCalls = 0
            private set
        var lastPayload: ByteArray? = null
            private set

        override fun startLegacyAdvertising(
            payload: ByteArray,
            callback: AdvertiseCallback
        ) {
            legacyStartCalls += 1
            lastPayload = payload
            callback.onStartSuccess(null)
        }

        override fun stopLegacyAdvertising(callback: AdvertiseCallback) = Unit

        override fun startExtendedAdvertising(
            payload: ByteArray,
            extendedConnectable: Boolean,
            useServiceDataForPayload: Boolean,
            serviceDataUuid: UUID?,
            callback: AdvertisingSetCallback
        ) = Unit

        override fun stopExtendedAdvertising(callback: AdvertisingSetCallback) = Unit
    }

    private object NoopScheduler : BleDispatchScheduler {
        override fun postDelayed(delayMs: Long, action: () -> Unit) = Unit
    }

    private fun messageHash(): ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private fun senderHash(): ByteArray = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)

    @Test fun `legacy beacon gossip starts one legacy advertisement`() {
        val radio = FakeRadio()
        val sink = InMemoryEventSink()

        val result = BleAdvertGossipDispatcher(radio, sink, NoopScheduler).dispatchLegacyBeacon(
            gossipIntentId = "gossip-0",
            messageIdHash = messageHash(),
            senderPeerIdHash = senderHash(),
            payloadKind = "TX",
            envelopeVersion = 1
        )

        assertEquals(BleAdvertGossipDispatcher.Result.Kind.GOSSIPED, result.kind)
        assertEquals(1, radio.legacyStartCalls)
        assertEquals(BleDispatcher.LEGACY_BEACON_PAYLOAD_SIZE, radio.lastPayload?.size)
        assertEquals('M'.code.toByte(), radio.lastPayload?.get(0))
        assertEquals('B'.code.toByte(), radio.lastPayload?.get(1))

        val event = sink.events.single() as BleEvent.AdvertGossipOutcome
        assertEquals("advert_gossip_outcome", event.toJsonObject().getString("event"))
        assertEquals("gossiped", event.kind)
        assertEquals("legacy_beacon_advert", event.advertiseAs)
    }

    @Test fun `dry-run produces would_gossip without touching radio`() {
        val radio = FakeRadio()

        val result = BleAdvertGossipDispatcher(radio, InMemoryEventSink(), NoopScheduler)
            .dispatchLegacyBeacon(
                gossipIntentId = "gossip-0",
                messageIdHash = messageHash(),
                senderPeerIdHash = senderHash(),
                payloadKind = "TX",
                envelopeVersion = 1,
                dryRun = true
            )

        assertEquals(BleAdvertGossipDispatcher.Result.Kind.WOULD_GOSSIP, result.kind)
        assertEquals(0, radio.legacyStartCalls)
    }

    @Test fun `invalid hashes never touch radio`() {
        val radio = FakeRadio()

        val result = BleAdvertGossipDispatcher(radio, InMemoryEventSink(), NoopScheduler)
            .dispatchLegacyBeacon(
                gossipIntentId = "gossip-0",
                messageIdHash = byteArrayOf(1, 2),
                senderPeerIdHash = senderHash(),
                payloadKind = "TX",
                envelopeVersion = 1
            )

        assertEquals(BleAdvertGossipDispatcher.Result.Kind.INVALID_INTENT, result.kind)
        assertEquals("validation", result.reason)
        assertEquals(0, radio.legacyStartCalls)
    }

    @Test fun `bluetooth unavailable fails before radio start`() {
        val radio = FakeRadio(isAvailable = false)

        val result = BleAdvertGossipDispatcher(radio, InMemoryEventSink(), NoopScheduler)
            .dispatchLegacyBeacon(
                gossipIntentId = "gossip-0",
                messageIdHash = messageHash(),
                senderPeerIdHash = senderHash(),
                payloadKind = "TX",
                envelopeVersion = 1
            )

        assertEquals(BleAdvertGossipDispatcher.Result.Kind.FAILED, result.kind)
        assertEquals("bluetooth_off", result.reason)
        assertEquals(0, radio.legacyStartCalls)
    }

    @Test fun `full envelope gossip is explicitly disabled`() {
        val result = BleAdvertGossipDispatcher(FakeRadio(), InMemoryEventSink(), NoopScheduler)
            .dispatchFullEnvelopeDisabled(
                gossipIntentId = "gossip-0",
                messageIdHash = messageHash(),
                senderPeerIdHash = senderHash()
            )

        assertEquals(BleAdvertGossipDispatcher.Result.Kind.SKIPPED, result.kind)
        assertEquals("full_envelope_gossip_disabled", result.reason)
        assertEquals("full_envelope_advert", result.advertiseAs)
    }

    @Test fun `legacy beacon gossip started JSON preserves hashes`() {
        val beacon = BleAdvertGossipDispatcher.legacyBeaconPayload(
            envelopeVersion = 1,
            payloadKind = "TX",
            messageIdHash = messageHash(),
            senderPeerIdHash = senderHash()
        )
        val json = JSONObject(
            BleAdvertGossipDispatcher.legacyBeaconGossipStartedJsonLine(
                gossipIntentId = "gossip-0",
                beacon = beacon,
                envelopeVersion = 1,
                payloadKind = "TX"
            )
        )

        assertEquals("legacy_beacon_gossip_started", json.getString("event"))
        assertEquals(BleDispatcher.LEGACY_BEACON_PAYLOAD_SIZE, json.getInt("beacon_size"))
        assertTrue(json.getString("message_id_hash").isNotEmpty())
        assertTrue(json.getString("sender_peer_id_hash").isNotEmpty())
    }
}
