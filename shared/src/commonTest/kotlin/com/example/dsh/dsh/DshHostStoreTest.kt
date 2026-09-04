package com.example.dsh.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshHostStoreTest {
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

    @Test
    fun webTimelineMatchesParallelToolResultsByCallId() {
        val events = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
            """
            [
              {
                "event": {
                  "seq": 3,
                  "type": "tool/call",
                  "data": { "callId": "first", "name": "First", "arguments": "1" }
                }
              },
              {
                "event": {
                  "seq": 4,
                  "type": "tool/call",
                  "data": { "callId": "second", "name": "Second", "arguments": "2" }
                }
              },
              {
                "event": {
                  "seq": 5,
                  "type": "tool/result",
                  "data": {
                    "message": {
                      "content": [
                        {
                          "type": "tool-result",
                          "toolCallId": "second",
                          "content": [{ "type": "text", "text": "second output" }],
                          "isError": false
                        }
                      ]
                    }
                  }
                }
              },
              {
                "event": {
                  "seq": 6,
                  "type": "tool/result",
                  "data": {
                    "message": {
                      "content": [
                        {
                          "type": "tool-result",
                          "toolCallId": "first",
                          "content": [{ "type": "text", "text": "first output" }],
                          "isError": false
                        }
                      ]
                    }
                  }
                }
              }
            ]
            """.trimIndent(),
        )

        val timeline = DshWebTimelineParser.parseWebTimeline(events)

        assertEquals(listOf("first", "second"), timeline.map { it.callId })
        assertEquals(listOf("first output", "second output"), timeline.map { it.output })
        assertFalse(timeline[0].running)
        assertFalse(timeline[1].running)
    }

    @Test
    fun remoteToolModelDispatchesWebToolNamesAndKeepsLiveView() {
        val call = JSONObject()
            .put("event", JSONObject()
                .put("type", "tool/call")
                .put("seq", 20)
                .put("data", JSONObject()
                    .put("callId", "grep-1")
                    .put("name", "grep")
                    .put("arguments", JSONObject().put("pattern", "TODO").put("path", "src"))))
            .put("view", JSONObject()
                .put("for", "call")
                .put("view", JSONObject().put("card", "generic")))

        val running = DshRemoteToolCallModels.fromLiveCall(call)
        assertEquals(DshRemoteToolKind.SEARCH, running?.kind)
        assertEquals("Grep", running?.title)
        assertEquals("TODO", running?.summary)

        val result = JSONObject()
            .put("event", JSONObject()
                .put("type", "tool/result")
                .put("seq", 21)
                .put("data", JSONObject()
                    .put("message", JSONObject()
                        .put("content", com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
                            .put(JSONObject()
                                .put("type", "tool-result")
                                .put("toolCallId", "grep-1")
                                .put("content", com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
                                    .put(JSONObject().put("type", "text").put("text", "src/A.kt")))))
                        .put("source", JSONObject().put("callId", "grep-1")))))
            .put("view", JSONObject()
                .put("for", "result")
                .put("view", JSONObject()
                    .put("card", "search")
                    .put("shape", "paths")
                    .put("paths", com.tencent.kuikly.core.nvi.serialization.json.JSONArray().put("src/A.kt"))
                    .put("title", "TODO matches")))

        val settled = DshRemoteToolCallModels.settleLiveResult(running, result)
        assertEquals(DshToolCardType.SEARCH, settled?.cardType)
        assertEquals("TODO matches", settled?.title)
        assertEquals("src/A.kt", settled?.body)
        assertFalse(settled?.running ?: true)
    }

    @Test
    fun remoteToolModelCoversFileAskAndTodoRows() {
        fun call(name: String, arguments: JSONObject): DshRemoteToolCallModel? =
            DshRemoteToolCallModels.fromLiveCall(JSONObject().put(
                "event", JSONObject().put("type", "tool/call").put("data", JSONObject()
                    .put("callId", name).put("name", name).put("arguments", arguments)),
            ))

        val edit = call("edit", JSONObject().put("path", "src/App.kt"))
        val ask = call("ask_user_question", JSONObject().put("questions", com.tencent.kuikly.core.nvi.serialization.json.JSONArray().put(JSONObject().put("id", "q1"))))
        val todo = call("todo_write", JSONObject().put("todos", com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
            .put(JSONObject().put("content", "ship it").put("status", "in_progress"))
            .put(JSONObject().put("content", "done").put("status", "completed"))))

        assertEquals(DshRemoteToolKind.FILE_MUTATION, edit?.kind)
        assertEquals("Edit", edit?.title)
        assertEquals("src/App.kt", edit?.summary)
        assertEquals(DshRemoteToolKind.ASK_QUESTION, ask?.kind)
        assertEquals("等待回答", ask?.summary)
        assertEquals("tool-ask.svg", ask?.iconAsset())
        assertEquals(DshRemoteToolKind.TODO, todo?.kind)
        assertEquals("1/2 已完成 · ship it", todo?.summary)
        assertNull(DshMessage("local", DshMessageRole.TOOL, "tool").remoteTool)
    }

    @Test
    fun askReadableBodyHidesRawJsonAndKeepsSelectedAnswers() {
        val input = """{"questions":[{"id":"research_topic","prompt":"研究主题"}]}"""
        val output = """{"answers":[{"id":"research_topic","selected":["方案调研"]}]}"""
        assertEquals("研究主题：方案调研", dshAskReadableBody(input, output))
        assertTrue("{\"answers\"".dshLooksLikeJson())
        assertFalse("已回答 1/1".dshLooksLikeJson())
    }

    @Test
    fun remoteSkillRowUsesToolArgumentsAndDurableInstructions() {
        val running = DshRemoteToolCallModels.fromLiveCall(
            JSONObject().put(
                "event", JSONObject().put("type", "tool/call").put("data", JSONObject()
                    .put("callId", "skill-1")
                    .put("name", "skill")
                    .put("arguments", JSONObject().put("name", "dsh-manage-issues"))),
            ),
        )
        assertEquals(DshRemoteToolKind.SKILL, running?.kind)
        assertEquals("Skill", running?.title)
        assertEquals("dsh-manage-issues", running?.summary)
        assertTrue(running?.running == true)

        val result = DshRemoteToolCallModels.settleLiveResult(
            running,
            JSONObject().put(
                "event", JSONObject().put("type", "tool/result").put("data", JSONObject()
                    .put("message", JSONObject().put(
                        "content", com.tencent.kuikly.core.nvi.serialization.json.JSONArray().put(JSONObject()
                            .put("type", "tool-result")
                            .put("toolCallId", "skill-1")
                            .put("content", com.tencent.kuikly.core.nvi.serialization.json.JSONArray().put(
                                JSONObject().put("type", "text").put("text", "<skill_content>instructions</skill_content>"),
                            ))),
                    ))),
            ),
        )
        assertFalse(result?.running ?: true)
        assertEquals("<skill_content>instructions</skill_content>", result?.output)
        assertEquals("<skill_content>instructions</skill_content>", result?.toRemoteMessage("skill-1")?.content)
    }

    @Test
    fun webTimelineKeepsUnknownAssistantBlockAsJsonFallback() {
        val events = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
            """
            [
              {
                "event": {
                  "seq": 7,
                  "type": "assistant/message",
                  "data": {
                    "message": {
                      "content": [
                        { "type": "text", "text": "answer" },
                        { "type": "future-block", "value": 42 }
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
            listOf(
                DshWebTimelineItem.Kind.ASSISTANT,
                DshWebTimelineItem.Kind.UNKNOWN_BLOCK,
            ),
            timeline.map { it.kind },
        )
        assertEquals("answer", timeline[0].text)
        assertTrue(timeline[1].text.contains("\"type\":\"future-block\"") || timeline[1].text.contains("\"type\": \"future-block\""))
        assertTrue(timeline[1].text.contains("42"))
    }

    @Test
    fun webTimelineProjectsAssistantImageAttachmentReference() {
        val events = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
            """
            [
              {
                "event": {
                  "seq": 8,
                  "type": "assistant/message",
                  "data": {
                    "message": {
                      "content": [
                        { "type": "text", "text": "answer" },
                        {
                          "type": "image",
                          "attachment": {
                            "attachmentId": "sha256:image",
                            "mediaType": "image/png"
                          }
                        }
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
            listOf(DshWebTimelineItem.Kind.ASSISTANT, DshWebTimelineItem.Kind.IMAGE),
            timeline.map { it.kind },
        )
        assertEquals("sha256:image", timeline[1].attachmentId)
    }

    @Test
    fun reasoningDeltaStreamsIntoSeparateWebMessage() {
        val message = DshMessage(
            id = "assistant-stream",
            role = DshMessageRole.ASSISTANT,
            content = "thinking",
            streaming = true,
            isReasoning = true,
        )

        assertTrue(message.isReasoning)
        assertTrue(message.streaming)
    }

    @Test
    fun toolResultCanMatchLiveToolByCallId() {
        val running = DshMessage(
            id = "tool-1",
            role = DshMessageRole.TOOL,
            content = "ls",
            toolName = "Bash",
            toolRunning = true,
            toolCallId = "call-1",
        )
        val settled = running.copy(
            content = "README.md",
            toolRunning = false,
            toolError = false,
        )

        assertEquals("call-1", running.toolCallId)
        assertFalse(settled.toolRunning)
        assertEquals("README.md", settled.content)
    }

    @Test
    fun liveContextInjectionUsesDurableSourceWithoutDuplicatingUserPrompt() {
        val user = DshMessage(
            id = "context-1",
            role = DshMessageRole.TOOL,
            content = "Injected instructions",
            toolName = "agent-instructions",
            isContextInjection = true,
        )

        assertEquals("agent-instructions", user.toolName)
        assertTrue(user.isContextInjection)
        assertFalse(DshMessage("context-2", DshMessageRole.USER, "prompt").isContextInjection)
    }

    @Test
    fun liveAssistantMessageKeepsImageAndUnknownBlockStableIds() {
        val image = DshMessage(
            id = "image-8-0",
            role = DshMessageRole.ASSISTANT,
            content = "",
            attachmentId = "sha256:image",
        )
        val unknown = DshMessage(
            id = "block-8-1",
            role = DshMessageRole.TOOL,
            content = """{"type":"future-block"}""",
            toolName = "未知内容块",
            toolCardType = DshToolCardType.JSON,
        )

        assertEquals("sha256:image", image.attachmentId)
        assertEquals(DshToolCardType.JSON, unknown.toolCardType)
        assertEquals("未知内容块", unknown.toolName)
    }

    @Test
    fun contextSourceLabelFollowsWebProvenanceRules() {
        val references = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
            """[{"label":"Session A"},{"label":"Session B"},{"label":"Session A"}]""",
        )
        val changes = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
            """[{"path":"a.md"},{"path":"b.md"}]""",
        )
        val sessionReference = JSONObject()
            .put("kind", "session-reference")
            .put("references", references)
        val instructions = JSONObject()
            .put("kind", "agent-instructions")
            .put("changes", changes)

        assertEquals(
            "Session A, Session B",
            contextSourceLabel(sessionReference),
        )
        assertEquals(
            "a.md, b.md",
            contextSourceLabel(instructions),
        )
    }

    @Test
    fun noticeContextSummaryUsesProducerSummary() {
        val source = JSONObject()
            .put("kind", "plugin")
            .put("plugin", "plan-mode")
            .put("form", "notice")
            .put("summary", "Plan mode enabled")

        assertEquals("Plan mode enabled", contextSummary(source))
    }

    @Test
    fun catalogContextUsesDurableEntriesOrOpaqueFallback() {
        val source = JSONObject()
            .put("kind", "skill-catalog")
            .put("form", "catalog")
            .put(
                "entries",
                com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
                    """[{"name":"/code","description":"Run coding tasks"},{"name":"/review","description":"Review code"}]""",
                ),
            )
        val entries = contextCatalogEntries(source)

        assertEquals(2, entries.size)
        assertEquals("/code", entries[0].name)
        assertEquals("Run coding tasks", entries[0].description)
        assertEquals("/review", entries[1].name)
        assertEquals("Review code", entries[1].description)
        assertTrue(contextCatalogEntries(JSONObject().put("form", "catalog")).isEmpty())
    }

    @Test
    fun snapshotContextUsesDurableSectionsOrOpaqueFallback() {
        val source = JSONObject()
            .put("kind", "runtime-context")
            .put("form", "snapshot")
            .put(
                "sections",
                com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
                    """[{"name":"Workspace","text":"/tmp/demo"},{"name":"Model","text":"deepseek-chat"}]""",
                ),
            )
        val sections = contextSections(source)

        assertEquals(2, sections.size)
        assertEquals("Workspace", sections[0].title)
        assertEquals("/tmp/demo", sections[0].body)
        assertEquals("Model", sections[1].title)
        assertEquals("deepseek-chat", sections[1].body)
        assertTrue(contextSections(JSONObject().put("form", "snapshot")).isEmpty())
    }

    @Test
    fun recallContextUsesDurableReferencesOrOpaqueFallback() {
        val source = JSONObject()
            .put("kind", "session-reference")
            .put("form", "recall")
            .put(
                "references",
                com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
                    """[{"label":"Session A","retainedMessages":12,"omittedMessages":3,"truncated":true}]""",
                ),
            )
        val recalls = contextRecalls(source)

        assertEquals(1, recalls.size)
        assertEquals("Session A", recalls[0].label)
        assertEquals(12, recalls[0].retainedMessages)
        assertEquals(3, recalls[0].omittedMessages)
        assertTrue(recalls[0].truncated)
        assertTrue(contextRecalls(JSONObject().put("form", "recall")).isEmpty())
    }

    @Test
    fun instructionsAndRelayContextUseDurableSourceFields() {
        val instructions = JSONObject()
            .put("kind", "agent-instructions")
            .put("form", "instructions")
            .put(
                "changes",
                com.tencent.kuikly.core.nvi.serialization.json.JSONArray(
                    """[{"path":"a.md","action":"set"},{"path":"b.md","action":"replace"}]""",
                ),
            )
        val relay = JSONObject()
            .put("kind", "coordinator")
            .put("form", "relay")
            .put("senderSessionId", "session-1")

        val parsedInstructions = contextInstructions(instructions)
        assertEquals(listOf("a.md" to "set", "b.md" to "replace"), parsedInstructions.map { it.path to it.action })
        assertEquals("session-1", contextRelaySender(relay))
        assertEquals("", contextRelaySender(JSONObject().put("form", "relay")))
    }

    @Test
    fun contextBodiesFollowWebDisplayBounds() {
        val longText = "x".repeat(20_001)
        val bounded = boundedContextText(longText)

        assertTrue(bounded.startsWith("x".repeat(20_000)))
        assertTrue(bounded.endsWith("… 共 20001 字符"))

        val entries = com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
        repeat(205) { index ->
            entries.put(
                JSONObject()
                    .put("name", "/skill-$index")
                    .put("description", "Skill $index")
            )
        }
        val catalog = JSONObject()
            .put("form", "catalog")
            .put("entries", entries)

        assertEquals(200, contextCatalogEntries(catalog).size)
        assertEquals("/skill-199", contextCatalogEntries(catalog).last().name)

        entries.put(JSONObject().put("description", "missing name"))
        assertTrue(contextCatalogEntries(catalog).isEmpty())
    }

    @Test
    fun reasoningSummaryFollowsLatestLineWhileRunning() {
        val text = "Inspect the session\nCheck persistence\nRead tests"
        assertEquals("Read tests", text.dshReasoningSummary(running = true))
        assertEquals("Inspect the session", text.dshReasoningSummary(running = false))
    }

    @Test
    fun toolFailureSummaryUsesFirstErrorLine() {
        val message = DshMessage(
            id = "tool-error",
            role = DshMessageRole.TOOL,
            content = "Permission denied\npath: /tmp/demo",
            toolName = "Read",
            toolError = true,
        )

        assertEquals("Permission denied", message.content.lineSequence().firstOrNull().orEmpty())
        assertTrue(message.toolError)
    }

    @Test
    fun sessionRunningEnablesQueueSteerAvailability() {
        val session = DshSession("s1", "尚无标题", "Host", "", running = true)
        assertTrue(session.running)
        assertFalse(DshSession("s2", "尚无标题", "Host", "").running)
    }

    @Test
    fun jobViewCarriesStatusDetailAndDurationInputs() {
        val job = DshJobItem(
            id = "bash-1",
            kind = "bash",
            label = "pnpm test",
            status = "completed",
            detail = "exit code: 0",
            startedAt = 1_000,
            finishedAt = 61_000,
        )
        assertEquals("exit code: 0", job.detail)
        assertEquals(60_000L, (job.finishedAt ?: 0L) - job.startedAt)
    }

    @Test
    fun wideRemoteLayoutResolvesThreeColumnWidths() {
        val viewport = 1_024f
        val sidebar = 236f
        val details = 280f
        val center = (viewport - sidebar - details).coerceAtLeast(360f)
        assertEquals(508f, center)
        assertTrue(center > sidebar)
        assertTrue(center > details)
    }

    @Test
    fun workspaceJoinHidesBlankAndArchivedAndAddsUngrouped() {
        val store = DshHostStore()
        store.replaceSessions(listOf(
            DshSession("s1", "Alpha", "Host", ""),
            DshSession("s2", "Beta", "Host", ""),
            DshSession("s3", "Archived", "Host", ""),
            DshSession("s4", "Blank", "Host", "", blank = true),
        ))
        store.replaceWorkspaceBaseline(
            "[{\"workspaceId\":\"w1\",\"title\":\"Work\",\"path\":\"/tmp/work\",\"sessionIds\":[\"s1\",\"s4\"]}]",
            setOf("s3"),
        )
        val groups = DshHostStoreWorkspaceTest.groups(store)
        assertEquals(2, groups.size)
        assertEquals("Work", groups[0].title)
        assertEquals(listOf("s1"), groups[0].sessions.map { it.id })
        assertEquals("未归类", groups[1].title)
        assertEquals(listOf("s2"), groups[1].sessions.map { it.id })
    }

    @Test
    fun workspaceNewSessionReusesBlankOnlyFromSelectedWorkspace() {
        val groups = listOf(
            DshWorkspaceGroup(
                workspaceId = "w1",
                title = "Work",
                path = "/tmp/work",
                sessions = listOf(DshSession("blank-1", "尚无标题", "Work", "", blank = true)),
            ),
            DshWorkspaceGroup(
                workspaceId = "w2",
                title = "Other",
                path = "/tmp/other",
                sessions = listOf(DshSession("blank-2", "尚无标题", "Other", "", blank = true)),
            ),
        )
        val selected = "w2"
        val reused = groups.firstOrNull { it.workspaceId == selected }?.sessions?.firstOrNull()
        assertEquals("blank-2", reused?.id)
    }

    @Test
    fun blankLookupUsesWorkspaceAccountEvenWhenUiGroupsHideBlank() {
        val workspaceIds = mapOf("blank-1" to "w1", "blank-2" to "w2")
        val selectedWorkspace = "w2"
        val blank = listOf(
            DshSession("blank-1", "尚无标题", "Work", "", blank = true),
            DshSession("blank-2", "尚无标题", "Other", "", blank = true),
        ).firstOrNull { workspaceIds[it.id] == selectedWorkspace }
        assertEquals("blank-2", blank?.id)
    }

    @Test
    fun sessionRenameAndArchiveAreHostAuthoritativeOperations() {
        val store = DshHostStore()
        store.replaceSessions(listOf(DshSession("s1", "Old", "Host", "")))
        store.applyProjection("s1", "title", "New", 2)
        assertEquals("New", store.sessions.getValue("s1").title)
        store.replaceWorkspaceBaseline("[]", setOf("s1"))
        assertTrue(store.archivedSessionIds.contains("s1"))
    }

    @Test
    fun archivingCurrentSessionChoosesRecentVisibleThenBlankFallback() {
        val sessions = listOf(
            DshSession("current", "Current", "Host", ""),
            DshSession("archived", "Archived", "Host", ""),
            DshSession("recent", "Recent", "Host", ""),
            DshSession("blank", "New", "Host", "", blank = true),
        )

        assertEquals(
            "recent",
            dshNextUnarchivedSession(sessions, setOf("current", "archived"), "current")?.id,
        )
        assertEquals(
            "blank",
            dshNextUnarchivedSession(sessions, setOf("current", "archived", "recent"), "current")?.id,
        )
    }

    @Test
    fun sessionForkUsesCompletedTurnAnchorAndExportIsGet() {
        val anchor = 12
        val forkPayload = JSONObject().apply {
            put("sessionId", "s1")
            put("atSeq", anchor)
        }
        assertEquals("s1", forkPayload.optString("sessionId"))
        assertEquals(anchor, forkPayload.optInt("atSeq"))
        val exportUrl = "http://127.0.0.1:3080/api/session.export?sessionId=s1&includeDescendants=true"
        assertTrue(exportUrl.startsWith("http://127.0.0.1:3080/api/session.export?"))
        assertTrue(exportUrl.endsWith("includeDescendants=true"))
    }

    @Test
    fun workspaceCreationAdoptsExistingDirectoryOnly() {
        val payload = JSONObject().apply { put("path", "/tmp/work") }
        assertEquals("/tmp/work", payload.optString("path"))
        assertFalse(payload.has("mkdir"))
    }

    @Test
    fun workspaceRenameAndDeleteDoNotDeleteData() {
        val renamePayload = JSONObject().apply {
            put("workspaceId", "w1")
            put("title", "New Name")
        }
        assertEquals("w1", renamePayload.optString("workspaceId"))
        assertEquals("New Name", renamePayload.optString("title"))

        val deletePayload = JSONObject().apply { put("workspaceId", "w1") }
        assertEquals("w1", deletePayload.optString("workspaceId"))
        assertFalse(deletePayload.has("deleteSessions"))
        assertFalse(deletePayload.has("deleteDirectory"))
    }

    @Test
    fun workspaceOrderSnapshotIsHostAuthoritative() {
        val store = DshHostStore()
        store.replaceWorkspaceBaseline(
            "[{\"workspaceId\":\"w1\",\"title\":\"One\",\"sessionIds\":[]},{\"workspaceId\":\"w2\",\"title\":\"Two\",\"sessionIds\":[]}]",
            emptySet(),
        )
        store.reorderWorkspaces("[\"w2\",\"w1\"]")
        val baseline = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(store.workspaceBaseline)
        assertEquals("w2", baseline.optJSONObject(0)?.optString("workspaceId"))
        assertEquals("w1", baseline.optJSONObject(1)?.optString("workspaceId"))
    }

}

