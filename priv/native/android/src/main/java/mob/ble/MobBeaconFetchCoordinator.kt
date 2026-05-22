package mob.ble

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the two-signal full-message path on Android:
 *
 * 1. An MB legacy beacon tells us which message hash exists.
 * 2. A connectable MobFetchGatt service advertisement tells us where
 *    to fetch that envelope.
 *
 * The fetch path is opt-in from RealBleBridge so fleet-safe MB-only
 * scanning remains the default unless full-MX debug mode is enabled.
 */
interface MobBeaconFetchCoordinatorHook {
    fun onLegacyBeacon(beacon: BleEvent.ReceivedMessageBeacon)
    fun onFetchServiceAdvertisement(
        deviceId: String,
        messageIdHash: ByteArray? = null,
        rssi: Int = 0,
        advertisement: ByteArray = ByteArray(0)
    )
}

interface MobBeaconFetchClient {
    fun fetchOnce(deviceAddress: String, request: MobFetchProtocol.Request): Boolean
    fun stopClient()
}

class MobBeaconFetchCoordinator(
    context: Context?,
    private val adapter: BluetoothAdapter?,
    private val sink: BleEventSink,
    private val requesterPeerId: String? = null,
    private val fetchDedupTtlMs: Long = DEFAULT_FETCH_DEDUP_TTL_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val fetchClientFactory: (MobFetchGatt.ClientListener) -> MobBeaconFetchClient =
        { listener -> MobFetchGatt(requireNotNull(context).applicationContext, adapter, listener) }
) : MobFetchGatt.ClientListener, MobBeaconFetchCoordinatorHook {

    private data class FetchCue(
        val messageIdHash: ByteArray,
        val beacon: BleEvent.ReceivedMessageBeacon?,
        val rssi: Int,
        val advertisement: ByteArray,
        val seenAtMs: Long
    )

    private data class PendingFetch(
        val request: MobFetchProtocol.Request,
        val cue: FetchCue,
        val fetchClient: MobBeaconFetchClient
    )

    private val recentCues = mutableListOf<FetchCue>()
    private val fetchedHashes = ConcurrentHashMap<String, Long>()
    private val pendingByRequestId = ConcurrentHashMap<String, PendingFetch>()

    @Synchronized
    override fun onLegacyBeacon(beacon: BleEvent.ReceivedMessageBeacon) {
        val now = nowMs()
        recentCues.add(
            FetchCue(
                messageIdHash = beacon.messageIdHash,
                beacon = beacon,
                rssi = beacon.rssi,
                advertisement = beacon.rawTransportMetadata.advertisement,
                seenAtMs = now
            )
        )
        recentCues.removeAll { now - it.seenAtMs >= fetchDedupTtlMs }
    }

    override fun onFetchServiceAdvertisement(
        deviceId: String,
        messageIdHash: ByteArray?,
        rssi: Int,
        advertisement: ByteArray
    ) {
        val now = nowMs()
        val cue = if (messageIdHash != null) {
            FetchCue(
                messageIdHash = messageIdHash,
                beacon = null,
                rssi = rssi,
                advertisement = advertisement,
                seenAtMs = now
            )
        } else {
            latestCue() ?: return
        }
        val hashKey = cue.messageIdHash.contentKey()
        fetchedHashes.entries.removeIf { now - it.value >= fetchDedupTtlMs }
        if (fetchedHashes.putIfAbsent(hashKey, now) != null) return

        val request = MobFetchProtocol.Request(
            requestId = UUID.randomUUID().toString(),
            messageIdHash = cue.messageIdHash,
            requesterPeerId = requesterPeerId
        )
        val fetchClient = fetchClientFactory(this)
        pendingByRequestId[request.requestId] = PendingFetch(request, cue, fetchClient)

        val accepted = fetchClient.fetchOnce(deviceId, request)
        Log.i(
            LOGCAT_TAG,
            "fetch_start device_id=$deviceId request_id=${request.requestId} " +
                "message_id_hash=$hashKey accepted=$accepted"
        )
        if (!accepted) {
            pendingByRequestId.remove(request.requestId)
            fetchedHashes.remove(hashKey)
        }
    }

    override fun onFetchComplete(
        deviceAddress: String,
        request: MobFetchProtocol.Request,
        envelope: ByteArray
    ) {
        val pending = pendingByRequestId.remove(request.requestId) ?: return
        val parsed = MobMessageEnvelope.parse(envelope) as? MobMessageEnvelope.ParseResult.Ok
        if (parsed == null) {
            sink.accept(
                BleEvent.Error(
                    kind = BleEvent.Companion.ErrorKind.UNKNOWN,
                    detail = "gatt_fetch_decode_error",
                    deviceId = deviceAddress
                )
            )
            return
        }

        val cue = pending.cue
        val beaconMetadata = cue.beacon?.rawTransportMetadata
        sink.accept(
            BleEvent.ReceivedMessage(
                messageId = parsed.envelope.messageId,
                senderPeerId = parsed.envelope.senderPeerId,
                recipientPeerId = parsed.envelope.recipientPeerId,
                receivedDeviceId = deviceAddress,
                receivedAt = System.currentTimeMillis(),
                rssi = cue.rssi,
                envelope = envelope,
                rawTransportMetadata = BleEvent.ReceivedMessage.RawTransportMetadata(
                    transport = "ble_android_gatt_fetch",
                    sourceEvent = "gatt_fetch_response",
                    receivedDeviceId = deviceAddress,
                    advertisement = cue.advertisement,
                    messagePayload = envelope,
                    manufacturerData = beaconMetadata?.manufacturerData ?: ByteArray(0),
                    companyIdentifier = beaconMetadata?.companyIdentifier ?: 0,
                    adType = beaconMetadata?.adType ?: 0
                )
            )
        )
    }

    override fun onFetchFailed(
        deviceAddress: String?,
        request: MobFetchProtocol.Request?,
        reason: String,
        detail: String?
    ) {
        if (request != null) {
            pendingByRequestId.remove(request.requestId)
        }
        Log.i(
            LOGCAT_TAG,
            "fetch_failed device_id=${deviceAddress ?: "?"} " +
                "request_id=${request?.requestId ?: "?"} reason=$reason detail=${detail ?: ""}"
        )
    }

    @Synchronized
    private fun latestCue(): FetchCue? {
        val now = nowMs()
        recentCues.removeAll { now - it.seenAtMs >= fetchDedupTtlMs }
        return recentCues.maxByOrNull { it.seenAtMs }
    }

    companion object {
        private const val LOGCAT_TAG = "MobBeaconFetch"
        private const val DEFAULT_FETCH_DEDUP_TTL_MS = 60_000L
    }
}

private fun ByteArray.contentKey(): String =
    joinToString(separator = "") { "%02x".format(it) }
