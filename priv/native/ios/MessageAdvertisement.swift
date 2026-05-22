import Foundation

public struct ReceivedMessageEvent: Equatable {
    public var messageId: Data
    public var senderPeerId: String
    public var recipientPeerId: String?
    public var receivedDeviceId: String
    public var receivedAt: UInt64
    public var rssi: Int
    public var envelope: MessageEnvelope
    public var rawTransportMetadata: RawTransportMetadata

    public struct RawTransportMetadata: Equatable {
        public var transport: String
        public var sourceEvent: String
        public var receivedDeviceId: String
        public var advertisement: Data
        public var messagePayload: Data
        public var manufacturerData: Data
        public var companyIdentifier: UInt16
        public var adType: UInt8
    }

    public func jsonLine() -> String {
        let recipient: Any = recipientPeerId.map { $0 as Any } ?? NSNull()
        let object: [String: Any] = [
            "v": 1,
            "event": "received_message",
            "message_id": messageId.base64EncodedString(),
            "sender_peer_id": senderPeerId,
            "recipient_peer_id": recipient,
            "received_device_id": receivedDeviceId,
            "received_at": receivedAt,
            "rssi": rssi,
            "envelope": rawTransportMetadata.messagePayload.base64EncodedString(),
            "raw_transport_metadata": [
                "transport": rawTransportMetadata.transport,
                "source_event": rawTransportMetadata.sourceEvent,
                "received_device_id": rawTransportMetadata.receivedDeviceId,
                "advertisement": rawTransportMetadata.advertisement.base64EncodedString(),
                "message_payload": rawTransportMetadata.messagePayload.base64EncodedString(),
                "manufacturer_data": rawTransportMetadata.manufacturerData.base64EncodedString(),
                "company_identifier": rawTransportMetadata.companyIdentifier,
                "ad_type": rawTransportMetadata.adType
            ]
        ]

        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let line = String(data: data, encoding: .utf8) else {
            preconditionFailure("received_message JSON line must be serializable")
        }

        return line
    }
}

public struct MessageAdvertisementDecodeErrorEvent: Equatable {
    public var reason: String
    public var deviceId: String
    public var rssi: Int

    public init(reason: String, deviceId: String, rssi: Int) {
        self.reason = reason
        self.deviceId = deviceId
        self.rssi = rssi
    }

    public func jsonLine() -> String {
        let object: [String: Any] = [
            "v": 1,
            "event": "error",
            "kind": "unknown",
            "detail": reason,
            "device_id": deviceId,
            "rssi": rssi
        ]

        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let line = String(data: data, encoding: .utf8) else {
            preconditionFailure("message advertisement error JSON line must be serializable")
        }

        return line
    }
}

public enum MessageAdvertisementDecodeResult: Equatable {
    case received(ReceivedMessageEvent)
    case notMessageAdvertisement
    case decodeError(reason: String)
}

public enum MessageAdvertisement {
    public static let companyIdentifier: UInt16 = 0xFFFF
    public static let manufacturerSpecificDataAdType: UInt8 = 0xFF

    public static func decode(
        manufacturerData: Data?,
        fullAdvertisement: Data,
        deviceId: String,
        rssi: Int,
        receivedAt: UInt64
    ) -> MessageAdvertisementDecodeResult {
        guard let manufacturerData, manufacturerData.count >= 4 else {
            return .notMessageAdvertisement
        }

        let company = UInt16(manufacturerData[manufacturerData.startIndex]) |
            (UInt16(manufacturerData[manufacturerData.index(after: manufacturerData.startIndex)]) << 8)

        guard company == companyIdentifier else {
            return .notMessageAdvertisement
        }

        let payloadStart = manufacturerData.index(manufacturerData.startIndex, offsetBy: 2)
        let payload = Data(manufacturerData[payloadStart..<manufacturerData.endIndex])

        guard payload.starts(with: Data([UInt8(ascii: "M"), UInt8(ascii: "X")])) else {
            return .notMessageAdvertisement
        }

        switch MessageEnvelope.parse(payload) {
        case .success(let envelope):
            return .received(
                ReceivedMessageEvent(
                    messageId: envelope.messageId,
                    senderPeerId: envelope.senderPeerId,
                    recipientPeerId: envelope.recipientPeerId,
                    receivedDeviceId: deviceId,
                    receivedAt: receivedAt,
                    rssi: rssi,
                    envelope: envelope,
                    rawTransportMetadata: .init(
                        transport: "ble_advertisement",
                        sourceEvent: "advertisement_received",
                        receivedDeviceId: deviceId,
                        advertisement: fullAdvertisement,
                        messagePayload: payload,
                        manufacturerData: manufacturerData,
                        companyIdentifier: company,
                        adType: manufacturerSpecificDataAdType
                    )
                )
            )

        case .failure(let reason):
            return .decodeError(reason: "message_advertisement_decode_error:\(reason.rawValue)")
        }
    }
}
