import Foundation

enum BLAKE2s {
    static let digestLength = 32
    static let blockLength = 64

    private static let iv: [UInt32] = [
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
        0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    ]

    private static let sigma: [[Int]] = [
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
        [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
        [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
        [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
        [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
        [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
        [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
        [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
        [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
        [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0]
    ]

    static func hash(_ data: Data) -> Data {
        var state = State()
        state.update(data)
        return state.finalize()
    }

    static func hmac(key: Data, data: Data) -> Data {
        var keyBlock: Data
        if key.count > blockLength {
            keyBlock = hash(key)
        } else {
            keyBlock = key
        }

        if keyBlock.count < blockLength {
            keyBlock.append(contentsOf: repeatElement(0, count: blockLength - keyBlock.count))
        }

        let outerKeyPad = Data(keyBlock.map { $0 ^ 0x5C })
        let innerKeyPad = Data(keyBlock.map { $0 ^ 0x36 })

        var inner = Data()
        inner.append(innerKeyPad)
        inner.append(data)

        var outer = Data()
        outer.append(outerKeyPad)
        outer.append(hash(inner))
        return hash(outer)
    }

    struct State {
        private var h = BLAKE2s.iv
        private var buffer = Data()
        private var bytesCompressed: UInt64 = 0
        private var finalized = false

        init() {
            h[0] ^= 0x01010000 ^ UInt32(digestLength)
        }

        mutating func update(_ data: Data) {
            precondition(!finalized, "BLAKE2s state already finalized")
            guard !data.isEmpty else { return }

            buffer.append(data)
            while buffer.count > blockLength {
                let block = buffer.prefix(blockLength)
                bytesCompressed += UInt64(blockLength)
                compress(block: Data(block), byteCount: bytesCompressed, isLast: false)
                buffer.removeFirst(blockLength)
            }
        }

        mutating func finalize() -> Data {
            precondition(!finalized, "BLAKE2s state already finalized")
            finalized = true

            let finalCount = bytesCompressed + UInt64(buffer.count)
            var block = buffer
            if block.count < blockLength {
                block.append(contentsOf: repeatElement(0, count: blockLength - block.count))
            }
            compress(block: block, byteCount: finalCount, isLast: true)

            var out = Data(capacity: digestLength)
            for word in h {
                out.append(UInt8(word & 0xFF))
                out.append(UInt8((word >> 8) & 0xFF))
                out.append(UInt8((word >> 16) & 0xFF))
                out.append(UInt8((word >> 24) & 0xFF))
            }
            return out.prefix(digestLength)
        }

        private mutating func compress(block: Data, byteCount: UInt64, isLast: Bool) {
            precondition(block.count == blockLength)

            let bytes = [UInt8](block)
            var m = [UInt32](repeating: 0, count: 16)
            for i in 0..<16 {
                let j = i * 4
                m[i] = UInt32(bytes[j])
                    | (UInt32(bytes[j + 1]) << 8)
                    | (UInt32(bytes[j + 2]) << 16)
                    | (UInt32(bytes[j + 3]) << 24)
            }

            var v = [UInt32](repeating: 0, count: 16)
            for i in 0..<8 {
                v[i] = h[i]
                v[i + 8] = BLAKE2s.iv[i]
            }
            v[12] ^= UInt32(byteCount & 0xFFFF_FFFF)
            v[13] ^= UInt32(byteCount >> 32)
            if isLast {
                v[14] = ~v[14]
            }

            for round in 0..<10 {
                let s = BLAKE2s.sigma[round]
                Self.g(&v, 0, 4, 8, 12, m[s[0]], m[s[1]])
                Self.g(&v, 1, 5, 9, 13, m[s[2]], m[s[3]])
                Self.g(&v, 2, 6, 10, 14, m[s[4]], m[s[5]])
                Self.g(&v, 3, 7, 11, 15, m[s[6]], m[s[7]])
                Self.g(&v, 0, 5, 10, 15, m[s[8]], m[s[9]])
                Self.g(&v, 1, 6, 11, 12, m[s[10]], m[s[11]])
                Self.g(&v, 2, 7, 8, 13, m[s[12]], m[s[13]])
                Self.g(&v, 3, 4, 9, 14, m[s[14]], m[s[15]])
            }

            for i in 0..<8 {
                h[i] ^= v[i] ^ v[i + 8]
            }
        }

        private static func g(
            _ v: inout [UInt32],
            _ a: Int,
            _ b: Int,
            _ c: Int,
            _ d: Int,
            _ x: UInt32,
            _ y: UInt32
        ) {
            v[a] = v[a] &+ v[b] &+ x
            v[d] = (v[d] ^ v[a]).rotatedRight(by: 16)
            v[c] = v[c] &+ v[d]
            v[b] = (v[b] ^ v[c]).rotatedRight(by: 12)
            v[a] = v[a] &+ v[b] &+ y
            v[d] = (v[d] ^ v[a]).rotatedRight(by: 8)
            v[c] = v[c] &+ v[d]
            v[b] = (v[b] ^ v[c]).rotatedRight(by: 7)
        }
    }
}

private extension UInt32 {
    func rotatedRight(by amount: UInt32) -> UInt32 {
        (self >> amount) | (self << (32 - amount))
    }
}
