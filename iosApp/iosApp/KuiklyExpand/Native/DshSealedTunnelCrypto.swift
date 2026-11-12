import CryptoKit
import Foundation
import Security

enum DshE2eeError: LocalizedError {
    case invalidBase64
    case invalidLength
    case proofFailed
    case sequence
    case truncated
    case authFailed

    var errorDescription: String? {
        switch self {
        case .invalidBase64: return "invalid base64url"
        case .invalidLength: return "invalid binary length"
        case .proofFailed: return "server proof failed"
        case .sequence: return "unexpected sequence"
        case .truncated: return "truncated ciphertext"
        case .authFailed: return "ciphertext authentication failed"
        }
    }
}

@objc(DshSealedPayload)
final class DshSealedPayload: NSObject {
    @objc let seq: String
    @objc let ciphertextB64: String
    @objc init(seq: String, ciphertextB64: String) {
        self.seq = seq
        self.ciphertextB64 = ciphertextB64
    }
}

enum DshSealedTunnelCrypto {
    static let claimInfo = "dsh-claim-v1"
    static let keyBytes = 32
    static let randomBytes = 32
    static let noncePrefixBytes = 4

    static func encodeBase64Url(_ value: Data) -> String {
        value.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decodeBase64Url(_ value: String, bytes: Int? = nil) throws -> Data {
        guard value.range(of: "^[A-Za-z0-9_-]*$", options: .regularExpression) != nil else {
            throw DshE2eeError.invalidBase64
        }
        var padded = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while padded.count % 4 != 0 { padded.append("=") }
        guard let decoded = Data(base64Encoded: padded) else { throw DshE2eeError.invalidBase64 }
        guard encodeBase64Url(decoded) == value else { throw DshE2eeError.invalidBase64 }
        if let bytes, decoded.count != bytes { throw DshE2eeError.invalidLength }
        return decoded
    }

    static func deriveClaimToken(masterKeyB64: String) throws -> String {
        let key = try decodeBase64Url(masterKeyB64, bytes: keyBytes)
        let derived = hkdfSha256(ikm: key, salt: Data(), info: Data(claimInfo.utf8), length: keyBytes)
        return encodeBase64Url(derived)
    }

    static func randomBytes(_ count: Int = randomBytes) -> Data {
        precondition(count > 0)
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        precondition(status == errSecSuccess, "Secure random generation failed")
        return data
    }

    static func clientProof(masterKeyB64: String, accessSessionId: String, clientRandomB64: String) throws -> String {
        let key = try decodeBase64Url(masterKeyB64, bytes: keyBytes)
        _ = try decodeBase64Url(clientRandomB64, bytes: randomBytes)
        let mac = hmacSha256(key: key, message: canonical(["dsh-e2ee-client", 1, accessSessionId, clientRandomB64]))
        return encodeBase64Url(mac)
    }

    static func serverProof(
        masterKeyB64: String,
        accessSessionId: String,
        clientRandomB64: String,
        serverRandomB64: String
    ) throws -> String {
        let key = try decodeBase64Url(masterKeyB64, bytes: keyBytes)
        _ = try decodeBase64Url(clientRandomB64, bytes: randomBytes)
        _ = try decodeBase64Url(serverRandomB64, bytes: randomBytes)
        let mac = hmacSha256(
            key: key,
            message: canonical(["dsh-e2ee-server", 1, accessSessionId, clientRandomB64, serverRandomB64])
        )
        return encodeBase64Url(mac)
    }

    static func createClientCipher(
        masterKeyB64: String,
        accessSessionId: String,
        clientRandomB64: String,
        serverRandomB64: String,
        serverProofB64: String
    ) throws -> DshSecureCipher {
        let expected = try serverProof(
            masterKeyB64: masterKeyB64,
            accessSessionId: accessSessionId,
            clientRandomB64: clientRandomB64,
            serverRandomB64: serverRandomB64
        )
        guard constantTimeEquals(expected, serverProofB64) else { throw DshE2eeError.proofFailed }
        let material = try deriveMaterial(
            masterKeyB64: masterKeyB64,
            accessSessionId: accessSessionId,
            clientRandomB64: clientRandomB64,
            serverRandomB64: serverRandomB64
        )
        return DshSecureCipher(
            accessSessionId: accessSessionId,
            sendDirection: "c2d",
            sendKey: material.c2dKey,
            sendNonceBase: material.c2dNonce,
            receiveDirection: "d2c",
            receiveKey: material.d2cKey,
            receiveNonceBase: material.d2cNonce
        )
    }

    static func createHostCipher(masterKeyB64: String, accessSessionId: String, clientRandomB64: String, serverRandomB64: String) throws -> DshSecureCipher {
        let material = try deriveMaterial(masterKeyB64: masterKeyB64, accessSessionId: accessSessionId,
            clientRandomB64: clientRandomB64, serverRandomB64: serverRandomB64)
        return DshSecureCipher(accessSessionId: accessSessionId, sendDirection: "d2c", sendKey: material.d2cKey,
            sendNonceBase: material.d2cNonce, receiveDirection: "c2d", receiveKey: material.c2dKey, receiveNonceBase: material.c2dNonce)
    }

    private static func deriveMaterial(
        masterKeyB64: String,
        accessSessionId: String,
        clientRandomB64: String,
        serverRandomB64: String
    ) throws -> (c2dKey: Data, d2cKey: Data, c2dNonce: Data, d2cNonce: Data) {
        let key = try decodeBase64Url(masterKeyB64, bytes: keyBytes)
        _ = try decodeBase64Url(clientRandomB64, bytes: randomBytes)
        _ = try decodeBase64Url(serverRandomB64, bytes: randomBytes)
        let salt = SHA256.hash(data: canonical(["dsh-e2ee-salt", 1, accessSessionId, clientRandomB64, serverRandomB64]))
        func expand(_ info: String, _ length: Int) -> Data {
            hkdfSha256(ikm: key, salt: Data(salt), info: Data(info.utf8), length: length)
        }
        return (
            expand("dsh-e2ee-v1:c2d:key", keyBytes),
            expand("dsh-e2ee-v1:d2c:key", keyBytes),
            expand("dsh-e2ee-v1:c2d:nonce", noncePrefixBytes),
            expand("dsh-e2ee-v1:d2c:nonce", noncePrefixBytes)
        )
    }

    static func canonical(_ parts: [Any]) -> Data {
        let items: [String] = parts.map { part in
            if let number = part as? Int { return String(number) }
            if let number = part as? Int64 { return String(number) }
            return jsonQuote(String(describing: part))
        }
        return Data(("[" + items.joined(separator: ",") + "]").utf8)
    }

    private static func jsonQuote(_ value: String) -> String {
        // String JSON serialization is total, including control characters and embedded newlines.
        let bytes = try! JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .withoutEscapingSlashes])
        return String(decoding: bytes, as: UTF8.self)
    }

    private static func hmacSha256(key: Data, message: Data) -> Data {
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: key))
        return Data(mac)
    }

    private static func hkdfSha256(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        let extractSalt = salt.isEmpty ? Data(count: 32) : salt
        let prk = hmacSha256(key: extractSalt, message: ikm)
        var result = Data()
        var previous = Data()
        var counter: UInt8 = 1
        while result.count < length {
            var block = previous
            block.append(info)
            block.append(counter)
            previous = hmacSha256(key: prk, message: block)
            result.append(previous)
            counter += 1
        }
        return result.prefix(length)
    }

    private static func constantTimeEquals(_ left: String, _ right: String) -> Bool {
        guard let a = try? decodeBase64Url(left), let b = try? decodeBase64Url(right), a.count == b.count else {
            return false
        }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[i] ^ b[i] }
        return diff == 0
    }
}

