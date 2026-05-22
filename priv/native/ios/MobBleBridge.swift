import Foundation
import CoreBluetooth

// NOTE: When compiled via the plugin manifest + native build (swiftc list of
// extracted sources), types from the co-compiled support files (BLEClient,
// BLEPeripheral, FetchGattResponder, LegacyBeaconAdvertisement, FetchProtocol,
// MessageEnvelope, etc.) are visible in the same module without an import
// statement containing external package names. The canImport guard is retained
// only for the harness/Xcode path.

final class MobBleBridge: NSObject {
    static let shared = MobBleBridge()

    private var client: BLEClient?
    private var peripheral: BLEPeripheral?
    private var messageObserver: MessageAdvertisementObserver?
    private var fetchResponder: FetchGattResponder?
    private var centralPeers = Set<String>()
    private var peripheralPeers = Set<String>()
    private var messageId: UInt32 = 1
    private var localPeerId = "ble-mobile"

    func startScan() {
        ensureClient().startScan()
        ensureMessageObserver().startScan()
        emitStatus("Scanning")
    }

    func startAdvertising(localName: String) {
        localPeerId = localName
        ensurePeripheral().startAdvertising(localName: localName)
        emitStatus("Advertising as \(localName)")
    }

    func stop() {
        client?.stopScan()
        peripheral?.stopAdvertising()
        messageObserver?.stopScan()
        fetchResponder?.stop()
        fetchResponder = nil
        emitStatus("Stopped")
    }

    func sendPing(peerId: String, payload: Data) {
        let packet = Packet(type: .data, msgId: nextMessageId(), payload: payload)

        do {
            if centralPeers.contains(peerId), let client {
                try client.send(packet: packet, to: peerId)
                emitStatus("Ping sent")
                return
            }

            if peripheralPeers.contains(peerId), let peripheral {
                try peripheral.send(packet: packet, to: peerId)
                emitStatus("Ping sent")
                return
            }

            // No secure GATT-connected peer: publish a full MX envelope
            // through FetchGattResponder and advertise an MB beacon
            // cue carrying the envelope's messageId hash. This is the
            // iOS counterpart to the Android full-envelope dispatch.
            dispatchFullEnvelopeBeacon(payload: payload)
        } catch {
            emitError(String(describing: error))
        }
    }

    /// Build a full MX envelope, serve it over the fetch GATT service,
    /// and advertise the MB legacy beacon cue. The MB manufacturer
    /// payload and 128-bit fetch-service UUID do not fit reliably in a
    /// single legacy advertisement, so they are published through the
    /// same two-signal shape the Android path uses.
    private func dispatchFullEnvelopeBeacon(payload: Data) {
        var messageId = Data(count: 16)
        let result = messageId.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, 16, buffer.baseAddress!)
        }
        guard result == errSecSuccess else {
            emitError("legacy beacon dispatch: SecRandomCopyBytes failed (\(result))")
            return
        }

        do {
            let envelope = try MessageEnvelope.buildV1(
                messageId: messageId,
                senderPeerId: localPeerId,
                recipientPeerId: nil,
                createdAt: UInt64(Date().timeIntervalSince1970 * 1000),
                ttl: 1,
                payloadType: "TX",
                payload: payload
            )
            let beacon = LegacyBeaconAdvertisement.build(
                messageId: messageId,
                senderPeerId: localPeerId,
                payloadKind: "TX"
            )

            fetchResponder?.stop()
            let responder = try FetchGattResponder(
                envelope: envelope,
                responderPeerId: localPeerId
            )
            responder.delegate = self
            fetchResponder = responder
            responder.start()
            ensurePeripheral().startBeaconAdvertising(beacon)
            emitStatus("Full envelope responder starting (\(payload.count)B payload)")
        } catch {
            fetchResponder = nil
            emitError("full envelope dispatch failed: \(String(describing: error))")
        }
    }

    private func ensureClient() -> BLEClient {
        if let client { return client }

        let client = BLEClient()
        client.delegate = self
        self.client = client
        return client
    }

    private func ensureMessageObserver() -> MessageAdvertisementObserver {
        if let messageObserver { return messageObserver }

        let observer = MessageAdvertisementObserver()
        observer.delegate = self
        self.messageObserver = observer
        return observer
    }

    private func ensurePeripheral() -> BLEPeripheral {
        if let peripheral { return peripheral }

        let peripheral = BLEPeripheral()
        peripheral.delegate = self
        self.peripheral = peripheral
        return peripheral
    }

    private func nextMessageId() -> UInt32 {
        defer { messageId &+= 1 }
        return messageId
    }

    private func emitStatus(_ status: String) {
        status.withCString { mob_ble_emit_status($0) }
    }

    private func emitError(_ message: String) {
        message.withCString { mob_ble_emit_error($0) }
    }

    private func emitReceived(frame: Data, peerId: String) {
        do {
            let (packet, rest) = try Frame.decode(frame)
            guard rest.isEmpty else {
                emitError("Received frame with trailing bytes")
                return
            }

            peerId.withCString {
                mob_ble_emit_received($0, Int32(packet.type.rawValue), packet.msgId, UInt32(packet.payload.count))
            }
        } catch {
            emitError(String(describing: error))
        }
    }
}

