import Darwin
import Foundation

/// Shutdown wakes blocked I/O. Only deinit closes the descriptor, after all readers/writers release this object.
final class DshLocalSocket {
    let fd: Int32
    private let stateLock = NSLock()
    private let writeLock = NSLock()
    private var ended = false
    init(_ fd: Int32) {
        self.fd = fd
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, socklen_t(MemoryLayout<Int32>.size))
    }
    deinit { Darwin.close(fd) }
    var closed: Bool { stateLock.lock(); defer { stateLock.unlock() }; return ended }
    func stop() {
        stateLock.lock(); defer { stateLock.unlock() }
        guard !ended else { return }; ended = true
        Darwin.shutdown(fd, SHUT_RDWR)
    }
    func timeout(_ seconds: Int) {
        var timeout = timeval(tv_sec: seconds, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
    }
    @discardableResult func write(_ data: Data) -> Bool {
        writeLock.lock(); defer { writeLock.unlock() }
        guard !closed else { return false }
        var offset = 0
        while offset < data.count {
            let n = data.withUnsafeBytes { Darwin.write(fd, $0.baseAddress!.advanced(by: offset), data.count - offset) }
            if n < 0 && errno == EINTR { continue }
            guard n > 0 else { stop(); return false }
            offset += n
        }
        return true
    }
    @discardableResult func write(_ text: String) -> Bool { write(Data(text.utf8)) }
    func readExact(_ count: Int) -> Data? {
        guard (0...DshWsFrame.maxBytes).contains(count), !closed else { return nil }
        if count == 0 { return Data() }
        var bytes = Data(count: count)
        var offset = 0
        while offset < count {
            let n = bytes.withUnsafeMutableBytes { Darwin.read(fd, $0.baseAddress!.advanced(by: offset), count - offset) }
            if n < 0 && errno == EINTR { continue }
            guard n > 0 else { return nil }
            offset += n
        }
        return bytes
    }
    func readHeaders() -> String? {
        var bytes = Data(), tail: UInt32 = 0
        while bytes.count < 64 * 1024 {
            guard let value = readExact(1)?.first else { return nil }
            bytes.append(value); tail = (tail << 8) | UInt32(value)
            if tail == 0x0d0a0d0a { return String(data: bytes, encoding: .isoLatin1) }
        }
        return nil
    }
}

struct DshWsFrame {
    static let maxBytes = 16 * 1024 * 1024
    let opcode: Int
    let payload: Data
    let final: Bool
    static func read(_ socket: DshLocalSocket) -> DshWsFrame? {
        guard let head = socket.readExact(2) else { return nil }
        let opcode = Int(head[0] & 15), final = head[0] & 0x80 != 0
        guard head[0] & 0x70 == 0, [0, 1, 2, 8, 9, 10].contains(opcode), head[1] & 0x80 != 0 else { return nil }
        let tag = Int(head[1] & 127)
        var length = UInt64(tag)
        if tag == 126 {
            guard let more = socket.readExact(2) else { return nil }
            length = UInt64(more[0]) << 8 | UInt64(more[1])
            guard length >= 126 else { return nil }
        } else if tag == 127 {
            guard let more = socket.readExact(8), more[0] & 0x80 == 0 else { return nil }
            length = more.reduce(0) { ($0 << 8) | UInt64($1) }
            guard length >= 65536 else { return nil }
        }
        guard length <= maxBytes, opcode < 8 || (final && length <= 125),
              let mask = socket.readExact(4), var bytes = socket.readExact(Int(length)) else { return nil }
        for index in bytes.indices { bytes[index] ^= mask[index % 4] }
        guard opcode != 8 || bytes.count != 1 else { return nil }
        return DshWsFrame(opcode: opcode, payload: bytes, final: final)
    }
    static func encode(_ opcode: Int, _ payload: Data) -> Data {
        var data = Data([UInt8(0x80 | opcode)])
        if payload.count < 126 { data.append(UInt8(payload.count)) }
        else if payload.count <= 65535 {
            data.append(126); data.append(UInt8(payload.count >> 8)); data.append(UInt8(payload.count & 255))
        } else {
            data.append(127)
            var value = UInt64(payload.count).bigEndian
            withUnsafeBytes(of: &value) { data.append(contentsOf: $0) }
        }
        data.append(payload); return data
    }
}

final class DshWsMessages {
    private var opcode: Int?
    private var bytes = Data()
    func accept(_ frame: DshWsFrame) throws -> DshWsFrame? {
        if frame.opcode >= 8 { return frame }
        if frame.opcode == 0 {
            guard opcode != nil else { throw dshRelayError("Unexpected continuation") }
        } else {
            guard opcode == nil else { throw dshRelayError("Nested WebSocket message") }
            opcode = frame.opcode
        }
        guard frame.payload.count <= DshWsFrame.maxBytes - bytes.count else { throw dshRelayError("WebSocket message too large") }
        bytes.append(frame.payload)
        guard frame.final, let opcode else { return nil }
        if opcode == 1 && String(data: bytes, encoding: .utf8) == nil { throw dshRelayError("Invalid UTF-8") }
        let result = DshWsFrame(opcode: opcode, payload: bytes, final: true)
        bytes = Data(); self.opcode = nil; return result
    }
}

final class DshRelayWaiter {
    private let condition = NSCondition()
    private var items: [[String: Any]] = []
    private var ended = false
    @discardableResult func offer(_ item: [String: Any]) -> Bool {
        condition.lock(); defer { condition.unlock() }
        guard !ended && items.count < 64 else { return false }
        items.append(item); condition.signal(); return true
    }
    func close() { condition.lock(); ended = true; items.removeAll(); condition.broadcast(); condition.unlock() }
    func poll(_ timeout: TimeInterval) -> [String: Any]? {
        condition.lock(); defer { condition.unlock() }
        let deadline = Date().addingTimeInterval(timeout)
        while items.isEmpty && !ended {
            if !condition.wait(until: deadline) { return nil }
        }
        return items.isEmpty ? nil : items.removeFirst()
    }
}
