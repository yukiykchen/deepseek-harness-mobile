import Foundation
import Network

/// Objective-C bridge; connection state and resources belong to the core rather than the view controller.
@objc(DshRelayRuntime)
final class DshRelayRuntime: NSObject {
    @objc static let shared = DshRelayRuntime()
    private let lock = NSLock()
    private var listeners: [UInt: (NSDictionary) -> Void] = [:]
    private var listenerSeq: UInt = 0
    private var monitor: NWPathMonitor?
    private let core: DshRelayCore

    private override init() {
        core = DshRelayCore(store: DshRelaySecrets(), transport: DshURLSessionRelayTransport(),
            gatewayFactory: { DshRelayLoopbackServer(token: $0, sendInner: $1) },
            schedule: { DshRelayWork(delay: $0, action: $1) })
        super.init()
        _ = core.addListener { [weak self] state in
            DispatchQueue.main.async { [weak self] in self?.publishIfCurrent(state) }
        }
    }
    @objc func currentState() -> NSDictionary { core.current() as NSDictionary }
    @objc func addListener(_ block: @escaping (NSDictionary) -> Void) -> UInt {
        lock.lock(); listenerSeq += 1; let token = listenerSeq; listeners[token] = block; lock.unlock()
        let state = core.current()
        DispatchQueue.main.async { [weak self] in
            guard let self, (self.core.current() as NSDictionary).isEqual(to: state) else { return }
            self.lock.lock(); let callback = self.listeners[token]; self.lock.unlock()
            callback?(state as NSDictionary)
        }
        return token
    }
    @objc func removeListener(_ token: UInt) { lock.lock(); listeners.removeValue(forKey: token); lock.unlock() }
    @objc func connect() { core.connect(); registerNetwork() }
    @objc func disconnect() { core.disconnect(); unregisterNetwork() }
    @objc func forgetPairing() { core.forget(); unregisterNetwork() }

    @objc func beginPairing() -> Int64 { core.beginPairing() }
    @objc func pairFromQr(_ qr: String) -> NSDictionary { pairFromQr(qr, generation: core.beginPairing()) }

    @objc func pairFromQr(_ qr: String, generation: Int64) -> NSDictionary {
        guard !Thread.isMainThread else { return ["ok": false, "message": "配对必须在后台线程运行"] }
        guard core.isPairing(generation) else { return ["ok": false, "message": "配对操作已取消"] }
        var token: Int64?
        do {
            let link = try dshParseRelayPairQr(qr)
            let claim = try DshSealedTunnelCrypto.deriveClaimToken(masterKeyB64: link.master)
            token = generation
            guard core.isPairing(generation) else { return ["ok": false, "message": "配对操作已取消"] }
            unregisterNetwork()
            DshLocalNetworkAccess.prepare(for: link.origin)
            guard let url = URL(string: link.origin + "/pair/claim-device") else { throw dshRelayError("二维码地址无效") }
            var request = URLRequest(url: url)
            request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: ["pairId": link.id, "claimToken": claim, "platform": "ios", "deviceLabel": "iOS"])
            let box = DshPairResponse()
            let call = DshRelayHttpOperation(request: request) { box.complete($0) }
            core.registerPairingCall(generation, call)
            guard box.signal.wait(timeout: .now() + 35) == .success else { call.cancel(); throw URLError(.timedOut) }
            let json = try box.value().get()
            let hostId = json["hostId"] as? String ?? ""
            let clientToken = json["clientToken"] as? String ?? ""
            guard !hostId.isEmpty, !clientToken.isEmpty else { throw dshRelayError("配对响应缺少凭据") }
            let credentials = DshRelayCredentials(master: link.master, clientToken: clientToken, hostId: hostId,
                hostName: json["hostName"] as? String ?? "电脑", origin: link.origin)
            guard try core.completePairing(generation, credentials) else { return ["ok": false, "message": "配对操作已取消"] }
            return ["ok": true, "hostId": hostId, "hostName": credentials.hostName, "relayOrigin": link.origin,
                    "pairedAt": Int64(Date().timeIntervalSince1970 * 1000)]
        } catch {
            // Server diagnostics may echo the QR master key or claim token.
            let native = error as NSError
            let message: String
            if token == nil { message = error.localizedDescription }
            else if native.domain == NSURLErrorDomain {
                message = native.code == NSURLErrorCancelled ? "配对操作已取消" : dshRelayNetworkMessage(error)
            } else { message = "配对失败，请检查网络与二维码后重试" }
            core.failPairing(generation, message)
            return ["ok": false, "message": message]
        }
    }
    private func publishIfCurrent(_ state: [String: Any]) {
        guard (core.current() as NSDictionary).isEqual(to: state) else { return }
        lock.lock(); let tokens = listeners.keys.sorted(); lock.unlock()
        for token in tokens {
            guard (core.current() as NSDictionary).isEqual(to: state) else { break }
            lock.lock(); let callback = listeners[token]; lock.unlock()
            callback?(state as NSDictionary)
        }
        if (core.current() as NSDictionary).isEqual(to: state), ["STOPPED", "ERROR", "PAIRING"].contains(state["phase"] as? String) {
            unregisterNetwork()
        }
    }
    private func registerNetwork() {
        let origin = core.current()["relayOrigin"] as? String ?? ""
        lock.lock(); defer { lock.unlock() }
        guard monitor == nil else { return }
        let next = NWPathMonitor()
        monitor = next
        next.pathUpdateHandler = { [weak self, weak next] path in
            guard let self, let next, path.status == .satisfied else { return }
            self.lock.lock(); let current = self.monitor === next; self.lock.unlock()
            if current { self.core.networkAvailable() }
        }
        next.start(queue: DispatchQueue(label: "dsh.relay.network"))
        DispatchQueue.global(qos: .utility).async { DshLocalNetworkAccess.prepare(for: origin) }
    }
    private func unregisterNetwork() {
        lock.lock(); let old = monitor; monitor = nil; lock.unlock()
        old?.cancel()
    }
}

