package mob.ble

import android.bluetooth.BluetoothAdapter
import android.os.SystemClock

/**
 * Hardware-free `BleBridge` for tests and offline bring-up.
 *
 * `emitDiscovery` / `emitAdvertisement` / `emitError` let a test drive
 * the same sink the real bridge feeds, so assertions about wire-map
 * shape don't require a phone. The `running*` flags exist purely for
 * lifecycle assertions in unit tests.
 */
class FakeBleBridge(private val sink: BleEventSink) : BleBridge {

    @Volatile var runningScan: Boolean = false
        private set

    @Volatile var runningAdvertise: Boolean = false
        private set

    var lastLocalName: String? = null
        private set

    // Intent state that survives radio off/on cycles — mirrors
    // RealBleBridge's resilience model so tests can assert on it.
    @Volatile var wantScan: Boolean = false
        private set

    @Volatile var wantAdvertise: Boolean = false
        private set

    val lastBluetoothState: Int
        get() = lastObservedState

    @Volatile private var lastObservedState: Int = -1

    override fun startScan(): Boolean {
        wantScan = true
        runningScan = true
        return true
    }

    override fun stopScan() {
        wantScan = false
        runningScan = false
    }

    override fun startAdvertising(localName: String): Boolean {
        wantAdvertise = true
        runningAdvertise = true
        lastLocalName = localName
        return true
    }

    override fun stopAdvertising() {
        wantAdvertise = false
        runningAdvertise = false
    }

    override fun onBluetoothStateChanged(state: Int) {
        if (state == lastObservedState) return
        lastObservedState = state

        when (state) {
            BluetoothAdapter.STATE_OFF -> {
                runningScan = false
                runningAdvertise = false
                sink.accept(
                    BleEvent.Error(
                        kind = BleEvent.Companion.ErrorKind.BLUETOOTH_OFF,
                        detail = "bluetooth adapter state -> STATE_OFF"
                    )
                )
            }

            BluetoothAdapter.STATE_ON -> {
                sink.accept(
                    BleEvent.Error(
                        kind = BleEvent.Companion.ErrorKind.UNKNOWN,
                        detail = "bluetooth_on_recovered:replaying intents " +
                            "wantScan=$wantScan wantAdvertise=$wantAdvertise"
                    )
                )

                if (wantScan) runningScan = true
                if (wantAdvertise) runningAdvertise = true
            }

            else -> Unit
        }
    }

    fun emitDiscovery(deviceId: String, rssi: Int = -55, advertisement: ByteArray = ByteArray(0)) {
        sink.accept(
            BleEvent.DeviceDiscovered(
                deviceId = deviceId,
                rssi = rssi,
                advertisement = advertisement,
                observedAtMs = SystemClock.elapsedRealtime()
            )
        )
    }

    fun emitAdvertisement(deviceId: String, rssi: Int = -60, advertisement: ByteArray = ByteArray(0)) {
        sink.accept(
            BleEvent.AdvertisementReceived(
                deviceId = deviceId,
                rssi = rssi,
                advertisement = advertisement,
                observedAtMs = SystemClock.elapsedRealtime()
            )
        )
    }

    fun emitError(kind: String, detail: String, deviceId: String? = null) {
        sink.accept(BleEvent.Error(kind = kind, detail = detail, deviceId = deviceId))
    }
}
