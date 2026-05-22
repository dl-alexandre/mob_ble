#if canImport(CoreBluetooth)
import Foundation
import CoreBluetooth

public protocol MessageAdvertisementObserverDelegate: AnyObject {
    func didObserveReceivedMessage(_ event: ReceivedMessageEvent)
    func didObserveMessageDecodeError(_ reason: String, deviceId: String, rssi: Int)
    func messageObserverDidObserveLegacyBeacon(
        _ beacon: LegacyBeaconAdvertisement,
        deviceId: String,
        rssi: Int
    )
    func messageObserverDidObserveAdvertisement(
        deviceId: String,
        rssi: Int,
        localName: String?,
        serviceUUIDs: [String],
        manufacturerDataLength: Int
    )
    func messageObserverDidStartScan()
    func messageObserverDidUpdateState(_ state: String)
    func messageObserverDidError(_ error: Error)

    /// Optional. Called when the observer's GATT-fetch coordinator
    /// successfully pulled a full MX envelope from a peer that earlier
    /// advertised an MB legacy beacon. The full envelope bytes start
    /// with the "MX" magic and can be parsed via
    /// `MessageEnvelope.parse`.
    func messageObserverDidFetchEnvelope(
        envelope: Data,
        fromDeviceId: String,
        beacon: LegacyBeaconAdvertisement,
        rssi: Int
    )

    /// Optional. Called when a fetch attempt fails. `reason` is the
    /// phase string ("connect_failed", "service_discovery_failed", ...).
    func messageObserverDidFailFetch(
        reason: String,
        detail: String?,
        fromDeviceId: String,
        beacon: LegacyBeaconAdvertisement
    )
}

public extension MessageAdvertisementObserverDelegate {
    func messageObserverDidObserveAdvertisement(
        deviceId: String,
        rssi: Int,
        localName: String?,
        serviceUUIDs: [String],
        manufacturerDataLength: Int
    ) {}
    func messageObserverDidObserveLegacyBeacon(
        _ beacon: LegacyBeaconAdvertisement,
        deviceId: String,
        rssi: Int
    ) {}
    func messageObserverDidFetchEnvelope(
        envelope: Data,
        fromDeviceId: String,
        beacon: LegacyBeaconAdvertisement,
        rssi: Int
    ) {}
    func messageObserverDidFailFetch(
        reason: String,
        detail: String?,
        fromDeviceId: String,
        beacon: LegacyBeaconAdvertisement
    ) {}
}

public final class MessageAdvertisementObserver: NSObject {
    public weak var delegate: MessageAdvertisementObserverDelegate?

    private let central: CBCentralManager
    private var shouldScan = false

    /// Requester peer id passed in MFQ Request frames. Optional; the
    /// Android responder treats it as informational.
    public var requesterPeerId: String?

    /// Set to false to disable the GATT-fetch follow-up entirely (so
    /// the observer only reports beacons / decode errors and never opens
    /// a GATT connection). Default: true.
    public var fetchOnBeacon: Bool = true

    /// Skip fetches for a peripheral whose `messageIdHash` matches one
    /// we've successfully fetched (or attempted) within this window.
    public var fetchDedupTTL: TimeInterval = 60.0

    /// When true, the observer will print the full advertisementData keys
    /// and types for every didDiscover (useful for extended advertising
    /// interop debugging on iOS when manufacturer data for custom company
    /// IDs is being filtered by CoreBluetooth).
    public var debugLogRawAdvertisementData = false

    private var fetchInFlight: [UUID: FetchGattClient] = [:]
    private var fetchedHashes: [Data: Date] = [:]
    private var pendingBeacons: [UUID: (beacon: LegacyBeaconAdvertisement, rssi: Int)] = [:]
    /// Recent MB legacy beacons keyed by messageIdHash. Used when we
    /// later see a connectable fetch-service advert from a DIFFERENT
    /// MAC than the MB beacon (Android uses two private resolvable
    /// addresses, one per advertise call). We pick the most recent
    /// beacon's hash to populate the MFQ Request.
    private var recentBeacons: [(beacon: LegacyBeaconAdvertisement, rssi: Int, at: Date)] = []

