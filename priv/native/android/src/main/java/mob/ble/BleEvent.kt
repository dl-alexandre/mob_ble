package mob.ble

import java.util.Base64
import org.json.JSONObject

/**
 * Kotlin mirror of the canonical v1 wire format defined by
 * `Mob.Ble.BridgeProtocol`. Every event the Android transport
 * surface emits is one of these — never an Android-specific shape.
 *
 * `toWireMap()` returns the NIF-friendly form (ByteArray for binary
 * fields). `toJsonObject()` returns the JSON-transport form (base64
 * strings for binary fields). The Elixir decoder is one and the same
 * for both, with a small base64 adapter on the JSON path.
 */
sealed class BleEvent {

    abstract fun toWireMap(): Map<String, Any?>
    abstract fun toJsonObject(): JSONObject

    data class DeviceDiscovered(
        val deviceId: String,
        val rssi: Int,
        val advertisement: ByteArray,
        val observedAtMs: Long
    ) : BleEvent() {

        override fun toWireMap(): Map<String, Any?> = mapOf(
            "v" to WIRE_VERSION,
            "event" to "device_discovered",
            "device_id" to deviceId,
            "rssi" to rssi,
            "advertisement" to advertisement,
            "observed_at_ms" to observedAtMs
        )

        override fun toJsonObject(): JSONObject = JSONObject().apply {
            put("v", WIRE_VERSION)
            put("event", "device_discovered")
            put("device_id", deviceId)
            put("rssi", rssi)
            put("advertisement", advertisement.toBase64())
            put("observed_at_ms", observedAtMs)
        }

        // data class with ByteArray field needs explicit equals/hashCode.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DeviceDiscovered) return false
            return deviceId == other.deviceId &&
                rssi == other.rssi &&
                advertisement.contentEquals(other.advertisement) &&
                observedAtMs == other.observedAtMs
        }

