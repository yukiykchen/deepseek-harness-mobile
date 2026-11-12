import Darwin
import Foundation

final class RelayTestHost {
    private let listener: DshLocalSocket
    private let lock = NSLock()
    private var peers: [DshLocalSocket] = []
    let errors = RelayTestBox<[String]>([])
    let port: Int
    let master = DshSealedTunnelCrypto.encodeBase64Url(Data((0..<32).map(UInt8.init)))
    init() throws {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { throw dshRelayError("Test host socket failed") }
        listener = DshLocalSocket(fd)
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let bound = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        guard bound == 0 && Darwin.listen(fd, 10) == 0 else { throw dshRelayError("Test host bind failed") }
        var size = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { _ = getsockname(fd, $0, &size) } }
        port = Int(UInt16(bigEndian: addr.sin_port))
        Thread.detachNewThread { [weak self] in self?.run() }
    }
    func stop() {
        listener.stop(); lock.lock(); let peers = self.peers; self.peers.removeAll(); lock.unlock(); peers.forEach { $0.stop() }
    }
    private func run() {
        while !listener.closed {
            let fd = Darwin.accept(listener.fd, nil, nil)
            if fd < 0 { break }
            let peer = DshLocalSocket(fd)
            lock.lock(); peers.append(peer); lock.unlock()
            Thread.detachNewThread { [weak self] in
                guard let self else { peer.stop(); return }
                defer { peer.stop() }
                do { try self.handle(peer) } catch { self.errors.set(self.errors.get() + [String(describing: error)]) }
            }
        }
    }
    private func handle(_ peer: DshLocalSocket) throws {
        peer.timeout(3)
        guard let text = peer.readHeaders() else { return }
        let lines = text.components(separatedBy: "\r\n")
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            if let index = line.firstIndex(of: ":") { headers[line[..<index].lowercased()] = line[line.index(after: index)...].trimmingCharacters(in: .whitespaces) }
        }
        let length = Int(headers["content-length"] ?? "0") ?? 0
        _ = peer.readExact(length)
        if lines[0].contains("/access-ticket") {
            try check(headers["authorization"] == "Bearer client-token")
            let body = "{\"accessSessionId\":\"live-session\",\"ticket\":\"ticket-token\",\"hostId\":\"host\"}"
            peer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n" + body)
            return
        }
        try check(headers["authorization"] == "Bearer ticket-token")
        let key = headers["sec-websocket-key"]!
        let accept = Data(Insecure.SHA1.hash(data: Data((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8))).base64EncodedString()
        peer.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Protocol: dsh-e2ee-v1\r\nSec-WebSocket-Accept: \(accept)\r\n\r\n")
        guard let frame = DshWsFrame.read(peer) else { throw dshRelayError("Missing client hello") }
        let hello = try JSONSerialization.jsonObject(with: frame.payload) as! [String: Any]
        let payload = hello["payload"] as! [String: Any]
        let random = payload["clientRandomB64"] as! String
        let serverRandom = DshSealedTunnelCrypto.encodeBase64Url(Data((1...32).map(UInt8.init)))
        let proof = try DshSealedTunnelCrypto.serverProof(masterKeyB64: master, accessSessionId: "live-session", clientRandomB64: random, serverRandomB64: serverRandom)
        try send(peer, ["type": "server_hello", "payload": ["serverRandomB64": serverRandom, "serverProofB64": proof]])
        let cipher = try DshSealedTunnelCrypto.createHostCipher(masterKeyB64: master, accessSessionId: "live-session", clientRandomB64: random, serverRandomB64: serverRandom)
        while !peer.closed {
            guard let frame = DshWsFrame.read(peer) else { return }
            if frame.opcode == 8 { return }
            if frame.opcode == 9 { peer.write(DshWsFrame.encode(10, frame.payload)); continue }
            let msg = try JSONSerialization.jsonObject(with: frame.payload) as! [String: Any]
            let sealed = msg["payload"] as! [String: Any]
            let opened = try cipher.open(DshSealedPayload(seq: sealed["seq"] as! String, ciphertextB64: sealed["ciphertextB64"] as! String))
            try check(opened["type"] as? String == "http_req")
            let request = opened["payload"] as! [String: Any]
            try check(request["path"] as? String == "/canary")
            try check((request["headers"] as! [String: Any])["authorization"] == nil)
            let response = try cipher.seal(["type": "http_res", "channel": opened["channel"]!,
                "payload": ["status": 200, "bodyB64": Data("{\"ok\":true}".utf8).base64EncodedString(), "final": true]])
            try send(peer, ["type": "sealed", "payload": ["seq": response.seq, "ciphertextB64": response.ciphertextB64]])
        }
    }
    private func send(_ peer: DshLocalSocket, _ value: [String: Any]) throws { peer.write(DshWsFrame.encode(1, try JSONSerialization.data(withJSONObject: value))) }
}