    public override init() {
        self.central = CBCentralManager(delegate: nil, queue: nil)
        super.init()
        self.central.delegate = self
    }

    public func startScan() {
        shouldScan = true
        guard central.state == .poweredOn else {
            if central.state != .unknown && central.state != .resetting {
                delegate?.messageObserverDidError(ObserverError.stateNotPoweredOn(central.state))
            }
            return
        }

        // Scan with `withServices: nil` so MB legacy beacons (their
        // primary-channel manufacturer-data advert is the supported path
        // on iOS) reach `didDiscover`. Pinning a service UUID filter
        // was tested and did NOT enable extended-advertising AUX_ADV_IND
        // reception on iPhone 13/iOS 26.4 — see commit notes. Full MX
        // envelopes >31 bytes must arrive via the GATT fetch path.
        central.scanForPeripherals(
            withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        delegate?.messageObserverDidStartScan()
    }

    public func stopScan() {
        shouldScan = false
        central.stopScan()
    }

    public enum ObserverError: Error {
        case stateNotPoweredOn(CBManagerState)
    }
}

extension MessageAdvertisementObserver: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        delegate?.messageObserverDidUpdateState(String(describing: central.state))

        if central.state == .poweredOn, shouldScan {
            startScan()
        } else if shouldScan && central.state != .poweredOn && central.state != .unknown && central.state != .resetting {
            delegate?.messageObserverDidError(ObserverError.stateNotPoweredOn(central.state))
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String : Any],
        rssi RSSI: NSNumber
    ) {
        if debugLogRawAdvertisementData {
            let keys = advertisementData.keys.sorted().joined(separator: ", ")
            let types = advertisementData.map { "\($0.key)=\(type(of: $0.value))" }.sorted().joined(separator: "; ")

            // For Data fields (manufacturer data, service data, etc.) also emit a short hex prefix
            // so we can see actual payload content (e.g. whether FF FF 4D 58 or service data arrived)
            // without flooding the log on every discovery.
            //
            // Additionally detect the MX envelope magic (FF FF 4D 58) so the log line immediately
            // answers the key question for all "different advertising strategy" experiments.
            let mxMagic: [UInt8] = [0xff, 0xff, 0x4d, 0x58]

            let dataFields = advertisementData
                .compactMap { (key, value) -> String? in
                    // Top-level Data (manufacturer data, etc.)
                    if let data = value as? Data, !data.isEmpty {
                        let hex = data.prefix(24).map { String(format: "%02x", $0) }.joined()
                        let suffix = data.count > 24 ? "..." : ""
                        let hasMagic = data.range(of: Data(mxMagic)) != nil
                        let magicNote = hasMagic ? " [MX_MAGIC]" : ""
                        return "\(key)=\(hex)\(suffix) (\(data.count)B)\(magicNote)"
                    }

                    // Service data dictionary: [CBUUID: Data] — very relevant for the service-data carrier strategy
                    if key == CBAdvertisementDataServiceDataKey as String,
                       let svcData = value as? [CBUUID: Data], !svcData.isEmpty {
                        let parts = svcData.map { (uuid, data) in
                            let hex = data.prefix(24).map { String(format: "%02x", $0) }.joined()
                            let suffix = data.count > 24 ? "..." : ""
                            let hasMagic = data.range(of: Data(mxMagic)) != nil
                            let magicNote = hasMagic ? " [MX_MAGIC]" : ""
                            return "\(uuid.uuidString)=\(hex)\(suffix) (\(data.count)B)\(magicNote)"
                        }.sorted().joined(separator: " ")
                        return "serviceData={\(parts)}"
                    }

                    return nil
                }
                .sorted()
                .joined(separator: " ")

            let dataPart = dataFields.isEmpty ? "" : " data={\(dataFields)}"

            // Quick summary flag so you can instantly see from the log line (or grep) whether
            // any advertisement in this discovery carried the MX envelope magic.
            let anyMxMagic = advertisementData.values.contains { value in
                if let data = value as? Data {
                    return data.range(of: Data(mxMagic)) != nil
                }
                // Also check inside service data dictionary
                if let svcData = value as? [CBUUID: Data] {
                    return svcData.values.contains { $0.range(of: Data(mxMagic)) != nil }
                }
                return false
            }
            let magicSummary = anyMxMagic ? " mx_magic_seen=true" : ""

            print("MessageObserver: raw_advert keys=[\(keys)] types={\(types)}\(dataPart)\(magicSummary) device_id=\(peripheral.identifier.uuidString)")

            // Special prominent signal for the hybrid / service-data strategy experiments.
            // When we see the dedicated direct-MX service data UUID carrying the MX magic,
            // print an unmistakable line so it's obvious the hybrid experiment produced a positive signal.
            if let svcData = advertisementData[CBAdvertisementDataServiceDataKey as String] as? [CBUUID: Data] {
                for (uuid, data) in svcData {
                    if uuid == BLEUUID.directMxService,
                       data.range(of: Data(mxMagic)) != nil {
                        let messageIdHex = data.prefix(16).map { String(format: "%02x", $0) }.joined()

                        // Check for recent legacy MB beacons (within last 15s) to help correlate hybrid experiments.
                        let recentMBCount = recentBeacons.filter { Date().timeIntervalSince($0.at) < 15 }.count
                        let correlationNote = recentMBCount > 0 ? " recent_legacy_mb_cues=\(recentMBCount)" : ""

                        print("MessageObserver: DIRECT_MX_SERVICE_DATA_WITH_MAGIC uuid=\(uuid.uuidString) messageId=\(messageIdHex)\(correlationNote) — hybrid/service-data experiment positive signal (see raw dump above for full hex)")

                        if recentMBCount > 0 {
                            print("MessageObserver: possible_hybrid_correlation — direct MX magic arrived shortly after recent MB beacon(s). Check messageId match on the emitter side.")
                        }

                        // Prominent success signal for the hybrid strategy when both parts are observed close together.
                        if recentMBCount > 0 {
                            print("MessageObserver: HYBRID_CORRELATED messageId=\(messageIdHex) recent_legacy_mb_cues=\(recentMBCount) — legacy MB cue + direct MX service data both observed. This is the expected positive outcome for the hybrid advertising strategy.")
                        }
                    }
                }
            }
        }

        let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
        let fullAdvertisement = manufacturerData ?? Data()
        let receivedAt = UInt64(Date().timeIntervalSince1970 * 1000)
        let serviceUUIDs = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? [])
            .map(\.uuidString)
        let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String

        delegate?.messageObserverDidObserveAdvertisement(
            deviceId: peripheral.identifier.uuidString,
            rssi: RSSI.intValue,
            localName: localName,
            serviceUUIDs: serviceUUIDs,
            manufacturerDataLength: manufacturerData?.count ?? 0
        )

        switch MessageAdvertisement.decode(
            manufacturerData: manufacturerData,
            fullAdvertisement: fullAdvertisement,
            deviceId: peripheral.identifier.uuidString,
            rssi: RSSI.intValue,
            receivedAt: receivedAt
        ) {
        case .received(let event):
            delegate?.didObserveReceivedMessage(event)

        case .decodeError(let reason):
            delegate?.didObserveMessageDecodeError(
                reason,
                deviceId: peripheral.identifier.uuidString,
                rssi: RSSI.intValue
            )

        case .notMessageAdvertisement:
            if let manufacturerData,
               let beacon = LegacyBeaconAdvertisement.parse(manufacturerData: manufacturerData) {
                delegate?.messageObserverDidObserveLegacyBeacon(
                    beacon,
                    deviceId: peripheral.identifier.uuidString,
                    rssi: RSSI.intValue
                )
                rememberBeacon(beacon, rssi: RSSI.intValue)
            }
            // Connectable peripherals advertising the mob fetch service
            // are the actual GATT-fetch entry point. The MB beacon's
            // peripheral (different MAC, non-connectable) is just the
            // "I have a message" cue.
            if let serviceUUIDs = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID],
               serviceUUIDs.contains(FetchGattUUID.service) {
                maybeStartFetchOnFetchService(
                    for: peripheral,
                    rssi: RSSI.intValue
                )
            }
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        fetchInFlight[peripheral.identifier]?.handleConnected()
    }

    public func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        fetchInFlight[peripheral.identifier]?.handleFailedToConnect(error: error)
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        fetchInFlight[peripheral.identifier]?.handleDisconnected(error: error)
        fetchInFlight.removeValue(forKey: peripheral.identifier)
    }
}

