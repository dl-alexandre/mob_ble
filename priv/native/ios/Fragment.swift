import Foundation

/// Application-level fragmentation per `docs/WIRE_FORMAT.md` §4.
/// Fragment payload layout:
///   <<orig_msg_id::u32-LE, index::u8, total::u8, chunk>>
public enum Fragment {
    public static let headerSize = 6
    public static let maxFragments = 255

    public struct Parts: Sendable {
        public let origMsgId: UInt32
        public let chunks: [Data]
    }

    /// Splits a payload into fragment packets. The per-fragment `msg_id` is a
    /// receiver-side dedup hint; mobile clients may use any unique value.
    /// (The Elixir node uses `:erlang.phash2({orig, index})`, which is not
    /// reproducible cross-platform — see WIRE_VECTORS.md.)
    public static func fragment(
        origMsgId: UInt32,
        payload: Data,
        maxChunkSize: Int = 185,
        ttl: UInt8 = 64,
        flags: PacketFlags = []
    ) -> [Packet] {
        precondition(maxChunkSize > 0)
        precondition(maxChunkSize <= Int(UInt16.max) - headerSize)

        var chunks: [Data] = []
        if payload.isEmpty {
            chunks = []
        } else {
            var i = payload.startIndex
            while i < payload.endIndex {
                let end = min(i + maxChunkSize, payload.endIndex)
                chunks.append(payload.subdata(in: i..<end))
                i = end
            }
        }
        precondition(chunks.count <= maxFragments, "fragment count exceeds 255")

        let total = UInt8(chunks.count)
        return chunks.enumerated().map { (index, chunk) in
            var fragPayload = Data(capacity: headerSize + chunk.count)
            fragPayload.appendLE(origMsgId)
            fragPayload.append(UInt8(index))
            fragPayload.append(total)
            fragPayload.append(chunk)

            return Packet(
                type: .fragment,
                flags: flags,
                ttl: ttl,
                msgId: UInt32.random(in: 1...UInt32.max),
                payload: fragPayload
            )
        }
    }

    /// Reassembles a complete set of fragment packets back into the original
    /// payload. Returns nil if the set is incomplete or inconsistent.
    public static func reassemble(_ fragments: [Packet]) -> Parts? {
        guard !fragments.isEmpty else { return nil }

        var origId: UInt32?
        var expectedTotal: UInt8?
        var parts: [UInt8: Data] = [:]

        for f in fragments {
            guard f.type == .fragment, f.payload.count >= headerSize else { return nil }
            let p = f.payload
            let oid = p.readLE(UInt32.self, at: p.startIndex)
            let idx = p[p.startIndex + 4]
            let total = p[p.startIndex + 5]
            let chunk = p.subdata(in: (p.startIndex + headerSize)..<p.endIndex)

            if let existing = origId, existing != oid { return nil }
            if let existing = expectedTotal, existing != total { return nil }
            origId = oid
            expectedTotal = total
            parts[idx] = chunk
        }

        guard let origMsgId = origId, let total = expectedTotal else { return nil }
        guard parts.count == Int(total) else { return nil }

        let ordered = (0..<total).map { parts[$0] ?? Data() }
        return Parts(origMsgId: origMsgId, chunks: ordered)
    }
}
