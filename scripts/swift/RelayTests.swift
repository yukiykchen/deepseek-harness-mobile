import Foundation

@main struct RelayTests {
    static func main() {
        var passed = 0, failed = 0
        func test(_ name: String, _ work: () throws -> Void) {
            do { try work(); passed += 1; print("PASS \(name)") }
            catch { failed += 1; print("FAIL \(name): \(error)") }
        }
        relayCoreTests(test)
        relaySocketTests(test)
        relayIntegrationTests(test)
        sshKeyRecordTests(test)
        print("Swift Relay tests: \(passed) passed, \(failed) failed")
        if failed > 0 { exit(1) }
    }
}
