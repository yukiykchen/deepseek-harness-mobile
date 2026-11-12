package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.model.DshHostStore
import io.github.yukiykchen.dsh.model.DshSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshHostStoreQueriesTest {
    private val store = DshHostStore()
    private val queries = DshHostStoreQueries(store)

    @Test
    fun queueFiltersNonQueuedEntriesAndKeepsFullText() {
        store.replaceQueue("s1", """[
            {"id":"q1","placement":"queued","message":{"content":[{"type":"text","text":"first\nsecond"}]}},
            {"id":"q2","placement":"steered"},
            {"id":"","placement":"queued"}
        ]""")
        val item = queries.queue("s1").single()
        assertEquals("q1", item.id)
        assertEquals("first", item.preview)
        assertEquals("first\nsecond", item.text)
        assertTrue(queries.queue("s2").isEmpty())
    }

    @Test
    fun jobsPreserveNullableFinishTimeAndRejectMissingIds() {
        store.replaceJobs("s1", """[{"id":"j1","startedAt":42,"finishedAt":null},{"id":"j2","finishedAt":99},{"id":""}]""")
        val jobs = queries.jobs("s1")
        assertEquals(listOf("j1", "j2"), jobs.map { it.id })
        assertEquals(42L, jobs[0].startedAt)
        assertNull(jobs[0].finishedAt)
        assertEquals(99L, jobs[1].finishedAt)
    }

    @Test
    fun workspaceGroupsExcludeBlankAndArchivedSessionsAndKeepUngroupedOnes() {
        store.replaceSessions(listOf(session("s1"), session("s2"), session("s3"), session("blank", blank = true)))
        store.replaceWorkspaceBaseline("""[{"workspaceId":"w1","title":"Project","path":"/work","sessionIds":["s1","s3","blank"]}]""", setOf("s3"))
        val groups = queries.workspaceGroups()
        assertEquals(listOf("w1", ""), groups.map { it.workspaceId })
        assertEquals(listOf("s1"), groups[0].sessions.map { it.id })
        assertEquals(listOf("s2"), groups[1].sessions.map { it.id })
        assertEquals("w1", queries.workspaceIdForSession("blank"))
        assertNull(queries.workspaceIdForSession("missing"))
        assertEquals("blank", queries.blankSessionInWorkspace("w1")?.id)
        assertEquals("blank", queries.blankSessionInWorkspace(null)?.id)
    }

    @Test
    fun pendingInteractionsAreScopedAndResolveApprovalCommandAndQuestionOptions() {
        store.applySessionEvent("s1", 1, "tool/call", """{"data":{"callId":"call-1","arguments":{"command":"pwd"}}}""")
        store.putPending("approval-rpc", """{"type":"approval/requested","sessionId":"s1","approvalId":"a1","callId":"call-1","toolName":"shell"}""")
        store.putPending("question-rpc", """{"type":"question/requested","sessionId":"s1","questions":[{"id":"q1","question":"Choose","multi_select":true,"options":[{"label":"Yes","description":"Proceed"}]}]}""")
        store.putPending("other-rpc", """{"type":"approval/requested","sessionId":"s2","approvalId":"a2"}""")
        val (approval, question) = queries.pendingInteractions("s1")
        assertEquals("approval-rpc", approval?.rpcId)
        assertEquals("pwd", approval?.command)
        assertEquals("question-rpc", question?.rpcId)
        assertTrue(question!!.questions.single().multiSelect)
        assertEquals("Yes", question.questions.single().options.single().label)
        assertEquals(null to null, queries.pendingInteractions("missing"))
    }

    @Test
    fun productionMuxEnvelopePreservesTheApprovalCommand() {
        val clientStore = DshHostStore()
        clientStore.putPending("approval-rpc", """{"type":"approval/requested","sessionId":"s1","approvalId":"a1","callId":"call-1"}""")
        val envelope = JSONObject().apply {
            put("event", JSONObject().apply {
                put("type", "tool/call")
                put("data", JSONObject().apply {
                    put("callId", "call-1")
                    put("arguments", JSONObject().put("command", "pwd"))
                })
            })
        }
        clientStore.applySessionEvent("s1", 1, "tool/call", envelope.toString())
        assertEquals("pwd", DshHostStoreQueries(clientStore).pendingInteractions("s1").first?.command)
    }

    @Test
    fun malformedSnapshotsFallBackToEmptyResults() {
        store.replaceQueue("s1", "not-json")
        store.replaceJobs("s1", "not-json")
        store.putPending("bad", "not-json")
        assertTrue(queries.queue("s1").isEmpty())
        assertTrue(queries.jobs("s1").isEmpty())
        assertEquals(null to null, queries.pendingInteractions("s1"))
    }

    private fun session(id: String, blank: Boolean = false) = DshSession(id, id, "Host", "", blank = blank)
}
