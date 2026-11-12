package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.module.NetworkResponse
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.data.FakeHostTransport
import io.github.yukiykchen.dsh.data.createTestHostClient
import io.github.yukiykchen.dsh.model.DshSession
import kotlin.test.Test
import kotlin.test.assertEquals

class DshHomeSessionBootstrapTest {
    @Test
    fun successCreatesPersistsAndSelectsBeforeRetryingSend() {
        val f = Fixture()
        f.transport.requests.last().reply("""{"sessionId":"created"}""")
        assertEquals("created", f.sessions.single().id)
        assertEquals(listOf("creating", "finished", "refresh", "persist", "select:created", "models:created", "send"), f.actions)
    }

    @Test
    fun successAfterScopeInvalidationOnlyReleasesTheCreationSlot() {
        val f = Fixture()
        f.current = false
        f.transport.requests.last().reply("""{"sessionId":"stale"}""")
        assertEquals(emptyList(), f.sessions)
        assertEquals(listOf("creating", "finished"), f.actions)
    }

    @Test
    fun failureAfterScopeInvalidationDoesNotWriteIntoAnotherConversation() {
        val f = Fixture()
        f.current = false
        f.transport.requests.last().callback(JSONObject(), false, "disconnected", NetworkResponse(JSONObject(), 503))
        assertEquals(listOf("creating", "finished"), f.actions)
    }

    private class Fixture {
        val transport = FakeHostTransport()
        val client = createTestHostClient(transport)
        var current = true
        val sessions = mutableListOf<DshSession>()
        val actions = mutableListOf<String>()
        init {
            transport.completeHandshake()
            bootstrapHomeSession(
                hostClient = client,
                isCurrent = { current },
                onFinished = { actions.add("finished") },
                addSession = { sessions.add(it) },
                refreshVisibleSessions = { actions.add("refresh") },
                persistSessions = { actions.add("persist") },
                currentSessions = { sessions },
                setActiveSessionId = { actions.add("select:$it") },
                loadModels = { actions.add("models:$it") },
                retrySend = { actions.add("send") },
                setConnectionLabel = { actions.add(if (it == "正在创建会话") "creating" else "error") },
                addMessage = { actions.add("message") },
                messageCount = { 0 },
            )
        }
    }
}