private final class DshPairResponse {
    let signal = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var result: Result<[String: Any], Error>?
    func complete(_ result: Result<[String: Any], Error>) {
        lock.lock(); self.result = result; lock.unlock(); signal.signal()
    }
    func value() -> Result<[String: Any], Error> { lock.lock(); defer { lock.unlock() }; return result ?? .failure(URLError(.badServerResponse)) }
}

func dshParseRelayPairQr(_ raw: String) throws -> (origin: String, id: String, master: String) {
    guard var url = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
          ["http", "https"].contains(url.scheme), let host = url.host, !host.isEmpty,
          url.user == nil, url.password == nil, url.port.map({ (1...65535).contains($0) }) ?? true else {
        throw dshRelayError("二维码地址无效")
    }
    let fragment = url.percentEncodedFragment ?? ""
    if let separator = fragment.firstIndex(of: "?") { url.percentEncodedQuery = String(fragment[fragment.index(after: separator)...]) }
    else if fragment.contains("=") { url.percentEncodedQuery = fragment }
    let ids = url.queryItems?.filter { $0.name == "id" } ?? []
    let keys = url.queryItems?.filter { $0.name == "key" } ?? []
    guard ids.count == 1, keys.count == 1, let id = ids[0].value, let key = keys[0].value,
          !id.isEmpty, !key.isEmpty else { throw dshRelayError("二维码缺少唯一的配对参数") }
    if ["localhost", "127.0.0.1", "::1", "[::1]"].contains(host.lowercased()) {
        throw dshRelayError("二维码指向电脑本机，手机无法访问。请设置 PUBLIC_RELAY_URL 为局域网地址后重新生成二维码。")
    }
    url.path = ""; url.query = nil; url.fragment = nil
    guard let origin = url.string else { throw dshRelayError("二维码地址无效") }
    return (origin, id, key)
}

func dshRelayNetworkMessage(_ error: Error) -> String {
    let error = error as NSError
    if error.domain == NSURLErrorDomain {
        switch error.code {
        case NSURLErrorAppTransportSecurityRequiresSecureConnection:
            return "iOS 阻止了明文连接，请改用 HTTPS Relay 或检查 App 的网络配置。"
        case NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorCannotConnectToHost,
             NSURLErrorTimedOut, NSURLErrorDataNotAllowed, NSURLErrorCannotFindHost:
            return "无法连接 Relay，请检查网络；局域网连接还需在系统设置中允许本 App 访问本地网络。"
        case NSURLErrorCancelled: return "连接已取消"
        default: break
        }
    }
    return error.localizedDescription
}
