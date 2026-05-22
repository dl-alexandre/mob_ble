package mob.ble

import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject
import java.util.UUID

class BleDispatcherTest {

    private fun dispatcher(): BleDispatcher = BleDispatcher(
        adapter = null,                     // no BLE in JVM tests
        sink = InMemoryEventSink()
    )

    private class FakeRadio(
        override val isAvailable: Boolean = true,
        override val isLeExtendedAdvertisingSupported: Boolean = false,
        override val leMaximumAdvertisingDataLength: Int = 31,
        override val isMultipleAdvertisementSupported: Boolean = true
    ) : BleDispatchRadio {
        var legacyStartCalls = 0
            private set
        var extendedStartCalls = 0
            private set
        val startCalls: Int
            get() = legacyStartCalls + extendedStartCalls

        override fun startLegacyAdvertising(
            payload: ByteArray,
            callback: AdvertiseCallback
        ) {
            legacyStartCalls += 1
            callback.onStartSuccess(null)
        }

        override fun stopLegacyAdvertising(callback: AdvertiseCallback) = Unit

        override fun startExtendedAdvertising(
            payload: ByteArray,
            extendedConnectable: Boolean,
            useServiceDataForPayload: Boolean,
            serviceDataUuid: UUID?,
            callback: AdvertisingSetCallback
        ) {
            extendedStartCalls += 1
        }

        override fun stopExtendedAdvertising(callback: AdvertisingSetCallback) = Unit
    }

    private object NoopScheduler : BleDispatchScheduler {
        override fun postDelayed(delayMs: Long, action: () -> Unit) = Unit
    }

    private fun messageId(): ByteArray = ByteArray(16) { it.toByte() }

    private fun envelope(
        messageId: ByteArray = messageId(),
        recipientPeerId: String? = "mob-beta"
    ): ByteArray = MobMessageEnvelope.buildV1(
        messageId = messageId,
        senderPeerId = "mob-alpha",
        recipientPeerId = recipientPeerId,
        createdAtMs = 1_700_000_000_000L,
        ttl = 1,
        payloadType = "TX",
        payload = "hi".toByteArray()
    )

