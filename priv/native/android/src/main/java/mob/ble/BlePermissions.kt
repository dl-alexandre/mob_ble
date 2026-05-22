package mob.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permission set required for BLE scan + advertise.
 *
 * Android 12+ (API 31+) split the legacy Bluetooth permission into
 * `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT`. Older
 * devices need `ACCESS_FINE_LOCATION` for scans to return results.
 *
 * The list is intentionally narrow: scan + advertise + the constrained
 * one-shot GATT fetch spike.
 */
object BlePermissions {

    /** Permissions that must be requested at runtime for the current API. */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun allGranted(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** First missing permission, or null if all granted. Useful for diagnostics. */
    fun firstMissing(context: Context): String? = required().firstOrNull {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}
