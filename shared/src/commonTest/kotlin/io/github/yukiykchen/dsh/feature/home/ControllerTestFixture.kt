package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.data.FakeHostTransport
import io.github.yukiykchen.dsh.data.createTestHostClient

internal class ControllerTestFixture {
    val transport = FakeHostTransport()
    var client: DshHostClient? = createTestHostClient(transport)
    var sessionId = "s1"
    val callbacks = mutableListOf<() -> Unit>()
    val scheduled = mutableListOf<() -> Unit>()
    var scheduledSequence = 0
    val ctx = object : DshHomeContext {
        override val pagerId = "test-page"
        override val activeSessionId get() = sessionId
        override val hostClient get() = client
    }

    init { transport.completeHandshake() }

    fun completeCredentialSetup(
        providerAvailable: Boolean = true,
        configured: Boolean = true,
        writable: Boolean = true,
        credentialRef: String = "CUSTOM_DEEPSEEK_KEY",
    ) {
        val providers = transport.requests.last { it.method == "llm.providers" }
        if (!providerAvailable) {
            providers.reply("""{"providers":[]}""")
            flush()
            return
        }
        providers.reply(
            """{"providers":[{"provider":"deepseek-official","settingsNs":"llm-deepseek","active":true}]}""",
        )
        transport.requests.last { it.method == "settings.describe" }.reply(
            """{"writable":$writable,"namespaces":[{"ns":"llm-deepseek","value":{"apiKeyEnv":"$credentialRef"}}]}""",
        )
        transport.requests.last { it.method == "credentials.describe" }.reply(
            """{"credentials":{"$credentialRef":{"configured":$configured,"writable":$writable}}}""",
        )
        flush()
    }

    fun post(callback: () -> Unit) { callbacks.add(callback) }
    fun schedule(callback: () -> Unit) { scheduled.add(callback); scheduledSequence++ }
    fun runScheduledSince(sequence: Int) {
        val count = minOf(scheduledSequence - sequence, scheduled.size)
        repeat(count) { scheduled.removeAt(0)() }
    }
    fun flush() {
        while (callbacks.isNotEmpty()) callbacks.removeAt(0)()
    }
    fun request(method: String) = transport.requests.last { it.method == method }

    fun question(rpcId: String = "question-1", count: Int = 1, duplicateItems: Boolean = false) {
        client!!.store.pendingInteractions.clear()
        client!!.store.putPending(rpcId, JSONObject().apply {
            put("type", "question/requested")
            put("sessionId", sessionId)
            put("questions", JSONArray().apply {
                repeat(count) { index ->
                    put(JSONObject().apply {
                        put("id", if (duplicateItems) "duplicate" else "q$index")
                        put("question", "Choose")
                        put("options", JSONArray().apply { put(JSONObject().apply { put("label", "Yes") }) })
                    })
                }
            })
        }.toString())
    }

    fun approval() {
        client!!.store.putPending("approval-1", """{"type":"approval/requested","sessionId":"$sessionId","approvalId":"a1","toolName":"shell"}""")
    }

    fun queue(id: String = "q1", text: String = "original") {
        client!!.store.replaceQueue(sessionId, JSONArray().apply {
            put(JSONObject().apply {
                put("id", id)
                put("placement", "queued")
                put("message", JSONObject().apply {
                    put("content", JSONArray().apply { put(JSONObject().apply { put("type", "text"); put("text", text) }) })
                })
            })
        }.toString())
    }
}

internal fun modelPayload(name: String = "Model One", model: String = "m1") =
    """{"current":{"provider":"p1","model":"$model"},"groups":[{"id":"p1","name":"Provider","models":[{"id":"$model","name":"$name"}]}],"routable":true}"""
