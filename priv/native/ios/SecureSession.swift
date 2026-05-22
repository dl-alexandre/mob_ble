import Foundation

public enum SecureSessionError: Error, Equatable {
    case trailingBytes
}

public enum SecureSessionEvent: Equatable {
    case outgoingFrame(Data)
    case established(remoteStaticKey: Data?)
    case applicationFrame(Data)
}

/// Bridges mob frames to the Noise transport.
///
/// Handshake messages travel as `MXN1`-wrapped `control` packets. Once Noise is
/// established, encrypted `data` packet payloads are decrypted and re-emitted as
/// normal mob frames with the `.encrypted` flag cleared.
public final class SecureSession {
    public let noiseSession: NoiseSession

    public var isEstablished: Bool {
        noiseSession.isEstablished
    }

    public var remoteStaticKey: Data? {
        noiseSession.remoteStaticKey
    }

    public init(role: NoiseRole = .initiator) {
        self.noiseSession = MobNoiseSession(role: role)
    }

    public init(noiseSession: NoiseSession) {
        self.noiseSession = noiseSession
    }

    public func startHandshake(msgId: UInt32, ttl: UInt8 = 64) throws -> Data? {
        guard let message = try noiseSession.handshakeSend() else {
            return nil
        }
        return try Self.handshakeFrame(message, msgId: msgId, ttl: ttl)
    }

    public func receive(frame: Data, replyMsgId: UInt32, replyTTL: UInt8 = 64) throws -> [SecureSessionEvent] {
        let (packet, rest) = try Frame.decode(frame)
        guard rest.isEmpty else { throw SecureSessionError.trailingBytes }

        if packet.type == .control, let handshakeMessage = try? unwrapHandshakePayload(packet.payload) {
            return try receiveHandshake(handshakeMessage, replyMsgId: replyMsgId, replyTTL: replyTTL)
        }

        if packet.flags.contains(.encrypted) {
            let decryptedFrame = try decrypt(packet)
            return [.applicationFrame(decryptedFrame)]
        }

        return [.applicationFrame(frame)]
    }

    public func encrypt(packet: Packet) throws -> Data {
        guard noiseSession.isEstablished else {
            throw NoiseError.handshakeIncomplete
        }

        var encryptedPacket = packet
        encryptedPacket.payload = try noiseSession.encrypt(packet.payload)
        encryptedPacket.flags.insert(.encrypted)
        return try Frame.encode(encryptedPacket)
    }

    private func receiveHandshake(_ message: Data, replyMsgId: UInt32, replyTTL: UInt8) throws -> [SecureSessionEvent] {
        try noiseSession.handshakeReceive(message)

        var events: [SecureSessionEvent] = []
        if let reply = try noiseSession.handshakeSend() {
            events.append(.outgoingFrame(try Self.handshakeFrame(reply, msgId: replyMsgId, ttl: replyTTL)))
        }
        if noiseSession.isEstablished {
            events.append(.established(remoteStaticKey: noiseSession.remoteStaticKey))
        }
        return events
    }

    private func decrypt(_ packet: Packet) throws -> Data {
        guard noiseSession.isEstablished else {
            throw NoiseError.handshakeIncomplete
        }

        var plaintextPacket = packet
        plaintextPacket.payload = try noiseSession.decrypt(packet.payload)
        plaintextPacket.flags.remove(.encrypted)
        return try Frame.encode(plaintextPacket)
    }

    private static func handshakeFrame(_ message: Data, msgId: UInt32, ttl: UInt8) throws -> Data {
        let packet = Packet(
            type: .control,
            ttl: ttl,
            msgId: msgId,
            payload: wrapHandshakePayload(message)
        )
        return try Frame.encode(packet)
    }
}