import CryptoKit
func relayIntegrationTests(_ test: (String, () throws -> Void) -> Void) {
    test("production URLSession and gateway complete a real encrypted HTTP roundtrip") {
        let host = try RelayTestHost(); defer { host.stop() }
        let store = FakeRelayStore(DshRelayCredentials(master: host.master, clientToken: "client-token", hostId: "host", hostName: "Host", origin: "http://127.0.0.1:\(host.port)"))
        let core = DshRelayCore(store: store, transport: DshURLSessionRelayTransport(),
            gatewayFactory: { DshRelayLoopbackServer(token: $0, sendInner: $1) }, schedule: { DshRelayWork(delay: $0, action: $1) })
        let ready = DispatchSemaphore(value: 0)
        _ = core.addListener { if $0["phase"] as? String == "READY" { ready.signal() } }
        core.connect(); defer { core.disconnect() }
        try check(ready.wait(timeout: .now() + 5) == .success, "native WebSocket handshake failed: \(core.current())")
        let state = core.current()
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(state["localPort"] as! Int)/canary")!)
        request.setValue("Bearer \(state["localToken"] as! String)", forHTTPHeaderField: "Authorization")
        let result = RelayTestBox<Result<[String: Any], Error>?>(nil), done = DispatchSemaphore(value: 0)
        let call = DshRelayHttpOperation(request: request) { result.set($0); done.signal() }
        defer { call.cancel() }
        try check(done.wait(timeout: .now() + 5) == .success)
        let value = try result.get()!.get()
        try check(value["ok"] as? Bool == true && host.errors.get().isEmpty)
    }
    test("shared cryptographic fixture matches native Swift and control characters are valid canonical JSON") {
        let root = CommandLine.arguments[1]
        let data = try Data(contentsOf: URL(fileURLWithPath: root + "/androidApp/src/test/resources/sealed-tunnel-v1.json"))
        let fixture = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let master = fixture["masterKeyB64"] as! String, id = fixture["accessSessionId"] as! String
        let client = fixture["clientRandomB64"] as! String, server = fixture["serverRandomB64"] as! String
        try check(try DshSealedTunnelCrypto.deriveClaimToken(masterKeyB64: master) == fixture["claimTokenB64"] as? String)
        let host = try DshSealedTunnelCrypto.createHostCipher(masterKeyB64: master, accessSessionId: id, clientRandomB64: client, serverRandomB64: server)
        let value = try host.open(DshSealedPayload(seq: "0", ciphertextB64: fixture["httpReqCiphertextB64"] as! String))
        try check(value["type"] as? String == "http_req")
        let canonical = DshSealedTunnelCrypto.canonical(["line\n\t\0/\"", 1])
        let parsed = try JSONSerialization.jsonObject(with: canonical) as! [Any]
        try check(parsed[0] as? String == "line\n\t\0/\"")
    }
    test("sequence overflow cannot alias sequence zero and failed authentication does not advance it") {
        let root = CommandLine.arguments[1]
        let data = try Data(contentsOf: URL(fileURLWithPath: root + "/androidApp/src/test/resources/sealed-tunnel-v1.json"))
        let fixture = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let master = fixture["masterKeyB64"] as! String, id = fixture["accessSessionId"] as! String
        let client = fixture["clientRandomB64"] as! String, server = fixture["serverRandomB64"] as! String
        let host = try DshSealedTunnelCrypto.createHostCipher(masterKeyB64: master, accessSessionId: id, clientRandomB64: client, serverRandomB64: server)
        let ciphertext = fixture["httpReqCiphertextB64"] as! String
        try expectFailure { _ = try host.open(DshSealedPayload(seq: "18446744073709551616", ciphertextB64: ciphertext)) }
        try expectFailure { _ = try host.open(DshSealedPayload(seq: "00", ciphertextB64: ciphertext)) }
        var modified = try DshSealedTunnelCrypto.decodeBase64Url(ciphertext)
        modified[0] ^= 1
        try expectFailure { _ = try host.open(DshSealedPayload(seq: "0", ciphertextB64: DshSealedTunnelCrypto.encodeBase64Url(modified))) }
        _ = try host.open(DshSealedPayload(seq: "0", ciphertextB64: ciphertext))
        try expectFailure { _ = try host.open(DshSealedPayload(seq: "0", ciphertextB64: ciphertext)) }
    }
}
