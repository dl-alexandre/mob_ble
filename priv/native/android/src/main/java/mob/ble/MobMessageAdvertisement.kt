package mob.ble

/**
 * Android-side parser for Mob message advertisements.
 *
 * This mirrors the Elixir bridge receive path: only manufacturer-specific
 * advertisements carrying the existing M14 MessageEnvelope shape become
 * canonical received_message events. Malformed tagged payloads become
 * tagged error events instead of exceptions.
 */
object MobMessageAdvertisement {
    const val MANUFACTURER_SPECIFIC_DATA_AD_TYPE = 0xFF
    private const val MAGIC_M = 'M'.code.toByte()
    private const val MAGIC_X = 'X'.code.toByte()
    private const val MAGIC_B = 'B'.code.toByte()
    private const val LEGACY_BEACON_SIZE = 22

    sealed class DecodeResult {
        data class Received(val event: BleEvent.ReceivedMessage) : DecodeResult()
        data class ReceivedBeacon(val event: BleEvent.ReceivedMessageBeacon) : DecodeResult()
        data class Error(val event: BleEvent.Error) : DecodeResult()
        data object NotMessageAdvertisement : DecodeResult()
    }

    fun decodeScanRecord(
        advertisement: ByteArray,
        deviceId: String,
        rssi: Int,
        observedAtMs: Long,
        sourceEvent: String
    ): DecodeResult {
        for (result in manufacturerEntries(advertisement)) {
            when (result) {
                is ManufacturerEntryResult.TruncatedMessageAdvertisement ->
                    return DecodeResult.Error(
                        BleEvent.Error(
                            kind = BleEvent.Companion.ErrorKind.UNKNOWN,
                            detail = "{:message_advertisement_decode_error, :truncated_ad_structure}",
                            deviceId = deviceId
                        )
                    )

                is ManufacturerEntryResult.Entry -> {
                    val entry = result.entry
                    if (entry.companyIdentifier != BleDispatcher.MOB_COMPANY_IDENTIFIER) {
                        continue
                    }
                    if (!entry.payload.startsWithMagic()) {
                        continue
                    }

                    if (entry.payload.isLegacyBeacon()) {
                        return DecodeResult.ReceivedBeacon(
                            receivedBeacon(
                                entry,
                                advertisement,
                                deviceId,
                                rssi,
                                observedAtMs,
                                sourceEvent
                            )
                        )
                    }

                    return when (val parsed = MobMessageEnvelope.parse(entry.payload)) {
                        is MobMessageEnvelope.ParseResult.Ok -> DecodeResult.Received(
                            receivedMessage(
                                parsed.envelope,
                                entry,
                                advertisement,
                                deviceId,
                                rssi,
                                observedAtMs,
                                sourceEvent
                            )
                        )

                        is MobMessageEnvelope.ParseResult.Error -> DecodeResult.Error(
                            BleEvent.Error(
                                kind = BleEvent.Companion.ErrorKind.UNKNOWN,
                                detail = "{:message_advertisement_decode_error, :${parsed.reason}}",
                                deviceId = deviceId
                            )
                        )
                    }
                }
            }
        }

        return DecodeResult.NotMessageAdvertisement
    }

    private sealed class ManufacturerEntryResult {
        data class Entry(val entry: ManufacturerEntry) : ManufacturerEntryResult()
        data object TruncatedMessageAdvertisement : ManufacturerEntryResult()
    }

    private data class ManufacturerEntry(
        val companyIdentifier: Int,
        val manufacturerData: ByteArray,
        val payload: ByteArray
    )

    private fun manufacturerEntries(advertisement: ByteArray): List<ManufacturerEntryResult> {
        val entries = mutableListOf<ManufacturerEntryResult>()
        var offset = 0

        while (offset < advertisement.size) {
            val length = advertisement[offset].toInt() and 0xFF
            if (length == 0) break

            val structureStart = offset + 1
            val structureEnd = structureStart + length
            if (structureEnd > advertisement.size) {
                if (truncatedMobAdStructure(advertisement, structureStart)) {
                    entries.add(ManufacturerEntryResult.TruncatedMessageAdvertisement)
                }
                break
            }

            val type = advertisement[structureStart].toInt() and 0xFF
            val dataStart = structureStart + 1
            val dataLength = length - 1

            if (type == MANUFACTURER_SPECIFIC_DATA_AD_TYPE && dataLength >= 2) {
                val companyIdentifier =
                    (advertisement[dataStart].toInt() and 0xFF) or
                        ((advertisement[dataStart + 1].toInt() and 0xFF) shl 8)
                val manufacturerData = advertisement.copyOfRange(dataStart, structureEnd)
                val payload = advertisement.copyOfRange(dataStart + 2, structureEnd)
                entries.add(
                    ManufacturerEntryResult.Entry(
                        ManufacturerEntry(
                            companyIdentifier = companyIdentifier,
                            manufacturerData = manufacturerData,
                            payload = payload
                        )
                    )
                )
            }

            offset = structureEnd
        }

        return entries
    }

