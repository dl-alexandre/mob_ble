package mob.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal interface BleDispatchRadio {
    val isAvailable: Boolean
    val isLeExtendedAdvertisingSupported: Boolean
    val leMaximumAdvertisingDataLength: Int
    val isMultipleAdvertisementSupported: Boolean

    fun startLegacyAdvertising(
        payload: ByteArray,
        callback: AdvertiseCallback
    )

    fun stopLegacyAdvertising(callback: AdvertiseCallback)

    fun startExtendedAdvertising(
        payload: ByteArray,
        extendedConnectable: Boolean,
        useServiceDataForPayload: Boolean = false,
        serviceDataUuid: UUID? = null,
        callback: AdvertisingSetCallback
    )

    fun stopExtendedAdvertising(callback: AdvertisingSetCallback)
}

internal interface BleDispatchScheduler {
    fun postDelayed(delayMs: Long, action: () -> Unit)
}

internal object MainLooperBleDispatchScheduler : BleDispatchScheduler {
    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMs)
    }
}

internal class AndroidBleDispatchRadio(
    private val adapter: BluetoothAdapter?
) : BleDispatchRadio {
    private val advertiser
        get() = adapter?.bluetoothLeAdvertiser

    override val isAvailable: Boolean
        get() = advertiser != null && adapter?.isEnabled == true

    override val isLeExtendedAdvertisingSupported: Boolean
        get() = adapter?.isLeExtendedAdvertisingSupported == true

    override val leMaximumAdvertisingDataLength: Int
        get() = adapter?.leMaximumAdvertisingDataLength
            ?: BleDispatcher.MAX_LEGACY_MANUFACTURER_PAYLOAD

    override val isMultipleAdvertisementSupported: Boolean
        get() = adapter?.isMultipleAdvertisementSupported == true

    @SuppressLint("MissingPermission")
    override fun startLegacyAdvertising(
        payload: ByteArray,
        callback: AdvertiseCallback
    ) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addManufacturerData(BleDispatcher.MOB_COMPANY_IDENTIFIER, payload)
            .build()
        advertiser?.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    override fun stopLegacyAdvertising(callback: AdvertiseCallback) {
        advertiser?.stopAdvertising(callback)
    }

    @SuppressLint("MissingPermission")
    override fun startExtendedAdvertising(
        payload: ByteArray,
        extendedConnectable: Boolean,
        useServiceDataForPayload: Boolean,
        serviceDataUuid: UUID?,
        callback: AdvertisingSetCallback
    ) {
        val scannable = !extendedConnectable
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(extendedConnectable)
            .setScannable(scannable)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        val data = if (useServiceDataForPayload && serviceDataUuid != null) {
            AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(BleDispatcher.MOB_SERVICE_UUID))
                .addServiceData(ParcelUuid(serviceDataUuid), payload)
                .build()
        } else {
            AdvertiseData.Builder()
                .addManufacturerData(BleDispatcher.MOB_COMPANY_IDENTIFIER, payload)
                .addServiceUuid(ParcelUuid(BleDispatcher.MOB_SERVICE_UUID))
                .build()
        }
        val advertiseData = if (scannable) AdvertiseData.Builder().build() else data
        val scanResponse = if (scannable) data else null
        advertiser?.startAdvertisingSet(parameters, advertiseData, scanResponse, null, null, callback)
    }

    @SuppressLint("MissingPermission")
    override fun stopExtendedAdvertising(callback: AdvertisingSetCallback) {
        advertiser?.stopAdvertisingSet(callback)
    }
}

/**
 * Real-transport send bridge for one Mob attempt.
 *
 * Mirrors the Elixir `Mob.Ble.Dispatcher.Android` shape:
 * accepts the inputs that would arrive from a planned [Attempt],
 * returns one [BleDispatchResult] describing what happened. Logs the
 * outcome as a v1 wire-format JSON line tagged `MobBleDispatch` so
 * the validation ledger can read it back from `adb logcat`.
 *
 * The "send" itself is intentionally minimal: start a short bounded
 * manufacturer-data BLE advertisement carrying one complete encoded
 * M14 MessageEnvelope, then stop. The dispatcher never truncates an
 * envelope; if it cannot fit in the available advertising budget, the
 * attempt is failed/skipped before any radio call is made.
 *
 * No threads, no jobs, no service. The advertise auto-stops via a
 * scheduled `stopAdvertising` on the main looper after the window.
 */
