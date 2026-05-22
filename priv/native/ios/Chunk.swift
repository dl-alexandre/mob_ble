import Foundation

/// BLE chunk layer per `docs/WIRE_FORMAT.md` §2.
/// Header: "MXB1" || stream_id::u32-BE || seq::u16-BE || total::u16-BE  (12 bytes)
public enum Chunk {
    public static let magic: [UInt8] = [0x4D, 0x58, 0x42, 0x31]  // "MXB1"
    public static let headerSize = 12

    public static func encode(frame: Data, mtu: Int, streamId: UInt32) -> [Data] {
        precondition(mtu > headerSize, "MTU must exceed chunk header size (12 bytes)")
        let chunkSize = max(1, mtu - headerSize)

        let chunks: [Data]
        if frame.isEmpty {
            chunks = [Data()]
        } else {
            var pieces: [Data] = []
            var i = frame.startIndex
            while i < frame.endIndex {
                let end = min(i + chunkSize, frame.endIndex)
                pieces.append(frame.subdata(in: i..<end))
                i = end
            }
            chunks = pieces
        }

        let total = UInt16(chunks.count)
        return chunks.enumerated().map { (seq, payload) in
            var out = Data(capacity: headerSize + payload.count)
            out.append(contentsOf: magic)
            out.appendBE(streamId)
            out.appendBE(UInt16(seq))
            out.appendBE(total)
            out.append(payload)
            return out
        }
    }
}

/// Reassembles MXB1 chunks into mob frames. Keyed by (peerId, streamId).
public final class ChunkReassembler {
    public struct Key: Hashable, Sendable {
        public let peerId: String
        public let streamId: UInt32
        public init(peerId: String, streamId: UInt32) {
            self.peerId = peerId
            self.streamId = streamId
        }
    }

    private struct Entry {
        var total: UInt16
        var parts: [UInt16: Data]
    }

    private var pending: [Key: Entry] = [:]

    public init() {}

    /// Pushes a chunk. Returns the fully reassembled frame iff this chunk completes one.
    /// A chunk that does NOT begin with the MXB1 magic is returned as-is (back-compat path).
    public func push(peerId: String, chunk: Data) -> Data? {
        guard chunk.count >= Chunk.headerSize else { return nil }

        let magicMatch = chunk.starts(with: Chunk.magic)
        guard magicMatch else { return chunk }

        let streamId = chunk.readBE(UInt32.self, at: chunk.startIndex + 4)
        let seq = chunk.readBE(UInt16.self, at: chunk.startIndex + 8)
        let total = chunk.readBE(UInt16.self, at: chunk.startIndex + 10)
        guard total > 0, seq < total else { return nil }

        let payload = chunk.subdata(in: (chunk.startIndex + Chunk.headerSize)..<chunk.endIndex)
        let key = Key(peerId: peerId, streamId: streamId)

        var entry = pending[key] ?? Entry(total: total, parts: [:])
        if entry.total != total {
            entry = Entry(total: total, parts: [:])
        }
        entry.parts[seq] = payload

        guard entry.parts.count == Int(total) else {
            pending[key] = entry
            return nil
        }

        pending.removeValue(forKey: key)
        var assembled = Data()
        for i in 0..<total {
            assembled.append(entry.parts[i] ?? Data())
        }
        return assembled
    }

    public func forget(peerId: String) {
        pending = pending.filter { $0.key.peerId != peerId }
    }
}

// MARK: - Data big-endian helpers (chunk header only)

extension Data {
    mutating func appendBE(_ value: UInt16) {
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8(value & 0xFF))
    }

    mutating func appendBE(_ value: UInt32) {
        append(UInt8((value >> 24) & 0xFF))
        append(UInt8((value >> 16) & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8(value & 0xFF))
    }

    func readBE<T: FixedWidthInteger & UnsignedInteger>(_: T.Type, at offset: Int) -> T {
        var value: T = 0
        for i in 0..<MemoryLayout<T>.size {
            value = (value << 8) | T(self[offset + i])
        }
        return value
    }
}
