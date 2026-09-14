package com.example.dsh.dsh

import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout

/** Supplies and persists the browser-session credentials one Host connection needs. */
internal interface DshHostAuthenticator {
    /** `name=value` cookie for `/api`, or empty when none has been minted yet. */
    fun cookie(): String

    /** The `?token=` value printed by `dsh web`, or empty when the relay plugin must supply it. */
    fun launchToken(): String

    /** Whether `GET /dsh-scan-remote/api/auth` may be used to discover the launch token. */
    val relayTokenAvailable: Boolean

    /** Remember a token discovered through the relay plugin. */
    fun onLaunchToken(token: String)

    /** Remember a freshly minted (or invalidated, when empty) cookie. */
    fun onCookie(cookie: String)

    /**
     * Mint a cookie with `GET {baseUrl}/?token=` and redirects disabled. Native
     * transports own this because Kuikly's NetworkModule follows redirects and
     * drops the `Set-Cookie` of the 303 response.
     */
    fun mint(baseUrl: String, launchToken: String, bearer: String, callback: (cookie: String?, error: String?) -> Unit)
}

internal interface DshMuxStreamListener {
    fun onItem(value: JSONObject)
    fun onEnd()
    fun onError(error: DshRpcError)
}

internal interface DshMuxStream {
    val streamId: String
    fun cancel()
}

