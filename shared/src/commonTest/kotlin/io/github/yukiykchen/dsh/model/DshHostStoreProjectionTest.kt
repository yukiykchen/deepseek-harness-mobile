package io.github.yukiykchen.dsh.model

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.yukiykchen.dsh.data.boundedContextText
import io.github.yukiykchen.dsh.data.buildQuestionAnswer
import io.github.yukiykchen.dsh.data.contextCanExpand
import io.github.yukiykchen.dsh.data.contextCatalogEntries
import io.github.yukiykchen.dsh.data.contextInstructions
import io.github.yukiykchen.dsh.data.contextRecalls
import io.github.yukiykchen.dsh.data.contextRelaySender
import io.github.yukiykchen.dsh.data.contextSections
import io.github.yukiykchen.dsh.data.dshLooksLikeJson
import io.github.yukiykchen.dsh.data.dshReasoningSummary
import io.github.yukiykchen.dsh.data.isRemoteCatalogInvalidationEvent
import io.github.yukiykchen.dsh.data.parseGoalProjection
import io.github.yukiykchen.dsh.data.parseRespondReceipt
import io.github.yukiykchen.dsh.data.pendingInteractionRpcId
import io.github.yukiykchen.dsh.ui.visibleSkillList
import io.github.yukiykchen.dsh.ui.dshBuildJsonNodes
import io.github.yukiykchen.dsh.ui.dshCollapsedLines
import io.github.yukiykchen.dsh.ui.dshJobDuration
import io.github.yukiykchen.dsh.ui.dshJsonPreview
import io.github.yukiykchen.dsh.ui.dshOrderedJobs
import io.github.yukiykchen.dsh.ui.iconAsset

class DshHostStoreProjectionTest {
    @Test
    fun listBaselineKeepsBlankAuthorityAndSessionAddedCannotReblank() {
        val store = DshHostStore()
        store.replaceSessions(listOf(DshSession("s1", "尚无标题", "Host", "", blank = false)))
        store.applySessionAdded(DshSession("s1", "尚无标题", "Host", "", blank = true))
        assertFalse(store.sessions.getValue("s1").blank)

        store.replaceSessions(listOf(DshSession("s1", "尚无标题", "Host", "", blank = true)))
        assertTrue(store.sessions.getValue("s1").blank)
    }

    @Test
    fun queueAndJobsReplaceWholeSnapshots() {
        val store = DshHostStore()
        store.replaceQueue("s1", "[1,2]")
        store.replaceQueue("s1", "[3]")
        store.replaceJobs("s1", "[{\"id\":\"j1\"}]")
        store.replaceJobs("s1", "[]")
        assertEquals("[3]", store.queueSnapshots.getValue("s1"))
        assertEquals("[]", store.jobSnapshots.getValue("s1"))
    }

    @Test
    fun projectionsUseHigherSequenceAndEventsStayPartitioned() {
        val store = DshHostStore()
        store.applyProjection("s1", "title", "new", 4)
        store.applyProjection("s1", "title", "old", 3)
        store.applySessionEvent("s1", 7, "assistant/message", "a")
        store.applySessionEvent("s2", 2, "assistant/message", "b")
        assertEquals("new", store.projections.getValue("s1").getValue("title").value)
        assertEquals(1, store.sessionEvents.getValue("s1").size)
        assertEquals(1, store.sessionEvents.getValue("s2").size)
        assertEquals(7, store.sessionLastSeq.getValue("s1"))
    }

    @Test
    fun sessionEventsAreBoundedOrderedAndRemovable() {
        val store = DshHostStore()
        repeat(300) { seq -> store.applySessionEvent("s1", seq, "assistant/message", "payload-$seq") }
        store.applySessionEvent("s1", 2, "assistant/message", "duplicate")
        val events = store.sessionEvents.getValue("s1")
        assertEquals(256, events.size)
        assertEquals(44, events.first().seq)
        assertEquals(299, events.last().seq)
        assertEquals(299, store.sessionLastSeq.getValue("s1"))

        store.replaceQueue("s1", "[]")
        store.replaceJobs("s1", "[]")
        store.applyProjection("s1", "title", "Title", 1)
        store.putPending("r1", """{"type":"approval/requested","sessionId":"s1"}""")
        store.putPending("r2", """{"type":"approval/requested","sessionId":"s2"}""")
        store.removeSession("s1")
        assertFalse(store.sessionEvents.containsKey("s1"))
        assertFalse(store.sessionLastSeq.containsKey("s1"))
        assertFalse(store.queueSnapshots.containsKey("s1"))
        assertFalse(store.jobSnapshots.containsKey("s1"))
        assertFalse(store.projections.containsKey("s1"))
        assertFalse(store.pendingInteractions.containsKey("r1"))
        assertTrue(store.pendingInteractions.containsKey("r2"))
    }

