import Foundation

final class DshRelayWork: DshRelayCancellation {
    private let item: DispatchWorkItem
    init(delay: TimeInterval, action: @escaping () -> Void) {
        item = DispatchWorkItem(block: action)
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + delay, execute: item)
    }
    func cancel() { item.cancel() }
}

/// Bounded streaming HTTP response. Cancellation completes exactly once and breaks the URLSession delegate cycle.
final class DshRelayHttpOperation: NSObject, URLSessionDataDelegate, DshRelayCancellation {
    private let lock = NSLock()
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var response: HTTPURLResponse?
    private var bytes = Data()
    private var callback: ((Result<[String: Any], Error>) -> Void)?

    init(request: URLRequest, completion: @escaping (Result<[String: Any], Error>) -> Void) {
        callback = completion
        super.init()
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 30
        config.waitsForConnectivity = true
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.dataTask(with: request)
        self.task = task
        task.resume()
    }
    func cancel() { finish(.failure(URLError(.cancelled))) }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        lock.lock()
        let accept = callback != nil && response.expectedContentLength <= 64 * 1024 && response is HTTPURLResponse
        if accept { self.response = response as? HTTPURLResponse }
        lock.unlock()
        completionHandler(accept ? .allow : .cancel)
        if !accept { finish(.failure(dshRelayError("Relay 响应过大或无效"))) }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let oversized = data.count > 64 * 1024 - bytes.count
        if callback != nil && !oversized { bytes.append(data) }
        lock.unlock()
        if oversized { finish(.failure(dshRelayError("Relay 响应过大"))) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let response = self.response; let bytes = self.bytes; lock.unlock()
        let result: Result<[String: Any], Error> = Result {
            if let error { throw error }
            guard let response, let json = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
                throw dshRelayError("非法 Relay 响应")
            }
            guard (200...299).contains(response.statusCode) else {
                throw DshRelayHttpError(status: response.statusCode, message: json["error"] as? String ?? "Relay 请求失败")
            }
            return json
        }
        finish(result)
    }
    private func finish(_ result: Result<[String: Any], Error>) {
        lock.lock()
        let callback = self.callback
        self.callback = nil
        let session = self.session; self.session = nil; task = nil
        bytes.removeAll()
        lock.unlock()
        guard let callback else { return }
        session?.invalidateAndCancel()
        callback(result)
    }
}

final class DshURLSessionRelayTransport: DshRelayTransport {
    func ticket(_ credentials: DshRelayCredentials, completion: @escaping (Result<DshRelayTicket, Error>) -> Void) -> DshRelayCancellation {
        guard let url = URL(string: credentials.origin + "/access-ticket") else {
            completion(.failure(dshRelayError("Relay 地址无效")))
            return DshRelayWork(delay: 0) {}
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"; request.httpBody = Data("{}".utf8)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(credentials.clientToken)", forHTTPHeaderField: "Authorization")
        return DshRelayHttpOperation(request: request) { result in
            completion(result.flatMap { json in Result {
                let id = json["accessSessionId"] as? String ?? ""
                let token = json["ticket"] as? String ?? ""
                var fallback = URLComponents(string: credentials.origin)
                let scheme = fallback?.scheme == "https" ? "wss" : "ws"
                fallback?.scheme = scheme
                fallback?.path = "/client-tunnel"
                guard !id.isEmpty, !token.isEmpty, let raw = json["tunnelUrl"] as? String ?? fallback?.string,
                      let url = URL(string: raw), ["ws", "wss"].contains(url.scheme), url.host != nil, url.user == nil, url.password == nil else {
                    throw dshRelayError("访问票内容无效")
                }
                return DshRelayTicket(sessionId: id, token: token, url: url, hostId: json["hostId"] as? String ?? credentials.hostId)
            } })
        }
    }
    func open(_ ticket: DshRelayTicket, receive: @escaping (DshRelaySocket, Result<String, Error>) -> Void) -> DshRelaySocket {
        DshURLSessionRelaySocket(ticket, receive)
    }
}

private final class DshURLSessionRelaySocket: NSObject, DshRelaySocket, URLSessionWebSocketDelegate {
    private let lock = NSLock()
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var ping: DispatchSourceTimer?
    private var receiver: ((DshRelaySocket, Result<String, Error>) -> Void)?
    init(_ ticket: DshRelayTicket, _ receive: @escaping (DshRelaySocket, Result<String, Error>) -> Void) {
        receiver = receive
        super.init()
        var request = URLRequest(url: ticket.url)
        request.setValue("Bearer \(ticket.token)", forHTTPHeaderField: "Authorization")
        request.setValue("dsh-e2ee-v1", forHTTPHeaderField: "Sec-WebSocket-Protocol")
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = .infinity; config.timeoutIntervalForResource = .infinity
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.webSocketTask(with: request)
        task.maximumMessageSize = 16 * 1024 * 1024
        self.task = task
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now() + 20, repeating: 20)
        timer.setEventHandler { [weak self, weak task] in task?.sendPing { error in if let error { self?.deliver(.failure(error)) } } }
        ping = timer; timer.resume()
        task.resume()
        read(task)
    }
    func send(_ value: [String: Any], completion: @escaping (Error?) -> Void) {
        lock.lock(); let task = self.task; lock.unlock()
        guard let task else { completion(URLError(.cancelled)); return }
        do {
            let data = try JSONSerialization.data(withJSONObject: value)
            guard let text = String(data: data, encoding: .utf8) else { throw dshRelayError("非法隧道消息") }
            task.send(.string(text), completionHandler: completion)
        } catch { completion(error) }
    }
    func cancel() {
        lock.lock()
        receiver = nil
        let session = self.session; self.session = nil
        let task = self.task; self.task = nil
        let ping = self.ping; self.ping = nil
        lock.unlock()
        ping?.cancel(); task?.cancel(with: .goingAway, reason: nil); session?.invalidateAndCancel()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        deliver(.failure(error ?? URLError(.networkConnectionLost)))
    }
    private func read(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self, weak task] result in
            guard let self, let task else { return }
            switch result {
            case .success(.string(let text)): self.deliver(.success(text)); self.readIfActive(task)
            case .success(.data(let data)):
                if let text = String(data: data, encoding: .utf8) { self.deliver(.success(text)); self.readIfActive(task) }
                else { self.deliver(.failure(dshRelayError("非法 UTF-8 隧道消息"))) }
            case .failure(let error): self.deliver(.failure(error))
            @unknown default: self.deliver(.failure(dshRelayError("未知隧道消息")))
            }
        }
    }
    private func readIfActive(_ task: URLSessionWebSocketTask) {
        lock.lock(); let active = receiver != nil && self.task === task; lock.unlock()
        if active { read(task) }
    }
    private func deliver(_ result: Result<String, Error>) {
        lock.lock(); let callback = receiver; lock.unlock()
        callback?(self, result)
    }
}
