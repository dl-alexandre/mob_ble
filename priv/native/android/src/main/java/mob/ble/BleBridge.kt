package mob.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * Facade composing scanner + advertiser behind a single start/stop surface.
 *
 * Counterpart to `Mob.Ble.Adapter` on the Elixir side. The
 * Kotlin code never exposes Android-specific event variants; everything
 * the runtime sees is a v1 wire-format map fed into the sink.
 *
 * No mesh routing, no crypto, no reconnect orchestration — this is the
 * transport layer.
 *
 * Doze / adapter-cycle resilience: implementations track the caller's
 * *intent* to scan/advertise across `onBluetoothStateChanged` cycles.
 * When the OS turns the radio off (Doze suspend, airplane mode, user
 * toggle) we surface a `BleEvent.Error(kind = :bluetooth_off)`; when
 * it comes back on, any pending intent is replayed without the BEAM
 * having to issue fresh start_scan/start_advertising calls.
 */
interface BleBridge {
    fun startScan(): Boolean
    fun stopScan()
    fun startAdvertising(localName: String): Boolean
    fun stopAdvertising()

    /**
     * Called by the host activity's BroadcastReceiver when
     * `BluetoothAdapter.ACTION_STATE_CHANGED` fires. `state` is one of
     * `BluetoothAdapter.STATE_OFF` / `STATE_TURNING_OFF` / `STATE_ON` /
     * `STATE_TURNING_ON`. Implementations should be idempotent — the OS
     * fires this multiple times per cycle.
     */
    fun onBluetoothStateChanged(state: Int)
}

class RealBleBridge(
    context: Context,
    private val sink: BleEventSink,
    fetchOnBeaconEnabled: Boolean = false
) : BleBridge {

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
        ?.adapter

    // Intent state — survives across radio off/on cycles so we can auto-
    // restart on recovery without waiting for the BEAM to re-issue calls.
    @Volatile private var wantScan: Boolean = false
    @Volatile private var wantAdvertise: Boolean = false
    @Volatile private var lastLocalName: String = "mob-mob"

    private val fetchCoordinator =
        if (fetchOnBeaconEnabled || MobBleConfig.useFullMxEnvelopes) {
            MobBeaconFetchCoordinator(
                context = context.applicationContext,
                adapter = adapter,
                sink = sink,
                requesterPeerId = lastLocalName
            )
        } else {
            null
        }
    private val scanner = BleScanner(adapter, sink, fetchCoordinator)
    private val advertiser = BleAdvertiser(adapter, sink)

    // Last STATE_* value we acted on, so a duplicate broadcast (the
    // platform fires several per cycle) doesn't redundantly start the
    // scanner mid-cycle. -1 = never observed.
    @Volatile private var lastObservedState: Int = -1

    override fun startScan(): Boolean {
        wantScan = true
        if (adapter?.isEnabled != true) {
            sink.accept(
                BleEvent.Error(
                    kind = BleEvent.Companion.ErrorKind.BLUETOOTH_OFF,
                    detail = "bluetooth adapter disabled or absent"
                )
            )
            return false
        }
        return scanner.start()
    }

    override fun stopScan() {
        wantScan = false
        scanner.stop()
    }

    override fun startAdvertising(localName: String): Boolean {
        wantAdvertise = true
        lastLocalName = localName
        if (adapter?.isEnabled != true) {
            sink.accept(
                BleEvent.Error(
                    kind = BleEvent.Companion.ErrorKind.BLUETOOTH_OFF,
                    detail = "bluetooth adapter disabled or absent"
                )
            )
            return false
        }
        return advertiser.start(localName)
    }

    override fun stopAdvertising() {
        wantAdvertise = false
        advertiser.stop()
    }

    override fun onBluetoothStateChanged(state: Int) {
        if (state == lastObservedState) return
        lastObservedState = state

        when (state) {
            BluetoothAdapter.STATE_OFF -> {
                // Radio went down. Surface an Error event so the BEAM
                // can route around it (mark peers stale, queue sends).
                // The intent state (wantScan/wantAdvertise) is *not*
                // cleared — STATE_ON will replay them.
                sink.accept(
                    BleEvent.Error(
                        kind = BleEvent.Companion.ErrorKind.BLUETOOTH_OFF,
                        detail = "bluetooth adapter state -> STATE_OFF"
                    )
                )
            }

            BluetoothAdapter.STATE_ON -> {
                // Radio recovered. Replay anything the caller previously
                // wanted. The closed-set Error kinds don't have a
                // dedicated "recovered" variant, so use UNKNOWN with a
                // descriptive detail — it remains a discrete event the
                // BEAM-side observer counts.
                sink.accept(
                    BleEvent.Error(
                        kind = BleEvent.Companion.ErrorKind.UNKNOWN,
                        detail = "bluetooth_on_recovered:replaying intents " +
                            "wantScan=$wantScan wantAdvertise=$wantAdvertise"
                    )
                )

                if (wantScan) scanner.start()
                if (wantAdvertise) advertiser.start(lastLocalName)
            }

            else -> {
                // STATE_TURNING_OFF / STATE_TURNING_ON / unknown — no
                // action; we wait for the terminal state.
            }
        }
    }
}
