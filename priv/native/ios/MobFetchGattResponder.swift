#if canImport(CoreBluetooth)
import Foundation
import CoreBluetooth

/// Swift mirror of the Android fetch Gatt responder (server) path —
/// the iOS-side GATT service that serves a full MX envelope to peer
/// requesters via the MFQ/MFR protocol.
///
/// This is the symmetric counterpart to the fetch Gatt client: that
/// type pulls full envelopes from a peer; this type serves them. With
/// both types, an iOS device can play either side of the MX
/// full-envelope fetch path that the Android-side fetch Gatt 
/// already supports.
///
/// Service / characteristic UUIDs match the fetch Gatt UUIDs (defined
/// in the fetch Gatt source) which in turn match the Android equivalents —
/// required for cross-platform GATT service discovery.

public protocol FetchGattResponderDelegate: AnyObject {
    func fetchResponderDidStart()
    func fetchResponderDidFail(reason: String)
    /// Fires after each successful MFQ Request decode — even
    /// `STATUS_NOT_FOUND` and `STATUS_INVALID_REQUEST` count, so the
    /// delegate sees every protocol-level event for observability.
    func fetchResponderDidServeRequest(
        request: FetchProtocol.Request,
        status: UInt8
    )
}

public extension FetchGattResponderDelegate {
    func fetchResponderDidStart() {}
    func fetchResponderDidFail(reason: String) {}
    func fetchResponderDidServeRequest(
        request: FetchProtocol.Request,
        status: UInt8
    ) {}
}

/// One-envelope GATT fetch responder.
///
/// Lifecycle mirrors the Android fetch Gatt startResponder /
/// `stopResponder`:
///
/// ```swift
/// let responder = FetchGattResponder(
///     envelope: envelope,
///     responderPeerId: "ios-smoke"
/// )
/// responder.start()
/// // ... peer connects, writes MFQ Request, reads MFR Response ...
/// responder.stop()
/// ```
///
/// Call `start()` to add the GATT service + begin advertising the
/// connectable fetch-service UUID. Call `stop()` to remove the
/// service + stop advertising. `start()` is idempotent across
/// `CBPeripheralManager` state changes — if Bluetooth is not yet
/// poweredOn, the responder waits for the state callback and starts
/// then.
///
/// Counters (`preparedOkCount`, `servedReadCount`) expose
/// success-observation metrics symmetric to the Android side, so
/// instrumented smoke tests can assert end-to-end success.
public final class FetchGattResponder: NSObject {
    public weak var delegate: FetchGattResponderDelegate?

    private let manager: CBPeripheralManager
    private let envelope: Data
    private let messageIdHash: Data
    private let responderPeerId: String

    private var requestCharacteristic: CBMutableCharacteristic?
    private var responseCharacteristic: CBMutableCharacteristic?
    private var service: CBMutableService?
    private var serviceAdded = false
    private var shouldServe = false

    /// Bytes of the most recently prepared MFR Response (encoded form).
    /// Returned on every read of the response characteristic. Nil
    /// before any request has been processed.
    private var preparedResponseBytes: Data?

    private var _preparedOkCount = 0
    private var _servedReadCount = 0

    /// Number of MFQ Requests received that matched the served envelope's
    /// `messageIdHash` (i.e. the requester asked for the right envelope
    /// and the responder prepared a STATUS_OK response).
    public var preparedOkCount: Int { _preparedOkCount }

    /// Number of times a peer central read the response characteristic
    /// AFTER a non-empty response had been prepared.
    public var servedReadCount: Int { _servedReadCount }

    /// Initializes a responder serving `envelope`.
    ///
    /// `envelope` must be a v1 MessageEnvelope (starts with the
    /// `MX` magic). The hash served is `sha256(envelope.messageId)[0..8]`,
    /// matching the Android fetch Gatt's `messageIdHash`
    /// computation.
    ///
    /// Throws if the envelope can't be parsed — refuses to start
    /// rather than silently serving garbage.
    public init(
        envelope: Data,
        responderPeerId: String,
        queue: DispatchQueue? = nil
    ) throws {
        guard case .success(let parsed) = MessageEnvelope.parse(envelope) else {
            throw ResponderError.invalidEnvelope
        }
        self.envelope = envelope
        self.responderPeerId = responderPeerId
        self.messageIdHash = FetchGattResponder.messageIdHash(of: parsed.messageId)
        self.manager = CBPeripheralManager(delegate: nil, queue: queue)
        super.init()
        self.manager.delegate = self
    }

    public func start() {
        shouldServe = true
        guard manager.state == .poweredOn else {
            // peripheralManagerDidUpdateState will resume start once
            // BT comes up.
            return
        }
        configureServiceIfNeeded()
        startAdvertisingIfReady()
    }

    public func stop() {
        shouldServe = false
        if manager.isAdvertising {
            manager.stopAdvertising()
        }
        if let svc = service, serviceAdded {
            manager.remove(svc)
            serviceAdded = false
        }
        service = nil
        requestCharacteristic = nil
        responseCharacteristic = nil
        preparedResponseBytes = nil
    }

    public enum ResponderError: Error {
        case invalidEnvelope
        case stateNotPoweredOn(CBManagerState)
    }

    // MARK: - Internals