extension MessageAdvertisementObserver: FetchGattClientDelegate {
    public func fetchDidComplete(
        envelope: Data,
        peripheral: CBPeripheral,
        request: FetchProtocol.Request
    ) {
        guard let pending = pendingBeacons.removeValue(forKey: peripheral.identifier) else { return }
        fetchInFlight.removeValue(forKey: peripheral.identifier)
        delegate?.messageObserverDidFetchEnvelope(
            envelope: envelope,
            fromDeviceId: peripheral.identifier.uuidString,
            beacon: pending.beacon,
            rssi: pending.rssi
        )
    }

    public func fetchDidFail(
        reason: String,
        detail: String?,
        request: FetchProtocol.Request
    ) {
        // Find the peripheral whose in-flight client matched this request.
        // We key by peripheral.identifier on the dictionary; iterate to find.
        let entry = fetchInFlight.first { _, client in client.matches(request: request) }
        if let (id, _) = entry {
            fetchInFlight.removeValue(forKey: id)
            if let pending = pendingBeacons.removeValue(forKey: id) {
                delegate?.messageObserverDidFailFetch(
                    reason: reason,
                    detail: detail,
                    fromDeviceId: id.uuidString,
                    beacon: pending.beacon
                )
            }
        }
    }
}

extension MessageAdvertisementObserver {
    fileprivate func rememberBeacon(_ beacon: LegacyBeaconAdvertisement, rssi: Int) {
        let now = Date()
        recentBeacons.append((beacon, rssi, now))
        // Cheap GC: drop entries older than dedup TTL.
        recentBeacons.removeAll { now.timeIntervalSince($0.at) >= fetchDedupTTL }
    }