extension MobBleBridge: BLEClientDelegate {
    func didConnect(peerId: String) {
        centralPeers.insert(peerId)
        peerId.withCString { mob_ble_emit_connected($0) }
    }

    func didDisconnect(peerId: String) {
        centralPeers.remove(peerId)
        peerId.withCString { mob_ble_emit_disconnected($0) }
    }

    func didReceive(frame: Data, from peerId: String) {
        emitReceived(frame: frame, peerId: peerId)
    }

    func didObserveLegacyBeacon(
        _ beacon: LegacyBeaconAdvertisement,
        deviceId: String,
        rssi: Int
    ) {
        deviceId.withCString { deviceIdPtr in
            beacon.payloadKind.withCString { payloadKindPtr in
                beacon.messageIdHash.withUnsafeBytes { messageHashBuffer in
                    beacon.senderPeerIdHash.withUnsafeBytes { senderHashBuffer in
                        beacon.advertisement.withUnsafeBytes { advertisementBuffer in
                            beacon.beaconPayload.withUnsafeBytes { beaconPayloadBuffer in
                                beacon.manufacturerData.withUnsafeBytes { manufacturerBuffer in
                                    mob_ble_emit_received_message_beacon(
                                        deviceIdPtr,
                                        Int32(rssi),
                                        Int32(beacon.beaconVersion),
                                        Int32(beacon.envelopeVersion),
                                        payloadKindPtr,
                                        messageHashBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        senderHashBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        advertisementBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        UInt32(beacon.advertisement.count),
                                        beaconPayloadBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        UInt32(beacon.beaconPayload.count),
                                        manufacturerBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        UInt32(beacon.manufacturerData.count),
                                        UInt32(LegacyBeaconAdvertisement.manufacturerCompanyIdentifier)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    func didError(_ error: Error) {
        emitError(String(describing: error))
    }
}

extension MobBleBridge: BLEPeripheralDelegate {
    func peripheralDidStartAdvertising() {
        emitStatus("Advertising")
    }

    func peripheralDidStopAdvertising() {
        emitStatus("Stopped")
    }

    func peripheralDidConnect(peerId: String) {
        peripheralPeers.insert(peerId)
        peerId.withCString { mob_ble_emit_connected($0) }
    }

    func peripheralDidDisconnect(peerId: String) {
        peripheralPeers.remove(peerId)
        peerId.withCString { mob_ble_emit_disconnected($0) }
    }

    func peripheralDidReceive(frame: Data, from peerId: String) {
        emitReceived(frame: frame, peerId: peerId)
    }

    func peripheralDidError(_ error: Error) {
        emitError(String(describing: error))
    }
}

extension MobBleBridge: FetchGattResponderDelegate {
    func fetchResponderDidStart() {
        emitStatus("Full envelope responder advertising")
    }

    func fetchResponderDidFail(reason: String) {
        emitError("full envelope responder failed: \(reason)")
    }

    func fetchResponderDidServeRequest(
        request: FetchProtocol.Request,
        status: UInt8
    ) {
        emitStatus("Fetch served \(request.requestId) status=\(status)")
    }
}

extension MobBleBridge: MessageAdvertisementObserverDelegate {
    func didObserveReceivedMessage(_ event: ReceivedMessageEvent) {
        let metadata = event.rawTransportMetadata

        event.receivedDeviceId.withCString { deviceIdPtr in
            event.senderPeerId.withCString { senderPtr in
                event.messageId.withUnsafeBytes { messageIdBuffer in
                    metadata.messagePayload.withUnsafeBytes { messagePayloadBuffer in
                        metadata.advertisement.withUnsafeBytes { advertisementBuffer in
                            metadata.manufacturerData.withUnsafeBytes { manufacturerBuffer in
                                let recipientCString = event.recipientPeerId.flatMap { $0.cString(using: .utf8) }

                                recipientCString.withOptionalCStringPointer { recipientPtr in
                                    mob_ble_emit_received_message(
                                        deviceIdPtr,
                                        Int32(event.rssi),
                                        Int64(event.receivedAt),
                                        messageIdBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        UInt32(event.messageId.count),
                                        senderPtr,
                                        recipientPtr,
                                        messagePayloadBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        UInt32(metadata.messagePayload.count),
                                        advertisementBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        UInt32(metadata.advertisement.count),
                                        messagePayloadBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        UInt32(metadata.messagePayload.count),
                                        manufacturerBuffer.bindMemory(to: UInt8.self).baseAddress,
                                        UInt32(metadata.manufacturerData.count),
                                        UInt32(metadata.companyIdentifier)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    func didObserveMessageDecodeError(_ reason: String, deviceId: String, rssi: Int) {
        emitError("message_advertisement_decode_error[\(deviceId)@\(rssi)]: \(reason)")
    }

    func messageObserverDidStartScan() {}

    func messageObserverDidUpdateState(_ state: String) {
        emitStatus("MessageObserver state: \(state)")
    }

    func messageObserverDidError(_ error: Error) {
        emitError(String(describing: error))
    }

    func messageObserverDidFetchEnvelope(
        envelope: Data,
        fromDeviceId: String,
        beacon: LegacyBeaconAdvertisement,
        rssi: Int
    ) {
        // Synthesize a ReceivedMessageEvent from the fetched MX bytes so
        // we can reuse the existing mob_ble_emit_received_message NIF
        // path.
        switch MessageEnvelope.parse(envelope) {
        case .success(let parsed):
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            let event = ReceivedMessageEvent(
                messageId: parsed.messageId,
                senderPeerId: parsed.senderPeerId,
                recipientPeerId: parsed.recipientPeerId,
                receivedDeviceId: fromDeviceId,
                receivedAt: now,
                rssi: rssi,
                envelope: parsed,
                rawTransportMetadata: .init(
                    transport: "ble_ios_gatt_fetch",
                    sourceEvent: "gatt_fetch_response",
                    receivedDeviceId: fromDeviceId,
                    advertisement: beacon.advertisement,
                    messagePayload: envelope,
                    manufacturerData: beacon.manufacturerData,
                    companyIdentifier: LegacyBeaconAdvertisement.manufacturerCompanyIdentifier,
                    adType: LegacyBeaconAdvertisement.manufacturerDataAdType
                )
            )
            didObserveReceivedMessage(event)
        case .failure(let reason):
            emitError("gatt_fetch_decode_error: \(reason)")
        }
    }

    func messageObserverDidFailFetch(
        reason: String,
        detail: String?,
        fromDeviceId: String,
        beacon: LegacyBeaconAdvertisement
    ) {
        emitStatus("Fetch failed [\(fromDeviceId)]: \(reason)\(detail.map { " (\($0))" } ?? "")")
    }
}

private extension Optional where Wrapped == [CChar] {
    func withOptionalCStringPointer<R>(_ body: (UnsafePointer<CChar>?) -> R) -> R {
        switch self {
        case .some(let chars):
            return chars.withUnsafeBufferPointer { buffer in
                body(buffer.baseAddress)
            }
        case .none:
            return body(nil)
        }
    }
}

@_cdecl("mob_ble_start_scan")
public func mob_ble_start_scan() {
    DispatchQueue.main.async {
        MobBleBridge.shared.startScan()
    }
}

@_cdecl("mob_ble_start_advertising")
public func mob_ble_start_advertising(_ localNamePtr: UnsafePointer<CChar>) {
    let localName = String(cString: localNamePtr)
    DispatchQueue.main.async {
        MobBleBridge.shared.startAdvertising(localName: localName)
    }
}

@_cdecl("mob_ble_stop")
public func mob_ble_stop() {
    DispatchQueue.main.async {
        MobBleBridge.shared.stop()
    }
}

@_cdecl("mob_ble_send_ping")
public func mob_ble_send_ping(
    _ peerIdPtr: UnsafePointer<CChar>,
    _ payloadPtr: UnsafePointer<UInt8>,
    _ payloadLength: Int32
) {
    let peerId = String(cString: peerIdPtr)
    let payload = Data(bytes: payloadPtr, count: Int(payloadLength))

    DispatchQueue.main.async {
        MobBleBridge.shared.sendPing(peerId: peerId, payload: payload)
    }
}

@_silgen_name("mob_ble_emit_status")
func mob_ble_emit_status(_ status: UnsafePointer<CChar>)

@_silgen_name("mob_ble_emit_connected")
func mob_ble_emit_connected(_ peerId: UnsafePointer<CChar>)

@_silgen_name("mob_ble_emit_disconnected")
func mob_ble_emit_disconnected(_ peerId: UnsafePointer<CChar>)

@_silgen_name("mob_ble_emit_received")
func mob_ble_emit_received(
    _ peerId: UnsafePointer<CChar>,
    _ packetType: Int32,
    _ msgId: UInt32,
    _ byteCount: UInt32
)

@_silgen_name("mob_ble_emit_received_message_beacon")
func mob_ble_emit_received_message_beacon(
    _ deviceId: UnsafePointer<CChar>,
    _ rssi: Int32,
    _ beaconVersion: Int32,
    _ envelopeVersion: Int32,
    _ payloadKind: UnsafePointer<CChar>,
    _ messageIdHash: UnsafePointer<UInt8>?,
    _ senderPeerIdHash: UnsafePointer<UInt8>?,
    _ advertisement: UnsafePointer<UInt8>?,
    _ advertisementLength: UInt32,
    _ beaconPayload: UnsafePointer<UInt8>?,
    _ beaconPayloadLength: UInt32,
    _ manufacturerData: UnsafePointer<UInt8>?,
    _ manufacturerDataLength: UInt32,
    _ companyIdentifier: UInt32
)

@_silgen_name("mob_ble_emit_received_message")
func mob_ble_emit_received_message(
    _ deviceId: UnsafePointer<CChar>,
    _ rssi: Int32,
    _ receivedAtMs: Int64,
    _ messageId: UnsafePointer<UInt8>?,
    _ messageIdLength: UInt32,
    _ senderPeerId: UnsafePointer<CChar>,
    _ recipientPeerId: UnsafePointer<CChar>?,
    _ envelope: UnsafePointer<UInt8>?,
    _ envelopeLength: UInt32,
    _ advertisement: UnsafePointer<UInt8>?,
    _ advertisementLength: UInt32,
    _ messagePayload: UnsafePointer<UInt8>?,
    _ messagePayloadLength: UInt32,
    _ manufacturerData: UnsafePointer<UInt8>?,
    _ manufacturerDataLength: UInt32,
    _ companyIdentifier: UInt32
)

@_silgen_name("mob_ble_emit_error")
func mob_ble_emit_error(_ message: UnsafePointer<CChar>)