    private static func messageIdHash(of messageId: Data) -> Data {
        // SHA-256(messageId)[0..8] — matches Android fetch Gatt
        // (`MessageDigest.getInstance("SHA-256").digest(...).copyOfRange(0, 8)`)
        // and LegacyBeaconAdvertisement's beacon hash. We use
        // CryptoKit for the hash to avoid pulling in a separate SHA
        // dependency.
        #if canImport(CryptoKit)
        return Data(SHA256.hash(data: messageId)).prefix(8)
        #else
        // Fallback for hosts without CryptoKit — should not be hit on
        // iOS/macOS targets. Fail loudly so this surfaces at runtime
        // rather than silently producing a wrong hash.
        fatalError("FetchGattResponder requires CryptoKit for SHA-256")
        #endif
    }

    private func configureServiceIfNeeded() {
        guard service == nil else { return }

        let req = CBMutableCharacteristic(
            type: FetchGattUUID.request,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let resp = CBMutableCharacteristic(
            type: FetchGattUUID.response,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let svc = CBMutableService(type: FetchGattUUID.service, primary: true)
        svc.characteristics = [req, resp]

        requestCharacteristic = req
        responseCharacteristic = resp
        service = svc
        manager.add(svc)
    }

    private func startAdvertisingIfReady() {
        guard shouldServe, serviceAdded, !manager.isAdvertising else { return }
        var advertisement: [String: Any] = [
            CBAdvertisementDataServiceUUIDsKey: [FetchGattUUID.service]
        ]
        if !responderPeerId.isEmpty {
            advertisement[CBAdvertisementDataLocalNameKey] = responderPeerId
        }
        manager.startAdvertising(advertisement)
    }

    public struct PreparedResponse: Equatable {
        public var request: FetchProtocol.Request
        public var response: FetchProtocol.Response
        public var encoded: Data
    }

    public func prepareResponse(for requestBytes: Data) -> PreparedResponse {
        let request: FetchProtocol.Request
        let status: UInt8
        let envelopeBytes: Data?
        let reason: String?

        if let decoded = FetchProtocol.decodeRequest(requestBytes) {
            request = decoded
            if decoded.messageIdHash == self.messageIdHash {
                status = FetchProtocol.statusOK
                envelopeBytes = envelope
                reason = nil
            } else {
                status = FetchProtocol.statusNotFound
                envelopeBytes = nil
                reason = "not_found"
            }
        } else {
            request = FetchProtocol.Request(
                requestId: "invalid",
                messageIdHash: Data(repeating: 0, count: 8),
                requesterPeerId: nil
            )
            status = FetchProtocol.statusInvalidRequest
            envelopeBytes = nil
            reason = "invalid_request"
        }

        let response = FetchProtocol.Response(
            requestId: request.requestId,
            messageIdHash: request.messageIdHash,
            status: status,
            envelope: envelopeBytes,
            reason: reason
        )

        return PreparedResponse(
            request: request,
            response: response,
            encoded: FetchProtocol.encodeResponse(response)
        )
    }
}

#if canImport(CryptoKit)
import CryptoKit
#endif

extension FetchGattResponder: CBPeripheralManagerDelegate {
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            if shouldServe {
                configureServiceIfNeeded()
                startAdvertisingIfReady()
            }
        case .unknown, .resetting:
            // Transient — wait for the next state update.
            break
        default:
            delegate?.fetchResponderDidFail(
                reason: "bluetooth state not poweredOn: \(peripheral.state.rawValue)"
            )
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didAdd service: CBService,
        error: Error?
    ) {
        if let error = error {
            delegate?.fetchResponderDidFail(reason: "add service failed: \(error)")
            return
        }
        guard service.uuid == FetchGattUUID.service else { return }
        serviceAdded = true
        startAdvertisingIfReady()
    }

    public func peripheralManagerDidStartAdvertising(
        _ peripheral: CBPeripheralManager,
        error: Error?
    ) {
        if let error = error {
            delegate?.fetchResponderDidFail(reason: "start advertising failed: \(error)")
            return
        }
        delegate?.fetchResponderDidStart()
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        for request in requests {
            handleWrite(peripheral: peripheral, attRequest: request)
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveRead request: CBATTRequest
    ) {
        guard request.characteristic.uuid == FetchGattUUID.response else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        guard request.offset == 0 else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        let bytes = preparedResponseBytes ?? Data()
        request.value = bytes
        peripheral.respond(to: request, withResult: .success)

        // Count only reads that actually carried response bytes — a
        // zero-length read means no prior write prepared one, which the
        // smoke test should not interpret as success.
        if !bytes.isEmpty {
            _servedReadCount += 1
        }
    }

    private func handleWrite(peripheral: CBPeripheralManager, attRequest: CBATTRequest) {
        guard attRequest.characteristic.uuid == FetchGattUUID.request else {
            peripheral.respond(to: attRequest, withResult: .attributeNotFound)
            return
        }
        guard attRequest.offset == 0 else {
            peripheral.respond(to: attRequest, withResult: .invalidOffset)
            return
        }
        guard let value = attRequest.value, !value.isEmpty else {
            // Empty write — reply success but don't prepare anything.
            // Mirrors Android's onCharacteristicWriteRequest behavior.
            peripheral.respond(to: attRequest, withResult: .success)
            return
        }

        let prepared = prepareResponse(for: value)
        if prepared.response.status == FetchProtocol.statusOK {
            _preparedOkCount += 1
        }
        preparedResponseBytes = prepared.encoded
        peripheral.respond(to: attRequest, withResult: .success)
        delegate?.fetchResponderDidServeRequest(
            request: prepared.request,
            status: prepared.response.status
        )
    }
}
#endif
