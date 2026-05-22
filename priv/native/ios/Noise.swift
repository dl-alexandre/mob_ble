import Foundation
import CryptoKit

/// Noise XX handshake for mob per `docs/WIRE_FORMAT.md` §5.
///
/// Implements `Noise_XX_25519_ChaChaPoly_BLAKE2s` directly against
/// CryptoKit's X25519 and ChaChaPoly primitives, with a local BLAKE2s/HKDF
/// implementation. Handshake messages are raw Noise bytes; callers still wrap
/// them with `MXN1` before placing them in mob `control` packet payloads.
public enum NoiseProtocol {
    public static let name = "Noise_XX_25519_ChaChaPoly_BLAKE2s"
    public static let handshakeTag: [UInt8] = [0x4D, 0x58, 0x4E, 0x31]  // "MXN1"
}

public enum NoiseRole: Sendable {
    case initiator
    case responder
}

public enum NoiseError: Error, Sendable {
    case notImplemented
    case handshakeIncomplete
    case handshakeAlreadyComplete
    case invalidHandshakeState
    case invalidHandshakeMessage
    case invalidKey
    case nonceOverflow
    case decryptFailed
    case unexpectedTag
}

public protocol NoiseSession: AnyObject {
    var isEstablished: Bool { get }
    var remoteStaticKey: Data? { get }

    /// Produces the next outbound handshake message, or returns nil if
    /// the handshake is complete from this side's perspective.
    func handshakeSend() throws -> Data?

    /// Consumes an inbound handshake message.
    func handshakeReceive(_ message: Data) throws

    /// Encrypts an application payload. Only valid after `isEstablished`.
    func encrypt(_ plaintext: Data) throws -> Data

    /// Decrypts an application payload. Only valid after `isEstablished`.
    func decrypt(_ ciphertext: Data) throws -> Data
}

/// Wraps a Noise handshake message in the mob `MXN1` control payload.
public func wrapHandshakePayload(_ noiseMessage: Data) -> Data {
    var out = Data(capacity: NoiseProtocol.handshakeTag.count + noiseMessage.count)
    out.append(contentsOf: NoiseProtocol.handshakeTag)
    out.append(noiseMessage)
    return out
}

/// Unwraps a mob control payload, returning the raw Noise message bytes.
/// Throws `NoiseError.unexpectedTag` if the payload does not begin with `"MXN1"`.
public func unwrapHandshakePayload(_ payload: Data) throws -> Data {
    guard payload.count >= NoiseProtocol.handshakeTag.count else {
        throw NoiseError.unexpectedTag
    }
    let prefix = Array(payload.prefix(NoiseProtocol.handshakeTag.count))
    guard prefix == NoiseProtocol.handshakeTag else {
        throw NoiseError.unexpectedTag
    }
    return payload.subdata(in: (payload.startIndex + NoiseProtocol.handshakeTag.count)..<payload.endIndex)
}

/// Concrete mob Noise session for `Noise_XX_25519_ChaChaPoly_BLAKE2s`.
public final class MobNoiseSession: NoiseSession {
    public let role: NoiseRole
    public let localStaticKey: Data
    public private(set) var isEstablished = false
    public private(set) var remoteStaticKey: Data?
    public private(set) var handshakeHash: Data?

    private enum Step {
        case start
        case wroteMessage1
        case readMessage1
        case wroteMessage2
        case readMessage2
        case complete
    }

    private let staticPrivateKey: Curve25519.KeyAgreement.PrivateKey
    private var configuredEphemeralKey: Curve25519.KeyAgreement.PrivateKey?
    private var ephemeralPrivateKey: Curve25519.KeyAgreement.PrivateKey?
    private var ephemeralPublicKey: Data?
    private var remoteEphemeralKey: Data?
    private var symmetricState = NoiseSymmetricState(protocolName: NoiseProtocol.name)
    private var sendCipher: NoiseCipherState?
    private var receiveCipher: NoiseCipherState?
    private var step: Step = .start

    public convenience init(role: NoiseRole) {
        // Generated Curve25519 keys cannot fail.
        try! self.init(role: role, staticPrivateKey: nil, ephemeralPrivateKey: nil)
    }

