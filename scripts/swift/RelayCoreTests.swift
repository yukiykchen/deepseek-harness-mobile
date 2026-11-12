import Foundation

func relayCoreTests(_ test: (String, () throws -> Void) -> Void) {
    test("stopped ticket and socket callbacks cannot resurrect the connection") {
        let f = RelayCoreFixture()
        f.core.connect(); let callback = f.transport.tickets.first!
        f.core.disconnect()
        callback(.success(DshRelayTicket(sessionId: "old", token: "old-token", url: URL(string: "ws://localhost/")!, hostId: "old")))
        try check(f.transport.calls.first!.cancelled)
        try check(f.transport.sockets.isEmpty)
        try check(f.core.current()["phase"] as? String == "STOPPED")
    }
    test("old gateway cannot send through the next cipher and socket") {
        let f = RelayCoreFixture(); let old = try f.ready("old"); let oldGateway = f.gateways.first!
        old.fail(); let current = try f.ready("next"); let count = current.sent.count
        oldGateway.send("http_req", [:], "old")
        old.sends.first?(URLError(.networkConnectionLost)); old.fail()
        try check(current.sent.count == count && !current.cancelled && oldGateway.stopped)
        f.gateways.last!.send("http_req", ["path": "/new"], "next")
        let payload = current.sent.last!["payload"] as! [String: Any]
        try check(payload["seq"] as? String == "0" && payload["accessSessionId"] as? String == "next")
        f.core.disconnect()
    }
    test("duplicate hello cannot reset encryption or replace the gateway") {
        let f = RelayCoreFixture(); let socket = try f.ready(); let host = try f.host(socket, "session")
        f.gateways.first!.send("http_req", [:], "first")
        let first = socket.sent.last!["payload"] as! [String: Any]
        _ = try host.open(DshSealedPayload(seq: first["seq"] as! String, ciphertextB64: first["ciphertextB64"] as! String))
        try socket.message(["type": "server_hello", "payload": [:]])
        f.gateways.first!.send("http_req", [:], "second")
        let second = socket.sent.last!["payload"] as! [String: Any]
        try check(second["seq"] as? String == "1" && f.gateways.count == 1)
        _ = try host.open(DshSealedPayload(seq: second["seq"] as! String, ciphertextB64: second["ciphertextB64"] as! String))
        f.core.disconnect()
    }
    test("cancelled retry cannot replace a newer ready attempt") {
        let f = RelayCoreFixture(); let old = try f.ready("old"); old.fail()
        let retry = f.tasks.last!
        f.core.networkAvailable(); let current = try f.ready("new"); let count = f.transport.tickets.count
        retry.action()
        try check(retry.cancelled && f.transport.tickets.count == count && !current.cancelled)
        f.core.disconnect()
    }
    test("handshake deadline retries but ready attempts ignore already dequeued deadlines") {
        let f = RelayCoreFixture(); f.core.connect(); f.tasks.last!.action()
        try check(f.core.current()["phase"] as? String == "RECONNECTING")
        _ = try f.ready(); let deadline = f.tasks.last { $0.delay == 30 }!
        deadline.action(); try check(deadline.cancelled && f.core.current()["phase"] as? String == "READY")
        f.core.disconnect()
    }
    test("revoked ticket and failed server proof stop automatic retry") {
        let f = RelayCoreFixture(); f.core.connect()
        f.transport.tickets.last?(.failure(DshRelayHttpError(status: 403, message: "revoked")))
        f.core.networkAvailable()
        try check(f.core.current()["phase"] as? String == "ERROR" && f.transport.tickets.count == 1)
        f.core.connect(); f.transport.succeed("bad")
        try f.transport.sockets.last!.message(["type": "server_hello", "payload": ["serverProofB64": "invalid", "serverRandomB64": f.serverRandom]])
        try check(f.core.current()["phase"] as? String == "ERROR" && f.gateways.isEmpty)
        f.core.disconnect()
    }
    test("pairing callbacks cannot save after forget or a newer pairing") {
        let f = RelayCoreFixture(); let credentials = f.store.credentials!
        let first = f.core.beginPairing(); let call = FakeRelayTask(); f.core.registerPairingCall(first, call)
        try check(f.core.isPairing(first))
        f.core.forget()
        try check(!f.core.isPairing(first))
        try check(call.cancelled && f.store.credentials == nil)
        let accepted = try f.core.completePairing(first, credentials); try check(!accepted)
        let second = f.core.beginPairing(); let third = f.core.beginPairing()
        try check(try !f.core.completePairing(second, credentials))
        try check(try f.core.completePairing(third, credentials))
        try check(f.store.saves == 1)
    }
    test("reentrant disconnect does not dispatch stale ready to later observers") {
        let f = RelayCoreFixture(); var phases: [String] = []
        _ = f.core.addListener { if $0["phase"] as? String == "READY" { f.core.disconnect() } }
        _ = f.core.addListener { phases.append($0["phase"] as? String ?? "") }
        _ = try f.ready()
        try check(phases.last == "STOPPED" && !phases.contains("READY"))
    }
    test("blocking gateway delivery does not prevent disconnect") {
        let f = RelayCoreFixture(); let socket = try f.ready(); let gateway = f.gateways.first!
        let entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0), done = DispatchSemaphore(value: 0)
        gateway.onDelivery = { entered.signal(); _ = release.wait(timeout: .now() + 3) }
        let sealed = try f.host(socket, "session").seal(["type": "http_res", "payload": [:], "channel": "ch"])
        DispatchQueue.global().async {
            try? socket.message(["type": "sealed", "payload": ["seq": sealed.seq, "ciphertextB64": sealed.ciphertextB64]])
            done.signal()
        }
        defer { release.signal(); _ = done.wait(timeout: .now() + 3); f.core.disconnect() }
        try check(entered.wait(timeout: .now() + 2) == .success)
        let stopped = DispatchSemaphore(value: 0)
        DispatchQueue.global().async { f.core.disconnect(); stopped.signal() }
        try check(stopped.wait(timeout: .now() + 1) == .success)
        try check(gateway.stopped)
    }
    test("state diagnostics redact credentials") {
        let f = RelayCoreFixture(); let socket = try f.ready()
        socket.receive(socket, .failure(dshRelayError(f.master + " client-token ticket-token")))
        let message = f.core.current()["message"] as! String
        try check(!message.contains(f.master) && !message.contains("client-token") && !message.contains("ticket-token"))
        f.core.disconnect()
    }
    test("QR parsing handles encoded values and rejects duplicate parameters") {
        let link = try dshParseRelayPairQr("https://relay.example/pair#/pair?id=one%26two&key=secret")
        try check(link.origin == "https://relay.example" && link.id == "one&two")
        try expectFailure { _ = try dshParseRelayPairQr("https://relay.example/?id=one&id=two&key=secret") }
        try expectFailure { _ = try dshParseRelayPairQr("https://relay.example/?id=one&id&key=secret") }
        try expectFailure { _ = try dshParseRelayPairQr("file:///pair?id=one&key=secret") }
        try expectFailure { _ = try dshParseRelayPairQr("https://user:pass@relay.example/?id=one&key=secret") }
        try expectFailure { _ = try dshParseRelayPairQr("http://localhost:8787/?id=one&key=secret") }
    }
    test("synchronous socket failure before handle return releases the returned socket") {
        let f = RelayCoreFixture(); f.transport.onOpen = { $0.fail() }
        f.core.connect(); f.transport.succeed("synchronous")
        try check(f.core.current()["phase"] as? String == "RECONNECTING")
        try check(f.transport.sockets.last!.cancelled && f.transport.calls.last!.cancelled)
        f.core.disconnect()
    }
    test("credential write failure does not falsely report a successful pairing") {
        final class FailingStore: DshRelayCredentialStore {
            func loadCredentials() throws -> DshRelayCredentials? { nil }
            func saveCredentials(_ credentials: DshRelayCredentials) throws { throw dshRelayError("Keychain write failed") }
            func clearCredentials() {}
        }
        let f = RelayCoreFixture()
        let core = DshRelayCore(store: FailingStore(), transport: f.transport,
            gatewayFactory: { _, send in FakeRelayGateway(send) }, schedule: { FakeRelayTask($0, $1) })
        let token = core.beginPairing()
        try expectFailure { _ = try core.completePairing(token, f.store.credentials!) }
        try check(core.current()["phase"] as? String == "PAIRING")
        core.disconnect()
    }
}
