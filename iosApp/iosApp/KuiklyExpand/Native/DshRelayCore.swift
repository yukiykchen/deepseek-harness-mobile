import Foundation

struct DshRelayCredentials: CustomStringConvertible {
    let master: String
    let clientToken: String
    let hostId: String
    let hostName: String
    let origin: String
    var description: String { "RelayCredentials(hostId=\(hostId), secrets=[redacted])" }
}

struct DshRelayTicket {
    let sessionId: String
    let token: String
    let url: URL
    let hostId: String
}

protocol DshRelayCancellation: AnyObject { func cancel() }
protocol DshRelayCredentialStore: AnyObject {
    func loadCredentials() throws -> DshRelayCredentials?
    func saveCredentials(_ credentials: DshRelayCredentials) throws
    func clearCredentials()
}
protocol DshRelaySocket: DshRelayCancellation {
    func send(_ value: [String: Any], completion: @escaping (Error?) -> Void)
}
protocol DshRelayTransport: AnyObject {
    func ticket(_ credentials: DshRelayCredentials, completion: @escaping (Result<DshRelayTicket, Error>) -> Void) -> DshRelayCancellation
    func open(_ ticket: DshRelayTicket, receive: @escaping (DshRelaySocket, Result<String, Error>) -> Void) -> DshRelaySocket
}
protocol DshRelayGateway: AnyObject {
    var port: Int { get }
    func start() throws -> Int
    func stop()
    func isListening() -> Bool
    func onInner(type: String, payload: [String: Any], channel: String)
}

struct DshRelayHttpError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}