    private fun truncatedMobAdStructure(advertisement: ByteArray, structureStart: Int): Boolean {
        val typeIndex = structureStart
        if (typeIndex >= advertisement.size) return false
        if ((advertisement[typeIndex].toInt() and 0xFF) != MANUFACTURER_SPECIFIC_DATA_AD_TYPE) {
            return false
        }

        val dataStart = typeIndex + 1
        if (dataStart + 3 >= advertisement.size) return false

        val companyIdentifier =
            (advertisement[dataStart].toInt() and 0xFF) or
                ((advertisement[dataStart + 1].toInt() and 0xFF) shl 8)
        return companyIdentifier == BleDispatcher.MOB_COMPANY_IDENTIFIER &&
            advertisement[dataStart + 2] == MAGIC_M &&
            advertisement[dataStart + 3] == MAGIC_X
    }

    private fun receivedMessage(
        envelope: MobMessageEnvelope.Decoded,
        entry: ManufacturerEntry,
        advertisement: ByteArray,
        deviceId: String,
        rssi: Int,
        observedAtMs: Long,
        sourceEvent: String
    ): BleEvent.ReceivedMessage {
        return BleEvent.ReceivedMessage(
            messageId = envelope.messageId,
            senderPeerId = envelope.senderPeerId,
            recipientPeerId = envelope.recipientPeerId,
            receivedDeviceId = deviceId,
            receivedAt = observedAtMs,
            rssi = rssi,
            envelope = entry.payload,
            rawTransportMetadata = BleEvent.ReceivedMessage.RawTransportMetadata(
                transport = "ble_advertisement",
                sourceEvent = sourceEvent,
                receivedDeviceId = deviceId,
                advertisement = advertisement,
                messagePayload = entry.payload,
                manufacturerData = entry.manufacturerData,
                companyIdentifier = entry.companyIdentifier,
                adType = MANUFACTURER_SPECIFIC_DATA_AD_TYPE
            )
        )
    }

    private fun ByteArray.startsWithMagic(): Boolean =
        size >= 2 && this[0] == MAGIC_M && (this[1] == MAGIC_X || this[1] == MAGIC_B)

    private fun ByteArray.isLegacyBeacon(): Boolean =
        size == LEGACY_BEACON_SIZE && this[0] == MAGIC_M && this[1] == MAGIC_B

    private fun receivedBeacon(
        entry: ManufacturerEntry,
        advertisement: ByteArray,
        deviceId: String,
        rssi: Int,
        observedAtMs: Long,
        sourceEvent: String
    ): BleEvent.ReceivedMessageBeacon {
        val payload = entry.payload
        val payloadKind = when (payload[4].toInt() and 0xFF) {
            1 -> "TX"
            else -> "unknown"
        }

        return BleEvent.ReceivedMessageBeacon(
            beaconVersion = payload[2].toInt() and 0xFF,
            envelopeVersion = payload[3].toInt() and 0xFF,
            payloadKind = payloadKind,
            messageIdHash = payload.copyOfRange(6, 14),
            senderPeerIdHash = payload.copyOfRange(14, 22),
            receivedDeviceId = deviceId,
            receivedAt = observedAtMs,
            rssi = rssi,
            rawTransportMetadata = BleEvent.ReceivedMessageBeacon.RawTransportMetadata(
                transport = "ble_advertisement",
                sourceEvent = sourceEvent,
                receivedDeviceId = deviceId,
                advertisement = advertisement,
                beaconPayload = payload,
                manufacturerData = entry.manufacturerData,
                companyIdentifier = entry.companyIdentifier,
                adType = MANUFACTURER_SPECIFIC_DATA_AD_TYPE
            )
        )
    }
}
