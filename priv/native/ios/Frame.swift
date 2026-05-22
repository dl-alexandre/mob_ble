import Foundation

public enum PacketType: UInt8, Sendable {
    case data = 0x01
    case ack = 0x02
    case gossip = 0x03
    case control = 0x04
    case fragment = 0x05
}

public struct PacketFlags: OptionSet, Sendable {
    public let rawValue: UInt8
    public init(rawValue: UInt8) { self.rawValue = rawValue }

    public static let encrypted     = PacketFlags(rawValue: 0x01)
    public static let fragmented    = PacketFlags(rawValue: 0x02)
    public static let ackRequested  = PacketFlags(rawValue: 0x04)
}

public struct Packet: Sendable, Equatable {
    public var version: UInt8
    public var type: PacketType
    public var flags: PacketFlags
    public var ttl: UInt8
    public var msgId: UInt32
    public var payload: Data

    public init(
        version: UInt8 = 0x01,
        type: PacketType,
        flags: PacketFlags = [],
        ttl: UInt8 = 64,
        msgId: UInt32,
        payload: Data
    ) {
        self.version = version
        self.type = type
        self.flags = flags
        self.ttl = ttl
        self.msgId = msgId
        self.payload = payload
    }
}

public enum FrameError: Error, Equatable {
    case payloadTooLarge
    case insufficientBytes
    case unknownType(UInt8)
    case checksumMismatch
}

public enum Frame {
    public static let headerSize = 10
    public static let checksumSize = 2
    public static let overhead = headerSize + checksumSize

    public static func encode(_ packet: Packet) throws -> Data {
        guard packet.payload.count <= UInt16.max else {
            throw FrameError.payloadTooLarge
        }
        let payloadLen = UInt16(packet.payload.count)

        var frame = Data(capacity: overhead + Int(payloadLen))
        frame.append(packet.version)
        frame.append(packet.type.rawValue)
        frame.append(packet.flags.rawValue)
        frame.append(packet.ttl)
        frame.appendLE(payloadLen)
        frame.appendLE(packet.msgId)
        frame.append(packet.payload)

        let crc = crc16Truncated(of: frame)
        frame.appendLE(crc)
        return frame
    }

    public static func decode(_ data: Data) throws -> (Packet, Data) {
        guard data.count >= overhead else { throw FrameError.insufficientBytes }

        let version = data[data.startIndex + 0]
        let typeByte = data[data.startIndex + 1]
        let flagsByte = data[data.startIndex + 2]
        let ttl = data[data.startIndex + 3]
        let payloadLen = data.readLE(UInt16.self, at: data.startIndex + 4)
        let msgId = data.readLE(UInt32.self, at: data.startIndex + 6)

        let totalNeeded = headerSize + Int(payloadLen) + checksumSize
        guard data.count >= totalNeeded else { throw FrameError.insufficientBytes }

        guard let type = PacketType(rawValue: typeByte) else {
            throw FrameError.unknownType(typeByte)
        }

        let payloadStart = data.startIndex + headerSize
        let payloadEnd = payloadStart + Int(payloadLen)
        let payload = data.subdata(in: payloadStart..<payloadEnd)

        let frameWithoutCrc = data.subdata(in: data.startIndex..<payloadEnd)
        let expected = crc16Truncated(of: frameWithoutCrc)
        let actual = data.readLE(UInt16.self, at: payloadEnd)
        guard expected == actual else { throw FrameError.checksumMismatch }

        let packet = Packet(
            version: version,
            type: type,
            flags: PacketFlags(rawValue: flagsByte),
            ttl: ttl,
            msgId: msgId,
            payload: payload
        )

        let rest = data.subdata(in: (payloadEnd + checksumSize)..<data.endIndex)
        return (packet, rest)
    }
}

/// CRC-32 (IEEE) truncated to the low 16 bits, matching `:erlang.crc32/1 &&& 0xFFFF`.
@inlinable
public func crc16Truncated(of data: Data) -> UInt16 {
    var crc: UInt32 = 0xFFFFFFFF
    for byte in data {
        crc ^= UInt32(byte)
        for _ in 0..<8 {
            let lsb = crc & 1
            let mask: UInt32 = (lsb == 0) ? 0 : 0xEDB88320
            crc = (crc >> 1) ^ mask
        }
    }
    crc ^= 0xFFFFFFFF
    return UInt16(crc & 0xFFFF)
}

// MARK: - Data little-endian helpers

extension Data {
    mutating func appendLE(_ value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
    }

    mutating func appendLE(_ value: UInt32) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8((value >> 16) & 0xFF))
        append(UInt8((value >> 24) & 0xFF))
    }

    func readLE<T: FixedWidthInteger & UnsignedInteger>(_: T.Type, at offset: Int) -> T {
        var value: T = 0
        for i in 0..<MemoryLayout<T>.size {
            value |= T(self[offset + i]) << (8 * i)
        }
        return value
    }
}

