package com.example.dsh.dsh

import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout

internal data class DshRpcCall(
    val rpcId: String,
    private val cancelAction: () -> Unit = {},
) {
    fun cancel() = cancelAction()
}

private data class QueuedRpc(
    val generation: Long,
    val method: String,
    val payload: JSONObject,
    val rpcId: String,
    val callback: (JSONObject?, DshRpcError?, String) -> Unit,
)

/** Owns one long-lived mux/host WebSocket connection generation. */
internal class DshHostConnectionRuntime(
    private val network: NetworkModule,
    private val webSocket: DshWebSocketModule,
    private val connection: DshHostConnection,
    private val pagerId: String,
    private val onFrame: (DshDownlinkFrame) -> Unit,
    private val onState: (DshHostRuntimeState) -> Unit = {},
    private val onWorkspaceBaseline: (JSONObject) -> Unit = {},
    private val onSessionBaseline: (JSONObject) -> Unit = {},
    private val onQueueSnapshot: (String) -> Unit = {},
    private val onJobsSnapshot: (String) -> Unit = {},
    private val onSessionStatus: (String, Boolean) -> Unit = { _, _ -> },
    private val onSessionEvent: (String, DshRawSessionEvent) -> Unit = { _, _ -> },
    private val onRemoteEvent: (String) -> Unit = {},
    private val onProjection: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
) {
    private var generation = 0L
    private var rpcSequence = 0L
    private var muxOpen = false
    private var hostOpen = false
    private var hostDescribed = false
    private var productReady = false
    private var stopped = false
    private var starting = false
    private var muxHandle: DshWebSocketHandle? = null
    private var hostHandle: DshWebSocketHandle? = null
    private val bufferedFrames = mutableListOf<DshDownlinkFrame>()
    private val queued = mutableListOf<QueuedRpc>()

    init { start() }

    fun currentState(): DshHostRuntimeState = DshHostRuntimeState(
        phase = when {
            stopped -> DshHostRuntimePhase.STOPPED
            productReady -> DshHostRuntimePhase.READY
            hostDescribed -> DshHostRuntimePhase.SYNCING
            muxOpen || hostOpen -> DshHostRuntimePhase.HOST_HANDSHAKE
            starting -> DshHostRuntimePhase.CONNECTING
            else -> DshHostRuntimePhase.DISCONNECTED
        },
        generation = generation,
        muxOpen = muxOpen,
        hostOpen = hostOpen,
    )

    fun start() {
        if (stopped || starting || productReady) return
        starting = true
        generation += 1
        val myGeneration = generation
        muxOpen = false
        hostOpen = false
        hostDescribed = false
        productReady = false
        bufferedFrames.clear()
        publish(DshHostRuntimePhase.CONNECTING, "正在打开 DSH 事件流")
        muxHandle = webSocket.connect(webSocketUrl(DshHostProtocol.MUX_EVENTS_PATH), connection.token) { event ->
            handleSocketEvent(myGeneration, DshEventStream.MUX, event)
        }
        hostHandle = webSocket.connect(webSocketUrl(DshHostProtocol.HOST_EVENTS_PATH), connection.token) { event ->
            handleSocketEvent(myGeneration, DshEventStream.HOST, event)
        }
    }

    fun stop() {
        stopped = true
        generation += 1
        starting = false
        productReady = false
        bufferedFrames.clear()
        queued.clear()
        muxHandle?.close()
        hostHandle?.close()
        muxHandle = null
        hostHandle = null
        publish(DshHostRuntimePhase.STOPPED, "连接已停止")
    }

    fun call(
        method: String,
        payload: JSONObject,
        callback: (JSONObject?, DshRpcError?, String) -> Unit,
    ): DshRpcCall {
        val myGeneration = generation
        val rpcId = nextRpcId(myGeneration)
        val request = QueuedRpc(myGeneration, method, payload, rpcId, callback)
        if (stopped) callback(null, DshRpcError("cancelled", "连接已停止"), rpcId)
        else if (productReady) dispatch(request)
        else queued += request
        return DshRpcCall(rpcId) { queued.removeAll { it.rpcId == rpcId } }
    }

    /** POST /api/respond has a ClientResponse body, not a unary RPC body. */
    fun respond(rpcId: String, value: JSONObject, callback: (Boolean, String) -> Unit) {
        if (rpcId.isEmpty()) {
            DshStreamLog.question("respond.http.skip empty-rpcId session=${value.optString("sessionId")}")
            callback(false, "缺少请求编号")
            return
        }
        if (!productReady) {
            DshStreamLog.question("respond.http.skip not-ready rpcId=$rpcId")
            callback(false, "连接尚未就绪")
            return
        }
        val myGeneration = generation
        val body = JSONObject().apply {
            put("type", "client-response")
            put("rpcId", rpcId)
            put("result", JSONObject().apply {
                put("ok", true)
                put("value", value)
            })
        }
        val headers = JSONObject().apply {
            put("Content-Type", "application/json")
            if (connection.token.isNotEmpty()) put("Authorization", "Bearer ${connection.token}")
        }
        DshStreamLog.question(
            "respond.http.start rpcId=$rpcId session=${value.optString("sessionId")} url=${connection.baseUrl.trimEnd('/')}${DshHostProtocol.RESPOND_PATH} body='${DshStreamLog.preview(body.toString(), 400)}'",
        )
        network.httpRequest(
            "${connection.baseUrl.trimEnd('/')}${DshHostProtocol.RESPOND_PATH}", true, body, headers, null, REQUEST_TIMEOUT_SECONDS,
        ) { data, success, errorMsg, response ->
            if (stopped || myGeneration != generation) {
                DshStreamLog.question("respond.http.cancel rpcId=$rpcId")
                callback(false, "generation-cancelled")
                return@httpRequest
            }
            if (!success) {
                DshStreamLog.question(
                    "respond.http.fail rpcId=$rpcId status=${response.statusCode ?: 0} error='$errorMsg' body='${DshStreamLog.preview(data.toString(), 400)}'",
                )
                callback(false, "respond failed (${response.statusCode ?: 0}): $errorMsg")
                return@httpRequest
            }
            val (accepted, reason) = parseRespondReceipt(data)
            DshStreamLog.question(
                "respond.http.done rpcId=$rpcId accepted=$accepted reason='$reason' status=${response.statusCode ?: 0} body='${DshStreamLog.preview(data.toString(), 400)}'",
            )
            callback(accepted, reason)
        }
    }

    private fun handleSocketEvent(myGeneration: Long, stream: DshEventStream, event: DshWebSocketEvent) {
        if (stopped || myGeneration != generation) return
        when (event.kind) {
            DshWebSocketEventKind.OPEN -> {
                if (stream == DshEventStream.MUX) muxOpen = true else hostOpen = true
                publish(DshHostRuntimePhase.HOST_HANDSHAKE, "事件流已连接")
                if (muxOpen && hostOpen && !hostDescribed) describeHost(myGeneration)
            }
            DshWebSocketEventKind.FRAME -> {
                bufferedFrames += DshDownlinkFrame(myGeneration, stream, event.data)
                if (productReady) flushFrames()
            }
            DshWebSocketEventKind.ERROR, DshWebSocketEventKind.CLOSED -> invalidateGeneration(
                myGeneration, event.message.ifEmpty { "DSH 事件流已断开" },
            )
        }
    }

    private fun describeHost(myGeneration: Long) {
        directCall(myGeneration, DshHostProtocol.HOST_DESCRIBE, JSONObject()) { value, error ->
            if (myGeneration != generation || stopped) return@directCall
            if (error != null || value == null) {
                invalidateGeneration(myGeneration, error?.message ?: "host.describe 失败")
                return@directCall
            }
            hostDescribed = true
            publish(DshHostRuntimePhase.SYNCING, "正在同步远程会话")
            var workspaceDone = false
            var sessionDone = false
            var baselineError: DshRpcError? = null
            fun finishBaseline() {
                if (!workspaceDone || !sessionDone) return
                if (baselineError != null) {
                    invalidateGeneration(myGeneration, baselineError?.message ?: "同步基线失败")
                    return
                }
                productReady = true
                starting = false
                flushFrames()
                publish(DshHostRuntimePhase.READY, "DSH 已就绪")
                val pending = queued.toList()
                queued.clear()
                pending.filter { it.generation == myGeneration }.forEach(::dispatch)
            }
            directCall(myGeneration, DshHostProtocol.WORKSPACE_LIST, JSONObject()) { workspaceValue, errorValue ->
                workspaceDone = true
                if (errorValue != null) baselineError = errorValue
                if (errorValue == null && workspaceValue != null) onWorkspaceBaseline(workspaceValue)
                finishBaseline()
            }
            directCall(myGeneration, DshHostProtocol.SESSION_LIST, JSONObject()) { sessionValue, errorValue ->
                sessionDone = true
                if (errorValue != null) baselineError = errorValue
                if (errorValue == null && sessionValue != null) onSessionBaseline(sessionValue)
                finishBaseline()
            }
        }
    }

    private fun flushFrames() {
        if (!productReady) return
        val frames = bufferedFrames.toList()
        bufferedFrames.clear()
        frames.forEach(onFrame)
    }

    private fun invalidateGeneration(myGeneration: Long, message: String) {
        if (stopped || myGeneration != generation) return
        generation += 1
        val reconnectGeneration = generation
        muxHandle?.close()
        hostHandle?.close()
        muxHandle = null
        hostHandle = null
        muxOpen = false
        hostOpen = false
        hostDescribed = false
        productReady = false
        starting = false
        bufferedFrames.clear()
        val cancelled = queued.filter { it.generation == myGeneration }
        queued.removeAll { it.generation == myGeneration }
        cancelled.forEach { request ->
            request.callback(null, DshRpcError("generation-cancelled", message), request.rpcId)
        }
        publish(DshHostRuntimePhase.RECONNECTING, message)
        setTimeout(pagerId, RECONNECT_DELAY_MS) {
            if (!stopped && generation == reconnectGeneration) start()
        }
    }

    private fun dispatch(request: QueuedRpc) {
        if (request.generation != generation || stopped) return
        val body = JSONObject().apply {
            put("type", "client-request")
            put("rpcId", request.rpcId)
            put("method", request.method)
            put("payload", request.payload)
        }
        val headers = JSONObject().apply {
            put("Content-Type", "application/json")
            if (connection.token.isNotEmpty()) put("Authorization", "Bearer ${connection.token}")
        }
        network.httpRequest(
            "${connection.baseUrl.trimEnd('/')}${DshHostProtocol.API_PREFIX}/${request.method}",
            true, body, headers, null, REQUEST_TIMEOUT_SECONDS,
        ) { data, success, errorMsg, response ->
            if (request.generation != generation || stopped) {
                request.callback(null, DshRpcError("generation-cancelled", "请求所属连接世代已失效"), request.rpcId)
                return@httpRequest
            }
            if (!success) {
                request.callback(null, DshRpcError(
                    "transport-${response.statusCode ?: 0}",
                    "${request.method} failed (${response.statusCode ?: 0}): $errorMsg",
                ), request.rpcId)
                return@httpRequest
            }
            val result = data.optJSONObject("result")
            if (result == null) {
                request.callback(null, DshRpcError("bad-response", "${request.method} 返回了非法 RPC 信封"), request.rpcId)
                return@httpRequest
            }
            if (!result.optBoolean("ok")) {
                val error = result.optJSONObject("error")
                request.callback(null, DshRpcError(
                    error?.optString("code").orEmpty().ifEmpty { "internal" },
                    error?.optString("message").orEmpty().ifEmpty { "${request.method} 失败" },
                    error?.optJSONObject("details")?.toString() ?: "{}",
                ), request.rpcId)
                return@httpRequest
            }
            request.callback(result.optJSONObject("value"), null, request.rpcId)
        }
    }

    private fun directCall(myGeneration: Long, method: String, payload: JSONObject, callback: (JSONObject?, DshRpcError?) -> Unit) {
        if (myGeneration != generation || stopped) return
        dispatch(QueuedRpc(myGeneration, method, payload, nextRpcId(myGeneration)) { value, error, _ -> callback(value, error) })
    }

    private fun webSocketUrl(path: String): String {
        val base = connection.baseUrl.trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}"
            else -> base
        }
        return "$wsBase$path"
    }

    private fun nextRpcId(myGeneration: Long): String = "dsh-g${myGeneration}-${++rpcSequence}"

    private fun publish(phase: DshHostRuntimePhase, message: String) {
        onState(DshHostRuntimeState(phase, generation, muxOpen, hostOpen, message))
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 30
        const val RECONNECT_DELAY_MS = 1_000
    }
}

internal fun parseRespondReceipt(data: JSONObject): Pair<Boolean, String> {
    val result = data.optJSONObject("result")
    val value = result?.optJSONObject("value")
    val accepted = jsonFlag(data, "accepted")
        ?: jsonFlag(value, "accepted")
        ?: false
    val reason = data.optString("reason")
        .ifEmpty { value?.optString("reason").orEmpty() }
        .ifEmpty { result?.optJSONObject("error")?.optString("message").orEmpty() }
        .ifEmpty { if (accepted) "" else "bad-response" }
    return accepted to reason
}

private fun jsonFlag(obj: JSONObject?, key: String): Boolean? {
    if (obj == null) return null
    val raw = obj.opt(key) ?: return null
    return when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> raw.equals("true", ignoreCase = true)
        else -> obj.optBoolean(key)
    }
}
