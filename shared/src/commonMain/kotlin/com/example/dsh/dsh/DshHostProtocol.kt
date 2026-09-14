package com.example.dsh.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.random.Random

/**
 * DeepSeek Harness Host wire protocol (DSH >= 0.1.2, verified against 0.1.5-rc.1).
 *
 * Unary calls: `POST {base}/api/{namespace}/{method}` with a `client-request`
 * envelope whose payload is the Typert `{ "args": { "<parameter>": ... } }` object.
 * Streams: one WebSocket on `/api/remote.mux` multiplexing logical streams.
 * Authentication: an authority-bound browser-session cookie minted by
 * `GET {base}/?token={launch token}`.
 */
internal object DshHostProtocol {
    const val API_PREFIX = "/api"
    const val REMOTE_MUX_PATH = "$API_PREFIX/remote.mux"
    const val SESSION_EXPORT_PATH = "$API_PREFIX/session.export"
    /** Served by the patched dsh-scan-remote plugin; reachable through the sealed tunnel. */
    const val RELAY_AUTH_PATH = "/dsh-scan-remote/api/auth"

    // Gateway-internal streams / endpoints.
    const val EVENTS_STREAM = "\$events"
    const val EVENTS_RESULT = "\$events/result"

    // session namespace
    const val SESSION_LIST = "session/list"
    const val SESSION_CREATE = "session/create"
    const val SESSION_PROMPT = "session/prompt"
    const val SESSION_CANCEL = "session/cancel"
    const val SESSION_RENAME = "session/rename"
    const val SESSION_FORK = "session/fork"
    const val SESSION_ATTACHMENT = "session/attachment"
    const val SESSION_UPDATE_QUEUE = "session/updateQueue"
    const val SESSION_PAGE = "session/page"
    const val SESSION_MODEL_CATALOG = "session/modelCatalog"
    const val SESSION_SELECT_MODEL = "session/selectModel"
    const val SESSION_FOLLOW = "session/follow"
    const val SESSION_CONTROL = "session/control"

    // other namespaces
    const val SKILLS_LIST = "skills/list"
    const val PLUGIN_INVENTORY_LIST = "pluginInventory/list"

    /**
     * Route of the optional companion Host plugin in `host-plugin/`, which adds the
     * lifecycle writes `pluginInventory/list` does not offer. Not part of official DSH:
     * when the plugin is absent the route 404s and the app stays read-only.
     */
    const val MOBILE_ADMIN_PATH = "/dsh-mobile/rpc"
    const val MOBILE_ADMIN_CAPABILITIES = "mobile.capabilities"
    const val MOBILE_ADMIN_INVENTORY = "plugin.inventory"
    const val MOBILE_ADMIN_CONTROL = "plugin.control"

    /** Body for the companion plugin's route: `{method, params}`, not the Host envelope. */
    fun adminRequest(method: String, params: JSONObject = JSONObject()): JSONObject =
        JSONObject().apply {
            put("method", method)
            put("params", params)
        }
    const val WORKSPACE_FOLLOW = "workspace/follow"
    const val WORKSPACE_CREATE = "workspace/create"
    const val WORKSPACE_RENAME = "workspace/rename"
    const val WORKSPACE_DELETE = "workspace/delete"
    const val WORKSPACE_INSERT_BEFORE = "workspace/insertBefore"
    const val WORKSPACE_ARCHIVE_SESSION = "workspace/archiveSession"
    const val DIRECTORY_LIST = "directoryPicker/list"
    const val DIRECTORY_CREATE = "directoryPicker/createDirectory"
    const val GOALS_EDIT = "goals/edit"
    const val GOALS_PAUSE = "goals/pause"
    const val GOALS_RESUME = "goals/resume"
    const val GOALS_CLEAR = "goals/clear"
    const val CREDENTIALS_DESCRIBE = "credentials/describe"
    const val CREDENTIALS_SET = "credentials/set"
    const val LLM_LIST_PROVIDERS = "llm/listProviders"
    const val SETTINGS_DESCRIBE = "settings/describe"