func dshRelayError(_ message: String) -> NSError {
    NSError(domain: "DshRelay", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
}

/// All lifecycle and cipher operations are serialized; each attempt owns its resources and callbacks.
final class DshRelayCore {
    typealias GatewayFactory = (String, @escaping (String, [String: Any], String) -> Void) -> DshRelayGateway
    typealias Scheduler = (TimeInterval, @escaping () -> Void) -> DshRelayCancellation
    private final class Attempt {
        let generation: Int64
        let credentials: DshRelayCredentials
        let random = DshSealedTunnelCrypto.encodeBase64Url(DshSealedTunnelCrypto.randomBytes())
        var ticket: DshRelayTicket?
        var ticketCall: DshRelayCancellation?
        var socket: DshRelaySocket?
        var deadline: DshRelayCancellation?
        var cipher: DshSecureCipher?
        var gateway: DshRelayGateway?
        init(_ generation: Int64, _ credentials: DshRelayCredentials) { self.generation = generation; self.credentials = credentials }
    }
    private let lock = NSRecursiveLock()
    private let store: DshRelayCredentialStore
    private let transport: DshRelayTransport
    private let gatewayFactory: GatewayFactory
    private let schedule: Scheduler
    private var generation: Int64 = 0
    private var enabled = false
    private var active: Attempt?
    private var retry: DshRelayCancellation?
    private var pairingCall: DshRelayCancellation?
    private var state: [String: Any] = ["phase": "IDLE", "message": "", "localPort": 0, "localToken": "", "paired": false, "generation": Int64(0)]
    private var listeners: [UInt: ([String: Any]) -> Void] = [:]
    private var listenerSeq: UInt = 0
    private var stateVersion: UInt64 = 0

    init(store: DshRelayCredentialStore, transport: DshRelayTransport, gatewayFactory: @escaping GatewayFactory,
         schedule: @escaping Scheduler) {
        self.store = store; self.transport = transport; self.gatewayFactory = gatewayFactory; self.schedule = schedule
        if let credentials = try? store.loadCredentials() { applyCredentials(credentials) }
    }
    func current() -> [String: Any] { locked { state } }
    func addListener(_ callback: @escaping ([String: Any]) -> Void) -> UInt { locked {
        listenerSeq += 1
        let token = listenerSeq
        listeners[token] = callback
        callback(state)
        return token
    } }
    func removeListener(_ token: UInt) { locked { _ = listeners.removeValue(forKey: token) } }
    func connect() { locked {
        enabled = true
        if let attempt = active, state["phase"] as? String != "READY" || attempt.gateway?.isListening() == true { return }
        startAttempt()
    } }
    func networkAvailable() { locked { if enabled && active == nil { startAttempt() } } }
    func disconnect() { locked {
        stopResources(); generation += 1
        publish("STOPPED", "扫码连接已断开", ["localPort": 0, "localToken": "", "generation": generation])
    } }
    func forget() { locked {
        stopResources(); generation += 1
        store.clearCredentials()
        state = ["localPort": 0, "localToken": "", "paired": false, "generation": generation, "hostId": "", "hostName": "", "relayOrigin": ""]
        publish("IDLE", "已移除配对")
    } }
    func beginPairing() -> Int64 { locked {
        stopResources(); generation += 1
        publish("PAIRING", "正在配对", ["generation": generation, "localPort": 0, "localToken": ""])
        return generation
    } }
    func completePairing(_ token: Int64, _ credentials: DshRelayCredentials) throws -> Bool { try locked {
        guard token == generation && state["phase"] as? String == "PAIRING" else { return false }
        try store.saveCredentials(credentials)
        pairingCall = nil
        applyCredentials(credentials)
        publish("IDLE", "已配对")
        return true
    } }
    func isPairing(_ token: Int64) -> Bool { locked { token == generation && state["phase"] as? String == "PAIRING" } }
    func failPairing(_ token: Int64, _ message: String) { locked {
        if token == generation && state["phase"] as? String == "PAIRING" { pairingCall = nil; publish("ERROR", message) }
    } }
    func registerPairingCall(_ token: Int64, _ call: DshRelayCancellation) { locked {
        if token == generation && state["phase"] as? String == "PAIRING" { pairingCall = call }
        else { call.cancel() }
    } }

    private func startAttempt() {
        retry?.cancel(); retry = nil
        releaseAttempt()
        guard let credentials = try? store.loadCredentials() else {
            enabled = false; generation += 1
            publish("ERROR", "请重新扫码配对电脑", ["paired": false, "generation": generation]); return
        }
        generation += 1
        let attempt = Attempt(generation, credentials)
        active = attempt
        applyCredentials(credentials)
        publish("CONNECTING", "正在申请访问票", ["generation": generation, "localPort": 0, "localToken": ""])
        guard accepts(attempt) else { return }
        let deadline = schedule(30) { [weak self, weak attempt] in
            guard let self, let attempt else { return }
            self.locked { if self.accepts(attempt) && attempt.cipher == nil { self.fail(attempt, dshRelayError("扫码隧道握手超时")) } }
        }
        guard accepts(attempt) else { deadline.cancel(); return }
        attempt.deadline = deadline
        let call = transport.ticket(credentials) { [weak self, weak attempt] result in
            guard let self, let attempt else { return }
            self.locked {
                guard self.accepts(attempt) else { return }
                switch result {
                case .success(let ticket): self.open(attempt, ticket)
                case .failure(let error): self.fail(attempt, error)
                }
            }
        }
        if accepts(attempt) { attempt.ticketCall = call } else { call.cancel() }
    }
    private func open(_ attempt: Attempt, _ ticket: DshRelayTicket) {
        guard accepts(attempt), attempt.ticket == nil else { return }
        attempt.ticket = ticket
        publish("HANDSHAKING", "正在建立加密隧道", ["hostId": ticket.hostId])
        guard accepts(attempt) else { return }
        let socket = transport.open(ticket) { [weak self, weak attempt] socket, result in
            guard let self, let attempt else { return }
            self.receive(attempt, socket, result)
        }
        guard accepts(attempt) else { socket.cancel(); return }
        attempt.socket = socket
        do {
            let proof = try DshSealedTunnelCrypto.clientProof(masterKeyB64: attempt.credentials.master,
                accessSessionId: ticket.sessionId, clientRandomB64: attempt.random)
            send(attempt, envelope("client_hello", ["accessSessionId": ticket.sessionId, "clientRandomB64": attempt.random, "clientProofB64": proof]))
        } catch { fail(attempt, error) }
    }
    private func receive(_ attempt: Attempt, _ socket: DshRelaySocket, _ result: Result<String, Error>) {
        var delivery: (DshRelayGateway, [String: Any])?
        locked {
            guard accepts(attempt) else { return }
            if attempt.socket == nil, case .failure(let error) = result { fail(attempt, error); return }
            guard attempt.socket === socket else { return }
            do {
                let text = try result.get()
                guard text.utf8.count <= 16 * 1024 * 1024, let bytes = text.data(using: .utf8),
                      let message = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
                      let payload = message["payload"] as? [String: Any] else { throw dshRelayError("非法隧道消息") }
                switch message["type"] as? String {
                case "server_hello": try establish(attempt, payload)
                case "sealed":
                    guard let cipher = attempt.cipher else { throw dshRelayError("隧道未完成握手") }
                    let value = try cipher.open(DshSealedPayload(seq: payload["seq"] as? String ?? "", ciphertextB64: payload["ciphertextB64"] as? String ?? ""))
                    if let gateway = attempt.gateway { delivery = (gateway, value) }
                case "device_close", "close": fail(attempt, dshRelayError(payload["reason"] as? String ?? "电脑连接已断开"))
                default: break
                }
            } catch { fail(attempt, error) }
        }
        // Do not hold the runtime lock during a potentially blocking local socket write.
        if let (gateway, value) = delivery {
            gateway.onInner(type: value["type"] as? String ?? "", payload: value["payload"] as? [String: Any] ?? [:], channel: value["channel"] as? String ?? "")
        }
    }
    private func establish(_ attempt: Attempt, _ payload: [String: Any]) throws {
        guard attempt.cipher == nil, let ticket = attempt.ticket else { return }
        let cipher = try DshSealedTunnelCrypto.createClientCipher(masterKeyB64: attempt.credentials.master,
            accessSessionId: ticket.sessionId, clientRandomB64: attempt.random,
            serverRandomB64: payload["serverRandomB64"] as? String ?? "", serverProofB64: payload["serverProofB64"] as? String ?? "")
        let token = DshSealedTunnelCrypto.encodeBase64Url(DshSealedTunnelCrypto.randomBytes(24))
        let gateway = gatewayFactory(token) { [weak self, weak attempt] type, payload, channel in
            guard let self, let attempt else { return }
            self.locked {
                guard self.accepts(attempt), attempt.cipher != nil else { return }
                do {
                    let sealed = try cipher.seal(self.envelope(type, payload, channel))
                    self.send(attempt, self.envelope("sealed", ["accessSessionId": ticket.sessionId, "seq": sealed.seq, "ciphertextB64": sealed.ciphertextB64]))
                } catch { self.fail(attempt, error) }
            }
        }
        attempt.gateway = gateway; attempt.cipher = cipher
        let port = try gateway.start()
        guard (1...65535).contains(port) else { throw dshRelayError("本机网关端口无效") }
        attempt.deadline?.cancel(); attempt.deadline = nil
        publish("READY", "扫码隧道已连接", ["localPort": port, "localToken": token])
    }
    private func send(_ attempt: Attempt, _ value: [String: Any]) {
        guard accepts(attempt), let socket = attempt.socket else { return }
        socket.send(value) { [weak self, weak attempt] error in
            guard let self, let attempt, let error else { return }
            self.locked { self.fail(attempt, error) }
        }
    }
    private func fail(_ attempt: Attempt, _ error: Error) {
        guard accepts(attempt) else { return }
        let permanent = (error as? DshRelayHttpError).map { [401, 403].contains($0.status) } == true || error is DshE2eeError
        var message = dshRelayNetworkMessage(error)
        for secret in [attempt.credentials.master, attempt.credentials.clientToken, attempt.ticket?.token ?? "", state["localToken"] as? String ?? ""] where !secret.isEmpty {
            message = message.replacingOccurrences(of: secret, with: "[redacted]")
        }
        releaseAttempt(); generation += 1
        let token = generation
        if permanent { enabled = false }
        publish(permanent ? "ERROR" : "RECONNECTING", String(message.prefix(512)), ["generation": token, "localPort": 0, "localToken": ""])
        guard enabled && token == generation else { return }
        let task = schedule(2) { [weak self] in self?.locked {
            guard let self, self.enabled && self.generation == token && self.active == nil else { return }
            self.retry = nil; self.startAttempt()
        } }
        if enabled && generation == token && active == nil { retry = task } else { task.cancel() }
    }
    private func accepts(_ attempt: Attempt) -> Bool { enabled && active === attempt && generation == attempt.generation }
    private func releaseAttempt() {
        guard let attempt = active else { return }
        active = nil
        attempt.deadline?.cancel(); attempt.ticketCall?.cancel(); attempt.socket?.cancel(); attempt.gateway?.stop()
        attempt.cipher = nil
    }
    private func stopResources() {
        enabled = false; retry?.cancel(); retry = nil
        let call = pairingCall; pairingCall = nil
        generation += 1
        call?.cancel()
        releaseAttempt()
    }
    private func applyCredentials(_ credentials: DshRelayCredentials) {
        state["hostId"] = credentials.hostId; state["hostName"] = credentials.hostName; state["relayOrigin"] = credentials.origin; state["paired"] = true
    }
    private func publish(_ phase: String, _ message: String, _ patch: [String: Any] = [:]) {
        state.merge(patch) { _, new in new }; state["phase"] = phase; state["message"] = message
        stateVersion += 1
        let version = stateVersion
        let snapshot = state
        for (token, callback) in listeners.sorted(by: { $0.key < $1.key }) {
            guard version == stateVersion else { break }
            if listeners[token] != nil { callback(snapshot) }
        }
    }
    private func envelope(_ type: String, _ payload: [String: Any], _ channel: String? = nil) -> [String: Any] {
        var value: [String: Any] = ["v": 1, "type": type, "id": UUID().uuidString, "ts": Int64(Date().timeIntervalSince1970 * 1000), "payload": payload]
        if let channel { value["channel"] = channel }; return value
    }
    @discardableResult private func locked<T>(_ action: () throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }; return try action()
    }
}