    public init(
        role: NoiseRole,
        staticPrivateKey: Data? = nil,
        ephemeralPrivateKey: Data? = nil
    ) throws {
        self.role = role
        self.staticPrivateKey = try Self.privateKey(from: staticPrivateKey)
        self.localStaticKey = Data(self.staticPrivateKey.publicKey.rawRepresentation)
        if let ephemeralPrivateKey {
            self.configuredEphemeralKey = try Self.privateKey(from: ephemeralPrivateKey)
        }
        self.symmetricState.mixHash(Data())
    }

    public func handshakeSend() throws -> Data? {
        switch (role, step) {
        case (.initiator, .start):
            let message = try writeMessage1()
            step = .wroteMessage1
            return message

        case (.responder, .readMessage1):
            let message = try writeMessage2()
            step = .wroteMessage2
            return message

        case (.initiator, .readMessage2):
            let message = try writeMessage3()
            try completeHandshake()
            return message

        case (_, .complete):
            return nil

        default:
            throw NoiseError.invalidHandshakeState
        }
    }

    public func handshakeReceive(_ message: Data) throws {
        switch (role, step) {
        case (.responder, .start):
            try readMessage1(message)
            step = .readMessage1

        case (.initiator, .wroteMessage1):
            try readMessage2(message)
            step = .readMessage2

        case (.responder, .wroteMessage2):
            try readMessage3(message)
            try completeHandshake()

        default:
            throw NoiseError.invalidHandshakeState
        }
    }

    public func encrypt(_ plaintext: Data) throws -> Data {
        guard isEstablished, var cipher = sendCipher else {
            throw NoiseError.handshakeIncomplete
        }
        let ciphertext = try cipher.encrypt(plaintext, aad: Data())
        sendCipher = cipher
        return ciphertext
    }

    public func decrypt(_ ciphertext: Data) throws -> Data {
        guard isEstablished, var cipher = receiveCipher else {
            throw NoiseError.handshakeIncomplete
        }
        let plaintext = try cipher.decrypt(ciphertext, aad: Data())
        receiveCipher = cipher
        return plaintext
    }

    private func writeMessage1() throws -> Data {
        let ephemeral = try ensureEphemeralKey()
        let publicKey = Data(ephemeral.publicKey.rawRepresentation)
        ephemeralPublicKey = publicKey
        symmetricState.mixHash(publicKey)
        _ = try symmetricState.encryptAndHash(Data())
        return publicKey
    }

    private func readMessage1(_ message: Data) throws {
        guard message.count == 32 else { throw NoiseError.invalidHandshakeMessage }
        remoteEphemeralKey = message
        symmetricState.mixHash(message)
        let payload = try symmetricState.decryptAndHash(Data())
        guard payload.isEmpty else { throw NoiseError.invalidHandshakeMessage }
    }

    private func writeMessage2() throws -> Data {
        guard let remoteEphemeralKey else { throw NoiseError.invalidHandshakeState }

        let ephemeral = try ensureEphemeralKey()
        let publicKey = Data(ephemeral.publicKey.rawRepresentation)
        ephemeralPublicKey = publicKey

        var message = Data(capacity: 96)
        message.append(publicKey)
        symmetricState.mixHash(publicKey)

        try symmetricState.mixKey(Self.dh(ephemeral, remoteEphemeralKey))
        message.append(try symmetricState.encryptAndHash(localStaticKey))
        try symmetricState.mixKey(Self.dh(staticPrivateKey, remoteEphemeralKey))
        message.append(try symmetricState.encryptAndHash(Data()))

        return message
    }

    private func readMessage2(_ message: Data) throws {
        guard message.count == 96 else { throw NoiseError.invalidHandshakeMessage }
        guard let ephemeralPrivateKey else { throw NoiseError.invalidHandshakeState }

        let remoteEphemeral = message.subdata(in: message.startIndex..<(message.startIndex + 32))
        remoteEphemeralKey = remoteEphemeral
        symmetricState.mixHash(remoteEphemeral)

        try symmetricState.mixKey(Self.dh(ephemeralPrivateKey, remoteEphemeral))

        let encryptedStaticStart = message.startIndex + 32
        let encryptedStaticEnd = encryptedStaticStart + 48
        let staticKey = try symmetricState.decryptAndHash(
            message.subdata(in: encryptedStaticStart..<encryptedStaticEnd)
        )
        guard staticKey.count == 32 else { throw NoiseError.invalidHandshakeMessage }
        remoteStaticKey = staticKey

        try symmetricState.mixKey(Self.dh(ephemeralPrivateKey, staticKey))
        let payload = try symmetricState.decryptAndHash(
            message.subdata(in: encryptedStaticEnd..<message.endIndex)
        )
        guard payload.isEmpty else { throw NoiseError.invalidHandshakeMessage }
    }