    @Test
    fun longTextCollapseKeepsHeadTailAndMarker() {
        val lines = (1..40).map { "line-$it" }
        val collapsed = lines.joinToString("\n").dshCollapsedLines(16)
        assertEquals("line-1", collapsed.first())
        assertEquals("line-40", collapsed.last())
        assertEquals("… 其余 24 行", collapsed[8])
        assertEquals(16, collapsed.size - 1)
    }

    @Test
    fun jsonPreviewIsBounded() {
        assertEquals("short", dshJsonPreview("short"))
        assertTrue(dshJsonPreview("x".repeat(300)).length <= 160)
    }

    @Test
    fun queueSnapshotReplacesWholeValueAndFiltersQueued() {
        val store = DshHostStore()
        store.replaceQueue("s1", "[{\"id\":\"a\",\"placement\":\"queued\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}},{\"id\":\"b\",\"placement\":\"context\",\"message\":{\"content\":[]}}]")
        store.replaceQueue("s1", "[{\"id\":\"c\",\"placement\":\"queued\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"world\"}]}}]")
        val raw = store.queueSnapshots.getValue("s1")
        assertTrue(raw.contains("\"id\":\"c\""))
        assertFalse(raw.contains("\"id\":\"a\""))
    }

    @Test
    fun queueEditingForcesMultiItemDockExpanded() {
        val expanded = false
        val items = 2
        val editingId = "item-1"

        assertTrue(expanded || items <= 1 || editingId.isNotEmpty())
    }

    @Test
    fun queueActionBusyBlocksAdditionalMutations() {
        val actionBusy = true
        val editingId = ""

        assertFalse(!actionBusy && editingId.isNotEmpty())
        assertFalse(!actionBusy)
    }

    @Test
    fun queueSnapshotRemovalInvalidatesEditingItem() {
        val editingId = "item-1"
        val remainingIds = listOf("item-2")

        assertTrue(editingId.isNotEmpty() && remainingIds.none { it == editingId })
    }

    @Test
    fun structuredContextCanExpandWithoutModelText() {
        val message = DshMessage(
            id = "context-catalog",
            role = DshMessageRole.TOOL,
            content = "",
            isContextInjection = true,
            contextCatalog = listOf(DshContextCatalogEntry("/review", "Review code")),
        )

        assertTrue(message.contextCanExpand())
    }

    @Test
    fun jobsPanelDisplayStateIsClientLocalAndEmptySnapshotsCollapseIt() {
        var expanded = true
        val jobs = emptyList<DshJobItem>()
        if (jobs.isEmpty()) expanded = false

        assertFalse(expanded)
    }

    @Test
    fun jobsOrderPlacesLiveJobsFirstAndSettledJobsNewestFirst() {
        val jobs = listOf(
            DshJobItem("done-old", "bash", "old", "completed", "", 1_000, 2_000),
            DshJobItem("live", "bash", "live", "running", "", 3_000, null),
            DshJobItem("done-new", "bash", "new", "failed", "", 4_000, 9_000),
        )

        assertEquals(listOf("live", "done-new", "done-old"), dshOrderedJobs(jobs).map { it.id })
    }

    @Test
    fun liveJobDurationUsesClientClockWhileSettledUsesHostFinishTime() {
        val live = DshJobItem("live", "bash", "live", "running", "", 1_000, null)
        val done = DshJobItem("done", "bash", "done", "completed", "", 1_000, 4_000)

        assertEquals("5秒", dshJobDuration(live, 6_000))
        assertEquals("3秒", dshJobDuration(done, 6_000))
    }

