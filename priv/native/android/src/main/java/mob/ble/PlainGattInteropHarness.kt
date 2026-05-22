package mob.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * M40 standalone Android GATT interop harness.
 *
 * Intentionally isolated from Mob protocol/fetch modules: one service,
 * one characteristic, one tiny payload, and structured Android GATT logs.
 */
@Suppress("DEPRECATION")
class PlainGattInteropHarness(
    context: Context,
    private val adapter: BluetoothAdapter?
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var clientGatt: BluetoothGatt? = null
    private var activeTargetAddress: String? = null
    private var activePhase: String? = null
    private var timeoutRunnable: Runnable? = null
    private var characteristicValue: ByteArray = SERVER_PAYLOAD

    @SuppressLint("MissingPermission")
    fun startAdvertise(): Boolean {
        stopAdvertise()

        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            value = characteristicValue
        }
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(characteristic)
        }

        val openedServer = bluetoothManager.openGattServer(appContext, serverCallback(characteristic))
        if (openedServer == null) {
            log("interop_server_start_failed") {
                put("reason", "open_gatt_server_failed")
                putDeviceDiagnostics()
            }
            return false
        }
        server = openedServer

        val serviceAccepted = server?.addService(service) == true
        val advertiseAccepted = if (serviceAccepted) startConnectableAdvertisement() else false
        log("interop_advertise_start") {
            put("service_accepted", serviceAccepted)
            put("advertise_accepted", advertiseAccepted)
            put("service_uuid", SERVICE_UUID.toString())
            put("characteristic_uuid", CHARACTERISTIC_UUID.toString())
            put("payload", characteristicValue.toBase64())
            putDeviceDiagnostics()
        }
        return serviceAccepted && advertiseAccepted
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertise() {
        advertiseCallback?.let { callback ->
            try {
                adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
                log("interop_advertise_stop") { putDeviceDiagnostics() }
            } catch (_: SecurityException) {
                // Permission can be revoked while the debug harness is open.
            }
        }
        advertiseCallback = null
        server?.close()
        server = null
        characteristicValue = SERVER_PAYLOAD
    }

    @SuppressLint("MissingPermission")
    fun connect(targetAddress: String): Boolean {
        if (clientGatt != null) {
            log("interop_connect_start_failed") {
                put("reason", "connect_in_progress")
                put("target_address", targetAddress)
                put("active_target_address", activeTargetAddress ?: JSONObject.NULL)
                putDeviceDiagnostics()
            }
            return false
        }

        val btAdapter = adapter
        if (btAdapter == null) {
            log("interop_connect_start_failed") {
                put("reason", "adapter_absent")
                put("target_address", targetAddress)
                putDeviceDiagnostics()
            }
            return false
        }

        val device = try {
            btAdapter.getRemoteDevice(targetAddress)
        } catch (_: IllegalArgumentException) {
            log("interop_connect_start_failed") {
                put("reason", "invalid_device_address")
                put("target_address", targetAddress)
                putDeviceDiagnostics()
            }
            return false
        }

        activeTargetAddress = targetAddress
        activePhase = "connect"
        log("interop_connect_start") {
            put("target_address", targetAddress)
            put("transport_mode", transportModeName())
            putDeviceDiagnostics()
        }
        scheduleTimeout("connect", CONNECT_TIMEOUT_MS)

        clientGatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, clientCallback(), BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, clientCallback())
            }

        if (clientGatt == null) {
            finishClient("connect_start_failed", "connect_gatt_returned_null")
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun closeClient() {
        finishClient("manual_stop", "manual_stop")
    }

    private fun serverCallback(
        characteristic: BluetoothGattCharacteristic
    ): BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                log("interop_server_connection_state") {
                    put("remote_address", device?.address ?: JSONObject.NULL)
                    put("gatt_status", status)
                    put("gatt_reason", gattStatusReason(status))
                    put("state", newState)
                    put("state_name", profileStateName(newState))
                    putDeviceDiagnostics()
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                writtenCharacteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                val ok = writtenCharacteristic?.uuid == CHARACTERISTIC_UUID && value != null && offset == 0
                if (ok) {
                    characteristicValue = value!!
                    characteristic.value = characteristicValue
                }
                log("interop_server_write_request") {
                    put("remote_address", device?.address ?: JSONObject.NULL)
                    put("request_id", requestId)
                    put("characteristic_uuid", writtenCharacteristic?.uuid?.toString() ?: JSONObject.NULL)
                    put("offset", offset)
                    put("payload", value?.toBase64() ?: JSONObject.NULL)
                    put("accepted", ok)
                    putDeviceDiagnostics()
                }
                sendServerResponse(
                    device = device,
                    requestId = requestId,
                    responseNeeded = responseNeeded,
                    status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    value = null
                )
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                readCharacteristic: BluetoothGattCharacteristic?
            ) {
                val ok = readCharacteristic?.uuid == CHARACTERISTIC_UUID && offset == 0
                val value = if (ok) characteristicValue else null
                log("interop_server_read_request") {
                    put("remote_address", device?.address ?: JSONObject.NULL)
                    put("request_id", requestId)
                    put("characteristic_uuid", readCharacteristic?.uuid?.toString() ?: JSONObject.NULL)
                    put("offset", offset)
                    put("payload", value?.toBase64() ?: JSONObject.NULL)
                    put("accepted", ok)
                    putDeviceDiagnostics()
                }
                sendServerResponse(
                    device = device,
                    requestId = requestId,
                    responseNeeded = true,
                    status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    value = value
                )
            }
        }

    @SuppressLint("MissingPermission")
    private fun sendServerResponse(
        device: BluetoothDevice?,
        requestId: Int,
        responseNeeded: Boolean,
        status: Int,
        value: ByteArray?
    ) {
        if (responseNeeded) server?.sendResponse(device, requestId, status, 0, value)
    }

    @SuppressLint("MissingPermission")
    private fun startConnectableAdvertisement(): Boolean {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return false
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                log("interop_advertising_started") {
                    put("connectable", true)
                    put("service_uuid", SERVICE_UUID.toString())
                    putDeviceDiagnostics()
                }
            }

            override fun onStartFailure(errorCode: Int) {
                log("interop_advertising_failed") {
                    put("error_code", errorCode)
                    put("reason", advertiseFailureReason(errorCode))
                    putDeviceDiagnostics()
                }
            }
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        return try {
            advertiser.startAdvertising(settings, data, callback)
            advertiseCallback = callback
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun clientCallback(): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                log("interop_connect_result") {
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("gatt_status", status)
                    put("gatt_reason", gattStatusReason(status))
                    put("state", newState)
                    put("state_name", profileStateName(newState))
                    put("transport_mode", transportModeName())
                    putDeviceDiagnostics()
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishClient("connect_failed", gattStatusReason(status))
                    return
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finishClient("disconnected", "remote_disconnected")
                    return
                }
                if (newState != BluetoothProfile.STATE_CONNECTED) return

                clearTimeout()
                activePhase = "service_discovery"
                log("interop_service_discovery_start") {
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("service_uuid", SERVICE_UUID.toString())
                    putDeviceDiagnostics()
                }
                scheduleTimeout("service_discovery", PHASE_TIMEOUT_MS)

                if (gatt?.discoverServices() != true) {
                    log("interop_service_discovery_result") {
                        put("accepted", false)
                        put("reason", "discover_services_returned_false")
                        put("target_address", activeTargetAddress ?: JSONObject.NULL)
                        putDeviceDiagnostics()
                    }
                    finishClient("service_discovery_failed", "discover_services_returned_false")
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                clearTimeout()
                val service = gatt?.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                log("interop_service_discovery_result") {
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("gatt_status", status)
                    put("gatt_reason", gattStatusReason(status))
                    put("service_found", service != null)
                    put("characteristic_found", characteristic != null)
                    put("service_uuid", SERVICE_UUID.toString())
                    put("characteristic_uuid", CHARACTERISTIC_UUID.toString())
                    putDeviceDiagnostics()
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishClient("service_discovery_failed", gattStatusReason(status))
                    return
                }
                if (characteristic == null) {
                    finishClient("characteristic_discovery_failed", "missing_characteristic")
                    return
                }

                characteristic.value = CLIENT_PAYLOAD
                activePhase = "write"
                log("interop_write_start") {
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("characteristic_uuid", CHARACTERISTIC_UUID.toString())
                    put("payload", CLIENT_PAYLOAD.toBase64())
                    putDeviceDiagnostics()
                }
                scheduleTimeout("write", PHASE_TIMEOUT_MS)
                if (gatt.writeCharacteristic(characteristic) != true) {
                    log("interop_write_result") {
                        put("accepted", false)
                        put("reason", "write_characteristic_returned_false")
                        put("target_address", activeTargetAddress ?: JSONObject.NULL)
                        putDeviceDiagnostics()
                    }
                    finishClient("write_failed", "write_characteristic_returned_false")
                }
            }

            @SuppressLint("MissingPermission")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                clearTimeout()
                log("interop_write_result") {
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("characteristic_uuid", characteristic?.uuid?.toString() ?: JSONObject.NULL)
                    put("gatt_status", status)
                    put("gatt_reason", gattStatusReason(status))
                    putDeviceDiagnostics()
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishClient("write_failed", gattStatusReason(status))
                    return
                }

                activePhase = "read"
                log("interop_read_start") {
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("characteristic_uuid", CHARACTERISTIC_UUID.toString())
                    putDeviceDiagnostics()
                }
                scheduleTimeout("read", PHASE_TIMEOUT_MS)
                if (gatt?.readCharacteristic(characteristic) != true) {
                    log("interop_read_result") {
                        put("accepted", false)
                        put("reason", "read_characteristic_returned_false")
                        put("target_address", activeTargetAddress ?: JSONObject.NULL)
                        putDeviceDiagnostics()
                    }
                    finishClient("read_failed", "read_characteristic_returned_false")
                }
            }

            @Deprecated("Android framework callback kept for API <= 32")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                handleRead(characteristic?.uuid, status, characteristic?.value)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                handleRead(characteristic.uuid, status, value)
            }
        }

    private fun handleRead(characteristicUuid: UUID?, status: Int, value: ByteArray?) {
        clearTimeout()
        log("interop_read_result") {
            put("target_address", activeTargetAddress ?: JSONObject.NULL)
            put("characteristic_uuid", characteristicUuid?.toString() ?: JSONObject.NULL)
            put("gatt_status", status)
            put("gatt_reason", gattStatusReason(status))
            put("payload", value?.toBase64() ?: JSONObject.NULL)
            putDeviceDiagnostics()
        }

        if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
            finishClient("complete", "complete")
        } else {
            finishClient("read_failed", gattStatusReason(status))
        }
    }

    @SuppressLint("MissingPermission")
    private fun finishClient(event: String, reason: String) {
        clearTimeout()
        val targetAddress = activeTargetAddress
        val phase = activePhase
        val gatt = clientGatt

        if (gatt != null) {
            log("interop_disconnect") {
                put("target_address", targetAddress ?: JSONObject.NULL)
                put("phase", phase ?: JSONObject.NULL)
                put("terminal_event", event)
                put("reason", reason)
                putDeviceDiagnostics()
            }
            try {
                gatt.disconnect()
            } catch (_: SecurityException) {
                // Permission can be revoked while the debug harness is open.
            }
            try {
                gatt.close()
            } catch (_: RuntimeException) {
                // close() should not throw in normal Android stacks.
            }
        }

        log("interop_closed") {
            put("target_address", targetAddress ?: JSONObject.NULL)
            put("phase", phase ?: JSONObject.NULL)
            put("terminal_event", event)
            put("reason", reason)
            putDeviceDiagnostics()
        }

        clientGatt = null
        activeTargetAddress = null
        activePhase = null
    }

    private fun scheduleTimeout(phase: String, timeoutMs: Long) {
        clearTimeout()
        timeoutRunnable = Runnable {
            if (activePhase == phase) {
                log("interop_timeout") {
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("phase", phase)
                    put("timeout_ms", timeoutMs)
                    put("timeout_reason", "${phase}_timeout")
                    putDeviceDiagnostics()
                }
                finishClient("timeout", "${phase}_timeout")
            }
        }
        handler.postDelayed(timeoutRunnable!!, timeoutMs)
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun JSONObject.putDeviceDiagnostics() {
        put("device_model", Build.MODEL ?: JSONObject.NULL)
        put("android_api", Build.VERSION.SDK_INT)
        put("adapter_state", adapterStateName(adapter?.state))
        put("adapter_enabled", adapter?.isEnabled == true)
    }

    private fun adapterStateName(state: Int?): String =
        when (state) {
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
            null -> "absent"
            else -> "unknown_$state"
        }

    private fun profileStateName(state: Int): String =
        when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
            BluetoothProfile.STATE_CONNECTING -> "connecting"
            BluetoothProfile.STATE_CONNECTED -> "connected"
            BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
            else -> "unknown_$state"
        }

    private fun transportModeName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "transport_le" else "default"

    private fun gattStatusReason(status: Int): String =
        when (status) {
            BluetoothGatt.GATT_SUCCESS -> "success"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "read_not_permitted"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "write_not_permitted"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "insufficient_authentication"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "request_not_supported"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "insufficient_encryption"
            BluetoothGatt.GATT_INVALID_OFFSET -> "invalid_offset"
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "invalid_attribute_length"
            BluetoothGatt.GATT_CONNECTION_CONGESTED -> "connection_congested"
            BluetoothGatt.GATT_FAILURE -> "failure"
            8 -> "connection_timeout"
            19 -> "remote_user_terminated"
            22 -> "local_host_terminated"
            34 -> "lmp_response_timeout"
            62 -> "connection_failed_establish"
            133 -> "android_gatt_error"
            else -> "unknown_status_$status"
        }

    private fun advertiseFailureReason(errorCode: Int): String =
        when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data_too_large"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too_many_advertisers"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already_started"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal_error"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature_unsupported"
            else -> "unknown_advertise_error_$errorCode"
        }

    private fun log(event: String, body: JSONObject.() -> Unit) {
        val line = JSONObject().apply {
            put("v", 1)
            put("event", event)
            body()
        }.toString()
        android.util.Log.i(LOGCAT_TAG, line)
    }

    companion object {
        const val LOGCAT_TAG = "MobGattInterop"
        val SERVICE_UUID: UUID = UUID.fromString("8f4f1201-6f3d-4f9c-9e3b-7f4a4f0f4000")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("8f4f1201-6f3d-4f9c-9e3b-7f4a4f0f4001")
        val SERVER_PAYLOAD: ByteArray = "ok".toByteArray(StandardCharsets.UTF_8)
        val CLIENT_PAYLOAD: ByteArray = "hi".toByteArray(StandardCharsets.UTF_8)
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val PHASE_TIMEOUT_MS = 8_000L
    }
}

private fun ByteArray.toBase64(): String =
    Base64.getEncoder().encodeToString(this)
