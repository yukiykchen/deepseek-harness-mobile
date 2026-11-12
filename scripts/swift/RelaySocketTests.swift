import Darwin
import Foundation

func relaySocketTests(_ test: (String, () throws -> Void) -> Void) {
    test("real HTTP proxy strips local authorization and streams a chunked response") {
        let received = RelayTestBox<[String: Any]?>(nil)
        var gateway: DshRelayLoopbackServer!
        gateway = DshRelayLoopbackServer(token: "local-token") { type, payload, channel in
            if type == "http_req" {
                received.set(payload)
                gateway.onInner(type: "http_res", payload: ["status": 200, "headers": ["Content-Type": "application/json"],
                    "bodyB64": Data("{\"ok\":true}".utf8).base64EncodedString(), "final": true], channel: channel)
            }
        }
        let port = try gateway.start(); defer { gateway.stop() }
        let peer = try localSocket(port); defer { peer.stop() }
        peer.write("GET /canary HTTP/1.1\r\nAuthorization: Bearer local-token\r\nHost: localhost\r\n\r\n")
        try check(peer.readHeaders()?.hasPrefix("HTTP/1.1 200") == true)
        let body = Data("b\r\n{\"ok\":true}\r\n0\r\n\r\n".utf8)
        try check(peer.readExact(body.count) == body)
        let headers = received.get()?["headers"] as? [String: String]
        try check(headers?["authorization"] == nil && headers?["host"] == nil)
    }
    test("stop wakes both incomplete header reads and HTTP waiters") {
        let entered = DispatchSemaphore(value: 0)
        let gateway = DshRelayLoopbackServer(token: "local-token") { _, _, _ in entered.signal() }
        let port = try gateway.start(); defer { gateway.stop() }
        let header = try localSocket(port), response = try localSocket(port)
        defer { header.stop(); response.stop() }
        response.write("GET /wait HTTP/1.1\r\nAuthorization: Bearer local-token\r\n\r\n")
        try check(entered.wait(timeout: .now() + 2) == .success)
        try waitFor { gateway.activePeerCount == 2 }
        gateway.stop()
        try waitFor { gateway.activePeerCount == 0 }
        try check(!gateway.isListening() && gateway.port == 0)
        try check(header.readExact(1) == nil && response.readExact(1) == nil)
    }
    test("early WebSocket data follows upgrade without being dropped") {
        var gateway: DshRelayLoopbackServer!
        gateway = DshRelayLoopbackServer(token: "local-token") { type, _, channel in
            if type == "ws_open" {
                gateway.onInner(type: "ws_frame", payload: ["opcode": 1, "dataB64": Data("first".utf8).base64EncodedString()], channel: channel)
                gateway.onInner(type: "ws_open_ok", payload: [:], channel: channel)
                gateway.onInner(type: "ws_frame", payload: ["opcode": 1, "dataB64": Data("second".utf8).base64EncodedString()], channel: channel)
            }
        }
        let port = try gateway.start(); defer { gateway.stop() }
        let peer = try localSocket(port); defer { peer.stop() }
        peer.write(relayUpgrade)
        try check(peer.readHeaders()?.hasPrefix("HTTP/1.1 101") == true)
        try check(peer.readExact(7) == DshWsFrame.encode(1, Data("first".utf8)))
        try check(peer.readExact(8) == DshWsFrame.encode(1, Data("second".utf8)))
    }
    test("large frames encode all 64-bit length bytes") {
        let data = Data(repeating: 7, count: 65536)
        let frame = DshWsFrame.encode(2, data)
        try check(frame.count == 65546 && frame[1] == 127)
        try check(Array(frame[2..<10]) == [0, 0, 0, 0, 0, 1, 0, 0])
    }
    test("masked binary fragments combine around an interleaved ping") {
        let messages = DshWsMessages()
        try check(try messages.accept(DshWsFrame(opcode: 2, payload: Data([1, 2]), final: false)) == nil)
        try check(try messages.accept(DshWsFrame(opcode: 9, payload: Data([3]), final: true))?.opcode == 9)
        let complete = try messages.accept(DshWsFrame(opcode: 0, payload: Data([4]), final: true))
        try check(complete?.opcode == 2 && complete?.payload == Data([1, 2, 4]))
        try expectFailure { _ = try messages.accept(DshWsFrame(opcode: 0, payload: Data(), final: true)) }
        try expectFailure { _ = try DshWsMessages().accept(DshWsFrame(opcode: 1, payload: Data([0xff]), final: true)) }
    }
    test("oversized or malformed client frame headers cannot allocate unbounded data") {
        var fds: [Int32] = [0, 0]
        try check(socketpair(AF_UNIX, SOCK_STREAM, 0, &fds) == 0)
        let reader = DshLocalSocket(fds[0]), writer = DshLocalSocket(fds[1])
        defer { reader.stop(); writer.stop() }
        reader.timeout(2)
        writer.write(Data([0x82, 0xff, 0, 0, 0, 1, 0, 0, 0, 1]))
        try check(DshWsFrame.read(reader) == nil)
    }
    test("descriptor is not reused after shutdown while an owner still exists") {
        var fds: [Int32] = [0, 0]
        try check(socketpair(AF_UNIX, SOCK_STREAM, 0, &fds) == 0)
        var owner: DshLocalSocket? = DshLocalSocket(fds[0]); let fd = fds[0]
        let peer = DshLocalSocket(fds[1]); defer { peer.stop() }
        owner?.stop(); owner?.stop()
        try check(fcntl(fd, F_GETFD) >= 0)
        owner = nil
        try check(fcntl(fd, F_GETFD) == -1)
    }
    test("request truncation and negative lengths never reach the Host") {
        let count = RelayTestBox(0)
        let gateway = DshRelayLoopbackServer(token: "local-token") { _, _, _ in count.set(count.get() + 1) }
        let port = try gateway.start(); defer { gateway.stop() }
        let bad = try localSocket(port); defer { bad.stop() }
        bad.write("POST /bad HTTP/1.1\r\nAuthorization: Bearer local-token\r\nContent-Length: -1\r\n\r\n")
        try check(bad.readHeaders()?.hasPrefix("HTTP/1.1 400") == true)
        let truncated = try localSocket(port); defer { truncated.stop() }
        truncated.write("POST /bad HTTP/1.1\r\nAuthorization: Bearer local-token\r\nContent-Length: 100\r\n\r\nabc")
        Darwin.shutdown(truncated.fd, SHUT_WR)
        try check(truncated.readExact(1) == nil)
        try check(count.get() == 0)
    }
    test("waiter close wakes sleeping consumers and bounds queued parts") {
        let waiter = DshRelayWaiter(), done = DispatchSemaphore(value: 0)
        let value = RelayTestBox<[String: Any]?>(nil)
        DispatchQueue.global().async { value.set(waiter.poll(120)); done.signal() }
        waiter.close()
        try check(done.wait(timeout: .now() + 1) == .success && value.get() == nil)
        try check(!waiter.offer(["late": true]))
        let bounded = DshRelayWaiter()
        for _ in 0..<64 { try check(bounded.offer([:])) }
        try check(!bounded.offer([:]))
    }
    test("response queue overflow closes only its own peer without waiting for the Host") {
        var gateway: DshRelayLoopbackServer!
        gateway = DshRelayLoopbackServer(token: "local-token") { type, _, channel in
            if type == "http_req" {
                for _ in 0..<65 { gateway.onInner(type: "http_res", payload: ["status": 200, "bodyB64": ""], channel: channel) }
            }
        }
        let port = try gateway.start(); defer { gateway.stop() }
        let socket = try localSocket(port); defer { socket.stop() }
        socket.write("GET /overflow HTTP/1.1\r\nAuthorization: Bearer local-token\r\n\r\n")
        try check(socket.readExact(1) == nil)
        try waitFor { gateway.activePeerCount == 0 }
        try check(gateway.isListening())
    }
}
