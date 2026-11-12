import Foundation
import Security

@objc(DshRelaySecrets)
final class DshRelaySecrets: NSObject {
    private let service = "io.github.yukiykchen.dsh.relay-secrets"
    private let legacyService = "com.example.dsh.relay-secrets"

    @objc func save(
        masterKeyB64: String,
        clientToken: String,
        hostId: String,
        hostName: String,
        relayOrigin: String
    ) -> Bool {
        do {
            try saveCredentials(DshRelayCredentials(master: masterKeyB64, clientToken: clientToken,
                hostId: hostId, hostName: hostName, origin: relayOrigin))
            return true
        } catch { return false }
    }

    @objc func masterKey() -> String? { try? loadCredentials()?.master }
    @objc func clientToken() -> String? { try? loadCredentials()?.clientToken }
    @objc func hostId() -> String? { try? loadCredentials()?.hostId }
    @objc func hostName() -> String? { try? loadCredentials()?.hostName }
    @objc func relayOrigin() -> String? { try? loadCredentials()?.origin }

    @objc func hasPairing() -> Bool {
        (try? loadCredentials()) != nil
    }

    @objc func clear() {
        ["pairing-v1", "master", "client", "host", "name", "origin"].forEach { account in
            delete(account, service: service)
            delete(account, service: legacyService)
        }
    }

    private func read(_ account: String) -> String? {
        if let value = read(account, service: service) { return value }
        return read(account, service: legacyService)
    }

    private func read(_ account: String, service: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private func delete(_ account: String, service: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

extension DshRelaySecrets: DshRelayCredentialStore {
    func loadCredentials() throws -> DshRelayCredentials? {
        let credentials: DshRelayCredentials
        if let stored = read("pairing-v1") {
            guard let bytes = stored.data(using: .utf8), let json = try JSONSerialization.jsonObject(with: bytes) as? [String: String],
                  let master = json["master"], let token = json["token"], let origin = json["origin"] else {
                throw dshRelayError("Stored Relay credentials are invalid")
            }
            credentials = DshRelayCredentials(master: master, clientToken: token, hostId: json["host"] ?? "", hostName: json["name"] ?? "", origin: origin)
        } else {
            guard let master = read("master"), let token = read("client"), let origin = read("origin") else { return nil }
            credentials = DshRelayCredentials(master: master, clientToken: token, hostId: read("host") ?? "", hostName: read("name") ?? "", origin: origin)
        }
        try validate(credentials)
        return credentials
    }
    func saveCredentials(_ credentials: DshRelayCredentials) throws {
        try validate(credentials)
        let bytes = try JSONSerialization.data(withJSONObject: ["master": credentials.master, "token": credentials.clientToken,
            "host": credentials.hostId, "name": credentials.hostName, "origin": credentials.origin])
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: "pairing-v1"]
        let value: [String: Any] = [kSecValueData as String: bytes, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, value as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(value) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
    func clearCredentials() { clear() }

    private func validate(_ credentials: DshRelayCredentials) throws {
        _ = try DshSealedTunnelCrypto.decodeBase64Url(credentials.master, bytes: 32)
        guard let url = URL(string: credentials.origin), ["http", "https"].contains(url.scheme), url.host != nil,
              url.user == nil, url.password == nil, !credentials.clientToken.isEmpty,
              credentials.clientToken.utf8.allSatisfy({ (0x21...0x7e).contains($0) }) else {
            throw dshRelayError("Stored Relay credentials are invalid")
        }
    }
}
