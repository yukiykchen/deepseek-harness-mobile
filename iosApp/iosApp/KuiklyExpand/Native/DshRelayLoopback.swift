import CommonCrypto
import Darwin
import Foundation

final class DshRelayLoopbackServer {
    private let token: String
    private let sendInner: (String, [String: Any], String) -> Void
    private let httpLimit = DshAtomicInt()
    private let wsLimit = DshAtomicInt()
    private var httpWaiters: [String: DshWaiter] = [:]
    private let waiterLock = NSLock()
    private var sockets: [String: Int32] = [:]
    private let socketsLock = NSLock()
    private var listenFd: Int32 = -1
    private(set) var port: Int = 0
    private var stopped = false
    private var acceptThread: Thread?

    init(token: String, sendInner: @escaping (String, [String: Any], String) -> Void) {
        self.token = token
        self.sendInner = sendInner
    }

    func start() throws -> Int {
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        addr.sin_port = 0
        listenFd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard listenFd >= 0 else { throw DshLoopbackError.bindFailed }
        var reuse: Int32 = 1
        setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        let bindResult = withUnsafePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(listenFd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0, Darwin.listen(listenFd, 32) == 0 else {
            Darwin.close(listenFd)
            listenFd = -1
            throw DshLoopbackError.bindFailed
        }
        var bound = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &bound) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(listenFd, $0, &len)
            }
        }
        port = Int(UInt16(bigEndian: bound.sin_port))
        let thread = Thread { [weak self] in self?.acceptLoop() }
        thread.name = "dsh-relay-loopback"
        acceptThread = thread
        thread.start()
        return port
    }

    func isListening() -> Bool {
        !stopped && listenFd >= 0 && port > 0
    }

    func stop() {
        stopped = true
        if listenFd >= 0 {
            Darwin.close(listenFd)
            listenFd = -1
        }
        socketsLock.lock()
        sockets.values.forEach { Darwin.close($0) }
        sockets.removeAll()
        socketsLock.unlock()
        waiterLock.lock()
        httpWaiters.removeAll()
        waiterLock.unlock()
    }

    func onInner(type: String, payload: [String: Any], channel: String) {
        switch type {
        case "http_res":
            waiter(channel)?.offer(payload)
        case "ws_open_ok":
            waiter(channel)?.offer(["ok": true])
        case "ws_frame":
            writeWsFrame(channel: channel, payload: payload)
        case "ws_close":
            if let waiter = waiter(channel) {
                let reason = (payload["reason"] as? String)?.nilIfEmpty ?? "host websocket closed"
                waiter.offer(["ok": false, "reason": reason])
            } else {
                socketsLock.lock()
                if let fd = sockets.removeValue(forKey: channel) {
                    Darwin.close(fd)
                }
                socketsLock.unlock()
            }
        default:
            break
        }
    }

    private func acceptLoop() {
        while !stopped {
            var addr = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let client = withUnsafeMutablePointer(to: &addr) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.accept(listenFd, $0, &len)
                }
            }
            if client < 0 {
                if stopped { return }
                continue
            }
            Thread.detachNewThread { [weak self] in
                self?.handle(client)
            }
        }
    }

    private func handle(_ fd: Int32) {
        defer { Darwin.close(fd) }
        var timeout = timeval(tv_sec: 120, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        guard let headerBytes = readHeaders(fd) else { return }
        let text = String(bytes: headerBytes, encoding: .isoLatin1) ?? ""
        let lines = text.components(separatedBy: "\r\n")
        let request = lines.first?.split(separator: " ", omittingEmptySubsequences: false).map(String.init) ?? []
        guard request.count >= 2 else { return }
        let method = request[0]
        let path = request[1]
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let idx = line.firstIndex(of: ":") else { continue }
            let name = line[..<idx].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: idx)...].trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }
        if headers["authorization"] != "Bearer \(token)" {
            writeHttp(fd, status: 401, body: "unauthorized")
            return
        }
        if headers["upgrade"]?.lowercased() == "websocket" {
            acceptWebSocket(fd, path: path, headers: headers)
            return
        }
        let length = Int(headers["content-length"] ?? "0") ?? 0
        if length > 2 * 1024 * 1024 {
            writeHttp(fd, status: 413, body: "request too large")
            return
        }
        let body = length > 0 ? (readExact(fd, length) ?? Data()) : Data()
        proxyHttp(fd, method: method, path: path, headers: headers, body: body)
    }

    private func proxyHttp(_ fd: Int32, method: String, path: String, headers: [String: String], body: Data) {
        if httpLimit.increment() > 32 {
            httpLimit.decrement()
            writeHttp(fd, status: 429, body: "too many tunnels")
            return
        }
        defer { httpLimit.decrement() }
        let channel = "http-\(UUID().uuidString)"
        let queue = DshWaiter()
        setWaiter(channel, queue)
        defer { removeWaiter(channel) }
        var forwarded: [String: Any] = [:]
        for (key, value) in headers where key != "authorization" && key != "host" && key != "content-length" {
            forwarded[key] = value
        }
        sendInner(
            "http_req",
            [
                "method": method,
                "path": path,
                "headers": forwarded,
                "bodyB64": body.base64EncodedString(),
            ],
            channel
        )
        var headerWritten = false
        while true {
            guard let part = queue.poll(timeout: 120) else { break }
            if !headerWritten {
                let status = intValue(part["status"], fallback: 200)
                writeRaw(fd, "HTTP/1.1 \(status) OK\r\n")
                writeRaw(fd, "Transfer-Encoding: chunked\r\nConnection: close\r\n")
                if let responseHeaders = part["headers"] as? [String: Any] {
                    for (name, value) in responseHeaders {
                        if name.lowercased() == "content-length" || name.lowercased() == "transfer-encoding" {
                            continue
                        }
                        // Node serializes multi-value headers (Set-Cookie) as arrays; emit one line each.
                        if let values = value as? [Any] {
                            for item in values { writeRaw(fd, "\(name): \(item)\r\n") }
                        } else {
                            writeRaw(fd, "\(name): \(value)\r\n")
                        }
                    }
                }
                writeRaw(fd, "\r\n")
                headerWritten = true
            }
            let chunk = Data(base64Encoded: part["bodyB64"] as? String ?? "") ?? Data()
            if !chunk.isEmpty {
                writeRaw(fd, "\(String(chunk.count, radix: 16))\r\n")
                _ = chunk.withUnsafeBytes { Darwin.write(fd, $0.baseAddress, chunk.count) }
                writeRaw(fd, "\r\n")
            }
            if boolValue(part["final"]) {
                writeRaw(fd, "0\r\n\r\n")
                break
            }
        }
    }

    private func acceptWebSocket(_ fd: Int32, path: String, headers: [String: String]) {
        if wsLimit.increment() > 16 {
            wsLimit.decrement()
            writeHttp(fd, status: 503, body: "too many tunnels")
            return
        }
        let channel = "ws-\(UUID().uuidString)"
        let queue = DshWaiter()
        setWaiter(channel, queue)
        var forwarded: [String: Any] = [:]
        for (name, value) in headers where name != "authorization" && name != "host" {
            forwarded[name] = value
        }
        sendInner("ws_open", ["path": path, "headers": forwarded], channel)
        let opened = queue.poll(timeout: 10)
        removeWaiter(channel)
        if opened == nil || !boolValue(opened?["ok"]) {
            wsLimit.decrement()
            let reason: String
            if opened == nil {
                reason = "host websocket timeout"
            } else {
                reason = (opened?["reason"] as? String)?.nilIfEmpty ?? "host websocket rejected"
            }
            NSLog("[DshRelayLoopback] ws_open failed path=%@ reason=%@", path, reason)
            writeHttp(fd, status: 502, body: reason)
            return
        }
        let accept = websocketAccept(headers["sec-websocket-key"] ?? "")
        writeRaw(fd, "HTTP/1.1 101 Switching Protocols\r\n")
        writeRaw(fd, "Upgrade: websocket\r\nConnection: Upgrade\r\n")
        writeRaw(fd, "Sec-WebSocket-Accept: \(accept)\r\n\r\n")
        var disableTimeout = timeval(tv_sec: 0, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &disableTimeout, socklen_t(MemoryLayout<timeval>.size))
        socketsLock.lock()
        sockets[channel] = fd
        socketsLock.unlock()
        defer {
            socketsLock.lock()
            sockets.removeValue(forKey: channel)
            socketsLock.unlock()
            wsLimit.decrement()
        }
        while !stopped {
            guard let frame = readWsFrame(fd) else { break }
            switch frame.opcode {
            case 8:
                sendInner("ws_close", ["code": 1000, "reason": ""], channel)
                return
            case 9:
                writeUnmasked(fd, opcode: 10, payload: frame.payload)
            case 10:
                continue
            case 0, 1, 2:
                sendInner(
                    "ws_frame",
                    [
                        "dataB64": frame.payload.base64EncodedString(),
                        "opcode": frame.opcode == 2 ? 2 : 1,
                    ],
                    channel
                )
            default:
                continue
            }
        }
    }

    private func writeWsFrame(channel: String, payload: [String: Any]) {
        socketsLock.lock()
        let fd = sockets[channel]
        socketsLock.unlock()
        guard let fd else { return }
        let data = Data(base64Encoded: payload["dataB64"] as? String ?? "") ?? Data()
        let opcode = intValue(payload["opcode"], fallback: 1) == 2 ? 2 : 1
        writeUnmasked(fd, opcode: opcode, payload: data)
    }

    private func writeUnmasked(_ fd: Int32, opcode: Int, payload: Data) {
        socketsLock.lock()
        defer { socketsLock.unlock() }
        var header = Data([UInt8(0x80 | (opcode & 0x0f))])
        if payload.count < 126 {
            header.append(UInt8(payload.count))
        } else {
            header.append(126)
            header.append(UInt8((payload.count >> 8) & 0xff))
            header.append(UInt8(payload.count & 0xff))
        }
        _ = header.withUnsafeBytes { Darwin.write(fd, $0.baseAddress, header.count) }
        _ = payload.withUnsafeBytes { Darwin.write(fd, $0.baseAddress, payload.count) }
    }

    private func readWsFrame(_ fd: Int32) -> (opcode: Int, payload: Data)? {
        guard let head = readExact(fd, 2), head.count == 2 else { return nil }
        let opcode = Int(head[0] & 0x0f)
        let masked = (head[1] & 0x80) != 0
        var len = Int(head[1] & 0x7f)
        if len == 126 {
            guard let extra = readExact(fd, 2), extra.count == 2 else { return nil }
            len = (Int(extra[0]) << 8) | Int(extra[1])
        } else if len == 127 {
            guard let extra = readExact(fd, 8), extra.count == 8 else { return nil }
            len = extra.reduce(0) { ($0 << 8) | Int($1) }
        }
        let mask = masked ? (readExact(fd, 4) ?? Data()) : Data()
        guard var data = readExact(fd, len) else { return nil }
        if masked && mask.count == 4 {
            for i in 0..<data.count {
                data[i] ^= mask[i % 4]
            }
        }
        return (opcode, data)
    }

    private func websocketAccept(_ key: String) -> String {
        let magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA1_DIGEST_LENGTH))
        let bytes = Array((key + magic).utf8)
        _ = bytes.withUnsafeBytes { CC_SHA1($0.baseAddress, CC_LONG(bytes.count), &digest) }
        return Data(digest).base64EncodedString()
    }

    private func readHeaders(_ fd: Int32) -> Data? {
        var buffer = Data()
        var last: UInt8 = 0
        var byte: UInt8 = 0
        while true {
            let n = Darwin.read(fd, &byte, 1)
            if n <= 0 { return nil }
            buffer.append(byte)
            if last == 13 && byte == 10 && buffer.count >= 4 {
                if buffer[buffer.count - 4] == 13 && buffer[buffer.count - 3] == 10 {
                    return buffer
                }
            }
            last = byte
            if buffer.count > 64 * 1024 { return nil }
        }
    }

    private func readExact(_ fd: Int32, _ count: Int) -> Data? {
        var data = Data(count: count)
        var offset = 0
        while offset < count {
            let n = data.withUnsafeMutableBytes { raw in
                Darwin.read(fd, raw.baseAddress!.advanced(by: offset), count - offset)
            }
            if n <= 0 { break }
            offset += n
        }
        return offset == count ? data : data.prefix(offset)
    }

    private func writeHttp(_ fd: Int32, status: Int, body: String) {
        let bytes = Data(body.utf8)
        writeRaw(fd, "HTTP/1.1 \(status) ERROR\r\nContent-Length: \(bytes.count)\r\nConnection: close\r\n\r\n")
        _ = bytes.withUnsafeBytes { Darwin.write(fd, $0.baseAddress, bytes.count) }
    }

    private func writeRaw(_ fd: Int32, _ text: String) {
        let bytes = Array(text.utf8)
        _ = bytes.withUnsafeBytes { Darwin.write(fd, $0.baseAddress, bytes.count) }
    }

    private func waiter(_ channel: String) -> DshWaiter? {
        waiterLock.lock()
        defer { waiterLock.unlock() }
        return httpWaiters[channel]
    }

    private func setWaiter(_ channel: String, _ waiter: DshWaiter) {
        waiterLock.lock()
        httpWaiters[channel] = waiter
        waiterLock.unlock()
    }

    private func removeWaiter(_ channel: String) {
        waiterLock.lock()
        httpWaiters.removeValue(forKey: channel)
        waiterLock.unlock()
    }
}

