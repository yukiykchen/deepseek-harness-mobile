package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.model.dshWireEvent
import io.github.yukiykchen.dsh.model.textFromBlocks

internal class DshPromptStreamRegistry {
    class ActiveStream(
        val sessionId: String,
        val promptRpcId: String,
        val onDelta: (String, Boolean) -> Unit,
        val onComplete: (String) -> Unit,
        val onError: (String) -> Unit,
    ) {
        var observed: Boolean = true
        val accumulated = StringBuilder()
        var finalMessage: String = ""
        var failure: String = ""
    }

    private val streams = mutableMapOf<String, ActiveStream>()

    fun put(stream: ActiveStream) {
        streams[stream.promptRpcId] = stream
    }

    fun remove(rpcId: String): ActiveStream? = streams.remove(rpcId)

    fun removeIfCurrent(rpcId: String, expected: ActiveStream): Boolean =
        if (streams[rpcId] === expected) streams.remove(rpcId) != null else false

    fun detachSession(sessionId: String): Int {
        val removed = streams.entries.filter { it.value.sessionId == sessionId }.map { it.key }
        removed.forEach(streams::remove)
        return removed.size
    }

    fun clear() = streams.clear()

    fun resolve(sessionId: String, rpcId: String): ActiveStream? {
        if (rpcId.isNotEmpty()) {
            streams[rpcId]?.takeIf { it.sessionId == sessionId }?.let { return it }
            return streams.values.lastOrNull {
                it.sessionId == sessionId && it.promptRpcId == "adopted-$sessionId"
            }
        }
        return streams.values.lastOrNull { it.sessionId == sessionId }
    }

    fun applyEvent(payload: JSONObject, sessionId: String): Boolean {
        val event = dshWireEvent(payload)
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        val source = data.optJSONObject("source") ?: data.optJSONObject("message")?.optJSONObject("source")
        val rpcId = source?.optString("rpcId").orEmpty()
        val active = resolve(sessionId, rpcId) ?: return false
        val sessionId = active.sessionId
        when (type) {
            "user/message" -> {
                val kind = source?.optString("kind").orEmpty()
                if (kind.isEmpty() || kind == "user") active.observed = true
            }
            "assistant/chunk" -> {
                val chunk = data.optJSONObject("chunk") ?: return false
                val chunkType = chunk.optString("type")
                val text = chunk.optString("text").ifEmpty { chunk.optString("delta") }
                when (chunkType) {
                    "text-delta", "text_delta", "text" -> text.takeIf { it.isNotEmpty() }?.let {
                        active.observed = true
                        active.accumulated.append(it)
                        active.onDelta(it, false)
                    }
                    "reasoning-delta", "reasoning_delta" -> text.takeIf { it.isNotEmpty() }?.let {
                        active.observed = true
                        active.onDelta(it, true)
                    }
                }
                if (chunkType == "finish") {
                    val reason = chunk.optJSONObject("reason")
                    if (reason?.optString("kind") == "error") {
                        active.failure = reason.optJSONObject("failure")?.optString("message").orEmpty()
                    }
                }
            }
            "assistant/message" -> {
                val message = data.optJSONObject("message") ?: data
                active.finalMessage = textFromBlocks(message.optJSONArray("content"))
            }
            "turn/end" -> {
                streams.remove(active.promptRpcId)
                val reason = data.optJSONObject("reason")
                val error = reason?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: active.failure.takeIf { it.isNotEmpty() }
                val completed = active.accumulated.toString().ifEmpty { active.finalMessage }
                DshStreamLog.i(
                    "mux.turn-end session=$sessionId rpcId=${active.promptRpcId} acc=${active.accumulated.length} final=${active.finalMessage.length} error=${error?.length ?: 0}",
                )
                if (error != null) active.onError(error) else active.onComplete(completed)
            }
        }
        return true
    }
}