        override fun hashCode(): Int {
            var h = deviceId.hashCode()
            h = 31 * h + rssi
            h = 31 * h + advertisement.contentHashCode()
            h = 31 * h + observedAtMs.hashCode()
            return h
        }
    }

    data class AdvertisementReceived(
        val deviceId: String,
        val rssi: Int,
        val advertisement: ByteArray,
        val observedAtMs: Long
    ) : BleEvent() {

        override fun toWireMap(): Map<String, Any?> = mapOf(
            "v" to WIRE_VERSION,
            "event" to "advertisement_received",
            "device_id" to deviceId,
            "rssi" to rssi,
            "advertisement" to advertisement,
            "observed_at_ms" to observedAtMs
        )

        override fun toJsonObject(): JSONObject = JSONObject().apply {
            put("v", WIRE_VERSION)
            put("event", "advertisement_received")
            put("device_id", deviceId)
            put("rssi", rssi)
            put("advertisement", advertisement.toBase64())
            put("observed_at_ms", observedAtMs)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AdvertisementReceived) return false
            return deviceId == other.deviceId &&
                rssi == other.rssi &&
                advertisement.contentEquals(other.advertisement) &&
                observedAtMs == other.observedAtMs
        }

        override fun hashCode(): Int {
            var h = deviceId.hashCode()
            h = 31 * h + rssi
            h = 31 * h + advertisement.contentHashCode()
            h = 31 * h + observedAtMs.hashCode()
            return h
        }
    }

    data class ReceivedMessage(
        val messageId: ByteArray,
        val senderPeerId: String,
        val recipientPeerId: String?,
        val receivedDeviceId: String,
        val receivedAt: Long,
        val rssi: Int,
        val envelope: ByteArray,
        val rawTransportMetadata: RawTransportMetadata
    ) : BleEvent() {

        data class RawTransportMetadata(
            val transport: String,
            val sourceEvent: String,
            val receivedDeviceId: String,
            val advertisement: ByteArray,
            val messagePayload: ByteArray,
            val manufacturerData: ByteArray,
            val companyIdentifier: Int,
            val adType: Int
        ) {
            fun toWireMap(): Map<String, Any?> = mapOf(
                "transport" to transport,
                "source_event" to sourceEvent,
                "received_device_id" to receivedDeviceId,
                "advertisement" to advertisement,
                "message_payload" to messagePayload,
                "manufacturer_data" to manufacturerData,
                "company_identifier" to companyIdentifier,
                "ad_type" to adType
            )

            fun toJsonObject(): JSONObject = JSONObject().apply {
                put("transport", transport)
                put("source_event", sourceEvent)
                put("received_device_id", receivedDeviceId)
                put("advertisement", advertisement.toBase64())
                put("message_payload", messagePayload.toBase64())
                put("manufacturer_data", manufacturerData.toBase64())
                put("company_identifier", companyIdentifier)
                put("ad_type", adType)
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is RawTransportMetadata) return false
                return transport == other.transport &&
                    sourceEvent == other.sourceEvent &&
                    receivedDeviceId == other.receivedDeviceId &&
                    advertisement.contentEquals(other.advertisement) &&
                    messagePayload.contentEquals(other.messagePayload) &&
                    manufacturerData.contentEquals(other.manufacturerData) &&
                    companyIdentifier == other.companyIdentifier &&
                    adType == other.adType
            }

            override fun hashCode(): Int {
                var h = transport.hashCode()
                h = 31 * h + sourceEvent.hashCode()
                h = 31 * h + receivedDeviceId.hashCode()
                h = 31 * h + advertisement.contentHashCode()
                h = 31 * h + messagePayload.contentHashCode()
                h = 31 * h + manufacturerData.contentHashCode()
                h = 31 * h + companyIdentifier
                h = 31 * h + adType
                return h
            }
        }

        override fun toWireMap(): Map<String, Any?> = mapOf(
            "v" to WIRE_VERSION,
            "event" to "received_message",
            "message_id" to messageId,
            "sender_peer_id" to senderPeerId,
            "recipient_peer_id" to recipientPeerId,
            "received_device_id" to receivedDeviceId,
            "received_at" to receivedAt,
            "rssi" to rssi,
            "envelope" to envelope,
            "raw_transport_metadata" to rawTransportMetadata.toWireMap()
        )

        override fun toJsonObject(): JSONObject = JSONObject().apply {
            put("v", WIRE_VERSION)
            put("event", "received_message")
            put("message_id", messageId.toBase64())
            put("sender_peer_id", senderPeerId)
            put("recipient_peer_id", recipientPeerId ?: JSONObject.NULL)
            put("received_device_id", receivedDeviceId)
            put("received_at", receivedAt)
            put("rssi", rssi)
            put("envelope", envelope.toBase64())
            put("raw_transport_metadata", rawTransportMetadata.toJsonObject())
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReceivedMessage) return false
            return messageId.contentEquals(other.messageId) &&
                senderPeerId == other.senderPeerId &&
                recipientPeerId == other.recipientPeerId &&
                receivedDeviceId == other.receivedDeviceId &&
                receivedAt == other.receivedAt &&
                rssi == other.rssi &&
                envelope.contentEquals(other.envelope) &&
                rawTransportMetadata == other.rawTransportMetadata
        }

        override fun hashCode(): Int {
            var h = messageId.contentHashCode()
            h = 31 * h + senderPeerId.hashCode()
            h = 31 * h + (recipientPeerId?.hashCode() ?: 0)
            h = 31 * h + receivedDeviceId.hashCode()
            h = 31 * h + receivedAt.hashCode()
            h = 31 * h + rssi
            h = 31 * h + envelope.contentHashCode()
            h = 31 * h + rawTransportMetadata.hashCode()
            return h
        }
    }

    data class ReceivedMessageBeacon(
        val beaconVersion: Int,
        val envelopeVersion: Int,
        val payloadKind: String,
        val messageIdHash: ByteArray,
        val senderPeerIdHash: ByteArray,
        val receivedDeviceId: String,
        val receivedAt: Long,
        val rssi: Int,
        val rawTransportMetadata: RawTransportMetadata
    ) : BleEvent() {

        data class RawTransportMetadata(
            val transport: String,
            val sourceEvent: String,
            val receivedDeviceId: String,
            val advertisement: ByteArray,
            val beaconPayload: ByteArray,
            val manufacturerData: ByteArray,
            val companyIdentifier: Int,
            val adType: Int
        ) {
            fun toWireMap(): Map<String, Any?> = mapOf(
                "transport" to transport,
                "source_event" to sourceEvent,
                "received_device_id" to receivedDeviceId,
                "advertisement" to advertisement,
                "beacon_payload" to beaconPayload,
                "manufacturer_data" to manufacturerData,
                "company_identifier" to companyIdentifier,
                "ad_type" to adType
            )

            fun toJsonObject(): JSONObject = JSONObject().apply {
                put("transport", transport)
                put("source_event", sourceEvent)
                put("received_device_id", receivedDeviceId)
                put("advertisement", advertisement.toBase64())
                put("beacon_payload", beaconPayload.toBase64())
                put("manufacturer_data", manufacturerData.toBase64())
                put("company_identifier", companyIdentifier)
                put("ad_type", adType)
            }
        }

        override fun toWireMap(): Map<String, Any?> = mapOf(
            "v" to WIRE_VERSION,
            "event" to "received_message_beacon",
            "beacon_version" to beaconVersion,
            "envelope_version" to envelopeVersion,
            "payload_kind" to payloadKind,
            "message_id_hash" to messageIdHash,
            "sender_peer_id_hash" to senderPeerIdHash,
            "received_device_id" to receivedDeviceId,
            "received_at" to receivedAt,
            "rssi" to rssi,
            "raw_transport_metadata" to rawTransportMetadata.toWireMap()
        )

        override fun toJsonObject(): JSONObject = JSONObject().apply {
            put("v", WIRE_VERSION)
            put("event", "received_message_beacon")
            put("beacon_version", beaconVersion)
            put("envelope_version", envelopeVersion)
            put("payload_kind", payloadKind)
            put("message_id_hash", messageIdHash.toBase64())
            put("sender_peer_id_hash", senderPeerIdHash.toBase64())
            put("received_device_id", receivedDeviceId)
            put("received_at", receivedAt)
            put("rssi", rssi)
            put("raw_transport_metadata", rawTransportMetadata.toJsonObject())
        }
    }

    data class AdvertGossipOutcome(
        val gossipIntentId: String,
        val messageIdHash: ByteArray,
        val senderPeerIdHash: ByteArray,
        val advertiseAs: String,
        val kind: String,
        val outcomeAtMs: Long,
        val reason: String?,
        val adapter: String
    ) : BleEvent() {

        override fun toWireMap(): Map<String, Any?> = mapOf(
            "v" to WIRE_VERSION,
            "event" to "advert_gossip_outcome",
            "gossip_intent_id" to gossipIntentId,
            "message_id_hash" to messageIdHash,
            "sender_peer_id_hash" to senderPeerIdHash,
            "advertise_as" to advertiseAs,
            "kind" to kind,
            "outcome_at_ms" to outcomeAtMs,
            "reason" to reason,
            "adapter" to adapter
        )

        override fun toJsonObject(): JSONObject = JSONObject().apply {
            put("v", WIRE_VERSION)
            put("event", "advert_gossip_outcome")
            put("gossip_intent_id", gossipIntentId)
            put("message_id_hash", messageIdHash.toBase64())
            put("sender_peer_id_hash", senderPeerIdHash.toBase64())
            put("advertise_as", advertiseAs)
            put("kind", kind)
            put("outcome_at_ms", outcomeAtMs)
            put("reason", reason ?: JSONObject.NULL)
            put("adapter", adapter)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AdvertGossipOutcome) return false
            return gossipIntentId == other.gossipIntentId &&
                messageIdHash.contentEquals(other.messageIdHash) &&
                senderPeerIdHash.contentEquals(other.senderPeerIdHash) &&
                advertiseAs == other.advertiseAs &&
                kind == other.kind &&
                outcomeAtMs == other.outcomeAtMs &&
                reason == other.reason &&
                adapter == other.adapter
        }

        override fun hashCode(): Int {
            var h = gossipIntentId.hashCode()
            h = 31 * h + messageIdHash.contentHashCode()
            h = 31 * h + senderPeerIdHash.contentHashCode()
            h = 31 * h + advertiseAs.hashCode()
            h = 31 * h + kind.hashCode()
            h = 31 * h + outcomeAtMs.hashCode()
            h = 31 * h + (reason?.hashCode() ?: 0)
            h = 31 * h + adapter.hashCode()
            return h
        }
    }

    /**
     * `kind` must be drawn from `Mob.Ble.Error.kinds/0`. Anything
     * else is coerced to `:unknown` on the Elixir side — the Kotlin code
     * should still respect the closed set so the wire log stays auditable.
     */
    data class Error(
        val kind: String,
        val detail: String,
        val deviceId: String? = null
    ) : BleEvent() {

        override fun toWireMap(): Map<String, Any?> = buildMap {
            put("v", WIRE_VERSION)
            put("event", "error")
            put("kind", kind)
            put("detail", detail)
            if (deviceId != null) put("device_id", deviceId)
        }

        override fun toJsonObject(): JSONObject = JSONObject().apply {
            put("v", WIRE_VERSION)
            put("event", "error")
            put("kind", kind)
            put("detail", detail)
            if (deviceId != null) put("device_id", deviceId)
        }
    }

    companion object {
        const val WIRE_VERSION = 1

        // Closed taxonomy mirror of Mob.Ble.Error.kinds/0. Kept
        // as plain strings (not an enum) so the wire format stays primitive.
        object ErrorKind {
            const val BLUETOOTH_OFF = "bluetooth_off"
            const val UNAUTHORIZED = "unauthorized"
            const val PERIPHERAL_UNSUPPORTED = "peripheral_unsupported"
            const val ADVERTISE_FAILED = "advertise_failed"
            const val SCAN_FAILED = "scan_failed"
            const val GATT_ERROR = "gatt_error"
            const val TIMEOUT = "timeout"
            const val NOT_CONNECTED = "not_connected"
            const val UNKNOWN = "unknown"
        }
    }
}

// Uses java.util.Base64 (available since API 26 = our minSdk) so the
// same code path runs in JVM unit tests and on-device. Android's
// android.util.Base64 is stubbed-to-null under the default JVM test
// runner, which would otherwise force a Robolectric dependency just
// to verify the wire format.
private fun ByteArray.toBase64(): String =
    Base64.getEncoder().encodeToString(this)
