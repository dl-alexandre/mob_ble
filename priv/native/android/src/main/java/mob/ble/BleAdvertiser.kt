package mob.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings

/**
 * Thin wrapper around `BluetoothLeAdvertiser`. Transport-only: starts
 * and stops broadcasting a local name. Failures surface as `Error`
 * events through the same sink the scanner uses.
 */
class BleAdvertiser(
    private val adapter: BluetoothAdapter?,
    private val sink: BleEventSink
) {

    @Volatile private var running = false

    private val callback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            running = false
            sink.accept(
                BleEvent.Error(
                    kind = BleEvent.Companion.ErrorKind.ADVERTISE_FAILED,
                    detail = "advertise start failed (code=$errorCode)"
                )
            )
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            // Intentionally silent: lifecycle state is owned by the
            // Elixir runtime, not surfaced via events here.
        }
    }

    @SuppressLint("MissingPermission")
    fun start(localName: String): Boolean {
        if (running) return true
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            sink.accept(
                BleEvent.Error(
                    kind = BleEvent.Companion.ErrorKind.PERIPHERAL_UNSUPPORTED,
                    detail = "no BluetoothLeAdvertiser (BT off or no peripheral support)"
                )
            )
            return false
        }

        // Setting the device name surfaces `localName` in scan results —
        // but BluetoothAdapter.setName() is asynchronous, so the payload
        // built immediately below would carry the *previous* adapter name.
        // We still set it (some scanners read it later), but the
        // authoritative carrier of `localName` is the manufacturer-data
        // entry added to the AdvertiseData itself: that lands in the very
        // first advertising packet, synchronously and reliably.
        // `adapter` was already null-checked via `?.bluetoothLeAdvertiser`.
        try {
            adapter!!.name = localName
        } catch (_: SecurityException) {
            // Setting name requires BLUETOOTH_CONNECT on API 31+. The
            // advertisement still carries localName via manufacturer data.
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        // MOB_COMPANY_ID (0xFFFF — the Bluetooth SIG "no company"
        // reserved id, fine for local/unregistered use) tags the packet
        // as ours and carries `localName` synchronously. Device name is
        // omitted: name + manufacturer data together can overflow the
        // 31-byte legacy advertisement budget.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MOB_COMPANY_ID, localName.toByteArray(Charsets.UTF_8))
            .build()

        try {
            advertiser.startAdvertising(settings, data, callback)
            running = true
            return true
        } catch (e: SecurityException) {
            sink.accept(
                BleEvent.Error(
                    kind = BleEvent.Companion.ErrorKind.UNAUTHORIZED,
                    detail = e.message ?: "BLUETOOTH_ADVERTISE denied"
                )
            )
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        try {
            advertiser.stopAdvertising(callback)
        } catch (_: SecurityException) {
            // Already torn down from the platform's perspective.
        }
    }

    companion object {
        /**
         * Manufacturer id used to tag Mob advertisements and carry the
         * local name synchronously in the advertising payload. 0xFFFF is
         * the Bluetooth SIG reserved "no company" id — appropriate for a
         * local mesh that is not a registered Bluetooth vendor.
         */
        const val MOB_COMPANY_ID = 0xFFFF
    }
}