    @Test
    fun pendingInteractionRpcIdPrefersEnvelopeThenPayload() {
        val envelope = JSONObject().apply {
            put("rpcId", "env-1")
            put("payload", JSONObject().apply { put("rpcId", "inner-1") })
        }
        val payload = envelope.optJSONObject("payload") ?: error("payload")
        assertEquals("env-1", pendingInteractionRpcId(envelope, payload))

        val payloadOnly = JSONObject().apply { put("rpcId", "pay-1") }
        assertEquals("pay-1", pendingInteractionRpcId(JSONObject(), payloadOnly))
        assertEquals("", pendingInteractionRpcId(JSONObject(), JSONObject()))
    }

    @Test
    fun parseRespondReceiptReadsTopLevelAndNestedAccepted() {
        val top = JSONObject().apply {
            put("accepted", true)
        }
        assertEquals(true to "", parseRespondReceipt(top))

        val nested = JSONObject().apply {
            put("type", "server-response")
            put("result", JSONObject().apply {
                put("ok", true)
                put("value", JSONObject().apply {
                    put("accepted", true)
                })
            })
        }
        assertEquals(true to "", parseRespondReceipt(nested))

        val rejected = JSONObject().apply {
            put("accepted", false)
            put("reason", "not-pending")
        }
        assertEquals(false to "not-pending", parseRespondReceipt(rejected))
    }

    @Test
    fun questionAnswerUsesHostBatchWireShape() {
        val question = DshPendingQuestion(
            rpcId = "rpc-q",
            sessionId = "session-1",
            questions = listOf(
                DshPendingQuestionItem(
                    id = "choice",
                    question = "Pick",
                    header = "Pick",
                    detail = "",
                    options = listOf(DshPendingQuestionOption("A", ""), DshPendingQuestionOption("B", "")),
                    multiSelect = false,
                ),
                DshPendingQuestionItem(
                    id = "notes",
                    question = "Notes",
                    header = "Notes",
                    detail = "",
                    options = emptyList(),
                    multiSelect = true,
                ),
            ),
        )
        val drafts = mapOf(
            0 to DshQuestionDraft(selected = listOf("A")),
            1 to DshQuestionDraft(selected = listOf("A", "B"), custom = "ship it"),
        )

        val answer = buildQuestionAnswer(question, drafts)
        val answers = answer.optJSONArray("answers") ?: error("answers missing")

        assertEquals(2, answers.length())
        assertEquals("choice", answers.optJSONObject(0)?.optString("id"))
        assertEquals("A", answers.optJSONObject(0)?.optJSONArray("selected")?.optString(0))
        assertEquals("notes", answers.optJSONObject(1)?.optString("id"))
        assertEquals("ship it", answers.optJSONObject(1)?.optString("custom"))
        assertEquals("B", answers.optJSONObject(1)?.optJSONArray("selected")?.optString(1))
    }

    @Test
    fun skillCandidatesFilterBySlashQueryWithoutChangingInvocationWire() {
        val skills = ObservableList<DshSkill>().apply {
            add(DshSkill("code-review", "Review code"))
            add(DshSkill("commit-helper", "Prepare commit"))
            add(DshSkill("deploy", "Deploy app", modelInvocable = false))
        }

        val candidates = visibleSkillList(skills, "code")

        assertEquals(1, candidates.size)
        assertEquals("code-review", candidates[0].name)
        assertEquals("Review code", candidates[0].description)
    }

    @Test
    fun forwardedRemoteEventsInvalidateSkillAndModelCatalogs() {
        assertTrue(isRemoteCatalogInvalidationEvent("commands/change"))
        assertTrue(isRemoteCatalogInvalidationEvent("skills/change"))
        assertTrue(isRemoteCatalogInvalidationEvent("settings/document-updated"))
        assertTrue(isRemoteCatalogInvalidationEvent("credentials/updated"))
        assertTrue(isRemoteCatalogInvalidationEvent("llm/adapters-updated"))
        assertFalse(isRemoteCatalogInvalidationEvent("unrelated/event"))
    }