    /** Forwarded Host events delivered as `emit` frames on `$events`. */
    const val EVENT_SESSION_ADDED = "api-session/added"
    const val EVENT_SESSION_REMOVED = "api-session/removed"
    const val EVENT_SESSION_STATUS = "api-session/status"
    const val EVENT_SESSION_ACTIVITY = "api-session/activity"
    const val EVENT_SESSION_ERROR = "api-session/error"
    /** Agent-scoped waterfalls delivered as `waterfall` frames on `$events`. */
    const val EVENT_APPROVAL_REQUEST = "approval/request"
    const val EVENT_QUESTION_REQUEST = "user-questions/request"

    /** Wrap Typert arguments as the Host expects them. */
    fun args(builder: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().apply { put("args", JSONObject().apply(builder)) }

    /** Most endpoints take exactly one `request` object parameter. */
    fun request(builder: JSONObject.() -> Unit): JSONObject =
        args { put("request", JSONObject().apply(builder)) }
}

/**
 * How the app reaches one Host.
 *
 * @property baseUrl loopback gateway (scan) or SSH forward (`http://127.0.0.1:port`), or a direct dev URL.
 * @property token bearer token the phone-local relay gateway requires; empty for SSH / direct.
 * @property cookie `name=value` browser-session cookie for `/api`; empty until minted.
 * @property launchToken the `?token=` printed by `dsh web`; empty when the relay plugin must supply it.
 */
internal data class DshHostConnection(
    val baseUrl: String,
    val token: String = "",
    val cookie: String = "",
    val launchToken: String = "",
) {
    val trimmedBase: String get() = baseUrl.trimEnd('/')

    fun webSocketUrl(path: String): String {
        val wsBase = when {
            trimmedBase.startsWith("https://") -> "wss://${trimmedBase.removePrefix("https://")}"
            trimmedBase.startsWith("http://") -> "ws://${trimmedBase.removePrefix("http://")}"
            else -> trimmedBase
        }
        return "$wsBase$path"
    }
}

/** Client-minted identifiers: request ids for `session/prompt`, stream ids for the mux. */
internal fun dshRandomId(prefix: String): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val body = buildString(20) { repeat(20) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    return "$prefix-$body"
}

internal object DshWebTimelineParser {
    /**
     * Fold durable Session events into ordered timeline items. Accepts both
     * `session/follow` records (`{type:"event", event}`) and bare events.
     */
    fun parseWebTimeline(events: JSONArray): List<DshWebTimelineItem> {
        val result = mutableListOf<DshWebTimelineItem>()
        val toolModels = mutableMapOf<String, DshRemoteToolCallModel>()
        val partials = linkedMapOf<String, StringBuilder>()
        for (index in 0 until events.length()) {
            val entry = events.optJSONObject(index) ?: continue
            val event = entry.optJSONObject("event") ?: entry
            val seq = event.optInt("seq", index)
            val type = event.optString("type")
            val data = event.optJSONObject("data") ?: continue
            when (type) {
                "user/message" -> {
                    val source = data.optJSONObject("source")
                    val sourceKind = source?.optString("kind").orEmpty()
                    val content = data.optJSONArray("content")
                    val text = textFromBlocks(content)
                    if (sourceKind == "user" || sourceKind.isEmpty()) {
                        if (text.isNotEmpty()) {
                            result += DshWebTimelineItem(
                                "user-$seq",
                                DshWebTimelineItem.Kind.USER,
                                text,
                                attachments = imageRefsFromBlocks(content),
                            )
                        } else {
                            val refs = imageRefsFromBlocks(content)
                            if (refs.isNotEmpty()) {
                                result += DshWebTimelineItem(
                                    "user-$seq",
                                    DshWebTimelineItem.Kind.USER,
                                    "",
                                    attachments = refs,
                                )
                            }
                        }
                    } else if (text.isNotEmpty()) {
                        result += DshWebTimelineItem(
                            key = "context-$seq",
                            kind = DshWebTimelineItem.Kind.CONTEXT,
                            text = text,
                            sourceLabel = contextSummary(source),
                            source = source,
                        )
                    }
                }
                "assistant/message" -> {
                    val message = data.optJSONObject("message") ?: data
                    val key = "${data.optInt("turn")}:${data.optInt("step")}"
                    val blocks = message.optJSONArray("content") ?: JSONArray()
                    appendAssistantBlocks(result, seq, blocks)
                    partials.remove(key)
                }
                "assistant/chunk" -> {
                    // Legacy durable chunk events (pre-0.1.2 logs) still replay as partial text.
                    val key = "${data.optInt("turn")}:${data.optInt("step")}"
                    val chunk = data.optJSONObject("chunk") ?: JSONObject()
                    val text = chunk.optString("text").ifEmpty { chunk.optString("delta") }
                    if (text.isNotEmpty() &&
                        chunk.optString("type") in setOf("", "text", "text-delta", "text_delta")
                    ) {
                        partials.getOrPut(key) { StringBuilder() }.append(text)
                    }
                }
                "tool/call" -> {
                    val remoteTool = DshRemoteToolCallModels.fromHistoryCall(entry) ?: continue
                    if (remoteTool.callId.isNotEmpty()) toolModels[remoteTool.callId] = remoteTool
                    result += DshWebTimelineItem(
                        key = "tool-$seq",
                        kind = DshWebTimelineItem.Kind.TOOL,
                        toolName = remoteTool.toolName,
                        input = remoteTool.input,
                        running = remoteTool.running,
                        callId = remoteTool.callId,
                        callSeq = seq,
                        cardType = remoteTool.cardType,
                        cardTitle = remoteTool.title,
                        cardBody = remoteTool.body,
                        remoteTool = remoteTool,
                    )
                }
                "tool/result" -> {
                    val message = data.optJSONObject("message")
                    val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
                    val callId = resultBlock?.optString("toolCallId")
                        ?: message?.optJSONObject("source")?.optString("callId")
                        ?: data.optString("callId")
                    val previous = toolModels[callId]
                    val settled = DshRemoteToolCallModels.settleHistoryResult(previous, entry) ?: continue
                    if (settled.callId.isNotEmpty()) toolModels[settled.callId] = settled
                    val call = result.lastOrNull {
                        it.kind == DshWebTimelineItem.Kind.TOOL &&
                            it.callSeq < seq &&
                            (settled.callId.isEmpty() || it.callId == settled.callId)
                    }
                    if (call != null) {
                        val callIndex = result.indexOf(call)
                        result[callIndex] = call.copy(
                            toolName = settled.toolName,
                            input = settled.input,
                            output = settled.output,
                            running = settled.running,
                            error = settled.error,
                            cardType = settled.cardType,
                            cardTitle = settled.title,
                            cardBody = settled.body,
                            remoteTool = settled,
                        )
                    } else {
                        result += DshWebTimelineItem(
                            key = "tool-$seq",
                            kind = DshWebTimelineItem.Kind.TOOL,
                            toolName = settled.toolName,
                            input = settled.input,
                            output = settled.output,
                            running = settled.running,
                            callId = settled.callId,
                            callSeq = seq,
                            error = settled.error,
                            cardType = settled.cardType,
                            cardTitle = settled.title,
                            cardBody = settled.body,
                            remoteTool = settled,
                        )
                    }
                }
                "turn/end" -> {
                    data.optJSONObject("reason")?.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { result += DshWebTimelineItem("turn-error-$seq", DshWebTimelineItem.Kind.ERROR, it) }
                }
            }
        }
        partials.forEach { (key, text) ->
            if (text.isNotEmpty()) {
                result += DshWebTimelineItem(
                    key = "partial-$key",
                    kind = DshWebTimelineItem.Kind.ASSISTANT,
                    text = text.toString(),
                )
            }
        }
        return result.filterNot { it.kind == DshWebTimelineItem.Kind.USER && it.isRuntimeContextSnapshot() }
    }
}

private fun DshWebTimelineItem.isRuntimeContextSnapshot(): Boolean {
    return text.startsWith("Current runtime context. This snapshot supersedes earlier runtime-context snapshots.")
}

internal fun contextSourceLabel(source: JSONObject?): String {
    if (source == null) return "未知来源"
    val kind = source.optString("kind")
    return when (kind) {
        "skill-invocation" -> source.optString("name").takeIf { it.isNotEmpty() } ?: kind
        "plugin" -> source.optString("plugin").takeIf { it.isNotEmpty() } ?: kind
        "session-reference" -> sourceLabels(source, "references", "label").takeIf { it.isNotEmpty() } ?: kind
        "agent-instructions" -> sourceLabels(source, "changes", "path").takeIf { it.isNotEmpty() } ?: kind
        else -> kind.takeIf { it.isNotEmpty() } ?: source.optString("name").takeIf { it.isNotEmpty() } ?: "未知来源"
    }
}

internal fun contextSummary(source: JSONObject?): String {
    if (source?.optString("form") == "notice") {
        source.optString("summary").takeIf { it.isNotEmpty() }?.let { return it }
    }
    return contextSourceLabel(source)
}

private fun sourceLabels(source: JSONObject, member: String, field: String): String {
    val values = source.optJSONArray(member) ?: return ""
    val labels = mutableListOf<String>()
    for (index in 0 until values.length()) {
        val value = values.optJSONObject(index) ?: continue
        val label = value.optString(field).takeIf { it.isNotEmpty() } ?: continue
        if (!labels.contains(label)) labels += label
    }
    return labels.joinToString(", ")
}

internal fun toolInputSummary(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    else -> value.toString()
}

internal fun toolOutputSummary(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    is JSONArray -> textFromBlocks(value)
    else -> value.toString()
}

internal fun toolCardType(view: JSONObject): DshToolCardType = when (view.optString("card")) {
    "terminal" -> DshToolCardType.TERMINAL
    "read" -> DshToolCardType.READ
    "diff" -> DshToolCardType.DIFF
    "search" -> DshToolCardType.SEARCH
    "web" -> DshToolCardType.WEB
    else -> DshToolCardType.GENERIC
}

internal fun textFromBlocks(blocks: JSONArray?): String {
    if (blocks == null) return ""
    return buildString {
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") == "text") append(block.optString("text"))
        }
    }
}

