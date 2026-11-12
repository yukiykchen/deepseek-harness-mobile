import CryptoKit
import Darwin
import Foundation

final class DshRelayLoopbackServer: DshRelayGateway {
    private final class WebSocketPeer {
        let socket: DshLocalSocket
        let lock = NSLock()
        var upgraded = false
        var pending: [Data] = []
        var pendingBytes = 0
        init(_ socket: DshLocalSocket) { self.socket = socket }
        func write(_ data: Data) {
            lock.lock(); defer { lock.unlock() }
            if upgraded { socket.write(data) }
            else if pending.count < 64 && data.count <= DshWsFrame.maxBytes - pendingBytes {
                pending.append(data); pendingBytes += data.count
            } else { socket.stop() }
        }
    }
    private let lock = NSLock()
    private let token: String
    private let sendInner: (String, [String: Any], String) -> Void
    private let responseTimeout: TimeInterval
    private var listener: DshLocalSocket?
    private var peers: [UUID: DshLocalSocket] = [:]
    private var waiters: [String: DshRelayWaiter] = [:]
    private var waiterSockets: [String: DshLocalSocket] = [:]
    private var webSockets: [String: WebSocketPeer] = [:]
    private var httpCount = 0
    private var wsCount = 0
    private var ended = false
    private var boundPort = 0
    var port: Int { locked { boundPort } }
    var activePeerCount: Int { locked { peers.count } }
    init(token: String, responseTimeout: TimeInterval = 120, sendInner: @escaping (String, [String: Any], String) -> Void) {
        self.token = token; self.sendInner = sendInner; self.responseTimeout = responseTimeout
    }
    func start() throws -> Int { try locked {
        guard !ended && listener == nil else { throw dshRelayError("Loopback already started or stopped") }
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { throw dshRelayError("Loopback socket failed") }
        let socket = DshLocalSocket(fd)
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); address.sin_family = sa_family_t(AF_INET)
        address.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let result = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        } }
        guard result == 0, Darwin.listen(fd, 32) == 0 else { throw dshRelayError("Loopback bind failed") }
        var size = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &size) } }
        guard named == 0 else { throw dshRelayError("Loopback address failed") }
        boundPort = Int(UInt16(bigEndian: address.sin_port)); listener = socket
        Thread.detachNewThread { [weak self] in self?.accept(socket) }
        return boundPort
    } }
    func isListening() -> Bool { locked { !ended && listener != nil && boundPort > 0 } }
    func stop() {
        let resources: (DshLocalSocket?, [DshLocalSocket], [DshRelayWaiter]) = locked {
            ended = true; boundPort = 0
            let resources = (listener, Array(peers.values), Array(waiters.values))
            listener = nil; waiters.removeAll(); waiterSockets.removeAll(); webSockets.removeAll()
            return resources
        }
        resources.0?.stop(); resources.1.forEach { $0.stop() }; resources.2.forEach { $0.close() }
    }
    func onInner(type: String, payload: [String: Any], channel: String) {
        let targets = locked { (ended, waiters[channel], webSockets[channel], waiterSockets[channel]) }
        guard !targets.0 else { return }
        switch type {
        case "http_res": if targets.1?.offer(payload) == false { targets.3?.stop(); targets.1?.close() }
        case "ws_open_ok":
            if let waiter = targets.1 {
                if !waiter.offer(["ok": true]) { targets.3?.stop(); waiter.close() }
            } else { sendInner("ws_close", ["code": 1001, "reason": "gateway closed"], channel) }
        case "ws_close":
            _ = targets.1?.offer(["ok": false, "reason": payload["reason"] ?? "host WebSocket closed"])
            targets.2?.socket.stop()
        case "ws_frame":
            guard let peer = targets.2, let text = payload["dataB64"] as? String,
                  let bytes = Data(base64Encoded: text), bytes.count <= DshWsFrame.maxBytes else { return }
            peer.write(DshWsFrame.encode((payload["opcode"] as? Int) == 2 ? 2 : 1, bytes))
        default: break
        }
    }
    private func accept(_ listener: DshLocalSocket) {
        while !listener.closed {
            let fd = Darwin.accept(listener.fd, nil, nil)
            if fd < 0 { if errno == EINTR { continue }; break }
            let socket = DshLocalSocket(fd), id = UUID()
            let registered = locked { () -> Bool in
                guard !ended && peers.count < 64 else { return false }
                peers[id] = socket; return true
            }
            guard registered else { socket.stop(); continue }
            Thread.detachNewThread { [weak self] in
                guard let self else { socket.stop(); return }
                defer { socket.stop(); self.locked { _ = self.peers.removeValue(forKey: id) } }
                self.handle(socket)
            }
        }
    }
    private func handle(_ socket: DshLocalSocket) {
        socket.timeout(10)
        guard let header = socket.readHeaders() else { return }
        let lines = header.components(separatedBy: "\r\n")
        let request = lines[0].split(separator: " ").map(String.init)
        guard request.count == 3, request[1].hasPrefix("/"), ["HTTP/1.0", "HTTP/1.1"].contains(request[2]) else {
            http(socket, 400, "invalid request"); return
        }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            guard let separator = line.firstIndex(of: ":") else { http(socket, 400, "invalid header"); return }
            let name = line[..<separator].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: separator)...].trimmingCharacters(in: .whitespaces)
            guard validHeader(name, value), headers[name] == nil else { http(socket, 400, "invalid header"); return }
            headers[name] = value
        }
        guard headers["authorization"] == "Bearer \(token)" else { http(socket, 401, "unauthorized"); return }
        if headers["upgrade"]?.lowercased() == "websocket" { upgrade(socket, request[1], headers); return }
        guard let length = Int(headers["content-length"] ?? "0"), length >= 0, headers["transfer-encoding"] == nil else {
            http(socket, 400, "unsupported body"); return
        }
        guard length <= 2 * 1024 * 1024 else { http(socket, 413, "request too large"); return }
        guard let body = socket.readExact(length) else { return }
        proxyHttp(socket, request[0], request[1], headers, body)
    }
    private func proxyHttp(_ socket: DshLocalSocket, _ method: String, _ path: String, _ headers: [String: String], _ body: Data) {
        let channel = "http-\(UUID().uuidString)", waiter = DshRelayWaiter()
        let accepted = locked { () -> Bool in
            guard !ended && httpCount < 32 else { return false }
            httpCount += 1; waiters[channel] = waiter; waiterSockets[channel] = socket; return true
        }
        guard accepted else { http(socket, 429, "too many requests"); return }
        defer { waiter.close(); locked { waiters.removeValue(forKey: channel); waiterSockets.removeValue(forKey: channel); httpCount -= 1 } }
        sendInner("http_req", ["method": method, "path": path, "headers": forwarded(headers), "bodyB64": body.base64EncodedString()], channel)
        var headersSent = false
        while !socket.closed {
            guard let value = waiter.poll(responseTimeout) else { if !socket.closed && !headersSent { http(socket, 504, "host timeout") }; break }
            if !headersSent {
                let status = (value["status"] as? NSNumber)?.intValue ?? 200
                let pairs = value["headers"] as? [String: String] ?? [:]
                guard (100...599).contains(status), pairs.allSatisfy({ validHeader($0.key, $0.value) }) else { return }
                var response = "HTTP/1.1 \(status) OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n"
                for (key, value) in pairs where !Self.hopHeaders.contains(key.lowercased()) { response += "\(key): \(value)\r\n" }
                guard socket.write(response + "\r\n") else { return }; headersSent = true
            }
            guard let bytes = Data(base64Encoded: value["bodyB64"] as? String ?? "") else { return }
            if !bytes.isEmpty {
                var chunk = Data("\(String(bytes.count, radix: 16))\r\n".utf8); chunk.append(bytes); chunk.append(Data("\r\n".utf8))
                guard socket.write(chunk) else { return }
            }
            if (value["final"] as? NSNumber)?.boolValue == true { socket.write("0\r\n\r\n"); return }
        }
    }
    private func upgrade(_ socket: DshLocalSocket, _ path: String, _ headers: [String: String]) {
        guard let key = headers["sec-websocket-key"], Data(base64Encoded: key)?.count == 16,
              headers["sec-websocket-version"] == "13" else { http(socket, 400, "invalid WebSocket handshake"); return }
        let channel = "ws-\(UUID().uuidString)", waiter = DshRelayWaiter(), peer = WebSocketPeer(socket)
        let accepted = locked { () -> Bool in
            guard !ended && wsCount < 16 else { return false }
            wsCount += 1; waiters[channel] = waiter; waiterSockets[channel] = socket; webSockets[channel] = peer; return true
        }
        guard accepted else { http(socket, 503, "too many WebSockets"); return }
        defer {
            waiter.close()
            let closed = locked { () -> Bool in
                waiters.removeValue(forKey: channel); waiterSockets.removeValue(forKey: channel)
                webSockets.removeValue(forKey: channel); wsCount -= 1; return ended
            }
            if !closed { sendInner("ws_close", ["code": 1000, "reason": ""], channel) }
        }
        sendInner("ws_open", ["path": path, "headers": forwarded(headers)], channel)
        let response = waiter.poll(10)
        locked { _ = waiters.removeValue(forKey: channel) }
        guard !socket.closed else { return }
        guard (response?["ok"] as? NSNumber)?.boolValue == true else { http(socket, 502, "host WebSocket rejected"); return }
        let accept = Data(Insecure.SHA1.hash(data: Data((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8))).base64EncodedString()
        peer.lock.lock()
        let sent = socket.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: \(accept)\r\n\r\n")
        if sent {
            peer.upgraded = true
            for data in peer.pending { socket.write(data) }; peer.pending.removeAll(); peer.pendingBytes = 0
        }
        peer.lock.unlock()
        guard sent else { return }
        socket.timeout(0)
        let messages = DshWsMessages()
        while !socket.closed {
            guard let frame = DshWsFrame.read(socket) else { return }
            switch frame.opcode {
            case 8: peer.write(DshWsFrame.encode(8, frame.payload)); return
            case 9: peer.write(DshWsFrame.encode(10, frame.payload))
            case 10: break
            default:
                do {
                    if let message = try messages.accept(frame) {
                        sendInner("ws_frame", ["opcode": message.opcode, "dataB64": message.payload.base64EncodedString()], channel)
                    }
                } catch { return }
            }
        }
    }
    private func http(_ socket: DshLocalSocket, _ status: Int, _ text: String) {
        let bytes = Data(text.utf8)
        var response = Data("HTTP/1.1 \(status) ERROR\r\nContent-Length: \(bytes.count)\r\nConnection: close\r\n\r\n".utf8)
        response.append(bytes); socket.write(response)
    }
    private func validHeader(_ name: String, _ value: String) -> Bool {
        name.range(of: "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$", options: .regularExpression) != nil && !value.contains("\r") && !value.contains("\n") && !value.contains("\0")
    }
    private func forwarded(_ headers: [String: String]) -> [String: String] {
        headers.filter { !Self.hopHeaders.contains($0.key) && $0.key != "authorization" && $0.key != "host" }
    }
    private static let hopHeaders: Set<String> = ["connection", "transfer-encoding", "content-length", "upgrade", "keep-alive", "trailer"]
    @discardableResult private func locked<T>(_ body: () throws -> T) rethrows -> T { lock.lock(); defer { lock.unlock() }; return try body() }
}