    @Test fun `dry_run produces WOULD_DISPATCH without touching the radio`() {
        val r = dispatcher().dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "mob-beta",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope(),
            dryRun = true
        )
        assertEquals(BleDispatcher.BleDispatchResult.Kind.WOULD_DISPATCH, r.kind)
        assertNull(r.reason)
        assertEquals("ble_android", r.adapter)
    }

    @Test fun `empty attemptId surfaces as INVALID_ATTEMPT`() {
        val r = dispatcher().dispatch(
            attemptId = "",
            messageId = messageId(),
            targetPeerId = "mob-alpha",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )
        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, r.kind)
        assertEquals("validation", r.reason)
    }

    @Test fun `empty messageId surfaces as INVALID_ATTEMPT`() {
        val r = dispatcher().dispatch(
            attemptId = "a-0",
            messageId = ByteArray(0),
            targetPeerId = "mob-alpha",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )
        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, r.kind)
    }

    @Test fun `empty targetPeerId surfaces as INVALID_ATTEMPT`() {
        val r = dispatcher().dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )
        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, r.kind)
    }

    @Test fun `empty targetDeviceIds surfaces as INVALID_ATTEMPT`() {
        val r = dispatcher().dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "mob-alpha",
            targetDeviceIds = emptyList(),
            payload = envelope()
        )
        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, r.kind)
    }

    @Test fun `non M14 payload surfaces as INVALID_ATTEMPT before radio use`() {
        val r = dispatcher().dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "mob-beta",
            targetDeviceIds = listOf("AA:01"),
            payload = "hi".toByteArray()
        )
        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, r.kind)
        assertEquals("invalid_message_envelope:missing_magic", r.reason)
    }

    @Test fun `non M14 payload never starts an available advertiser`() {
        val radio = FakeRadio(
            isAvailable = true,
            isLeExtendedAdvertisingSupported = true,
            leMaximumAdvertisingDataLength = 128
        )
        val r = BleDispatcher(radio, InMemoryEventSink(), NoopScheduler).dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "mob-beta",
            targetDeviceIds = listOf("AA:01"),
            payload = "hi".toByteArray()
        )

        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, r.kind)
        assertEquals("invalid_message_envelope:missing_magic", r.reason)
        assertEquals(0, radio.startCalls)
    }

    @Test fun `messageId mismatch surfaces as INVALID_ATTEMPT before radio use`() {
        val r = dispatcher().dispatch(
            attemptId = "a-0",
            messageId = ByteArray(16),
            targetPeerId = "mob-beta",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )
        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, r.kind)
        assertEquals("message_id_mismatch", r.reason)
    }

    @Test fun `targetPeerId mismatch surfaces as INVALID_ATTEMPT before radio use`() {
        val r = dispatcher().dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "mob-alpha",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )
        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, r.kind)
        assertEquals("target_peer_mismatch", r.reason)
    }

    @Test fun `validated envelope mismatches never start an available advertiser`() {
        val radio = FakeRadio(
            isAvailable = true,
            isLeExtendedAdvertisingSupported = true,
            leMaximumAdvertisingDataLength = 128
        )
        val mismatchedMessage = BleDispatcher(radio, InMemoryEventSink(), NoopScheduler).dispatch(
            attemptId = "a-0",
            messageId = ByteArray(16),
            targetPeerId = "mob-beta",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )
        val mismatchedPeer = BleDispatcher(radio, InMemoryEventSink(), NoopScheduler).dispatch(
            attemptId = "a-1",
            messageId = messageId(),
            targetPeerId = "mob-alpha",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )

        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, mismatchedMessage.kind)
        assertEquals("message_id_mismatch", mismatchedMessage.reason)
        assertEquals(BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT, mismatchedPeer.kind)
        assertEquals("target_peer_mismatch", mismatchedPeer.reason)
        assertEquals(0, radio.startCalls)
    }

    @Test fun `null adapter or BT off surfaces as FAILED bluetooth_off`() {
        // BluetoothAdapter is null in JVM tests, so any non-dry, non-invalid
        // call should go down the bluetooth_off branch.
        val r = dispatcher().dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "mob-beta",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )
        assertEquals(BleDispatcher.BleDispatchResult.Kind.FAILED, r.kind)
        assertEquals("bluetooth_off", r.reason)
    }

    @Test fun `result fields preserve provenance from inputs`() {
        val mid = messageId()
        val r = dispatcher().dispatch(
            attemptId = "audit-1",
            messageId = mid,
            targetPeerId = "mob-peer-x",
            targetDeviceIds = listOf("AA:01", "BB:02"),
            payload = envelope(mid, "mob-peer-x"),
            dryRun = true
        )

        assertEquals("audit-1", r.attemptId)
        assertEquals(mid.toList(), r.messageId.toList())
        assertEquals("mob-peer-x", r.targetPeerId)
        assertEquals(listOf("AA:01", "BB:02"), r.targetDeviceIds)
        assertNotNull(r.outcomeAtMs)
    }

    @Test fun `attempt outcome JSON escapes string fields`() {
        val sink = InMemoryEventSink()
        val mid = messageId()
        BleDispatcher(adapter = null, sink = sink).dispatch(
            attemptId = "audit-\"1",
            messageId = mid,
            targetPeerId = "mob\npeer",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope(mid, "mob\npeer"),
            dryRun = true
        )

        val event = sink.events.single() as BleEvent.Error
        val json = JSONObject(event.detail)

        assertEquals("attempt_outcome", json.getString("event"))
        assertEquals("audit-\"1", json.getString("attempt_id"))
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(mid),
            json.getString("message_id")
        )
        assertEquals("mob\npeer", json.getString("target_peer_id"))
        assertEquals("AA:01", json.getJSONArray("target_device_ids").getString(0))
        assertEquals("would_dispatch", json.getString("kind"))
        assertEquals("ble_android", json.getString("adapter"))
        assertEquals(JSONObject.NULL, json.get("reason"))
    }

    @Test fun `advertising_set_started JSON escapes string fields and preserves payload size`() {
        val payload = envelope()
        val json = JSONObject(
            BleDispatcher.advertisingSetStartedJsonLine(
                attemptId = "spike-\"att\n0",
                payload = payload,
                txPower = -7,
                connectable = false,
                scannable = true,
                dataCarrier = "scan_response"
            )
        )

        assertEquals("advertising_set_started", json.getString("event"))
        assertEquals("spike-\"att\n0", json.getString("attempt_id"))
        assertEquals(payload.size, json.getInt("payload_size"))
        assertEquals(java.util.Base64.getEncoder().encodeToString(payload), json.getString("payload"))
        assertEquals(-7, json.getInt("tx_power"))
        assertEquals(BleDispatcher.ADVERTISE_WINDOW_MS, json.getLong("window_ms"))
        assertEquals(false, json.getBoolean("connectable"))
        assertEquals(true, json.getBoolean("scannable"))
        assertEquals("scan_response", json.getString("data_carrier"))
    }

    @Test fun `budget helper refuses truncation`() {
        assertEquals(false, BleDispatcher.fitsManufacturerPayloadBudget(envelope().size, 24))
        assertEquals(true, BleDispatcher.fitsManufacturerPayloadBudget(envelope().size, 128))
    }

    @Test fun `budget decision skips extended envelope when extended advertising is unsupported`() {
        val failure = BleDispatcher.payloadBudgetFailure(
            payloadSize = envelope().size,
            extendedAdvertisingSupported = false,
            maximumAdvertisingDataLength = 31
        )

        assertNotNull(failure)
        assertEquals(BleDispatcher.BleDispatchResult.Kind.SKIPPED, failure?.kind)
        assertEquals("extended_advertising_unsupported:size=60,legacy_budget=24", failure?.reason)
    }

    @Test fun `unsupported extended envelope falls back to legacy beacon without truncating envelope`() {
        val radio = FakeRadio(
            isAvailable = true,
            isLeExtendedAdvertisingSupported = false,
            leMaximumAdvertisingDataLength = 31
        )
        val r = BleDispatcher(radio, InMemoryEventSink(), NoopScheduler).dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "mob-beta",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )

        assertEquals(BleDispatcher.BleDispatchResult.Kind.DISPATCHED, r.kind)
        assertEquals("legacy_beacon_fallback", r.reason)
        assertEquals(1, radio.legacyStartCalls)
        assertEquals(0, radio.extendedStartCalls)
    }

    @Test fun `budget decision fails before truncating when extended advertising budget is too small`() {
        val failure = BleDispatcher.payloadBudgetFailure(
            payloadSize = envelope().size,
            extendedAdvertisingSupported = true,
            maximumAdvertisingDataLength = 64
        )

        assertNotNull(failure)
        assertEquals(BleDispatcher.BleDispatchResult.Kind.FAILED, failure?.kind)
        assertEquals("payload_too_large:size=60,budget=42", failure?.reason)
    }

    @Test fun `oversized extended envelope uses legacy beacon fallback before advertiser start`() {
        val radio = FakeRadio(
            isAvailable = true,
            isLeExtendedAdvertisingSupported = true,
            leMaximumAdvertisingDataLength = 64
        )
        val r = BleDispatcher(radio, InMemoryEventSink(), NoopScheduler).dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "mob-beta",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )

        assertEquals(BleDispatcher.BleDispatchResult.Kind.DISPATCHED, r.kind)
        assertEquals("legacy_beacon_fallback", r.reason)
        assertEquals(1, radio.legacyStartCalls)
        assertEquals(0, radio.extendedStartCalls)
    }

    @Test fun `budget decision accepts complete envelope when extended budget fits`() {
        val failure = BleDispatcher.payloadBudgetFailure(
            payloadSize = envelope().size,
            extendedAdvertisingSupported = true,
            maximumAdvertisingDataLength = 128
        )

        assertNull(failure)
    }

    @Test fun `complete envelope over legacy budget starts extended advertiser once`() {
        val radio = FakeRadio(
            isAvailable = true,
            isLeExtendedAdvertisingSupported = true,
            leMaximumAdvertisingDataLength = 128
        )
        val r = BleDispatcher(radio, InMemoryEventSink(), NoopScheduler).dispatch(
            attemptId = "a-0",
            messageId = messageId(),
            targetPeerId = "mob-beta",
            targetDeviceIds = listOf("AA:01"),
            payload = envelope()
        )

        assertEquals(BleDispatcher.BleDispatchResult.Kind.DISPATCHED, r.kind)
        assertNull(r.reason)
        assertEquals(0, radio.legacyStartCalls)
        assertEquals(1, radio.extendedStartCalls)
    }

    @Test fun `legacy beacon payload fits the observed legacy manufacturer budget`() {
        val parsed = MobMessageEnvelope.parse(envelope())
        require(parsed is MobMessageEnvelope.ParseResult.Ok)

        val beacon = BleDispatcher.legacyBeaconPayload(parsed.envelope)

        assertEquals(BleDispatcher.LEGACY_BEACON_PAYLOAD_SIZE, beacon.size)
        assertEquals(true, beacon.size <= BleDispatcher.MAX_LEGACY_MANUFACTURER_PAYLOAD)
        assertEquals('M'.code.toByte(), beacon[0])
        assertEquals('B'.code.toByte(), beacon[1])
        assertEquals(MobMessageEnvelope.CURRENT_VERSION, beacon[3].toInt())
    }
}
