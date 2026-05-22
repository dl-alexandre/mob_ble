package mob.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.os.SystemClock
import android.util.Log
import java.util.Base64
import org.json.JSONObject

/**
 * Constrained Android execution path for advertisement gossip intents.
 *
 * This accepts only legacy beacon gossip. It does not synthesize a full
 * MessageEnvelope, does not fetch, route, retry, ACK, persist, fragment,
 * encrypt, or run as a background service.
 */
class BleAdvertGossipDispatcher internal constructor(
    private val radio: BleDispatchRadio,
    private val sink: BleEventSink,
    private val scheduler: BleDispatchScheduler = MainLooperBleDispatchScheduler
) {
    constructor(
        adapter: BluetoothAdapter?,
        sink: BleEventSink
    ) : this(AndroidBleDispatchRadio(adapter), sink, MainLooperBleDispatchScheduler)

    data class Result(
        val gossipIntentId: String,
        val messageIdHash: ByteArray,
        val senderPeerIdHash: ByteArray,
        val advertiseAs: String,
        val kind: Kind,
        val outcomeAtMs: Long,
        val reason: String?,
        val adapter: String = ADAPTER
    ) {
        enum class Kind { GOSSIPED, FAILED, SKIPPED, INVALID_INTENT, WOULD_GOSSIP }
    }

    companion object {
        const val ADAPTER = "ble_android"
        const val LOGCAT_TAG = "MobBleGossip"
        const val ADVERTISE_AS_LEGACY_BEACON = "legacy_beacon_advert"
        const val ADVERTISE_AS_FULL_ENVELOPE = "full_envelope_advert"

        fun legacyBeaconPayload(
            envelopeVersion: Int,
            payloadKind: String,
            messageIdHash: ByteArray,
            senderPeerIdHash: ByteArray
        ): ByteArray {
            val kindCode = when (payloadKind.uppercase()) {
                "TX" -> 1
                else -> 0
            }

            return byteArrayOf(
                'M'.code.toByte(),
                'B'.code.toByte(),
                1,
                envelopeVersion.toByte(),
                kindCode.toByte(),
                0
            ) + messageIdHash + senderPeerIdHash
        }

        fun legacyBeaconGossipStartedJsonLine(
            gossipIntentId: String,
            beacon: ByteArray,
            envelopeVersion: Int,
            payloadKind: String
        ): String {
            return JSONObject().apply {
                put("v", 1)
                put("event", "legacy_beacon_gossip_started")
                put("gossip_intent_id", gossipIntentId)
                put("beacon_size", beacon.size)
                put("beacon", Base64.getEncoder().encodeToString(beacon))
                put("message_id_hash", Base64.getEncoder().encodeToString(beacon.copyOfRange(6, 14)))
                put("sender_peer_id_hash", Base64.getEncoder().encodeToString(beacon.copyOfRange(14, 22)))
                put("payload_kind", payloadKind)
                put("envelope_version", envelopeVersion)
                put("window_ms", BleDispatcher.ADVERTISE_WINDOW_MS)
                put("data_carrier", "legacy_advertisement")
            }.toString()
        }
    }

    @SuppressLint("MissingPermission")
    fun dispatchLegacyBeacon(
        gossipIntentId: String,
        messageIdHash: ByteArray,
        senderPeerIdHash: ByteArray,
        payloadKind: String,
        envelopeVersion: Int,
        dryRun: Boolean = false
    ): Result {
        val now = SystemClock.elapsedRealtime()

        if (gossipIntentId.isBlank() ||
            messageIdHash.size != 8 ||
            senderPeerIdHash.size != 8 ||
            payloadKind.isBlank() ||
            envelopeVersion <= 0
        ) {
            return finished(
                gossipIntentId,
                messageIdHash,
                senderPeerIdHash,
                ADVERTISE_AS_LEGACY_BEACON,
                Result.Kind.INVALID_INTENT,
                now,
                "validation"
            )
        }

        if (dryRun) {
            return finished(
                gossipIntentId,
                messageIdHash,
                senderPeerIdHash,
                ADVERTISE_AS_LEGACY_BEACON,
                Result.Kind.WOULD_GOSSIP,
                now,
                null
            )
        }

        if (!radio.isAvailable) {
            return finished(
                gossipIntentId,
                messageIdHash,
                senderPeerIdHash,
                ADVERTISE_AS_LEGACY_BEACON,
                Result.Kind.FAILED,
                now,
                "bluetooth_off"
            )
        }

        val beacon = legacyBeaconPayload(envelopeVersion, payloadKind, messageIdHash, senderPeerIdHash)
        if (beacon.size > BleDispatcher.MAX_LEGACY_MANUFACTURER_PAYLOAD) {
            return finished(
                gossipIntentId,
                messageIdHash,
                senderPeerIdHash,
                ADVERTISE_AS_LEGACY_BEACON,
                Result.Kind.FAILED,
                now,
                "legacy_beacon_too_large:size=${beacon.size},budget=${BleDispatcher.MAX_LEGACY_MANUFACTURER_PAYLOAD}"
            )
        }

        return try {
            startLegacyBeaconAdvertising(
                gossipIntentId,
                messageIdHash,
                senderPeerIdHash,
                payloadKind,
                envelopeVersion,
                now,
                beacon
            )
            finished(
                gossipIntentId,
                messageIdHash,
                senderPeerIdHash,
                ADVERTISE_AS_LEGACY_BEACON,
                Result.Kind.GOSSIPED,
                now,
                null
            )
        } catch (e: SecurityException) {
            finished(
                gossipIntentId,
                messageIdHash,
                senderPeerIdHash,
                ADVERTISE_AS_LEGACY_BEACON,
                Result.Kind.FAILED,
                now,
                e.message ?: "unauthorized"
            )
        }
    }

    fun dispatchFullEnvelopeDisabled(
        gossipIntentId: String,
        messageIdHash: ByteArray,
        senderPeerIdHash: ByteArray
    ): Result {
        return finished(
            gossipIntentId,
            messageIdHash,
            senderPeerIdHash,
            ADVERTISE_AS_FULL_ENVELOPE,
            Result.Kind.SKIPPED,
            SystemClock.elapsedRealtime(),
            "full_envelope_gossip_disabled"
        )
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyBeaconAdvertising(
        gossipIntentId: String,
        messageIdHash: ByteArray,
        senderPeerIdHash: ByteArray,
        payloadKind: String,
        envelopeVersion: Int,
        now: Long,
        beacon: ByteArray
    ) {
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                finished(
                    gossipIntentId,
                    messageIdHash,
                    senderPeerIdHash,
                    ADVERTISE_AS_LEGACY_BEACON,
                    Result.Kind.FAILED,
                    now,
                    "advertise_failed:code=$errorCode"
                )
            }

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                logInfo(
                    legacyBeaconGossipStartedJsonLine(
                        gossipIntentId = gossipIntentId,
                        beacon = beacon,
                        envelopeVersion = envelopeVersion,
                        payloadKind = payloadKind
                    )
                )
                scheduler.postDelayed(BleDispatcher.ADVERTISE_WINDOW_MS) {
                    try {
                        radio.stopLegacyAdvertising(this)
                    } catch (_: SecurityException) {
                        // Permission revoked mid-window; nothing else to surface.
                    }
                }
            }
        }

        radio.startLegacyAdvertising(beacon, callback)
    }

    private fun finished(
        gossipIntentId: String,
        messageIdHash: ByteArray,
        senderPeerIdHash: ByteArray,
        advertiseAs: String,
        kind: Result.Kind,
        nowMs: Long,
        reason: String?
    ): Result {
        val result = Result(
            gossipIntentId = gossipIntentId,
            messageIdHash = messageIdHash,
            senderPeerIdHash = senderPeerIdHash,
            advertiseAs = advertiseAs,
            kind = kind,
            outcomeAtMs = nowMs,
            reason = reason
        )
        val event = result.toEvent()
        logInfo(event.toJsonObject().toString())
        sink.accept(event)
        return result
    }

    private fun logInfo(message: String) {
        try {
            Log.i(LOGCAT_TAG, message)
        } catch (_: RuntimeException) {
            // Android Log is unavailable in local JVM tests.
        }
    }
}

private fun BleAdvertGossipDispatcher.Result.toEvent(): BleEvent.AdvertGossipOutcome {
    return BleEvent.AdvertGossipOutcome(
        gossipIntentId = gossipIntentId,
        messageIdHash = messageIdHash,
        senderPeerIdHash = senderPeerIdHash,
        advertiseAs = advertiseAs,
        kind = when (kind) {
            BleAdvertGossipDispatcher.Result.Kind.GOSSIPED -> "gossiped"
            BleAdvertGossipDispatcher.Result.Kind.FAILED -> "failed"
            BleAdvertGossipDispatcher.Result.Kind.SKIPPED -> "skipped"
            BleAdvertGossipDispatcher.Result.Kind.INVALID_INTENT -> "invalid_intent"
            BleAdvertGossipDispatcher.Result.Kind.WOULD_GOSSIP -> "would_gossip"
        },
        outcomeAtMs = outcomeAtMs,
        reason = reason,
        adapter = adapter
    )
}