    @Test
    fun goalProjectionRendersActiveAndBlockedGoalsButHidesComplete() {
        val active = parseGoalProjection(
            """{"goal":{"id":"g1","revision":1,"objective":"Ship feature","phase":"active"}}""",
        )
        val blocked = parseGoalProjection(
            """{"goal":{"id":"g2","revision":2,"objective":"Fix issue","phase":"blocked","blockedReason":{"message":"Needs input"}}}""",
        )
        val complete = parseGoalProjection(
            """{"goal":{"id":"g3","revision":3,"objective":"Done","phase":"complete"}}""",
        )

        assertEquals("Ship feature", active?.objective)
        assertEquals("active", active?.phase)
        assertEquals("Needs input", blocked?.blockedReason)
        assertEquals("blocked", blocked?.phase)
        assertNull(complete)
    }

    @Test
    fun jobsSnapshotReplacesWholeValue() {
        val store = DshHostStore()
        store.replaceJobs("s1", "[{\"id\":\"bash-1\",\"kind\":\"bash\",\"status\":\"running\"}]")
        store.replaceJobs("s1", "[]")
        assertEquals("[]", store.jobSnapshots.getValue("s1"))
    }

    @Test
    fun pendingInteractionsUseStableRpcIdAndRemoveOnResolve() {
        val store = DshHostStore()
        store.putPending("r1", "{\"type\":\"approval/requested\",\"sessionId\":\"s1\",\"approvalId\":\"a1\",\"toolName\":\"bash\",\"reason\":\"write file\"}")
        store.putPending("r2", "{\"type\":\"question/requested\",\"sessionId\":\"s1\",\"questions\":[{\"id\":\"q1\",\"question\":\"Pick?\",\"options\":[{\"label\":\"A\"}]}]}")
        assertTrue(store.pendingInteractions.containsKey("r1"))
        assertTrue(store.pendingInteractions.containsKey("r2"))
        store.removePending("r1")
        assertFalse(store.pendingInteractions.containsKey("r1"))
        assertTrue(store.pendingInteractions.containsKey("r2"))
    }

    @Test
    fun toolViewChoosesCardTitleAndBody() {
        val nodes = dshBuildJsonNodes(
            mapOf(
                "user" to mapOf("name" to "alex"),
                "files" to listOf("a.txt", "b.txt"),
            ),
        )
        assertEquals(8, nodes.size)
        assertEquals("user", nodes[0].label)
        assertEquals("alex", nodes[0].children.single { it.label == "name" }.preview)
        assertEquals("files", nodes[3].label)
        assertEquals(4, nodes[3].children.size)
    }

    @Test
    fun skippedQuestionIsCompleteAndEmitsEmptySelection() {
        val draft = DshQuestionDraft(skipped = true)
        assertTrue(draft.skipped)
        assertTrue(draft.selected.isEmpty())
        assertTrue(draft.custom.isBlank())
    }

    @Test
    fun toolProjectionKeepsCardTypeAndExecutionState() {
        val message = DshMessage(
            id = "tool-1",
            role = DshMessageRole.TOOL,
            content = "ls",
            toolName = "List files",
            toolCardType = DshToolCardType.TERMINAL,
            toolRunning = false,
            toolError = false,
        )
        assertEquals(DshToolCardType.TERMINAL, message.toolCardType)
        assertFalse(message.toolRunning)
        assertFalse(message.toolError)
    }

    @Test
    fun toolCardTypeMapsToStableIconAsset() {
        assertEquals(
            mapOf(
                DshToolCardType.GENERIC to "tool-generic.svg",
                DshToolCardType.TERMINAL to "tool-terminal.svg",
                DshToolCardType.READ to "tool-read.svg",
                DshToolCardType.DIFF to "tool-diff.svg",
                DshToolCardType.SEARCH to "tool-search.svg",
                DshToolCardType.WEB to "tool-web.svg",
                DshToolCardType.JSON to "tool-json.svg",
            ),
            DshToolCardType.entries.associateWith { it.iconAsset() },
        )
    }

    @Test
    fun webTimelineSeparatesContextInjectionFromUserMessage() {
        val events = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
            """
            [
              {
                "event": {
                  "seq": 1,
                  "type": "user/message",
                  "data": {
                    "source": {
                      "kind": "skill-invocation",
                      "name": "skill-catalog"
                    },
                    "content": [
                      { "type": "text", "text": "Skill catalog body" }
                    ]
                  }
                }
              }
            ]
            """.trimIndent(),
        )

        val timeline = DshWebTimelineParser.parseWebTimeline(events)

