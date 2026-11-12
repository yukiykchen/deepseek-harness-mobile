package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout
import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.framework.DshDiagnostics
import io.github.yukiykchen.dsh.platform.DshWebSocketEventKind
import io.github.yukiykchen.dsh.platform.DshWebSocketEvent
import io.github.yukiykchen.dsh.platform.DshWebSocketHandle
import io.github.yukiykchen.dsh.model.DshHostProtocol
import io.github.yukiykchen.dsh.model.DshHostConnection
import io.github.yukiykchen.dsh.model.DshHostRuntimePhase
import io.github.yukiykchen.dsh.model.DshHostRuntimeState
import io.github.yukiykchen.dsh.model.DshEventStream
import io.github.yukiykchen.dsh.model.DshDownlinkFrame
import io.github.yukiykchen.dsh.model.DshRpcError
import io.github.yukiykchen.dsh.model.DshRawSessionEvent

data class DshRpcCall(
    val rpcId: String,
    val cancelAction: () -> Unit = {},
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

class DshHostConnectionRuntime(
    private val transport: DshHostTransport,
    val connection: DshHostConnection,
    val pagerId: String,
    val onFrame: (DshDownlinkFrame) -> Unit,
    val onState: (DshHostRuntimeState) -> Unit = {},
    val onWorkspaceBaseline: (JSONObject) -> Unit = {},
    val onSessionBaseline: (JSONObject) -> Unit = {},
    val onQueueSnapshot: (String) -> Unit = {},
    val onJobsSnapshot: (String) -> Unit = {},
    val onSessionStatus: (String, Boolean) -> Unit = { _, _ -> },
    val onSessionEvent: (String, DshRawSessionEvent) -> Unit = { _, _ -> },
    val onRemoteEvent: (String) -> Unit = {},
    val onProjection: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
    private val schedule: (Int, () -> Unit) -> Unit = { delay, action -> setTimeout(pagerId, delay, action) },
) {
    var generation = 0L
        private set
    private var rpcSequence = 0L
    var muxOpen = false
        private set
    var hostOpen = false
        private set
    private var hostDescribed = false
    var productReady = false
        private set
    private var stopped = false
    private var starting = false
    private var reconnectGenerationReserved = false
    private var lastPublishedPhase = DshHostRuntimePhase.DISCONNECTED
    private var handshakeRequested = false
    private var muxHandle: DshWebSocketHandle? = null
    private var hostHandle: DshWebSocketHandle? = null
    private val bufferedFrames = mutableListOf<DshDownlinkFrame>()
    private val queued = mutableListOf<QueuedRpc>()
    private val inFlight = mutableMapOf<String, QueuedRpc>()

    fun currentState(): DshHostRuntimeState = DshHostRuntimeState(
        phase = when {
            stopped -> DshHostRuntimePhase.STOPPED
            productReady -> DshHostRuntimePhase.READY
            hostDescribed -> DshHostRuntimePhase.SYNCING
            muxOpen || hostOpen -> DshHostRuntimePhase.HOST_HANDSHAKE
            starting -> DshHostRuntimePhase.CONNECTING
            else -> lastPublishedPhase
        },
        generation = generation,
        muxOpen = muxOpen,
        hostOpen = hostOpen,
    )
    fun start() {
        if (stopped || starting || productReady) return
        starting = true
        if (!reconnectGenerationReserved) generation += 1
        reconnectGenerationReserved = false
        val myGeneration = generation
        // Calls submitted during the reconnect delay belong to the new transport.
        val waiting = queued.map { it.copy(generation = myGeneration) }
        queued.clear()
        queued.addAll(waiting)
        muxOpen = false
        hostOpen = false
        hostDescribed = false
        handshakeRequested = false
        productReady = false
        bufferedFrames.clear()
        publish(DshHostRuntimePhase.CONNECTING, "正在打开 DSH 事件流")
        if (!openStream(myGeneration, DshEventStream.MUX, DshHostProtocol.MUX_EVENTS_PATH)) return
        if (!openStream(myGeneration, DshEventStream.HOST, DshHostProtocol.HOST_EVENTS_PATH)) return
        schedule(REQUEST_TIMEOUT_SECONDS * 1_000) {
            if (!stopped && myGeneration == generation && !productReady) {
                invalidateGeneration(myGeneration, "DSH 连接握手超时")
            }
        }
    }

    private fun openStream(myGeneration: Long, stream: DshEventStream, path: String): Boolean {
        val handle = try {
            transport.connect(webSocketUrl(path), connection.token) { event ->
                handleSocketEvent(myGeneration, stream, event)
            }
        } catch (error: Throwable) {
            invalidateGeneration(myGeneration, error.message ?: "无法打开 DSH 事件流")
            return false
        }
        if (stopped || myGeneration != generation) {
            handle.close()
            return false
        }
        if (stream == DshEventStream.MUX) muxHandle = handle else hostHandle = handle
        return true
    }
    fun stop() {
        if (stopped) return
        stopped = true
        reconnectGenerationReserved = false
        generation += 1
        starting = false
        productReady = false
        bufferedFrames.clear()
        val cancelled = queued.toList() + inFlight.values.toList()
        queued.clear()
        inFlight.clear()
        muxHandle?.close()
        hostHandle?.close()
        muxHandle = null
        hostHandle = null
        muxOpen = false
        hostOpen = false
        hostDescribed = false
        cancelled.forEach { it.callback(null, DshRpcError("cancelled", "连接已停止"), it.rpcId) }
        publish(DshHostRuntimePhase.STOPPED, "连接已停止")
    }
    fun call(
        method: String,
        payload: JSONObject,
        beforeDispatch: (String) -> Unit = {},
        callback: (JSONObject?, DshRpcError?, String) -> Unit,
    ): DshRpcCall {
        val myGeneration = generation
        val rpcId = nextRpcId(myGeneration)
        val request = QueuedRpc(myGeneration, method, JSONObject(payload.toString()), rpcId, callback)
        beforeDispatch(rpcId)
        if (stopped) callback(null, DshRpcError("cancelled", "连接已停止"), rpcId)
        else if (productReady) dispatch(request)
        else queued += request
        return DshRpcCall(rpcId) {
            queued.removeAll { it.rpcId == rpcId }
            inFlight.remove(rpcId)
        }
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
            "respond.http.start rpcId=$rpcId session=${value.optString("sessionId")} body=${DshDiagnostics.preview(body.toString())}",
        )
        transport.postSafely(
            "${connection.baseUrl.trimEnd('/')}${DshHostProtocol.RESPOND_PATH}", body, headers, REQUEST_TIMEOUT_SECONDS,
        ) { data, success, errorMsg, response ->
            if (stopped || myGeneration != generation) {
                DshStreamLog.question("respond.http.cancel rpcId=$rpcId")
                callback(false, "generation-cancelled")
                return@postSafely
            }
            if (!success) {
                DshStreamLog.question(
                    "respond.http.fail rpcId=$rpcId status=${response.statusCode ?: 0} error=${DshDiagnostics.redactedError(errorMsg)} body=${DshDiagnostics.preview(data.toString())}",
                )
                callback(false, "respond failed (${response.statusCode ?: 0}): $errorMsg")
                return@postSafely
            }
            val (accepted, reason) = parseRespondReceipt(data)
            DshStreamLog.question(
                "respond.http.done rpcId=$rpcId accepted=$accepted reason=${DshDiagnostics.redactedError(reason)} status=${response.statusCode ?: 0} body=${DshDiagnostics.preview(data.toString())}",
            )
            callback(accepted, reason)
        }
    }

    private fun handleSocketEvent(myGeneration: Long, stream: DshEventStream, event: DshWebSocketEvent) {
        if (stopped || myGeneration != generation) return
        when (event.kind) {
            DshWebSocketEventKind.OPEN -> {
                if (if (stream == DshEventStream.MUX) muxOpen else hostOpen) return
                if (stream == DshEventStream.MUX) muxOpen = true else hostOpen = true
                publish(DshHostRuntimePhase.HOST_HANDSHAKE, "事件流已连接")
                if (muxOpen && hostOpen && !handshakeRequested) {
                    handshakeRequested = true
                    describeHost(myGeneration)
                }
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
                if (stopped || myGeneration != generation) return
                if (!workspaceDone || !sessionDone) return
                if (baselineError != null) {
                    invalidateGeneration(myGeneration, baselineError?.message ?: "同步基线失败")
                    return
                }
                productReady = true
                starting = false
                flushFrames()
                if (stopped || myGeneration != generation) return
                publish(DshHostRuntimePhase.READY, "DSH 已就绪")
                val pending = queued.toList()
                queued.clear()
                pending.filter { it.generation == myGeneration }.forEach(::dispatch)
            }
            directCall(myGeneration, DshHostProtocol.WORKSPACE_LIST, JSONObject()) { workspaceValue, errorValue ->
                if (stopped || myGeneration != generation) return@directCall
                workspaceDone = true
                if (errorValue != null) baselineError = errorValue
                if (errorValue == null && workspaceValue != null) onWorkspaceBaseline(workspaceValue)
                finishBaseline()
            }
            directCall(myGeneration, DshHostProtocol.SESSION_LIST, JSONObject()) { sessionValue, errorValue ->
                if (stopped || myGeneration != generation) return@directCall
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
        frames.forEach { frame ->
            if (!stopped && productReady && frame.generation == generation) onFrame(frame)
        }
    }

    private fun invalidateGeneration(myGeneration: Long, message: String) {
        if (stopped || myGeneration != generation) return
        generation += 1
        val reconnectGeneration = generation
        // Reserve this generation during the delay so queued callers keep a valid scope.
        reconnectGenerationReserved = true
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
        val cancelled = queued.filter { it.generation == myGeneration } +
            inFlight.values.filter { it.generation == myGeneration }
        queued.removeAll { it.generation == myGeneration }
        inFlight.entries.removeAll { it.value.generation == myGeneration }
        cancelled.forEach { request ->
            request.callback(null, DshRpcError("generation-cancelled", message), request.rpcId)
        }
        if (stopped || generation != reconnectGeneration) return
        publish(DshHostRuntimePhase.RECONNECTING, message)
        schedule(RECONNECT_DELAY_MS) {
            if (!stopped && generation == reconnectGeneration) start()
        }
    }

    private fun dispatch(request: QueuedRpc) {
        if (request.generation != generation || stopped) return
        inFlight[request.rpcId] = request
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
        transport.postSafely(
            "${connection.baseUrl.trimEnd('/')}${DshHostProtocol.API_PREFIX}/${request.method}",
            body, headers, REQUEST_TIMEOUT_SECONDS,
        ) { data, success, errorMsg, response ->
            // Native HTTP cannot be aborted; removing the request suppresses its late response.
            if (inFlight.remove(request.rpcId) !== request) return@postSafely
            if (request.generation != generation || stopped) {
                request.callback(null, DshRpcError("generation-cancelled", "请求所属连接世代已失效"), request.rpcId)
                return@postSafely
            }
            if (!success) {
                request.callback(null, DshRpcError(
                    "transport-${response.statusCode ?: 0}",
                    "${request.method} failed (${response.statusCode ?: 0}): $errorMsg",
                ), request.rpcId)
                return@postSafely
            }
            val result = data.optJSONObject("result")
            if (result == null) {
                request.callback(null, DshRpcError("bad-response", "${request.method} 返回了非法 RPC 信封"), request.rpcId)
                return@postSafely
            }
            if (!result.optBoolean("ok")) {
                val error = result.optJSONObject("error")
                request.callback(null, DshRpcError(
                    error?.optString("code").orEmpty().ifEmpty { "internal" },
                    error?.optString("message").orEmpty().ifEmpty { "${request.method} 失败" },
                    error?.optJSONObject("details")?.toString() ?: "{}",
                ), request.rpcId)
                return@postSafely
            }
            request.callback(result.optJSONObject("value"), null, request.rpcId)
        }
    }

    private fun directCall(myGeneration: Long, method: String, payload: JSONObject, callback: (JSONObject?, DshRpcError?) -> Unit) {
        if (myGeneration != generation || stopped) return
        dispatch(QueuedRpc(myGeneration, method, payload, nextRpcId(myGeneration)) { value, error, _ ->
            callback(value, error ?: if (value == null) DshRpcError("bad-response", "$method 返回了空基线") else null)
        })
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
        lastPublishedPhase = phase
        onState(DshHostRuntimeState(phase, generation, muxOpen, hostOpen, message))
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 30
        const val RECONNECT_DELAY_MS = 1_000
    }
}

fun parseRespondReceipt(data: JSONObject): Pair<Boolean, String> {
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

fun jsonFlag(obj: JSONObject?, key: String): Boolean? {
    if (obj == null) return null
    val raw = obj.opt(key) ?: return null
    return when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> raw.equals("true", ignoreCase = true)
        else -> obj.optBoolean(key)
    }
}
