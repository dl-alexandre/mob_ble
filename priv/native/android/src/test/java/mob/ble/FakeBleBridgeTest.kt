package mob.ble

import android.bluetooth.BluetoothAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBleBridgeTest {

    @Test fun `lifecycle flags track start and stop`() {
        val sink = InMemoryEventSink()
        val bridge = FakeBleBridge(sink)

        assertFalse(bridge.runningScan)
        bridge.startScan()
        assertTrue(bridge.runningScan)
        bridge.stopScan()
        assertFalse(bridge.runningScan)

        bridge.startAdvertising("mob-mob")
        assertTrue(bridge.runningAdvertise)
        assertEquals("mob-mob", bridge.lastLocalName)
    }

    @Test fun `emit helpers feed the configured sink`() {
        val sink = InMemoryEventSink()
        val bridge = FakeBleBridge(sink)

        bridge.emitDiscovery("AA:BB:CC:DD:EE:01")
        bridge.emitAdvertisement("AA:BB:CC:DD:EE:01")
        bridge.emitError(
            BleEvent.Companion.ErrorKind.SCAN_FAILED,
            detail = "boom"
        )

        val events = sink.events
        assertEquals(3, events.size)
        assertTrue(events[0] is BleEvent.DeviceDiscovered)
        assertTrue(events[1] is BleEvent.AdvertisementReceived)
        assertTrue(events[2] is BleEvent.Error)
    }

    // Doze / adapter-cycle resilience: the bridge must preserve the
    // caller's intent across radio off/on transitions, surface a
    // BLUETOOTH_OFF event when the radio drops, and replay the intent
    // on recovery without the BEAM having to re-issue start_* calls.

    @Test fun `onBluetoothStateChanged STATE_OFF emits bluetooth_off and clears running flags`() {
        val sink = InMemoryEventSink()
        val bridge = FakeBleBridge(sink)
        bridge.startScan()
        bridge.startAdvertising("mob-t")

        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF)

        assertFalse(bridge.runningScan)
        assertFalse(bridge.runningAdvertise)
        // Intent survives the radio dropping — that's the whole point.
        assertTrue(bridge.wantScan)
        assertTrue(bridge.wantAdvertise)

        val err = sink.events.last() as BleEvent.Error
        assertEquals(BleEvent.Companion.ErrorKind.BLUETOOTH_OFF, err.kind)
        assertTrue(err.detail.contains("STATE_OFF"))
    }

    @Test fun `onBluetoothStateChanged STATE_ON replays wantScan and wantAdvertise`() {
        val sink = InMemoryEventSink()
        val bridge = FakeBleBridge(sink)
        bridge.startScan()
        bridge.startAdvertising("mob-t")
        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF)

        assertFalse(bridge.runningScan)
        assertFalse(bridge.runningAdvertise)

        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_ON)

        assertTrue(bridge.runningScan)
        assertTrue(bridge.runningAdvertise)

        // Recovery event carries enough detail for the BEAM-side
        // observer to count replays separately from real errors.
        val recoveryEvent = sink.events.last() as BleEvent.Error
        assertEquals(BleEvent.Companion.ErrorKind.UNKNOWN, recoveryEvent.kind)
        assertTrue(recoveryEvent.detail.contains("bluetooth_on_recovered"))
        assertTrue(recoveryEvent.detail.contains("wantScan=true"))
        assertTrue(recoveryEvent.detail.contains("wantAdvertise=true"))
    }

    @Test fun `STATE_ON does not replay an intent the caller never set`() {
        val sink = InMemoryEventSink()
        val bridge = FakeBleBridge(sink)

        // Caller never wanted scan — recovery must not start one.
        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF)
        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_ON)

        assertFalse(bridge.runningScan)
        assertFalse(bridge.runningAdvertise)
        assertFalse(bridge.wantScan)
        assertFalse(bridge.wantAdvertise)
    }

    @Test fun `stopScan clears intent so STATE_ON does not auto-restart it`() {
        val sink = InMemoryEventSink()
        val bridge = FakeBleBridge(sink)
        bridge.startScan()
        bridge.stopScan()

        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF)
        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_ON)

        assertFalse(bridge.wantScan)
        assertFalse(bridge.runningScan)
    }

    @Test fun `duplicate state broadcasts are idempotent`() {
        val sink = InMemoryEventSink()
        val bridge = FakeBleBridge(sink)
        bridge.startScan()

        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF)
        val errorCount = sink.events.count { (it as? BleEvent.Error)?.kind == BleEvent.Companion.ErrorKind.BLUETOOTH_OFF }

        // Platform fires several broadcasts per cycle; the second
        // STATE_OFF must not double-emit.
        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF)
        val errorCountAfter = sink.events.count { (it as? BleEvent.Error)?.kind == BleEvent.Companion.ErrorKind.BLUETOOTH_OFF }

        assertEquals(errorCount, errorCountAfter)
    }

    @Test fun `intermediate STATE_TURNING_OFF and STATE_TURNING_ON are no-ops`() {
        val sink = InMemoryEventSink()
        val bridge = FakeBleBridge(sink)
        bridge.startScan()

        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_TURNING_OFF)
        bridge.onBluetoothStateChanged(BluetoothAdapter.STATE_TURNING_ON)

        // Scanner stays running; no error event was emitted because we
        // wait for the terminal state before reacting.
        assertTrue(bridge.runningScan)
        assertNotNull(bridge.lastBluetoothState)
    }
}