/** One `/api/remote.mux` socket carrying independently cancellable logical streams. */
internal class DshRemoteMux(
    private val webSocket: DshWebSocketModule,
    private val connection: DshHostConnection,
    private val cookie: String,
    private val onOpen: () -> Unit,
    private val onClosed: (message: String, httpStatus: Int) -> Unit,
) {
    private var handle: DshWebSocketHandle? = null
    private val listeners = linkedMapOf<String, DshMuxStreamListener>()
    private var open = false
    private var closed = false

    fun start() {
        handle = webSocket.connect(
            url = connection.webSocketUrl(DshHostProtocol.REMOTE_MUX_PATH),
            token = connection.token,
            cookie = cookie,
        ) { event -> handleSocketEvent(event) }
    }

    val isOpen: Boolean get() = open && !closed

    fun open(endpoint: String, payload: JSONObject, listener: DshMuxStreamListener): DshMuxStream {
        val streamId = dshRandomId("stream")
        listeners[streamId] = listener
        val message = JSONObject().apply {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", endpoint)
            put("payload", payload)
        }
        handle?.send(message.toString())
        DshStreamLog.record(
            DshLogLevel.DEBUG,
            DshLogEvent.MUX,
            "mux.open endpoint=$endpoint stream=$streamId",
            ref = streamId,
        )
        return object : DshMuxStream {
            override val streamId: String = streamId
            override fun cancel() {
                if (listeners.remove(streamId) == null) return
                DshStreamLog.i("mux.cancel endpoint=$endpoint stream=$streamId")
                if (isOpen) {
                    handle?.send(JSONObject().apply {
                        put("type", "cancel")
                        put("streamId", streamId)
                    }.toString())
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        open = false
        listeners.clear()
        handle?.close()
        handle = null
    }

    private fun handleSocketEvent(event: DshWebSocketEvent) {
        if (closed) return
        when (event.kind) {
            DshWebSocketEventKind.OPEN -> {
                open = true
                onOpen()
            }
            DshWebSocketEventKind.FRAME -> handleFrame(event.data)
            DshWebSocketEventKind.ERROR, DshWebSocketEventKind.CLOSED -> {
                open = false
                val pending = listeners.values.toList()
                listeners.clear()
                DshStreamLog.record(
                    DshLogLevel.WARN,
                    DshLogEvent.CONNECTION,
                    "mux.closed status=${event.httpStatus} streams=${pending.size} message='${event.message}'",
                )
                val error = DshRpcError("transport-${event.httpStatus}", event.message.ifEmpty { "DSH 事件流已断开" })
                pending.forEach { it.onError(error) }
                onClosed(event.message, event.httpStatus)
            }
        }
    }

    private fun handleFrame(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull()
        if (message == null) {
            DshStreamLog.i("mux.frame parse-error chars=${raw.length} raw='${DshStreamLog.preview(raw, 200)}'")
            return
        }
        val streamId = message.optString("streamId")
        val listener = listeners[streamId] ?: run {
            DshStreamLog.i("mux.frame drop unknown-stream=$streamId type=${message.optString("type")}")
            return
        }
        when (message.optString("type")) {
            "item" -> {
                val value = message.optJSONObject("value")
                if (value == null) {
                    DshStreamLog.i("mux.item non-object stream=$streamId raw='${DshStreamLog.preview(raw, 200)}'")
                    return
                }
                listener.onItem(value)
            }
            "end" -> {
                listeners.remove(streamId)
                listener.onEnd()
            }
            "error" -> {
                listeners.remove(streamId)
                val error = message.optJSONObject("error")
                DshStreamLog.record(
                    DshLogLevel.ERROR,
                    DshLogEvent.MUX,
                    "mux.error code=${error?.optString("code").orEmpty()} message='${error?.optString("message").orEmpty()}'",
                    ref = streamId,
                )
                listener.onError(DshRpcError(
                    error?.optString("code").orEmpty().ifEmpty { "gateway/internal" },
                    error?.optString("message").orEmpty().ifEmpty { "Remote stream failed" },
                    error?.optJSONObject("details")?.toString() ?: "{}",
                ))
            }
        }
    }
}

internal data class DshRpcCall(
    val rpcId: String,
    private val cancelAction: () -> Unit = {},
) {
    fun cancel() = cancelAction()
}

private data class QueuedRpc(
    val generation: Long,
    val endpoint: String,
    val payload: JSONObject,
    val rpcId: String,
    val timeoutSeconds: Int,
    val callback: (JSONObject?, DshRpcError?, String) -> Unit,
)

/** Identity-compared on purpose: two identical requests are still two streams. */
private class QueuedStream(
    val generation: Long,
    val endpoint: String,
    val payload: JSONObject,
    val listener: DshMuxStreamListener,
    var opened: DshMuxStream? = null,
    var cancelled: Boolean = false,
)

/**
 * Owns one authenticated Host connection generation: cookie bootstrap, the
 * `remote.mux` socket, the three Host-wide baseline streams, and unary RPC.
 */
internal class DshHostConnectionRuntime(
    private val network: NetworkModule,
    private val webSocket: DshWebSocketModule,
    private val connection: DshHostConnection,
    private val auth: DshHostAuthenticator,
    private val pagerId: String,
    private val onState: (DshHostRuntimeState) -> Unit = {},
    private val onEventsFrame: (JSONObject) -> Unit = {},
    private val onControlFrame: (JSONObject) -> Unit = {},
    private val onWorkspaceFrame: (JSONObject) -> Unit = {},
    private val onSessionBaseline: (JSONObject) -> Unit = {},
) {
    private var generation = 0L
    private var rpcSequence = 0L
    private var stopped = false
    private var starting = false
    private var productReady = false
    private var mux: DshRemoteMux? = null
    private var cookie = ""
    private var clientId = ""
    private var eventsReady = false
    private var controlReady = false
    private var workspaceReady = false
    private var sessionsReady = false
    private var authRequired = false
    private var remintAttempted = false
    private val queuedRpcs = mutableListOf<QueuedRpc>()
    private val queuedStreams = mutableListOf<QueuedStream>()
    private var lastMessage = ""

    init { start() }

    val eventClientId: String get() = clientId

    fun currentState(): DshHostRuntimeState = DshHostRuntimeState(
        phase = when {
            stopped -> DshHostRuntimePhase.STOPPED
            authRequired -> DshHostRuntimePhase.AUTH_REQUIRED
            productReady -> DshHostRuntimePhase.READY
            eventsReady -> DshHostRuntimePhase.SYNCING
            mux?.isOpen == true -> DshHostRuntimePhase.HOST_HANDSHAKE
            starting -> DshHostRuntimePhase.CONNECTING
            else -> DshHostRuntimePhase.DISCONNECTED
        },
        generation = generation,
        muxOpen = mux?.isOpen == true,
        hostOpen = eventsReady,
        message = lastMessage,
    )

    fun start() {
        if (stopped || starting || productReady) return
        starting = true
        authRequired = false
        generation += 1
        val myGeneration = generation
        resetGenerationState()
        publish(DshHostRuntimePhase.CONNECTING, "正在验证 DSH 登录状态")
        ensureAuthenticated(myGeneration) { ok, message ->
            if (myGeneration != generation || stopped) return@ensureAuthenticated
            if (!ok) {
                starting = false
                authRequired = true
                publish(DshHostRuntimePhase.AUTH_REQUIRED, message)
                return@ensureAuthenticated
            }
            openMux(myGeneration)
        }
    }

    /** Called after the user supplied a launch token; drops the cookie and reconnects. */
    fun retryAuthentication() {
        if (stopped) return
        remintAttempted = false
        authRequired = false
        starting = false
        productReady = false
        mux?.close()
        mux = null
        start()
    }

    fun stop() {
        stopped = true
        generation += 1
        starting = false
        productReady = false
        val pending = queuedRpcs.toList()
        queuedRpcs.clear()
        queuedStreams.clear()
        mux?.close()
        mux = null
        pending.forEach { it.callback(null, DshRpcError("cancelled", "连接已停止"), it.rpcId) }
        publish(DshHostRuntimePhase.STOPPED, "连接已停止")
    }

    fun call(
        endpoint: String,
        payload: JSONObject,
        timeoutSeconds: Int = REQUEST_TIMEOUT_SECONDS,
        callback: (JSONObject?, DshRpcError?, String) -> Unit,
    ): DshRpcCall {
        val myGeneration = generation
        val rpcId = nextRpcId(myGeneration)
        val request = QueuedRpc(myGeneration, endpoint, payload, rpcId, timeoutSeconds, callback)
        if (stopped) callback(null, DshRpcError("cancelled", "连接已停止"), rpcId)
        else if (productReady) dispatch(request)
        else queuedRpcs += request
        return DshRpcCall(rpcId) { queuedRpcs.removeAll { it.rpcId == rpcId } }
    }

    /**
     * POST to a Host path outside `/api`, for a companion plugin that registers its own
     * route. The reply envelope is the plugin's `{ok, value|error}` rather than the Host's
     * `server-response`, and nothing is queued against the ready sequence: these are
     * user-initiated actions that should fail fast instead of waiting for a generation.
     * A 404 is reported as such so the caller can treat the plugin as simply not installed.
     */
    fun callPath(
        path: String,
        body: JSONObject,
        timeoutSeconds: Int = REQUEST_TIMEOUT_SECONDS,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) {
        if (stopped) {
            callback(null, DshRpcError("cancelled", "连接已停止"))
            return
        }
        val headers = JSONObject().apply {
            put("Content-Type", "application/json")
            if (connection.token.isNotEmpty()) put("Authorization", "Bearer ${connection.token}")
        }
        val url = "${connection.trimmedBase}$path"
        val myGeneration = generation
        network.httpRequest(url, true, body, headers, cookie.ifEmpty { null }, timeoutSeconds) { data, success, errorMsg, response ->
            val status = response.statusCode ?: 0
            if (myGeneration != generation || stopped) {
                callback(null, DshRpcError("generation-cancelled", "请求所属连接世代已失效"))
                return@httpRequest
            }
            if (!success) {
                if (status == 401) handleUnauthorized(myGeneration, "$path 返回 401")
                callback(null, DshRpcError("transport-$status", "$path failed ($status): $errorMsg"))
                return@httpRequest
            }
            if (data.optBoolean("ok")) {
                callback(data.optJSONObject("value") ?: JSONObject(), null)
                return@httpRequest
            }
            val error = data.optJSONObject("error")
            callback(null, DshRpcError(
                error?.optString("code").orEmpty().ifEmpty { "bad-response" },
                error?.optString("message").orEmpty().ifEmpty { "$path 返回了非法信封" },
                error?.optJSONObject("details")?.toString() ?: "{}",
            ))
        }
    }

    /** Open a logical stream now, or as soon as this generation is ready. */
    fun openStream(endpoint: String, payload: JSONObject, listener: DshMuxStreamListener): DshMuxStream {
        val queued = QueuedStream(generation, endpoint, payload, listener)
        if (stopped) {
            listener.onError(DshRpcError("cancelled", "连接已停止"))
        } else if (productReady && mux?.isOpen == true) {
            queued.opened = mux?.open(endpoint, payload, listener)
        } else {
            queuedStreams += queued
        }
        return object : DshMuxStream {
            override val streamId: String get() = queued.opened?.streamId.orEmpty()
            override fun cancel() {
                queued.cancelled = true
                queuedStreams.remove(queued)
                queued.opened?.cancel()
            }
        }
    }

    /** Answer one `$events` waterfall; `outcome` is `{kind:"result", value}` / `{kind:"next"}` / `{kind:"rejected", error}`. */
    fun respondEvent(eventId: String, outcome: JSONObject, callback: (Boolean, String) -> Unit) {
        if (clientId.isEmpty()) {
            callback(false, "连接尚未就绪")
            return
        }
        val payload = DshHostProtocol.args {
            put("clientId", clientId)
            put("eventId", eventId)
            put("outcome", outcome)
        }
        call(DshHostProtocol.EVENTS_RESULT, payload) { _, error, _ ->
            if (error != null) callback(false, error.message) else callback(true, "")
        }
    }

    // ------------------------------------------------------------------ bootstrap

    private fun resetGenerationState() {
        productReady = false
        eventsReady = false
        controlReady = false
        workspaceReady = false
        sessionsReady = false
        clientId = ""
    }

    private fun ensureAuthenticated(myGeneration: Long, callback: (Boolean, String) -> Unit) {
        cookie = auth.cookie()
        if (cookie.isNotEmpty()) {
            callback(true, "")
            return
        }
        val token = auth.launchToken()
        if (token.isNotEmpty()) {
            mintCookie(myGeneration, token, callback)
            return
        }
        if (!auth.relayTokenAvailable) {
            callback(false, "需要 dsh web 打印的登录 token")
            return
        }
        publish(DshHostRuntimePhase.CONNECTING, "正在向电脑端插件获取登录 token")
        val headers = JSONObject().apply {
            if (connection.token.isNotEmpty()) put("Authorization", "Bearer ${connection.token}")
        }
        network.httpRequest(
            "${connection.trimmedBase}${DshHostProtocol.RELAY_AUTH_PATH}", false, JSONObject(), headers, null, REQUEST_TIMEOUT_SECONDS,
        ) { data, success, errorMsg, response ->
            if (myGeneration != generation || stopped) return@httpRequest
            val discovered = data.optString("token")
            if (!success || discovered.isEmpty()) {
                val status = response.statusCode ?: 0
                val reason = if (status == 503) "电脑端 DSH 版本过旧或插件未注入 connection 服务" else errorMsg.ifEmpty { "HTTP $status" }
                DshStreamLog.i("auth.relay-token.fail status=$status error='$reason'")
                callback(false, "无法获取登录 token：$reason")
                return@httpRequest
            }
            DshStreamLog.i("auth.relay-token.ok authority=${data.optString("authority")}")
            auth.onLaunchToken(discovered)
            mintCookie(myGeneration, discovered, callback)
        }
    }

    private fun mintCookie(myGeneration: Long, token: String, callback: (Boolean, String) -> Unit) {
        publish(DshHostRuntimePhase.CONNECTING, "正在登录远程 DSH")
        auth.mint(connection.trimmedBase, token, connection.token) { minted, error ->
            if (myGeneration != generation || stopped) return@mint
            if (minted.isNullOrEmpty()) {
                DshStreamLog.i("auth.mint.fail error='${error.orEmpty()}'")
                callback(false, "登录失败：${error.orEmpty().ifEmpty { "token 无效或已过期，请重新启动 dsh web 并粘贴新 token" }}")
                return@mint
            }
            DshStreamLog.i("auth.mint.ok cookieChars=${minted.length}")
            cookie = minted
            auth.onCookie(minted)
            callback(true, "")
        }
    }

    private fun openMux(myGeneration: Long) {
        publish(DshHostRuntimePhase.CONNECTING, "正在打开 DSH 事件流")
        val socket = DshRemoteMux(
            webSocket = webSocket,
            connection = connection,
            cookie = cookie,
            onOpen = {
                if (myGeneration != generation || stopped) return@DshRemoteMux
                publish(DshHostRuntimePhase.HOST_HANDSHAKE, "事件流已连接")
                openEventsStream(myGeneration)
            },
            onClosed = { message, httpStatus ->
                if (myGeneration != generation || stopped) return@DshRemoteMux
                if (httpStatus == 401 || httpStatus == 403) handleUnauthorized(myGeneration, "事件流认证失败 ($httpStatus)")
                else invalidateGeneration(myGeneration, message.ifEmpty { "DSH 事件流已断开" })
            },
        )
        mux = socket
        socket.start()
    }

    private fun openEventsStream(myGeneration: Long) {
        val socket = mux ?: return
        socket.open(DshHostProtocol.EVENTS_STREAM, DshHostProtocol.args(), object : DshMuxStreamListener {
            override fun onItem(value: JSONObject) {
                if (myGeneration != generation || stopped) return
                if (!eventsReady) {
                    if (value.optString("type") != "ready") {
                        invalidateGeneration(myGeneration, "\$events 未以 ready 帧开始")
                        return
                    }
                    clientId = value.optString("clientId")
                    eventsReady = true
                    publish(DshHostRuntimePhase.SYNCING, "正在同步远程会话")
                    openBaselineStreams(myGeneration)
                    return
                }
                onEventsFrame(value)
            }

            override fun onEnd() {
                if (myGeneration == generation && !stopped) invalidateGeneration(myGeneration, "\$events 流已结束")
            }

            override fun onError(error: DshRpcError) {
                if (myGeneration == generation && !stopped) invalidateGeneration(myGeneration, error.message)
            }
        })
    }

    private fun openBaselineStreams(myGeneration: Long) {
        val socket = mux ?: return
        socket.open(DshHostProtocol.SESSION_CONTROL, DshHostProtocol.args(), object : DshMuxStreamListener {
            override fun onItem(value: JSONObject) {
                if (myGeneration != generation || stopped) return
                onControlFrame(value)
                if (!controlReady && value.optString("type") == "baseline") {
                    controlReady = true
                    finishBaseline(myGeneration)
                }
            }

            override fun onEnd() {
                if (myGeneration == generation && !stopped) invalidateGeneration(myGeneration, "session/control 流已结束")
            }

            override fun onError(error: DshRpcError) {
                if (myGeneration == generation && !stopped) invalidateGeneration(myGeneration, error.message)
            }
        })
        socket.open(DshHostProtocol.WORKSPACE_FOLLOW, DshHostProtocol.args(), object : DshMuxStreamListener {
            override fun onItem(value: JSONObject) {
                if (myGeneration != generation || stopped) return
                onWorkspaceFrame(value)
                if (!workspaceReady && value.optString("type") == "baseline") {
                    workspaceReady = true
                    finishBaseline(myGeneration)
                }
            }

            override fun onEnd() {
                if (myGeneration == generation && !stopped) invalidateGeneration(myGeneration, "workspace/follow 流已结束")
            }

            override fun onError(error: DshRpcError) {
                if (myGeneration == generation && !stopped) invalidateGeneration(myGeneration, error.message)
            }
        })
        directCall(myGeneration, DshHostProtocol.SESSION_LIST, DshHostProtocol.args { put("_request", JSONObject()) }) { value, error ->
            if (myGeneration != generation || stopped) return@directCall
            if (error != null || value == null) {
                invalidateGeneration(myGeneration, error?.message ?: "session/list 失败")
                return@directCall
            }
            onSessionBaseline(value)
            sessionsReady = true
            finishBaseline(myGeneration)
        }
    }

    private fun finishBaseline(myGeneration: Long) {
        if (!eventsReady || !controlReady || !workspaceReady || !sessionsReady || productReady) return
        productReady = true
        starting = false
        remintAttempted = false
        publish(DshHostRuntimePhase.READY, "DSH 已就绪")
        val pendingRpcs = queuedRpcs.toList()
        queuedRpcs.clear()
        pendingRpcs.filter { it.generation == myGeneration }.forEach(::dispatch)
        val pendingStreams = queuedStreams.toList()
        queuedStreams.clear()
        pendingStreams.filter { !it.cancelled }.forEach { queued ->
            queued.opened = mux?.open(queued.endpoint, queued.payload, queued.listener)
        }
    }

    private fun handleUnauthorized(myGeneration: Long, message: String) {
        if (stopped || myGeneration != generation) return
        DshStreamLog.i("auth.unauthorized remint=${!remintAttempted} message='$message'")
        cookie = ""
        auth.onCookie("")
        if (remintAttempted || (auth.launchToken().isEmpty() && !auth.relayTokenAvailable)) {
            remintAttempted = false
            generation += 1
            starting = false
            resetGenerationState()
            mux?.close()
            mux = null
            authRequired = true
            failQueued(myGeneration, "认证已失效")
            publish(DshHostRuntimePhase.AUTH_REQUIRED, "DSH 登录已失效，请重新粘贴 dsh web 打印的 token")
            return
        }
        remintAttempted = true
        invalidateGeneration(myGeneration, "登录已过期，正在重新登录", delayMs = 0)
    }

    private fun invalidateGeneration(myGeneration: Long, message: String, delayMs: Int = RECONNECT_DELAY_MS) {
        if (stopped || myGeneration != generation) return
        generation += 1
        val reconnectGeneration = generation
        mux?.close()
        mux = null
        starting = false
        resetGenerationState()
        failQueued(myGeneration, message)
        publish(DshHostRuntimePhase.RECONNECTING, message)
        setTimeout(pagerId, delayMs) {
            if (!stopped && generation == reconnectGeneration) start()
        }
    }

    private fun failQueued(myGeneration: Long, message: String) {
        val cancelled = queuedRpcs.filter { it.generation == myGeneration }
        queuedRpcs.removeAll { it.generation == myGeneration }
        cancelled.forEach { it.callback(null, DshRpcError("generation-cancelled", message), it.rpcId) }
        // Queued streams survive reconnects: the repository re-requests them on READY.
    }

    // ------------------------------------------------------------------ unary

    private fun dispatch(request: QueuedRpc) {
        if (request.generation != generation || stopped) return
        val body = JSONObject().apply {
            put("type", "client-request")
            put("rpcId", request.rpcId)
            put("method", request.endpoint)
            put("payload", request.payload)
        }
        val headers = JSONObject().apply {
            put("Content-Type", "application/json")
            if (connection.token.isNotEmpty()) put("Authorization", "Bearer ${connection.token}")
        }
        val url = "${connection.trimmedBase}${DshHostProtocol.API_PREFIX}/${request.endpoint}"
        DshStreamLog.record(
            DshLogLevel.INFO,
            DshLogEvent.RPC,
            "rpc.start ${request.endpoint}",
            ref = request.rpcId,
            sizeBytes = body.toString().length,
        )
        network.httpRequest(url, true, body, headers, cookie.ifEmpty { null }, request.timeoutSeconds) { data, success, errorMsg, response ->
            val status = response.statusCode ?: 0
            if (request.generation != generation || stopped) {
                request.callback(null, DshRpcError("generation-cancelled", "请求所属连接世代已失效"), request.rpcId)
                return@httpRequest
            }
            if (!success) {
                if (status == 401) {
                    logRpcFailure(request, "transport-401", "unauthorized")
                    request.callback(null, DshRpcError("transport-401", "${request.endpoint} unauthorized"), request.rpcId)
                    handleUnauthorized(request.generation, "${request.endpoint} 返回 401")
                    return@httpRequest
                }
                logRpcFailure(request, "transport-$status", errorMsg)
                request.callback(null, DshRpcError(
                    "transport-$status",
                    "${request.endpoint} failed ($status): $errorMsg",
                ), request.rpcId)
                return@httpRequest
            }
            val result = data.optJSONObject("result")
            if (data.optString("type") != "server-response" || result == null) {
                logRpcFailure(request, "bad-response", "illegal RPC envelope")
                request.callback(null, DshRpcError("bad-response", "${request.endpoint} 返回了非法 RPC 信封"), request.rpcId)
                return@httpRequest
            }
            if (!result.optBoolean("ok")) {
                val error = result.optJSONObject("error")
                logRpcFailure(
                    request,
                    error?.optString("code").orEmpty().ifEmpty { "internal" },
                    error?.optString("message").orEmpty(),
                )
                request.callback(null, DshRpcError(
                    error?.optString("code").orEmpty().ifEmpty { "internal" },
                    error?.optString("message").orEmpty().ifEmpty { "${request.endpoint} 失败" },
                    error?.optJSONObject("details")?.toString() ?: "{}",
                ), request.rpcId)
                return@httpRequest
            }
            // `value` may legitimately be absent (void results) or a non-object; callers get an empty object then.
            request.callback(result.optJSONObject("value") ?: JSONObject(), null, request.rpcId)
        }
    }

    private fun logRpcFailure(request: QueuedRpc, code: String, message: String) {
        DshStreamLog.record(
            DshLogLevel.ERROR,
            DshLogEvent.RPC,
            "rpc.fail ${request.endpoint} code=$code message='$message'",
            ref = request.rpcId,
        )
    }

    private fun directCall(myGeneration: Long, endpoint: String, payload: JSONObject, callback: (JSONObject?, DshRpcError?) -> Unit) {
        if (myGeneration != generation || stopped) return
        dispatch(QueuedRpc(myGeneration, endpoint, payload, nextRpcId(myGeneration), REQUEST_TIMEOUT_SECONDS) { value, error, _ -> callback(value, error) })
    }

    private fun nextRpcId(myGeneration: Long): String = "dsh-g${myGeneration}-${++rpcSequence}"

    private fun publish(phase: DshHostRuntimePhase, message: String) {
        lastMessage = message
        // Every connection establishment, drop and retry passes through here, so this is
        // the one hook the log centre needs for connection state.
        DshStreamLog.record(
            level = when (phase) {
                DshHostRuntimePhase.RECONNECTING, DshHostRuntimePhase.AUTH_REQUIRED -> DshLogLevel.WARN
                else -> DshLogLevel.INFO
            },
            eventType = if (phase == DshHostRuntimePhase.RECONNECTING) DshLogEvent.RECONNECT else DshLogEvent.CONNECTION,
            message = "connection.phase=$phase generation=$generation message='$message'",
            ref = "g$generation",
        )
        onState(DshHostRuntimeState(phase, generation, mux?.isOpen == true, eventsReady, message))
    }

    companion object {
        const val REQUEST_TIMEOUT_SECONDS = 30
        /** Image prompts carry base64 bodies through the tunnel; give them longer. */
        const val PROMPT_TIMEOUT_SECONDS = 120
        private const val RECONNECT_DELAY_MS = 1_000
    }
}
