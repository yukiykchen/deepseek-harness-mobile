import Foundation
import Security

protocol DshSshKeychain {
    func read(service: String, account: String) throws -> Data?
    func write(service: String, account: String, data: Data) throws
    func delete(service: String, account: String) throws
}

struct DshSystemSshKeychain: DshSshKeychain {
    private func query(_ service: String, _ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }
    func read(service: String, account: String) throws -> Data? {
        var request = query(service, account)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        return data
    }
    func write(service: String, account: String, data: Data) throws {
        let request = query(service, account)
        let value: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(request as CFDictionary, value as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(request.merging(value) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
    func delete(service: String, account: String) throws {
        let status = SecItemDelete(query(service, account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }
}

/** A file selection is one bounded, expiring in-memory capability, not a plaintext URL. */
final class DshSshKeyRecords {
    private let lock = NSRecursiveLock()
    private let keychain: DshSshKeychain
    private let now: () -> Date
    private var pending: (token: String, bytes: Data, expires: Date)?
    private let service = "io.github.yukiykchen.dsh.ssh-keys"
    private let legacyService = "com.example.dsh.ssh-keys"
    static let maxBytes = 64 * 1024

    init(keychain: DshSshKeychain = DshSystemSshKeychain(), now: @escaping () -> Date = Date.init) {
        self.keychain = keychain
        self.now = now
    }

    func stage(_ bytes: Data) throws -> String {
        lock.lock(); defer { lock.unlock() }
        pending = nil
        guard !bytes.isEmpty, bytes.count <= Self.maxBytes else { throw failure("Invalid SSH private key size") }
        let token = "dsh-ssh-import://" + UUID().uuidString
        pending = (token, bytes, now().addingTimeInterval(60))
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 60) { [weak self] in
            guard let self else { return }
            self.lock.lock(); defer { self.lock.unlock() }
            if self.pending?.token == token { self.pending = nil }
        }
        return token
    }

    func consume(_ token: String) throws -> String {
        lock.lock(); defer { lock.unlock() }
        guard let selection = pending, selection.token == token else { throw failure("SSH key selection unavailable") }
        pending = nil
        guard selection.expires > now() else { throw failure("SSH key selection expired") }
        return try importBytes(selection.bytes)
    }

    func cancelSelection() { lock.lock(); pending = nil; lock.unlock() }

    func importBytes(_ bytes: Data) throws -> String {
        lock.lock(); defer { lock.unlock() }
        guard !bytes.isEmpty, bytes.count <= Self.maxBytes else { throw failure("Invalid SSH private key size") }
        let id = "key-" + UUID().uuidString
        try keychain.write(service: service, account: id, data: bytes)
        return id
    }

    func read(_ id: String) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        guard validId(id) else { throw failure("Invalid SSH key id") }
        if let data = try keychain.read(service: service, account: id) {
            guard !data.isEmpty, data.count <= Self.maxBytes else { throw failure("Invalid SSH private key size") }
            return data
        }
        guard let data = try keychain.read(service: legacyService, account: id) else { return nil }
        guard !data.isEmpty, data.count <= Self.maxBytes else { throw failure("Invalid SSH private key size") }
        // A keychain error is not permission to use another service's stale record.
        try keychain.write(service: service, account: id, data: data)
        try keychain.delete(service: legacyService, account: id)
        return data
    }

    func delete(_ id: String) throws {
        lock.lock(); defer { lock.unlock() }
        guard validId(id) else { throw failure("Invalid SSH key id") }
        var error: Error?
        for name in [service, legacyService] {
            do { try keychain.delete(service: name, account: id) } catch let failure { error = failure }
        }
        if let error { throw error }
    }

    private func validId(_ id: String) -> Bool {
        !id.isEmpty && id.utf8.count <= 128 && id.utf8.allSatisfy {
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 || $0 == 95
        }
    }
    private func failure(_ text: String) -> NSError { NSError(domain: "DshSshKeyRecords", code: 1, userInfo: [NSLocalizedDescriptionKey: text]) }
}

func readSshKeyFile(_ url: URL) throws -> Data {
    guard url.isFileURL else { throw CocoaError(.fileReadUnsupportedScheme) }
    let file = try FileHandle(forReadingFrom: url)
    defer { try? file.close() }
    var data = Data()
    while true {
        let chunk = try file.read(upToCount: min(4096, DshSshKeyRecords.maxBytes + 1 - data.count)) ?? Data()
        if chunk.isEmpty { break }
        data.append(chunk)
        guard data.count <= DshSshKeyRecords.maxBytes else { throw CocoaError(.fileReadTooLarge) }
    }
    guard !data.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
    return data
}

func removeLegacySshPickerFiles(in directory: URL) throws {
    let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
    for file in files {
        let name = file.lastPathComponent
        guard name.hasPrefix("ssh-pick-"), name.hasSuffix(".key"),
              UUID(uuidString: String(name.dropFirst(9).dropLast(4))) != nil else { continue }
        try FileManager.default.removeItem(at: file)
    }
}