    fileprivate func maybeStartFetchOnFetchService(
        for peripheral: CBPeripheral,
        rssi: Int
    ) {
        guard fetchOnBeacon else { return }
        guard fetchInFlight[peripheral.identifier] == nil else { return }
        guard let recent = recentBeacons.max(by: { $0.at < $1.at }) else { return }

        let now = Date()
        fetchedHashes = fetchedHashes.filter { now.timeIntervalSince($0.value) < fetchDedupTTL }
        if let last = fetchedHashes[recent.beacon.messageIdHash],
           now.timeIntervalSince(last) < fetchDedupTTL {
            return
        }
        fetchedHashes[recent.beacon.messageIdHash] = now

        let request = FetchProtocol.Request(
            requestId: UUID().uuidString,
            messageIdHash: recent.beacon.messageIdHash,
            requesterPeerId: requesterPeerId
        )
        let client = FetchGattClient(
            central: central,
            peripheral: peripheral,
            request: request
        )
        client.delegate = self
        fetchInFlight[peripheral.identifier] = client
        // Track which MB beacon's metadata to use when emitting the
        // resulting `received_message` event.
        pendingBeacons[peripheral.identifier] = (recent.beacon, recent.rssi)
        client.start()
    }
}

extension FetchGattClient {
    /// Used by the observer to find which in-flight client a delegate
    /// callback belongs to. Exposed via this extension so we don't have
    /// to add a `request` accessor to the public API.
    fileprivate func matches(request other: FetchProtocol.Request) -> Bool {
        return request.requestId == other.requestId
    }
}
#endif