    private func writeMessage3() throws -> Data {
        guard let remoteEphemeralKey else { throw NoiseError.invalidHandshakeState }

        var message = Data(capacity: 64)
        message.append(try symmetricState.encryptAndHash(localStaticKey))
        try symmetricState.mixKey(Self.dh(staticPrivateKey, remoteEphemeralKey))
        message.append(try symmetricState.encryptAndHash(Data()))

        return message
    }

    private func readMessage3(_ message: Data) throws {
        guard message.count == 64 else { throw NoiseError.invalidHandshakeMessage }
        guard let ephemeralPrivateKey else { throw NoiseError.invalidHandshakeState }

        let encryptedStaticEnd = message.startIndex + 48
        let staticKey = try symmetricState.decryptAndHash(
            message.subdata(in: message.startIndex..<encryptedStaticEnd)
        )
        guard staticKey.count == 32 else { throw NoiseError.invalidHandshakeMessage }
        remoteStaticKey = staticKey

        try symmetricState.mixKey(Self.dh(ephemeralPrivateKey, staticKey))
        let payload = try symmetricState.decryptAndHash(
            message.subdata(in: encryptedStaticEnd..<message.endIndex)
        )
        guard payload.isEmpty else { throw NoiseError.invalidHandshakeMessage }
    }

    private func completeHandshake() throws {
        let ciphers = symmetricState.split()
        if role == .initiator {
            sendCipher = ciphers.0
            receiveCipher = ciphers.1
        } else {
            receiveCipher = ciphers.0
            sendCipher = ciphers.1
        }
        handshakeHash = symmetricState.handshakeHash
        isEstablished = true
        step = .complete
    }

    private func ensureEphemeralKey() throws -> Curve25519.KeyAgreement.PrivateKey {
        if let ephemeralPrivateKey {
            return ephemeralPrivateKey
        }

        let key = configuredEphemeralKey ?? Curve25519.KeyAgreement.PrivateKey()
        configuredEphemeralKey = nil
        ephemeralPrivateKey = key
        return key
    }

    private static func privateKey(from raw: Data?) throws -> Curve25519.KeyAgreement.PrivateKey {
        guard let raw else {
            return Curve25519.KeyAgreement.PrivateKey()
        }
        guard raw.count == 32 else { throw NoiseError.invalidKey }
        do {
            return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw)
        } catch {
            throw NoiseError.invalidKey
        }
    }

    private static func dh(
        _ privateKey: Curve25519.KeyAgreement.PrivateKey,
        _ publicKeyData: Data
    ) throws -> Data {
        guard publicKeyData.count == 32 else { throw NoiseError.invalidKey }
        do {
            let publicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKeyData)
            let shared = try privateKey.sharedSecretFromKeyAgreement(with: publicKey)
            return shared.withUnsafeBytes { Data($0) }
        } catch {
            throw NoiseError.decryptFailed
        }
    }
}

private struct NoiseSymmetricState {
    private(set) var chainingKey: Data
    private(set) var handshakeHash: Data
    private var cipherState = NoiseCipherState(key: nil)

    init(protocolName: String) {
        let name = Data(protocolName.utf8)
        if name.count <= BLAKE2s.digestLength {
            var h = name
            h.append(contentsOf: repeatElement(0, count: BLAKE2s.digestLength - h.count))
            self.handshakeHash = h
        } else {
            self.handshakeHash = BLAKE2s.hash(name)
        }
        self.chainingKey = handshakeHash
    }

    mutating func mixHash(_ data: Data) {
        var input = Data(capacity: handshakeHash.count + data.count)
        input.append(handshakeHash)
        input.append(data)
        handshakeHash = BLAKE2s.hash(input)
    }

