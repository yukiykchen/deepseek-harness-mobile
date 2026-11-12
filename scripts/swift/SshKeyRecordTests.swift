import Foundation

private final class FakeSshKeychain: DshSshKeychain {
    var values: [String: Data] = [:]
    var failRead = false
    var failWrite = false
    var deletions: [String] = []
    func read(service: String, account: String) throws -> Data? {
        if failRead { throw RelayTestFailure(description: "keychain unavailable") }
        return values[service + ":" + account]
    }
    func write(service: String, account: String, data: Data) throws {
        if failWrite { throw RelayTestFailure(description: "keychain unavailable") }
        values[service + ":" + account] = data
    }
    func delete(service: String, account: String) throws {
        let id = service + ":" + account
        values[id] = nil
        deletions.append(id)
    }
}

func sshKeyRecordTests(_ test: (String, () throws -> Void) -> Void) {
    test("SSH selection is in memory, one-use, bounded and expires") {
        let keychain = FakeSshKeychain()
        var now = Date()
        let records = DshSshKeyRecords(keychain: keychain, now: { now })
        let data = Data("private-key".utf8)
        let token = try records.stage(data)
        try check(token.hasPrefix("dsh-ssh-import://"))
        let id = try records.consume(token)
        try check(try records.read(id) == data)
        try expectFailure { _ = try records.consume(token) }
        let expired = try records.stage(data)
        now = now.addingTimeInterval(61)
        try expectFailure { _ = try records.consume(expired) }
        try expectFailure { _ = try records.stage(Data(repeating: 1, count: 65537)) }
    }
    test("new SSH file selection invalidates the previous capability") {
        let records = DshSshKeyRecords(keychain: FakeSshKeychain())
        let old = try records.stage(Data([1]))
        let new = try records.stage(Data([2]))
        try expectFailure { _ = try records.consume(old) }
        try check(try records.read(records.consume(new)) == Data([2]))
        let cancelled = try records.stage(Data([3]))
        records.cancelSelection()
        try expectFailure { _ = try records.consume(cancelled) }
    }
    test("failed Keychain import cannot return an invented successful key id") {
        let keychain = FakeSshKeychain()
        let records = DshSshKeyRecords(keychain: keychain)
        let token = try records.stage(Data([1]))
        keychain.failWrite = true
        try expectFailure { _ = try records.consume(token) }
        try check(keychain.values.isEmpty)
        try expectFailure { _ = try records.consume(token) }
    }
    test("legacy SSH Keychain records migrate only after a successful new write") {
        let keychain = FakeSshKeychain()
        let legacy = "com.example.dsh.ssh-keys:key-legacy"
        keychain.values[legacy] = Data([1, 2])
        let records = DshSshKeyRecords(keychain: keychain)
        keychain.failWrite = true
        try expectFailure { _ = try records.read("key-legacy") }
        try check(keychain.values[legacy] == Data([1, 2]))
        keychain.failWrite = false
        try check(try records.read("key-legacy") == Data([1, 2]))
        try check(keychain.values[legacy] == nil)
    }
    test("Keychain permission failure does not silently read a legacy service") {
        let keychain = FakeSshKeychain()
        keychain.values["com.example.dsh.ssh-keys:key-old"] = Data([1])
        keychain.failRead = true
        try expectFailure { _ = try DshSshKeyRecords(keychain: keychain).read("key-old") }
    }
    test("SSH ids and selected-file reads reject malformed or oversized input") {
        let records = DshSshKeyRecords(keychain: FakeSshKeychain())
        for id in ["", "../key", "/absolute", String(repeating: "x", count: 129)] {
            try expectFailure { _ = try records.read(id) }
            try expectFailure { try records.delete(id) }
        }
        try expectFailure { _ = try readSshKeyFile(URL(string: "https://example.com/key")!) }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("key")
        try Data(repeating: 1, count: 65537).write(to: path)
        try expectFailure { _ = try readSshKeyFile(path) }
        try Data([1, 2]).write(to: path)
        try check(try readSshKeyFile(path) == Data([1, 2]))
    }
    test("upgrade cleanup only removes old app-generated plaintext picker files") {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let old = directory.appendingPathComponent("ssh-pick-\(UUID().uuidString).key")
        let retained = directory.appendingPathComponent("ssh-pick-custom.key")
        try Data([1]).write(to: old)
        try Data([1]).write(to: retained)
        try removeLegacySshPickerFiles(in: directory)
        try check(!FileManager.default.fileExists(atPath: old.path))
        try check(FileManager.default.fileExists(atPath: retained.path))
    }
}
