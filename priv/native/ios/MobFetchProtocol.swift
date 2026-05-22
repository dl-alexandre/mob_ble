import Foundation

/// Swift mirror of Android's `FetchProtocol`. Same wire format —
/// MFQ/MFR magic, version 1, length-prefixed strings, big-endian payload
/// size — so the iOS requester can talk to the Android responder
/// implemented in the Android fetch Gatt.
public enum FetchProtocol {
    public static let version: UInt8 = 1
    public static let statusOK: UInt8 = 0
    public static let statusNotFound: UInt8 = 1
    public static let statusInvalidRequest: UInt8 = 2

    private static let requestMagic = Data([0x4D, 0x46, 0x51]) // "MFQ"
    private static let responseMagic = Data([0x4D, 0x46, 0x52]) // "MFR"

    public struct Request: Equatable {
        public var requestId: String
        public var messageIdHash: Data
        public var requesterPeerId: String?

        public init(requestId: String, messageIdHash: Data, requesterPeerId: String?) {
            self.requestId = requestId
            self.messageIdHash = messageIdHash
            self.requesterPeerId = requesterPeerId
        }
    }

    public struct Response: Equatable {
        public var requestId: String
        public var messageIdHash: Data
        public var status: UInt8
        public var envelope: Data?
        public var reason: String?

        public init(
            requestId: String,
            messageIdHash: Data,
            status: UInt8,
            envelope: Data?,
            reason: String?
        ) {
            self.requestId = requestId
            self.messageIdHash = messageIdHash
            self.status = status
            self.envelope = envelope
            self.reason = reason
        }
    }

    public static func encodeRequest(_ request: Request) -> Data? {
        guard !request.requestId.isEmpty,
              request.messageIdHash.count == 8 else {
            return nil
        }
        let requestId = Data(request.requestId.utf8)
        let requester = request.requesterPeerId.map { Data($0.utf8) } ?? Data()
        guard requestId.count <= 255, requester.count <= 255 else { return nil }

        var out = Data()
        out.append(requestMagic)
        out.append(version)
        out.append(UInt8(requestId.count))
        out.append(requestId)
        out.append(request.messageIdHash)
        out.append(UInt8(requester.count))
        out.append(requester)
        return out
    }

    /// Decode a peer's MFQ Request. Used by the responder
    /// (FetchGattResponder) to parse what a client wrote.
    public static func decodeRequest(_ bytes: Data) -> Request? {
        guard bytes.count >= 3 + 1 + 1 + 8 + 1 else { return nil }
        let start = bytes.startIndex
        guard bytes[start..<start.advanced(by: 3)] == requestMagic else { return nil }
        guard bytes[start.advanced(by: 3)] == version else { return nil }

        var offset = start.advanced(by: 4)
        let requestIdLength = Int(bytes[offset])
        offset = offset.advanced(by: 1)
        guard offset.advanced(by: requestIdLength + 8 + 1) <= bytes.endIndex else { return nil }

        let requestIdRange = offset..<offset.advanced(by: requestIdLength)
        guard let requestId = String(data: bytes[requestIdRange], encoding: .utf8),
              !requestId.isEmpty else { return nil }
        offset = offset.advanced(by: requestIdLength)

        let hash = Data(bytes[offset..<offset.advanced(by: 8)])
        offset = offset.advanced(by: 8)

        let requesterLength = Int(bytes[offset])
        offset = offset.advanced(by: 1)
        guard offset.advanced(by: requesterLength) <= bytes.endIndex else { return nil }

        let requesterPeerId: String?
        if requesterLength == 0 {
            requesterPeerId = nil
        } else {
            let requesterRange = offset..<offset.advanced(by: requesterLength)
            requesterPeerId = String(data: bytes[requesterRange], encoding: .utf8)
        }

        return Request(
            requestId: requestId,
            messageIdHash: hash,
            requesterPeerId: requesterPeerId
        )
    }

    /// Encode an MFR Response. Used by the responder to produce the
    /// bytes a client will read.
    public static func encodeResponse(_ response: Response) -> Data {
        precondition(!response.requestId.isEmpty)
        precondition(response.messageIdHash.count == 8)
        let requestId = Data(response.requestId.utf8)
        let payload = response.envelope
            ?? response.reason.map { Data($0.utf8) }
            ?? Data()
        precondition(requestId.count <= 255)
        precondition(payload.count <= 0xFFFF)

        var out = Data()
        out.append(responseMagic)
        out.append(version)
        out.append(response.status)
        out.append(UInt8(requestId.count))
        out.append(requestId)
        out.append(response.messageIdHash)
        // Big-endian 16-bit payload length — matches Android's
        // ByteBuffer.allocate(2).order(BIG_ENDIAN).putShort(...).
        out.append(UInt8((payload.count >> 8) & 0xFF))
        out.append(UInt8(payload.count & 0xFF))
        out.append(payload)
        return out
    }

    public static func decodeResponse(_ bytes: Data) -> Response? {
        guard bytes.count >= 3 + 1 + 1 + 1 + 8 + 2 else { return nil }
        let start = bytes.startIndex
        guard bytes[start..<start.advanced(by: 3)] == responseMagic else { return nil }
        guard bytes[start.advanced(by: 3)] == version else { return nil }
        let status = bytes[start.advanced(by: 4)]
        guard status <= statusInvalidRequest else { return nil }

        var offset = start.advanced(by: 5)
        let requestIdLength = Int(bytes[offset])
        offset = offset.advanced(by: 1)
        guard offset.advanced(by: requestIdLength + 8 + 2) <= bytes.endIndex else { return nil }

        let requestIdRange = offset..<offset.advanced(by: requestIdLength)
        guard let requestId = String(data: bytes[requestIdRange], encoding: .utf8),
              !requestId.isEmpty else { return nil }
        offset = offset.advanced(by: requestIdLength)

        let hash = Data(bytes[offset..<offset.advanced(by: 8)])
        offset = offset.advanced(by: 8)

        let payloadLength =
            (Int(bytes[offset]) << 8) | Int(bytes[offset.advanced(by: 1)])
        offset = offset.advanced(by: 2)
        guard offset.advanced(by: payloadLength) <= bytes.endIndex else { return nil }
        let payload = Data(bytes[offset..<offset.advanced(by: payloadLength)])

        if status == statusOK {
            return Response(
                requestId: requestId,
                messageIdHash: hash,
                status: status,
                envelope: payload,
                reason: nil
            )
        } else {
            let reason = String(data: payload, encoding: .utf8) ?? ""
            return Response(
                requestId: requestId,
                messageIdHash: hash,
                status: status,
                envelope: nil,
                reason: reason
            )
        }
    }
}