    mutating func mixKey(_ inputKeyMaterial: Data) throws {
        let outputs = hkdf(chainingKey: chainingKey, inputKeyMaterial: inputKeyMaterial, outputs: 2)
        chainingKey = outputs[0]
        cipherState = NoiseCipherState(key: outputs[1])
    }

    mutating func encryptAndHash(_ plaintext: Data) throws -> Data {
        let ciphertext = try cipherState.encrypt(plaintext, aad: handshakeHash)
        mixHash(ciphertext)
        return ciphertext
    }

    mutating func decryptAndHash(_ ciphertext: Data) throws -> Data {
        let plaintext = try cipherState.decrypt(ciphertext, aad: handshakeHash)
        mixHash(ciphertext)
        return plaintext
    }

    func split() -> (NoiseCipherState, NoiseCipherState) {
        let outputs = hkdf(chainingKey: chainingKey, inputKeyMaterial: Data(), outputs: 2)
        return (NoiseCipherState(key: outputs[0]), NoiseCipherState(key: outputs[1]))
    }

    private func hkdf(chainingKey: Data, inputKeyMaterial: Data, outputs: Int) -> [Data] {
        precondition((2...3).contains(outputs))
        let tempKey = BLAKE2s.hmac(key: chainingKey, data: inputKeyMaterial)
        let output1 = BLAKE2s.hmac(key: tempKey, data: Data([0x01]))

        var output2Input = Data()
        output2Input.append(output1)
        output2Input.append(0x02)
        let output2 = BLAKE2s.hmac(key: tempKey, data: output2Input)

        if outputs == 2 {
            return [output1, output2]
        }

        var output3Input = Data()
        output3Input.append(output2)
        output3Input.append(0x03)
        let output3 = BLAKE2s.hmac(key: tempKey, data: output3Input)
        return [output1, output2, output3]
    }
}

private struct NoiseCipherState {
    private var key: Data?
    private var nonce: UInt64 = 0

    init(key: Data?) {
        self.key = key
    }

    mutating func encrypt(_ plaintext: Data, aad: Data) throws -> Data {
        guard let key else { return plaintext }
        guard nonce < UInt64.max else { throw NoiseError.nonceOverflow }

        do {
            let sealed = try ChaChaPoly.seal(
                plaintext,
                using: SymmetricKey(data: key),
                nonce: ChaChaPoly.Nonce(data: nonceData(nonce)),
                authenticating: aad
            )
            nonce += 1

            var out = Data()
            out.append(sealed.ciphertext)
            out.append(sealed.tag)
            return out
        } catch {
            throw NoiseError.decryptFailed
        }
    }

    mutating func decrypt(_ ciphertext: Data, aad: Data) throws -> Data {
        guard let key else { return ciphertext }
        guard nonce < UInt64.max else { throw NoiseError.nonceOverflow }
        guard ciphertext.count >= 16 else { throw NoiseError.decryptFailed }

        let tagStart = ciphertext.endIndex - 16
        do {
            let box = try ChaChaPoly.SealedBox(
                nonce: ChaChaPoly.Nonce(data: nonceData(nonce)),
                ciphertext: ciphertext.subdata(in: ciphertext.startIndex..<tagStart),
                tag: ciphertext.subdata(in: tagStart..<ciphertext.endIndex)
            )
            let plaintext = try ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: aad)
            nonce += 1
            return plaintext
        } catch {
            throw NoiseError.decryptFailed
        }
    }

    private func nonceData(_ nonce: UInt64) -> Data {
        var out = Data(repeating: 0, count: 12)
        for i in 0..<8 {
            out[4 + i] = UInt8((nonce >> UInt64(8 * i)) & 0xFF)
        }
        return out
    }
}

/// Placeholder session retained for tests and callers that need to inject a
/// not-yet-wired implementation explicitly.
public final class StubNoiseSession: NoiseSession {
    public let role: NoiseRole
    public var isEstablished: Bool { false }
    public var remoteStaticKey: Data? { nil }

    public init(role: NoiseRole) { self.role = role }

    public func handshakeSend() throws -> Data? { throw NoiseError.notImplemented }
    public func handshakeReceive(_ message: Data) throws { throw NoiseError.notImplemented }
    public func encrypt(_ plaintext: Data) throws -> Data { throw NoiseError.notImplemented }
    public func decrypt(_ ciphertext: Data) throws -> Data { throw NoiseError.notImplemented }
}