/** `ImageAttachmentRef`s carried by admitted image blocks; never raw base64. */
internal fun imageRefsFromBlocks(blocks: JSONArray?): List<DshImageAttachmentRef> {
    if (blocks == null) return emptyList()
    return buildList {
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") != "image") continue
            val ref = block.optJSONObject("attachment") ?: continue
            val id = ref.optString("attachmentId")
            if (id.isEmpty()) continue
            add(DshImageAttachmentRef(
                attachmentId = id,
                mediaType = ref.optString("mediaType").ifEmpty { "image/png" },
                bytes = ref.optLong("bytes"),
                width = ref.optInt("width"),
                height = ref.optInt("height"),
                name = ref.optString("name").takeIf { it.isNotEmpty() },
            ))
        }
    }
}

internal fun appendAssistantBlocks(
    result: MutableList<DshWebTimelineItem>,
    seq: Int,
    blocks: JSONArray?,
) {
    if (blocks == null) return
    for (blockIndex in 0 until blocks.length()) {
        val block = blocks.optJSONObject(blockIndex) ?: continue
        when (block.optString("type")) {
            "text" -> block.optString("text").takeIf { it.isNotEmpty() }?.let {
                result += DshWebTimelineItem("text-$seq-$blockIndex", DshWebTimelineItem.Kind.ASSISTANT, it)
            }
            "reasoning" -> block.optString("text").takeIf { it.isNotEmpty() }?.let {
                result += DshWebTimelineItem(
                    "reasoning-$seq-$blockIndex",
                    DshWebTimelineItem.Kind.REASONING,
                    it,
                )
            }
            "image" -> block.optJSONObject("attachment")?.optString("attachmentId")
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    result += DshWebTimelineItem(
                        "image-$seq-$blockIndex",
                        DshWebTimelineItem.Kind.IMAGE,
                        attachmentId = it,
                    )
                }
            "tool-call" -> Unit
            else -> result += DshWebTimelineItem(
                "block-$seq-$blockIndex",
                DshWebTimelineItem.Kind.UNKNOWN_BLOCK,
                block.toString(),
            )
        }
    }
}

internal fun dshEncodeQueryComponent(value: String): String {
    val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"
    return buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (char in allowed) append(char)
            else append('%').append(unsigned.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