final class DshSecureCipher {
    private let accessSessionId: String
    private let sendDirection: String
    private let sendKey: SymmetricKey
    private let sendNonceBase: Data
    private let receiveDirection: String
    private let receiveKey: SymmetricKey
    private let receiveNonceBase: Data
    private var sendSequence: UInt64 = 0
    private var receiveSequence: UInt64 = 0
    private let lock = NSLock()

    init(
        accessSessionId: String,
        sendDirection: String,
        sendKey: Data,
        sendNonceBase: Data,
        receiveDirection: String,
        receiveKey: Data,
        receiveNonceBase: Data
    ) {
        self.accessSessionId = accessSessionId
        self.sendDirection = sendDirection
        self.sendKey = SymmetricKey(data: sendKey)
        self.sendNonceBase = sendNonceBase
        self.receiveDirection = receiveDirection
        self.receiveKey = SymmetricKey(data: receiveKey)
        self.receiveNonceBase = receiveNonceBase
    }

    func seal(_ value: [String: Any]) throws -> DshSealedPayload {
        lock.lock()
        defer { lock.unlock() }
        let sequence = sendSequence
        guard sequence < UInt64.max else { throw DshE2eeError.sequence }
        let json = try JSONSerialization.data(withJSONObject: value, options: [])
        let sealed = try AES.GCM.seal(
            json,
            using: sendKey,
            nonce: AES.GCM.Nonce(data: nonce(sendNonceBase, sequence)),
            authenticating: aad(sendDirection, sequence)
        )
        sendSequence += 1
        var combined = sealed.ciphertext
        combined.append(sealed.tag)
        return DshSealedPayload(seq: String(sequence), ciphertextB64: DshSealedTunnelCrypto.encodeBase64Url(combined))
    }

    func open(_ payload: DshSealedPayload) throws -> [String: Any] {
        lock.lock()
        defer { lock.unlock() }
        guard payload.seq.range(of: "^(0|[1-9][0-9]*)$", options: .regularExpression) != nil else {
            throw DshE2eeError.sequence
        }
        guard let sequence = UInt64(payload.seq), sequence < UInt64.max, sequence == receiveSequence else {
            throw DshE2eeError.sequence
        }
        let sealed = try DshSealedTunnelCrypto.decodeBase64Url(payload.ciphertextB64)
        guard sealed.count >= 16 else { throw DshE2eeError.truncated }
        let ciphertext = sealed.dropLast(16)
        let tag = sealed.suffix(16)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce(receiveNonceBase, sequence)),
                ciphertext: Data(ciphertext),
                tag: Data(tag)
            )
            let plain = try AES.GCM.open(box, using: receiveKey, authenticating: aad(receiveDirection, sequence))
            guard let object = try JSONSerialization.jsonObject(with: plain) as? [String: Any] else {
                throw DshE2eeError.authFailed
            }
            receiveSequence += 1
            return object
        } catch {
            throw DshE2eeError.authFailed
        }
    }

    private func nonce(_ prefix: Data, _ sequence: UInt64) -> Data {
        var data = Data(prefix.prefix(4))
        if data.count < 4 { data.append(Data(count: 4 - data.count)) }
        var big = sequence.bigEndian
        data.append(Data(bytes: &big, count: 8))
        return data
    }

    private func aad(_ direction: String, _ sequence: UInt64) -> Data {
        DshSealedTunnelCrypto.canonical(["dsh-e2ee", 1, accessSessionId, direction, String(sequence)])
    }
}
