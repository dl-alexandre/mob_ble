import Foundation

public struct MessageEnvelope: Equatable {
    public static let currentVersion: UInt8 = 1
    public static let maxTTL: UInt8 = 16
    public static let maxPeerIDSize = 32
    public static let maxPayloadTypeSize = 16
    public static let maxPayloadSize = 4096

    public var envelopeVersion: UInt8
    public var messageId: Data
    public var senderPeerId: String
    public var recipientPeerId: String?
    public var createdAt: UInt64
    public var ttl: UInt8
    public var payloadType: String
    public var payload: Data
    public var capabilityRequirements: UInt8

    public enum DecodeError: String, Error, Equatable {
        case missingMagic = "missing_magic"
        case invalidEnvelopeVersion = "invalid_envelope_version"
        case unsupportedEnvelopeVersion = "unsupported_envelope_version"
        case invalidFlags = "invalid_flags"
        case invalidMessageId = "invalid_message_id"
        case invalidSenderPeerId = "invalid_sender_peer_id"
        case invalidRecipientPeerId = "invalid_recipient_peer_id"
        case invalidCreatedAt = "invalid_created_at"
        case invalidTtl = "invalid_ttl"
        case invalidPayloadType = "invalid_payload_type"
        case invalidCapabilityRequirements = "invalid_capability_requirements"
        case payloadTooLarge = "payload_too_large"
        case truncatedEnvelope = "truncated_envelope"
    }

    public enum EncodeError: String, Error, Equatable {
        case invalidMessageId = "invalid_message_id"
        case invalidSenderPeerId = "invalid_sender_peer_id"
        case invalidRecipientPeerId = "invalid_recipient_peer_id"
        case invalidTtl = "invalid_ttl"
        case invalidPayloadType = "invalid_payload_type"
        case invalidCapabilityRequirements = "invalid_capability_requirements"
        case payloadTooLarge = "payload_too_large"
    }

    public static func buildV1(
        messageId: Data,
        senderPeerId: String,
        recipientPeerId: String? = nil,
        createdAt: UInt64,
        ttl: UInt8 = 1,
        payloadType: String = "TX",
        payload: Data,
        capabilityRequirements: UInt8 = 0
    ) throws -> Data {
        guard messageId.count == 16 else { throw EncodeError.invalidMessageId }
        guard ttl <= maxTTL else { throw EncodeError.invalidTtl }
        guard capabilityRequirements == 0 else { throw EncodeError.invalidCapabilityRequirements }

        let senderBytes = Data(senderPeerId.utf8)
        guard (1...maxPeerIDSize).contains(senderBytes.count) else {
            throw EncodeError.invalidSenderPeerId
        }

        let recipientBytes = recipientPeerId.map { Data($0.utf8) } ?? Data()
        guard recipientBytes.isEmpty || (1...maxPeerIDSize).contains(recipientBytes.count) else {
            throw EncodeError.invalidRecipientPeerId
        }

        let payloadTypeBytes = Data(payloadType.utf8)
        guard (1...maxPayloadTypeSize).contains(payloadTypeBytes.count) else {
            throw EncodeError.invalidPayloadType
        }
        guard payload.count <= maxPayloadSize else { throw EncodeError.payloadTooLarge }

        var out = Data()
        out.append(UInt8(ascii: "M"))
        out.append(UInt8(ascii: "X"))
        out.append(currentVersion)
        out.append(0x00)
        out.append(messageId)
        appendUInt64BE(createdAt, to: &out)
        out.append(ttl)
        out.append(UInt8(senderBytes.count))
        out.append(senderBytes)
        out.append(UInt8(recipientBytes.count))
        out.append(recipientBytes)
        out.append(UInt8(payloadTypeBytes.count))
        out.append(payloadTypeBytes)
        out.append(capabilityRequirements)
        appendUInt16BE(UInt16(payload.count), to: &out)
        out.append(payload)
        return out
    }

    private static func appendUInt64BE(_ value: UInt64, to data: inout Data) {
        for shift in stride(from: 56, through: 0, by: -8) {
            data.append(UInt8((value >> UInt64(shift)) & 0xFF))
        }
    }

    private static func appendUInt16BE(_ value: UInt16, to data: inout Data) {
        data.append(UInt8((value >> 8) & 0xFF))
        data.append(UInt8(value & 0xFF))
    }