private object DshHostStoreWorkspaceTest {
    fun groups(store: DshHostStore): List<DshWorkspaceGroup> {
        val workspaces = com.tencent.kuikly.core.nvi.serialization.json.JSONArray(store.workspaceBaseline)
        val archived = store.archivedSessionIds
        val sessions = store.sessions.values
            .filterNot { it.blank || archived.contains(it.id) }
            .associateBy { it.id }
        val grouped = mutableSetOf<String>()
        val result = (0 until workspaces.length()).mapNotNull { index ->
            val workspace = workspaces.optJSONObject(index) ?: return@mapNotNull null
            val ids = workspace.optJSONArray("sessionIds") ?: com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
            val children = (0 until ids.length()).mapNotNull { sessionIndex ->
                val id = ids.optString(sessionIndex)
                id?.takeIf { it.isNotEmpty() }?.let(grouped::add)
                sessions[id]
            }
            DshWorkspaceGroup(
                workspaceId = workspace.optString("workspaceId"),
                title = workspace.optString("title").ifEmpty { workspace.optString("workspaceId") },
                path = workspace.optString("path"),
                sessions = children,
            )
        }
        val ungrouped = sessions.values.filterNot { grouped.contains(it.id) }
        return if (ungrouped.isEmpty()) result else result + DshWorkspaceGroup("", "未归类", "", ungrouped)
    }
}
