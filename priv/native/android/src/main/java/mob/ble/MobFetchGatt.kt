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
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * M36-M39 constrained Android BLE fetch spike.
 *
 * This is deliberately one requester, one responder, one envelope, no retry,
 * no routing, no persistence, no fragmentation, and no background service.
 */
@Suppress("DEPRECATION")
class MobFetchGatt(
    context: Context,
    private val adapter: BluetoothAdapter?,
    private val clientListener: ClientListener? = null
) : MobBeaconFetchClient {
    interface ClientListener {
        fun onFetchComplete(
            deviceAddress: String,
            request: MobFetchProtocol.Request,
            envelope: ByteArray
        )

        fun onFetchFailed(
            deviceAddress: String?,
            request: MobFetchProtocol.Request?,
            reason: String,
            detail: String?
        )
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var server: BluetoothGattServer? = null
    private var clientGatt: BluetoothGatt? = null
    private var activeRequest: MobFetchProtocol.Request? = null
    private var activeTargetAddress: String? = null
    private var activePhase: String? = null
    private var serviceDiscoveryRetryCount = 0
    private var timeoutRunnable: Runnable? = null
    private var responseBytes: ByteArray? = null
    private var servedEnvelope: ByteArray? = null
    private var servedMessageHash: ByteArray? = null
    private var advertiseCallback: AdvertiseCallback? = null

    // Success-observation counters for the smoke test. The
    // BluetoothGattServerCallback callbacks run on a binder thread, so
    // the test's main thread reads these via the public accessors below;
    // AtomicInteger keeps the visibility cheap and correct.
    private val preparedOkCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val servedReadCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastClientTerminalEvent = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val lastClientReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val lastClientResponseStatus = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val lastClientEnvelope = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)

    /**
     * Number of times this responder prepared a STATUS_OK response for an
     * inbound MFQ Request whose `messageIdHash` matched what the server
     * has cached (i.e. the requester asked for the envelope this server
     * is serving). Increments BEFORE the response characteristic is read,
     * so the count can be >= [servedReadCount].
     */
    fun preparedOkCount(): Int = preparedOkCount.get()

    /**
     * Number of times the response characteristic was successfully read
     * by a remote central AFTER a STATUS_OK response was prepared. This
     * is the strongest "the fetch protocol completed end-to-end" signal
     * available on the server side: it means the requester wrote a
     * valid MFQ Request, the responder prepared the envelope, and the
     * requester then read it back over GATT.
     */
    fun servedReadCount(): Int = servedReadCount.get()

    fun lastClientTerminalEvent(): String? = lastClientTerminalEvent.get()
    fun lastClientReason(): String? = lastClientReason.get()
    fun lastClientResponseStatus(): String? = lastClientResponseStatus.get()
    fun lastClientEnvelope(): ByteArray? = lastClientEnvelope.get()

    @SuppressLint("MissingPermission")
    fun startResponder(envelope: ByteArray, responderPeerId: String): Boolean {
        stopResponder()

        val parsed = MobMessageEnvelope.parse(envelope)
        val decoded = (parsed as? MobMessageEnvelope.ParseResult.Ok)?.envelope
        if (decoded == null) {
            log("fetch_server_start_failed") {
                put("reason", "invalid_envelope")
                putDeviceDiagnostics()
            }
            return false
        }

        val messageHash = messageIdHash(decoded.messageId)
        servedEnvelope = envelope
        servedMessageHash = messageHash
        responseBytes = null

        val requestCharacteristic = BluetoothGattCharacteristic(
            REQUEST_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val responseCharacteristic = BluetoothGattCharacteristic(
            RESPONSE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(requestCharacteristic)
            addCharacteristic(responseCharacteristic)
        }

        val openedServer = bluetoothManager.openGattServer(
            appContext,
            serverCallback(responderPeerId, responseCharacteristic)
        )
        if (openedServer == null) {
            log("fetch_server_start_failed") {
                put("reason", "open_gatt_server_failed")
                put("responder_peer_id", responderPeerId)
                putDeviceDiagnostics()
            }
            return false
        }
        server = openedServer

        val accepted = server?.addService(service) == true
        val advertisingAccepted = if (accepted) {
            startConnectableAdvertisement(responderPeerId, messageHash)
        } else {
            false
        }
        log("fetch_server_started") {
            put("accepted", accepted)
            put("advertising_accepted", advertisingAccepted)
            put("responder_peer_id", responderPeerId)
            put("message_id_hash", messageHash.toBase64())
            put("service_uuid", SERVICE_UUID.toString())
            put("request_characteristic_uuid", REQUEST_CHARACTERISTIC_UUID.toString())
            put("response_characteristic_uuid", RESPONSE_CHARACTERISTIC_UUID.toString())
            putDeviceDiagnostics()
        }
        return accepted
    }

    @SuppressLint("MissingPermission")
    fun stopResponder() {
        advertiseCallback?.let { callback ->
            try {
                adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
                log("fetch_advertising_stopped") { putDeviceDiagnostics() }
            } catch (_: SecurityException) {
                // Permission can be revoked while the debug harness is open.
            }
        }
        advertiseCallback = null
        server?.close()
        server = null
        responseBytes = null
        servedEnvelope = null
        servedMessageHash = null
    }

    @SuppressLint("MissingPermission")
    override fun fetchOnce(
        deviceAddress: String,
        request: MobFetchProtocol.Request
    ): Boolean {
        val btAdapter = adapter
        if (btAdapter == null) {
            log("fetch_client_start_failed") {
                put("reason", "adapter_absent")
                put("target_address", deviceAddress)
                putDeviceDiagnostics()
            }
            return false
        }
        if (clientGatt != null || activeRequest != null) {
            log("fetch_client_start_failed") {
                put("reason", "fetch_in_progress")
                put("request_id", request.requestId)
                put("active_request_id", activeRequest?.requestId ?: JSONObject.NULL)
                put("target_address", deviceAddress)
                putDeviceDiagnostics()
            }
            return false
        }

        val device = try {
            btAdapter.getRemoteDevice(deviceAddress)
        } catch (_: IllegalArgumentException) {
            log("fetch_client_start_failed") {
                put("reason", "invalid_device_address")
                put("target_address", deviceAddress)
                putDeviceDiagnostics()
            }
            return false
        }

        activeRequest = request
        activeTargetAddress = deviceAddress
        activePhase = "connect"
        serviceDiscoveryRetryCount = 0
        lastClientTerminalEvent.set(null)
        lastClientReason.set(null)
        lastClientResponseStatus.set(null)
        lastClientEnvelope.set(null)

        log("fetch_gatt_experimental_warning") {
            put("reason", "gatt_fetch_unvalidated_hardware")
            put("enabled_by_default", false)
            put("allowed_surface", "explicit_debug_action")
            put("target_address", deviceAddress)
            put("request_id", request.requestId)
            put("message_id_hash", request.messageIdHash.toBase64())
            put("known_blocked_pair", isKnownBlockedPair())
            put("blocked_status", "android_gatt_error_133_before_service_discovery")
            putDeviceDiagnostics()
        }
        log("fetch_connect_start") {
            put("target_address", deviceAddress)
            put("request_id", request.requestId)
            put("message_id_hash", request.messageIdHash.toBase64())
            put("transport_mode", transportModeName())
            putDeviceDiagnostics()
        }
        scheduleTimeout("connect", CONNECT_TIMEOUT_MS)

        clientGatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, clientCallback(request), BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, clientCallback(request))
            }

        if (clientGatt == null) {
            finishClient("connect_start_failed", "connect_gatt_returned_null")
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    override fun stopClient() {
        finishClient("manual_stop", "manual_stop")
    }

    private fun serverCallback(
        responderPeerId: String,
        responseCharacteristic: BluetoothGattCharacteristic
    ): BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (characteristic?.uuid != REQUEST_CHARACTERISTIC_UUID || value == null || offset != 0) {
                    sendServerResponse(device, requestId, responseNeeded, BluetoothGatt.GATT_FAILURE, null)
                    return
                }

                val request = MobFetchProtocol.decodeRequest(value)
                val response = if (request == null) {
                    MobFetchProtocol.Response(
                        requestId = "invalid",
                        messageIdHash = ByteArray(8),
                        status = MobFetchProtocol.STATUS_INVALID_REQUEST,
                        envelope = null,
                        reason = "invalid_request"
                    )
                } else if (request.messageIdHash.contentEquals(servedMessageHash)) {
                    MobFetchProtocol.Response(
                        requestId = request.requestId,
                        messageIdHash = request.messageIdHash,
                        status = MobFetchProtocol.STATUS_OK,
                        envelope = servedEnvelope,
                        reason = null
                    )
                } else {
                    MobFetchProtocol.Response(
                        requestId = request.requestId,
                        messageIdHash = request.messageIdHash,
                        status = MobFetchProtocol.STATUS_NOT_FOUND,
                        envelope = null,
                        reason = "not_found"
                    )
                }

                responseBytes = MobFetchProtocol.encodeResponse(response)
                responseCharacteristic.value = responseBytes
                if (response.status == MobFetchProtocol.STATUS_OK) {
                    preparedOkCount.incrementAndGet()
                }
                log("fetch_request_received") {
                    put("target_address", device?.address ?: JSONObject.NULL)
                    put("request_id", response.requestId)
                    put("message_id_hash", response.messageIdHash.toBase64())
                    put("status", MobFetchProtocol.statusName(response.status))
                    put("responder_peer_id", responderPeerId)
                    putDeviceDiagnostics()
                }
                sendServerResponse(device, requestId, responseNeeded, BluetoothGatt.GATT_SUCCESS, null)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?
            ) {
                if (characteristic?.uuid != RESPONSE_CHARACTERISTIC_UUID || offset != 0) {
                    sendServerResponse(device, requestId, true, BluetoothGatt.GATT_FAILURE, null)
                    return
                }
                val bytes = responseBytes ?: ByteArray(0)
                sendServerResponse(device, requestId, true, BluetoothGatt.GATT_SUCCESS, bytes)
                // Only count reads that delivered an actual envelope: a
                // zero-length response means no prior write prepared one,
                // which the test should not interpret as success.
                if (bytes.isNotEmpty()) {
                    servedReadCount.incrementAndGet()
                }
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
    private fun startConnectableAdvertisement(responderPeerId: String, messageHash: ByteArray): Boolean {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return false
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                log("fetch_advertising_started") {
                    put("responder_peer_id", responderPeerId)
                    put("message_id_hash", messageHash.toBase64())
                    put("connectable", true)
                    put("service_uuid", SERVICE_UUID.toString())
                    putDeviceDiagnostics()
                }
            }

            override fun onStartFailure(errorCode: Int) {
                log("fetch_advertising_failed") {
                    put("error_code", errorCode)
                    put("reason", advertiseFailureReason(errorCode))
                    put("responder_peer_id", responderPeerId)
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

    private fun clientCallback(request: MobFetchProtocol.Request): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                log("fetch_connect_result") {
                    put("request_id", request.requestId)
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
                activePhase = "mtu"
                log("fetch_mtu_request_start") {
                    put("request_id", request.requestId)
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("mtu", REQUESTED_MTU)
                    putDeviceDiagnostics()
                }
                scheduleTimeout("mtu", PHASE_TIMEOUT_MS)
                if (gatt?.requestMtu(REQUESTED_MTU) != true) {
                    log("fetch_mtu_request_result") {
                        put("request_id", request.requestId)
                        put("target_address", activeTargetAddress ?: JSONObject.NULL)
                        put("accepted", false)
                        put("reason", "request_mtu_returned_false")
                        putDeviceDiagnostics()
                    }
                    startServiceDiscovery(gatt, request)
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                log("fetch_mtu_request_result") {
                    put("request_id", request.requestId)
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("mtu", mtu)
                    put("gatt_status", status)
                    put("gatt_reason", gattStatusReason(status))
                    putDeviceDiagnostics()
                }
                startServiceDiscovery(gatt, request)
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                clearTimeout()
                log("fetch_service_discovery_result") {
                    put("request_id", request.requestId)
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("gatt_status", status)
                    put("gatt_reason", gattStatusReason(status))
                    put("service_found", gatt?.getService(SERVICE_UUID) != null)
                    putDeviceDiagnostics()
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishClient("service_discovery_failed", gattStatusReason(status))
                    return
                }

                val service = gatt?.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(REQUEST_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    log("fetch_service_characteristics_missing") {
                        put("request_id", request.requestId)
                        put("target_address", activeTargetAddress ?: JSONObject.NULL)
                        put("service_uuid", SERVICE_UUID.toString())
                        put("expected_request_characteristic_uuid", REQUEST_CHARACTERISTIC_UUID.toString())
                        put("expected_response_characteristic_uuid", RESPONSE_CHARACTERISTIC_UUID.toString())
                        put(
                            "characteristic_uuids",
                            service?.characteristics?.map { it.uuid.toString() } ?: emptyList<String>()
                        )
                        put(
                            "characteristic_properties",
                            JSONObject(
                                service?.characteristics
                                    ?.associate { it.uuid.toString() to it.properties }
                                    ?: emptyMap<String, Int>()
                            )
                        )
                        putDeviceDiagnostics()
                    }

                    if (service != null && serviceDiscoveryRetryCount == 0) {
                        serviceDiscoveryRetryCount += 1
                        activePhase = "service_discovery_retry"
                        handler.postDelayed({
                            val currentGatt = clientGatt
                            if (currentGatt != null && activeRequest === request) {
                                log("fetch_service_discovery_retry") {
                                    put("request_id", request.requestId)
                                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                                    put("reason", "missing_request_characteristic")
                                    put("attempt", serviceDiscoveryRetryCount)
                                    putDeviceDiagnostics()
                                }
                                startServiceDiscovery(currentGatt, request)
                            }
                        }, 750)
                        return
                    }

                    finishClient("service_discovery_failed", "missing_request_characteristic")
                    return
                }

                val bytes = MobFetchProtocol.encodeRequest(request)
                characteristic.value = bytes
                activePhase = "characteristic_write"
                log("fetch_characteristic_write_start") {
                    put("request_id", request.requestId)
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("characteristic_uuid", REQUEST_CHARACTERISTIC_UUID.toString())
                    put("payload_size", bytes.size)
                    putDeviceDiagnostics()
                }
                scheduleTimeout("characteristic_write", PHASE_TIMEOUT_MS)

                if (gatt.writeCharacteristic(characteristic) != true) {
                    log("fetch_characteristic_write_result") {
                        put("request_id", request.requestId)
                        put("target_address", activeTargetAddress ?: JSONObject.NULL)
                        put("accepted", false)
                        put("reason", "write_characteristic_returned_false")
                        putDeviceDiagnostics()
                    }
                    finishClient("characteristic_write_failed", "write_characteristic_returned_false")
                }
            }

            @SuppressLint("MissingPermission")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                clearTimeout()
                log("fetch_characteristic_write_result") {
                    put("request_id", request.requestId)
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("characteristic_uuid", characteristic?.uuid?.toString() ?: JSONObject.NULL)
                    put("gatt_status", status)
                    put("gatt_reason", gattStatusReason(status))
                    putDeviceDiagnostics()
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishClient("characteristic_write_failed", gattStatusReason(status))
                    return
                }

                val response = gatt
                    ?.getService(SERVICE_UUID)
                    ?.getCharacteristic(RESPONSE_CHARACTERISTIC_UUID)
                if (response == null) {
                    finishClient("characteristic_read_failed", "missing_response_characteristic")
                    return
                }

                activePhase = "characteristic_read"
                log("fetch_characteristic_read_start") {
                    put("request_id", request.requestId)
                    put("target_address", activeTargetAddress ?: JSONObject.NULL)
                    put("characteristic_uuid", RESPONSE_CHARACTERISTIC_UUID.toString())
                    putDeviceDiagnostics()
                }
                scheduleTimeout("characteristic_read", PHASE_TIMEOUT_MS)
                if (gatt.readCharacteristic(response) != true) {
                    log("fetch_characteristic_read_result") {
                        put("request_id", request.requestId)
                        put("target_address", activeTargetAddress ?: JSONObject.NULL)
                        put("accepted", false)
                        put("reason", "read_characteristic_returned_false")
                        putDeviceDiagnostics()
                    }
                    finishClient("characteristic_read_failed", "read_characteristic_returned_false")
                }
            }

            @Deprecated("Android framework callback kept for API <= 32")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                handleRead(request, characteristic?.uuid, status, characteristic?.value)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                handleRead(request, characteristic.uuid, status, value)
            }
        }

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(gatt: BluetoothGatt?, request: MobFetchProtocol.Request) {
        clearTimeout()
        activePhase = "service_discovery"
        log("fetch_service_discovery_start") {
            put("request_id", request.requestId)
            put("target_address", activeTargetAddress ?: JSONObject.NULL)
            put("service_uuid", SERVICE_UUID.toString())
            putDeviceDiagnostics()
        }
        scheduleTimeout("service_discovery", PHASE_TIMEOUT_MS)

        if (gatt?.discoverServices() != true) {
            log("fetch_service_discovery_result") {
                put("request_id", request.requestId)
                put("target_address", activeTargetAddress ?: JSONObject.NULL)
                put("accepted", false)
                put("reason", "discover_services_returned_false")
                putDeviceDiagnostics()
            }
            finishClient("service_discovery_failed", "discover_services_returned_false")
        }
    }

    private fun handleRead(
        request: MobFetchProtocol.Request,
        characteristicUuid: UUID?,
        status: Int,
        value: ByteArray?
    ) {
        clearTimeout()
        log("fetch_characteristic_read_result") {
            put("request_id", request.requestId)
            put("target_address", activeTargetAddress ?: JSONObject.NULL)
            put("characteristic_uuid", characteristicUuid?.toString() ?: JSONObject.NULL)
            put("gatt_status", status)
            put("gatt_reason", gattStatusReason(status))
            put("payload_size", value?.size ?: JSONObject.NULL)
            putDeviceDiagnostics()
        }

        if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
            finishClient("characteristic_read_failed", gattStatusReason(status))
            return
        }

        val response = MobFetchProtocol.decodeResponse(value)
        if (response == null) {
            log("fetch_response_received") {
                put("status", "invalid_response")
                put("request_id", request.requestId)
                put("target_address", activeTargetAddress ?: JSONObject.NULL)
                putDeviceDiagnostics()
            }
            clientListener?.onFetchFailed(
                activeTargetAddress,
                request,
                "invalid_response",
                "invalid_response"
            )
            finishClient("invalid_response", "invalid_response")
            return
        }

        val parsedEnvelope =
            response.envelope?.let { MobMessageEnvelope.parse(it) } as? MobMessageEnvelope.ParseResult.Ok
        lastClientResponseStatus.set(MobFetchProtocol.statusName(response.status))
        lastClientEnvelope.set(response.envelope)
        log("fetch_response_received") {
            put("request_id", response.requestId)
            put("target_address", activeTargetAddress ?: JSONObject.NULL)
            put("message_id_hash", response.messageIdHash.toBase64())
            put("status", MobFetchProtocol.statusName(response.status))
            put("envelope", response.envelope?.toBase64() ?: JSONObject.NULL)
            put(
                "envelope_parse",
                if (parsedEnvelope != null) "ok" else if (response.envelope == null) JSONObject.NULL else "error"
            )
            put("reason", response.reason ?: JSONObject.NULL)
            putDeviceDiagnostics()
        }
        if (response.status == MobFetchProtocol.STATUS_OK && parsedEnvelope != null) {
            clientListener?.onFetchComplete(
                activeTargetAddress ?: request.requestId,
                request,
                response.envelope
            )
        } else if (response.status != MobFetchProtocol.STATUS_OK) {
            clientListener?.onFetchFailed(
                activeTargetAddress,
                request,
                MobFetchProtocol.statusName(response.status),
                response.reason
            )
        } else {
            clientListener?.onFetchFailed(
                activeTargetAddress,
                request,
                "envelope_parse_error",
                "fetch response envelope failed Mob parse"
            )
        }
        finishClient("complete", "complete")
    }

    @SuppressLint("MissingPermission")
    private fun finishClient(event: String, reason: String) {
        clearTimeout()
        val requestId = activeRequest?.requestId
        val targetAddress = activeTargetAddress
        val phase = activePhase
        val gatt = clientGatt

        if (gatt != null) {
            log("fetch_client_disconnect") {
                put("request_id", requestId ?: JSONObject.NULL)
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

        log("fetch_client_closed") {
            put("request_id", requestId ?: JSONObject.NULL)
            put("target_address", targetAddress ?: JSONObject.NULL)
            put("phase", phase ?: JSONObject.NULL)
            put("terminal_event", event)
            put("reason", reason)
            putDeviceDiagnostics()
        }

        if (event != "complete") {
            clientListener?.onFetchFailed(targetAddress, activeRequest, event, reason)
        }

        lastClientTerminalEvent.set(event)
        lastClientReason.set(reason)
        clientGatt = null
        activeRequest = null
        activeTargetAddress = null
        activePhase = null
    }

    private fun scheduleTimeout(phase: String, timeoutMs: Long) {
        clearTimeout()
        timeoutRunnable = Runnable {
            if (activePhase == phase) {
                log("fetch_timeout") {
                    put("request_id", activeRequest?.requestId ?: JSONObject.NULL)
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

    private fun isKnownBlockedPair(): Boolean =
        Build.MODEL == "SM-T577U" || Build.MODEL == "SM-T390"

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
        const val LOGCAT_TAG = "MobBleFetch"
        val SERVICE_UUID: UUID = UUID.fromString("8f4f1201-6f3d-4f9c-9e3b-7f4a4f0f2000")
        val REQUEST_CHARACTERISTIC_UUID: UUID = UUID.fromString("8f4f1201-6f3d-4f9c-9e3b-7f4a4f0f2001")
        val RESPONSE_CHARACTERISTIC_UUID: UUID = UUID.fromString("8f4f1201-6f3d-4f9c-9e3b-7f4a4f0f2002")
        const val REQUESTED_MTU = 185
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val PHASE_TIMEOUT_MS = 8_000L

        fun messageIdHash(messageId: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(messageId).copyOfRange(0, 8)
    }
}

private fun ByteArray.toBase64(): String =
    Base64.getEncoder().encodeToString(this)