private enum DshLoopbackError: Error {
    case bindFailed
}

private final class DshWaiter {
    private let lock = NSCondition()
    private var items: [[String: Any]] = []

    func offer(_ item: [String: Any]) {
        lock.lock()
        items.append(item)
        lock.signal()
        lock.unlock()
    }

    func poll(timeout: TimeInterval) -> [String: Any]? {
        lock.lock()
        defer { lock.unlock() }
        let deadline = Date().addingTimeInterval(timeout)
        while items.isEmpty {
            let remaining = deadline.timeIntervalSinceNow
            if remaining <= 0 { return nil }
            _ = lock.wait(until: Date().addingTimeInterval(remaining))
        }
        return items.removeFirst()
    }
}

private final class DshAtomicInt {
    private let lock = NSLock()
    private var value = 0

    func increment() -> Int {
        lock.lock()
        value += 1
        let next = value
        lock.unlock()
        return next
    }

    func decrement() {
        lock.lock()
        value -= 1
        lock.unlock()
    }
}

private func intValue(_ value: Any?, fallback: Int) -> Int {
    if let number = value as? Int { return number }
    if let number = value as? NSNumber { return number.intValue }
    if let text = value as? String, let number = Int(text) { return number }
    return fallback
}

private func boolValue(_ value: Any?) -> Bool {
    if let flag = value as? Bool { return flag }
    if let number = value as? NSNumber { return number.boolValue }
    if let text = value as? String { return text == "true" || text == "1" }
    return false
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