    public static func parse(_ data: Data) -> Result<MessageEnvelope, DecodeError> {
        var cursor = Cursor(data)

        guard let m = cursor.readByte(), let x = cursor.readByte(),
              m == UInt8(ascii: "M"), x == UInt8(ascii: "X") else {
            return .failure(.missingMagic)
        }

        guard let version = cursor.readByte() else { return .failure(.truncatedEnvelope) }
        if version == 0 { return .failure(.invalidEnvelopeVersion) }
        if version != currentVersion { return .failure(.unsupportedEnvelopeVersion) }

        guard let flags = cursor.readByte() else { return .failure(.truncatedEnvelope) }
        guard flags == 0 else { return .failure(.invalidFlags) }

        guard let messageId = cursor.readData(count: 16) else { return .failure(.truncatedEnvelope) }
        guard messageId.count == 16 else { return .failure(.invalidMessageId) }

        guard let createdAt = cursor.readUInt64BE() else { return .failure(.truncatedEnvelope) }

        guard let ttl = cursor.readByte() else { return .failure(.truncatedEnvelope) }
        guard ttl <= maxTTL else { return .failure(.invalidTtl) }

        guard let senderBytes = cursor.readLengthPrefixed8() else {
            return .failure(.invalidSenderPeerId)
        }
        guard (1...maxPeerIDSize).contains(senderBytes.count),
              let senderPeerId = String(data: senderBytes, encoding: .utf8) else {
            return .failure(.invalidSenderPeerId)
        }

        guard let recipientBytes = cursor.readLengthPrefixed8() else {
            return .failure(.invalidRecipientPeerId)
        }
        let recipientPeerId: String?
        if recipientBytes.isEmpty {
            recipientPeerId = nil
        } else {
            guard (1...maxPeerIDSize).contains(recipientBytes.count),
                  let decoded = String(data: recipientBytes, encoding: .utf8) else {
                return .failure(.invalidRecipientPeerId)
            }
            recipientPeerId = decoded
        }

        guard let payloadTypeBytes = cursor.readLengthPrefixed8() else {
            return .failure(.invalidPayloadType)
        }
        guard (1...maxPayloadTypeSize).contains(payloadTypeBytes.count),
              let payloadType = String(data: payloadTypeBytes, encoding: .utf8) else {
            return .failure(.invalidPayloadType)
        }

        guard let capabilityRequirements = cursor.readByte() else {
            return .failure(.truncatedEnvelope)
        }

        guard let payload = cursor.readLengthPrefixed16BE() else {
            return .failure(.truncatedEnvelope)
        }
        guard payload.count <= maxPayloadSize else { return .failure(.payloadTooLarge) }

        return .success(
            MessageEnvelope(
                envelopeVersion: version,
                messageId: messageId,
                senderPeerId: senderPeerId,
                recipientPeerId: recipientPeerId,
                createdAt: createdAt,
                ttl: ttl,
                payloadType: payloadType,
                payload: payload,
                capabilityRequirements: capabilityRequirements
            )
        )
    }
}

private struct Cursor {
    let data: Data
    var offset: Data.Index

    init(_ data: Data) {
        self.data = data
        self.offset = data.startIndex
    }

    mutating func readByte() -> UInt8? {
        guard offset < data.endIndex else { return nil }
        defer { offset = data.index(after: offset) }
        return data[offset]
    }

    mutating func readData(count: Int) -> Data? {
        guard count >= 0 else { return nil }
        let end = data.index(offset, offsetBy: count, limitedBy: data.endIndex)
        guard let end else { return nil }
        let out = data[offset..<end]
        offset = end
        return Data(out)
    }

    mutating func readUInt64BE() -> UInt64? {
        guard let bytes = readData(count: 8), bytes.count == 8 else { return nil }
        return bytes.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }

    mutating func readLengthPrefixed8() -> Data? {
        guard let length = readByte() else { return nil }
        return readData(count: Int(length))
    }

    mutating func readLengthPrefixed16BE() -> Data? {
        guard let hi = readByte(), let lo = readByte() else { return nil }
        let length = (Int(hi) << 8) | Int(lo)
        return readData(count: length)
    }
}