class BleDispatcher internal constructor(
    private val radio: BleDispatchRadio,
    private val sink: BleEventSink,
    private val scheduler: BleDispatchScheduler = MainLooperBleDispatchScheduler
) {
    constructor(
        adapter: BluetoothAdapter?,
        sink: BleEventSink
    ) : this(AndroidBleDispatchRadio(adapter), sink, MainLooperBleDispatchScheduler)


    /** Result of one dispatch call. Mirrors `AttemptOutcome` field-by-field. */
    data class BleDispatchResult(
        val attemptId: String,
        val messageId: ByteArray,
        val targetPeerId: String,
        val targetDeviceIds: List<String>,
        val kind: Kind,
        val outcomeAtMs: Long,
        val reason: String?,
        val adapter: String = ADAPTER
    ) {
        enum class Kind { DISPATCHED, FAILED, SKIPPED, INVALID_ATTEMPT, WOULD_DISPATCH }
    }

    companion object {
        const val ADAPTER = "ble_android"
        const val LOGCAT_TAG = "MobBleDispatch"
        const val MOB_COMPANY_IDENTIFIER = 0xFFFF
        val MOB_SERVICE_UUID: UUID = UUID.fromString("8f4f1201-6f3d-4f9c-9e3b-7f4a4f0f1000")

        /**
         * Dedicated service UUID for the "direct full-MX via service data" experimental carrier
         * (different advertising strategy for the iOS AUX interop blocker).
         * Used with useServiceDataForPayload=true in the experimental smoke tests.
         */
        val MOB_DIRECT_MX_SERVICE_UUID: UUID = UUID.fromString("8f4f1201-6f3d-4f9c-9e3b-7f4a4f0f1001")

        const val MAX_LEGACY_MANUFACTURER_PAYLOAD = 24
        const val MAX_MANUFACTURER_PAYLOAD = MAX_LEGACY_MANUFACTURER_PAYLOAD
        const val LEGACY_BEACON_PAYLOAD_SIZE = 22
        const val MANUFACTURER_DATA_AD_OVERHEAD = 4
        const val SERVICE_UUID_128_AD_OVERHEAD = 18
        const val ADVERTISE_WINDOW_MS = 5_000L

        fun manufacturerPayloadBudget(adapter: BluetoothAdapter?): Int {
            return manufacturerPayloadBudget(
                extendedAdvertisingSupported = adapter?.isLeExtendedAdvertisingSupported == true,
                maximumAdvertisingDataLength = adapter?.leMaximumAdvertisingDataLength
                    ?: MAX_LEGACY_MANUFACTURER_PAYLOAD
            )
        }

        fun manufacturerPayloadBudget(
            extendedAdvertisingSupported: Boolean,
            maximumAdvertisingDataLength: Int
        ): Int {
            if (extendedAdvertisingSupported) {
                val extendedBudget = maximumAdvertisingDataLength -
                    MANUFACTURER_DATA_AD_OVERHEAD -
                    SERVICE_UUID_128_AD_OVERHEAD
                return maxOf(MAX_LEGACY_MANUFACTURER_PAYLOAD, extendedBudget)
            }
            return MAX_LEGACY_MANUFACTURER_PAYLOAD
        }

        fun fitsManufacturerPayloadBudget(payloadSize: Int, budget: Int): Boolean =
            payloadSize <= budget

        data class PayloadBudgetFailure(
            val kind: BleDispatchResult.Kind,
            val reason: String
        )

        fun payloadBudgetFailure(
            payloadSize: Int,
            extendedAdvertisingSupported: Boolean,
            maximumAdvertisingDataLength: Int
        ): PayloadBudgetFailure? {
            val needsExtendedAdvertising = payloadSize > MAX_LEGACY_MANUFACTURER_PAYLOAD

            if (needsExtendedAdvertising && !extendedAdvertisingSupported) {
                return PayloadBudgetFailure(
                    BleDispatchResult.Kind.SKIPPED,
                    "extended_advertising_unsupported:size=$payloadSize,legacy_budget=$MAX_LEGACY_MANUFACTURER_PAYLOAD"
                )
            }

            val budget = manufacturerPayloadBudget(
                extendedAdvertisingSupported = extendedAdvertisingSupported,
                maximumAdvertisingDataLength = maximumAdvertisingDataLength
            )
            if (!fitsManufacturerPayloadBudget(payloadSize, budget)) {
                return PayloadBudgetFailure(
                    BleDispatchResult.Kind.FAILED,
                    "payload_too_large:size=$payloadSize,budget=$budget"
                )
            }

            return null
        }

        fun advertisingSetStartedJsonLine(
            attemptId: String,
            payload: ByteArray,
            txPower: Int,
            connectable: Boolean,
            scannable: Boolean,
            dataCarrier: String
        ): String {
            return JSONObject().apply {
                put("v", 1)
                put("event", "advertising_set_started")
                put("attempt_id", attemptId)
                put("payload_size", payload.size)
                put("payload", Base64.getEncoder().encodeToString(payload))
                put("tx_power", txPower)
                put("window_ms", ADVERTISE_WINDOW_MS)
                put("connectable", connectable)
                put("scannable", scannable)
                put("data_carrier", dataCarrier)
            }.toString()
        }

        internal fun capabilitiesJsonLine(radio: BleDispatchRadio): String {
            return JSONObject().apply {
                put("v", 1)
                put("event", "ble_capabilities")
                put("supports_extended_advertising", radio.isLeExtendedAdvertisingSupported)
                put("max_advertising_data_length", radio.leMaximumAdvertisingDataLength)
                put("is_multiple_advertisement_supported", radio.isMultipleAdvertisementSupported)
                put("legacy_manufacturer_payload_budget", MAX_LEGACY_MANUFACTURER_PAYLOAD)
            }.toString()
        }

        fun legacyBeaconPayload(envelope: MobMessageEnvelope.Decoded): ByteArray {
            val messageHash = sha256(envelope.messageId).copyOfRange(0, 8)
            val senderHash = sha256(envelope.senderPeerId.toByteArray(Charsets.UTF_8)).copyOfRange(0, 8)
            val kindCode = when (envelope.payloadType.uppercase()) {
                "TX" -> 1
                else -> 0
            }

            return byteArrayOf(
                'M'.code.toByte(),
                'B'.code.toByte(),
                1, // beacon format version
                MobMessageEnvelope.CURRENT_VERSION.toByte(),
                kindCode.toByte(),
                0 // reserved flags
            ) + messageHash + senderHash
        }

        fun legacyBeaconStartedJsonLine(
            attemptId: String,
            beacon: ByteArray,
            envelope: MobMessageEnvelope.Decoded
        ): String {
            return JSONObject().apply {
                put("v", 1)
                put("event", "legacy_beacon_advertising_started")
                put("attempt_id", attemptId)
                put("beacon_size", beacon.size)
                put("beacon", Base64.getEncoder().encodeToString(beacon))
                put("message_id_hash", Base64.getEncoder().encodeToString(beacon.copyOfRange(6, 14)))
                put("sender_peer_id_hash", Base64.getEncoder().encodeToString(beacon.copyOfRange(14, 22)))
                put("payload_kind", envelope.payloadType)
                put("envelope_version", MobMessageEnvelope.CURRENT_VERSION)
                put("window_ms", ADVERTISE_WINDOW_MS)
                put("data_carrier", "legacy_advertisement")
            }.toString()
        }

        private fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    @SuppressLint("MissingPermission")
    fun dispatch(
        attemptId: String,
        messageId: ByteArray,
        targetPeerId: String,
        targetDeviceIds: List<String>,
        payload: ByteArray,
        dryRun: Boolean = false,
        extendedConnectable: Boolean = false,
        legacyBeaconFallback: Boolean = true,
        forceLegacyBeacon: Boolean = false,
        useServiceDataForPayload: Boolean = false,
        serviceDataUuid: UUID? = null
    ): BleDispatchResult {
        val now = SystemClock.elapsedRealtime()

        // ── validation (mirrors Elixir-side rules) ─────────────────────────
        if (attemptId.isBlank() ||
            messageId.isEmpty() ||
            targetPeerId.isBlank() ||
            targetDeviceIds.isEmpty()
        ) {
            return finished(
                attemptId, messageId, targetPeerId, targetDeviceIds,
                BleDispatchResult.Kind.INVALID_ATTEMPT, now, "validation"
            )
        }

        val envelope = when (val parsed = MobMessageEnvelope.parse(payload)) {
            is MobMessageEnvelope.ParseResult.Ok -> parsed.envelope
            is MobMessageEnvelope.ParseResult.Error -> {
                return finished(
                    attemptId, messageId, targetPeerId, targetDeviceIds,
                    BleDispatchResult.Kind.INVALID_ATTEMPT, now,
                    "invalid_message_envelope:${parsed.reason}"
                )
            }
        }

        if (!envelope.messageId.contentEquals(messageId)) {
            return finished(
                attemptId, messageId, targetPeerId, targetDeviceIds,
                BleDispatchResult.Kind.INVALID_ATTEMPT, now, "message_id_mismatch"
            )
        }

        if (envelope.recipientPeerId != null && envelope.recipientPeerId != targetPeerId) {
            return finished(
                attemptId, messageId, targetPeerId, targetDeviceIds,
                BleDispatchResult.Kind.INVALID_ATTEMPT, now, "target_peer_mismatch"
            )
        }

        if (dryRun) {
            return finished(
                attemptId, messageId, targetPeerId, targetDeviceIds,
                BleDispatchResult.Kind.WOULD_DISPATCH, now, null
            )
        }

        if (!radio.isAvailable) {
            return finished(
                attemptId, messageId, targetPeerId, targetDeviceIds,
                BleDispatchResult.Kind.FAILED, now, "bluetooth_off"
            )
        }

        logInfo(capabilitiesJsonLine(radio))

        val budgetFailure = payloadBudgetFailure(
            payloadSize = payload.size,
            extendedAdvertisingSupported = radio.isLeExtendedAdvertisingSupported,
            maximumAdvertisingDataLength = radio.leMaximumAdvertisingDataLength
        )
        val useLegacyBeacon =
            forceLegacyBeacon ||
                (budgetFailure != null &&
                    legacyBeaconFallback &&
                    payload.size > MAX_LEGACY_MANUFACTURER_PAYLOAD)

        if (budgetFailure != null && !useLegacyBeacon) {
            val failure = budgetFailure
            return finished(
                attemptId, messageId, targetPeerId, targetDeviceIds,
                failure.kind, now, failure.reason
            )
        }

        val outboundPayload = if (useLegacyBeacon) legacyBeaconPayload(envelope) else payload
        if (useLegacyBeacon && outboundPayload.size > MAX_LEGACY_MANUFACTURER_PAYLOAD) {
            return finished(
                attemptId, messageId, targetPeerId, targetDeviceIds,
                BleDispatchResult.Kind.FAILED, now,
                "legacy_beacon_too_large:size=${outboundPayload.size},budget=$MAX_LEGACY_MANUFACTURER_PAYLOAD"
            )
        }

        val needsExtendedAdvertising = !useLegacyBeacon && payload.size > MAX_LEGACY_MANUFACTURER_PAYLOAD

        return try {
            if (needsExtendedAdvertising) {
                startExtendedAdvertising(
                    payload,
                    attemptId,
                    messageId,
                    targetPeerId,
                    targetDeviceIds,
                    now,
                    extendedConnectable,
                    useServiceDataForPayload,
                    serviceDataUuid
                )
            } else {
                startLegacyAdvertising(
                    attemptId,
                    messageId,
                    targetPeerId,
                    targetDeviceIds,
                    now,
                    outboundPayload,
                    if (useLegacyBeacon) envelope else null
                )
            }
            // Synchronous return: the advertise has been ACCEPTED by the
            // stack. We don't wait for the stop callback because :dispatched
            // means "local stack took the send", not "peer received".
            finished(
                attemptId, messageId, targetPeerId, targetDeviceIds,
                BleDispatchResult.Kind.DISPATCHED, now,
                if (useLegacyBeacon) "legacy_beacon_fallback" else null
            )
        } catch (e: SecurityException) {
            finished(
                attemptId, messageId, targetPeerId, targetDeviceIds,
                BleDispatchResult.Kind.FAILED, now,
                e.message ?: "unauthorized"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyAdvertising(
        attemptId: String,
        messageId: ByteArray,
        targetPeerId: String,
        targetDeviceIds: List<String>,
        now: Long,
        payload: ByteArray,
        legacyBeaconEnvelope: MobMessageEnvelope.Decoded?
    ) {
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                finished(
                    attemptId, messageId, targetPeerId, targetDeviceIds,
                    BleDispatchResult.Kind.FAILED, now,
                    "advertise_failed:code=$errorCode"
                )
            }

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (legacyBeaconEnvelope != null) {
                    logInfo(
                        legacyBeaconStartedJsonLine(
                            attemptId = attemptId,
                            beacon = payload,
                            envelope = legacyBeaconEnvelope
                        )
                    )
                }
                scheduler.postDelayed(ADVERTISE_WINDOW_MS) {
                    try {
                        radio.stopLegacyAdvertising(this)
                    } catch (_: SecurityException) {
                        // Permission revoked mid-window; nothing to surface.
                    }
                }
            }
        }

        radio.startLegacyAdvertising(payload, callback)
    }

    @SuppressLint("MissingPermission")
    private fun startExtendedAdvertising(
        payload: ByteArray,
        attemptId: String,
        messageId: ByteArray,
        targetPeerId: String,
        targetDeviceIds: List<String>,
        now: Long,
        extendedConnectable: Boolean,
        useServiceDataForPayload: Boolean = false,
        serviceDataUuid: UUID? = null
    ) {
        val scannable = !extendedConnectable
        val dataCarrier = if (scannable) "scan_response" else "advertisement"

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    finished(
                        attemptId, messageId, targetPeerId, targetDeviceIds,
                        BleDispatchResult.Kind.FAILED, now,
                        "advertising_set_failed:code=$status"
                    )
                    return
                }

                logInfo(
                    advertisingSetStartedJsonLine(
                        attemptId = attemptId,
                        payload = payload,
                        txPower = txPower,
                        connectable = extendedConnectable,
                        scannable = scannable,
                        dataCarrier = dataCarrier
                    )
                )

                scheduler.postDelayed(ADVERTISE_WINDOW_MS) {
                    try {
                        radio.stopExtendedAdvertising(this)
                    } catch (_: SecurityException) {
                        // Permission revoked mid-window; nothing to surface.
                    }
                }
            }
        }

        radio.startExtendedAdvertising(
            payload,
            extendedConnectable,
            useServiceDataForPayload,
            serviceDataUuid,
            callback
        )
    }

    private fun finished(
        attemptId: String,
        messageId: ByteArray,
        targetPeerId: String,
        targetDeviceIds: List<String>,
        kind: BleDispatchResult.Kind,
        nowMs: Long,
        reason: String?
    ): BleDispatchResult {
        val result = BleDispatchResult(
            attemptId = attemptId,
            messageId = messageId,
            targetPeerId = targetPeerId,
            targetDeviceIds = targetDeviceIds,
            kind = kind,
            outcomeAtMs = nowMs,
            reason = reason
        )
        logInfo(result.toJsonLine())
        // Also feed the shared event sink so the existing v1 wire
        // pipeline sees dispatch outcomes alongside scan events.
        sink.accept(
            BleEvent.Error(
                kind = mapReasonKind(result),
                detail = result.toJsonLine()
            )
        )
        return result
    }

    private fun mapReasonKind(r: BleDispatchResult): String = when (r.kind) {
        BleDispatchResult.Kind.DISPATCHED -> BleEvent.Companion.ErrorKind.UNKNOWN
        BleDispatchResult.Kind.WOULD_DISPATCH -> BleEvent.Companion.ErrorKind.UNKNOWN
        BleDispatchResult.Kind.SKIPPED -> BleEvent.Companion.ErrorKind.UNKNOWN
        BleDispatchResult.Kind.INVALID_ATTEMPT -> BleEvent.Companion.ErrorKind.UNKNOWN
        BleDispatchResult.Kind.FAILED -> BleEvent.Companion.ErrorKind.ADVERTISE_FAILED
    }

    private fun logInfo(message: String) {
        try {
            Log.i(LOGCAT_TAG, message)
        } catch (_: RuntimeException) {
            // Android Log is not available in local JVM unit tests.
        }
    }
}

/** Serialize a dispatch result as a v1-style JSON line. */
private fun BleDispatcher.BleDispatchResult.toJsonLine(): String {
    val kindStr = when (kind) {
        BleDispatcher.BleDispatchResult.Kind.DISPATCHED -> "dispatched"
        BleDispatcher.BleDispatchResult.Kind.FAILED -> "failed"
        BleDispatcher.BleDispatchResult.Kind.SKIPPED -> "skipped"
        BleDispatcher.BleDispatchResult.Kind.INVALID_ATTEMPT -> "invalid_attempt"
        BleDispatcher.BleDispatchResult.Kind.WOULD_DISPATCH -> "would_dispatch"
    }

    return JSONObject().apply {
        put("v", 1)
        put("event", "attempt_outcome")
        put("attempt_id", attemptId)
        put("message_id", Base64.getEncoder().encodeToString(messageId))
        put("target_peer_id", targetPeerId)
        put("target_device_ids", JSONArray(targetDeviceIds))
        put("kind", kindStr)
        put("reason", reason ?: JSONObject.NULL)
        put("adapter", adapter)
        put("outcome_at_ms", outcomeAtMs)
    }.toString()
}