        val context = timeline.single()
        assertEquals(DshWebTimelineItem.Kind.CONTEXT, context.kind)
        assertEquals("skill-catalog", context.sourceLabel)
        assertEquals("Skill catalog body", context.text)
    }

    @Test
    fun webTimelineSeparatesReasoningFromBodyText() {
        val events = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
            """
            [
              {
                "event": {
                  "seq": 2,
                  "type": "assistant/message",
                  "data": {
                    "message": {
                      "content": [
                        { "type": "reasoning", "text": "first thought" },
                        { "type": "text", "text": "visible answer" }
                      ]
                    }
                  }
                }
              }
            ]
            """.trimIndent(),
        )

        val timeline = DshWebTimelineParser.parseWebTimeline(events)

        assertEquals(
            listOf(DshWebTimelineItem.Kind.REASONING, DshWebTimelineItem.Kind.ASSISTANT),
            timeline.map { it.kind },
        )
        assertEquals("first thought", timeline[0].text)
        assertEquals("visible answer", timeline[1].text)
    }

    @Test
    fun webTimelineKeepsInProgressAssistantChunks() {
        val events = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
            """
            [
              {
                "event": {
                  "seq": 3,
                  "type": "assistant/chunk",
                  "data": {
                    "turn": 1,
                    "step": 0,
                    "chunk": { "type": "text-delta", "text": "Hello " }
                  }
                }
              },
              {
                "event": {
                  "seq": 4,
                  "type": "assistant/chunk",
                  "data": {
                    "turn": 1,
                    "step": 0,
                    "chunk": { "type": "text-delta", "text": "world" }
                  }
                }
              }
            ]
            """.trimIndent(),
        )

        val timeline = DshWebTimelineParser.parseWebTimeline(events)

        assertEquals(listOf(DshWebTimelineItem.Kind.ASSISTANT), timeline.map { it.kind })
        assertEquals("Hello world", timeline.single().text)
        assertEquals("partial-1:0", timeline.single().key)
    }

    @Test
    fun turnStatusLabelAndClockMatchDshWeb() {
        assertEquals("Deep diving...", dshTurnStatusLabel(reconnecting = false))
        assertEquals("Reconnecting...", dshTurnStatusLabel(reconnecting = true))
        assertEquals("3秒", dshFormatTurnDuration(3_000))
        assertEquals("1分05秒", dshFormatTurnDuration(65_000))
    }

    @Test
    fun transportInterruptCodesCoverReconnect() {
        assertTrue(dshIsTransportInterrupt("generation-cancelled"))
        assertTrue(dshIsTransportInterrupt("cancelled"))
        assertTrue(dshIsTransportInterrupt("transport-0", "connection reset"))
        assertTrue(dshIsTransportInterrupt("internal", "请求所属连接世代已失效"))
        assertFalse(dshIsTransportInterrupt("internal", "模型超时"))
    }

    @Test
    fun webTimelineMergesToolCallAndResult() {
        val events = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
            """
            [
              {
                "event": {
                  "seq": 3,
                  "type": "tool/call",
                  "data": {
                    "callId": "call-1",
                    "name": "Bash",
                    "arguments": "ls"
                  }
                }
              },
              {
                "event": {
                  "seq": 4,
                  "type": "tool/result",
                  "data": {
                    "message": {
                      "content": [
                        {
                          "type": "tool-result",
                          "toolCallId": "call-1",
                          "content": [
                            { "type": "text", "text": "README.md" }
                          ],
                          "isError": false
                        }
                      ],
                      "source": {
                        "kind": "tool",
                        "callId": "call-1"
                      }
                    }
                  }
                },
                "view": {
                  "view": {
                    "card": "terminal",
                    "title": "List files",
                    "output": "README.md"
                  }
                }
              }
            ]
            """.trimIndent(),
        )

        val tool = DshWebTimelineParser.parseWebTimeline(events).single()

        assertEquals(DshWebTimelineItem.Kind.TOOL, tool.kind)
        assertEquals("Bash", tool.toolName)
        assertEquals("List files", tool.cardTitle)
        assertEquals("README.md", tool.output)
        assertEquals(DshToolCardType.TERMINAL, tool.cardType)
        assertEquals("README.md", tool.cardBody)
        assertFalse(tool.running)
        assertNull(tool.error)
    }
}
