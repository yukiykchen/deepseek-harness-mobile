import Darwin
import Foundation

struct RelayTestFailure: Error, CustomStringConvertible {
    let description: String
}
func check(_ value: @autoclosure () throws -> Bool, _ message: String = "check failed", file: StaticString = #filePath, line: UInt = #line) throws {
    if try !value() { throw RelayTestFailure(description: "\(file):\(line): \(message)") }
}
func expectFailure(_ work: () throws -> Void) throws {
    do { try work() } catch { return }; throw RelayTestFailure(description: "expected failure")
}
func waitFor(_ predicate: () -> Bool) throws {
    let deadline = Date().addingTimeInterval(3)
    while !predicate() && Date() < deadline { Thread.sleep(forTimeInterval: 0.005) }
    try check(predicate(), "condition timed out")
}
final class RelayTestBox<T> {
    private let lock = NSLock()
    private var value: T
    init(_ value: T) { self.value = value }
    func get() -> T { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ next: T) { lock.lock(); value = next; lock.unlock() }
}
final class FakeRelayStore: DshRelayCredentialStore {
    var credentials: DshRelayCredentials?
    var saves = 0
    init(_ credentials: DshRelayCredentials?) { self.credentials = credentials }
    func loadCredentials() throws -> DshRelayCredentials? { credentials }
    func saveCredentials(_ credentials: DshRelayCredentials) throws { self.credentials = credentials; saves += 1 }
    func clearCredentials() { credentials = nil }
}
final class FakeRelayTask: DshRelayCancellation {
    var cancelled = false
    let delay: TimeInterval
    let action: () -> Void
    init(_ delay: TimeInterval = 0, _ action: @escaping () -> Void = {}) { self.delay = delay; self.action = action }
    func cancel() { cancelled = true }
}
final class FakeRelaySocket: DshRelaySocket {
    var sent: [[String: Any]] = []
    var sends: [(Error?) -> Void] = []
    var cancelled = false
    let receive: (DshRelaySocket, Result<String, Error>) -> Void
    init(_ receive: @escaping (DshRelaySocket, Result<String, Error>) -> Void) { self.receive = receive }
    func send(_ value: [String: Any], completion: @escaping (Error?) -> Void) { sent.append(value); sends.append(completion) }
    func cancel() { cancelled = true }
    func message(_ value: [String: Any]) throws {
        receive(self, .success(String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self)))
    }
    func fail() { receive(self, .failure(URLError(.networkConnectionLost))) }
}
final class FakeRelayTransport: DshRelayTransport {
    var tickets: [(Result<DshRelayTicket, Error>) -> Void] = []
    var calls: [FakeRelayTask] = []
    var sockets: [FakeRelaySocket] = []
    var onOpen: (FakeRelaySocket) -> Void = { _ in }
    func ticket(_ credentials: DshRelayCredentials, completion: @escaping (Result<DshRelayTicket, Error>) -> Void) -> DshRelayCancellation {
        tickets.append(completion); let call = FakeRelayTask(); calls.append(call); return call
    }
    func open(_ ticket: DshRelayTicket, receive: @escaping (DshRelaySocket, Result<String, Error>) -> Void) -> DshRelaySocket {
        let socket = FakeRelaySocket(receive); sockets.append(socket); onOpen(socket); return socket
    }
    func succeed(_ session: String) {
        tickets.last?(.success(DshRelayTicket(sessionId: session, token: "ticket-token", url: URL(string: "ws://localhost/")!, hostId: "host")))
    }
}
final class FakeRelayGateway: DshRelayGateway {
    var port = 9876
    var stopped = false
    var delivered = 0
    var onDelivery: () -> Void = {}
    let send: (String, [String: Any], String) -> Void
    init(_ send: @escaping (String, [String: Any], String) -> Void) { self.send = send }
    func start() throws -> Int { port }
    func isListening() -> Bool { !stopped }
    func stop() { stopped = true }
    func onInner(type: String, payload: [String: Any], channel: String) { onDelivery(); if !stopped { delivered += 1 } }
}
final class RelayCoreFixture {
    let master = DshSealedTunnelCrypto.encodeBase64Url(Data((0..<32).map(UInt8.init)))
    let serverRandom = DshSealedTunnelCrypto.encodeBase64Url(Data((1...32).map(UInt8.init)))
    let transport = FakeRelayTransport()
    var gateways: [FakeRelayGateway] = []
    var tasks: [FakeRelayTask] = []
    lazy var store = FakeRelayStore(DshRelayCredentials(master: master, clientToken: "client-token", hostId: "host", hostName: "Host", origin: "http://localhost"))
    lazy var core = DshRelayCore(store: store, transport: transport, gatewayFactory: { [unowned self] _, send in
        let gateway = FakeRelayGateway(send); gateways.append(gateway); return gateway
    }, schedule: { [unowned self] delay, action in let task = FakeRelayTask(delay, action); tasks.append(task); return task })
    func ready(_ session: String = "session") throws -> FakeRelaySocket {
        core.connect(); transport.succeed(session)
        let socket = transport.sockets.last!
        let random = (socket.sent.first!["payload"] as! [String: Any])["clientRandomB64"] as! String
        try socket.message(["type": "server_hello", "payload": ["serverRandomB64": serverRandom,
            "serverProofB64": DshSealedTunnelCrypto.serverProof(masterKeyB64: master, accessSessionId: session, clientRandomB64: random, serverRandomB64: serverRandom)]])
        return socket
    }
    func host(_ socket: FakeRelaySocket, _ session: String) throws -> DshSecureCipher {
        let random = (socket.sent.first!["payload"] as! [String: Any])["clientRandomB64"] as! String
        return try DshSealedTunnelCrypto.createHostCipher(masterKeyB64: master, accessSessionId: session, clientRandomB64: random, serverRandomB64: serverRandom)
    }
}
func localSocket(_ port: Int) throws -> DshLocalSocket {
    let fd = Darwin.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
    guard fd >= 0 else { throw RelayTestFailure(description: "client socket failed") }
    let socket = DshLocalSocket(fd)
    var addr = sockaddr_in()
    addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); addr.sin_family = sa_family_t(AF_INET)
    addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1")); addr.sin_port = UInt16(port).bigEndian
    let result = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
        Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
    } }
    guard result == 0 else { throw RelayTestFailure(description: "client connect failed") }
    socket.timeout(2); return socket
}
let relayUpgrade = "GET /events HTTP/1.1\r\nAuthorization: Bearer local-token\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: MDEyMzQ1Njc4OWFiY2RlZg==\r\n\r\n"
