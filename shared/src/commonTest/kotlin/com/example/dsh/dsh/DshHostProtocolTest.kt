package com.example.dsh.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wire-shape guards for the DSH 0.1.5 protocol (Typert args, follow records, projections). */
class DshHostProtocolTest {
    @Test
    fun typertPayloadWrapsArgsAndRequest() {
        val payload = DshHostProtocol.request { put("sessionId", "s1") }
        val args = payload.optJSONObject("args") ?: error("args missing")
        assertEquals(1, payload.length())
        assertEquals("s1", args.optJSONObject("request")?.optString("sessionId"))
        val listArgs = DshHostProtocol.args { put("_request", JSONObject()) }.optJSONObject("args")
        assertNotNull(listArgs?.optJSONObject("_request"))
    }

    @Test
    fun endpointsUseNamespaceSlashMethod() {
        listOf(
            DshHostProtocol.SESSION_PROMPT, DshHostProtocol.SESSION_FOLLOW, DshHostProtocol.WORKSPACE_ARCHIVE_SESSION,
            DshHostProtocol.PLUGIN_INVENTORY_LIST, DshHostProtocol.GOALS_PAUSE, DshHostProtocol.EVENTS_RESULT,
        ).forEach { endpoint ->
            val segments = endpoint.split("/")
            assertEquals(2, segments.size, endpoint)
            assertTrue(segments.all { it.isNotEmpty() }, endpoint)
        }
        assertEquals("\$events", DshHostProtocol.EVENTS_STREAM)
        assertEquals("/api/remote.mux", DshHostProtocol.REMOTE_MUX_PATH)
    }

    @Test
    fun webSocketUrlRewritesSchemes() {
        assertEquals("ws://127.0.0.1:3080/api/remote.mux", DshHostConnection("http://127.0.0.1:3080/").webSocketUrl(DshHostProtocol.REMOTE_MUX_PATH))
        assertEquals("wss://host/api/remote.mux", DshHostConnection("https://host").webSocketUrl(DshHostProtocol.REMOTE_MUX_PATH))
    }

    @Test
    fun launchTokenAcceptsBareTokenOrPrintedUrl() {
        assertEquals("abc-123", dshExtractLaunchToken("abc-123"))
        assertEquals("abc-123", dshExtractLaunchToken("  http://127.0.0.1:3080/?token=abc-123  "))
        assertEquals("abc", dshExtractLaunchToken("http://h/?token=abc&x=1#frag"))
        assertEquals("", dshExtractLaunchToken("   "))
    }

    @Test
    fun directProfileIdIsFilesystemSafeAndStable() {
        assertEquals("192_168_1_10_3080", dshDirectProfileId("http://192.168.1.10:3080/"))
        assertEquals(dshDirectProfileId("http://a:1"), dshDirectProfileId("http://a:1/"))
        assertEquals("default", dshDirectProfileId(""))
    }

    @Test
    fun followRecordsParseLikeLegacyEventArrays() {
        val records = JSONArray(
            """
            [
              {"type":"event","event":{"type":"turn/start","seq":0,"time":1,"data":{"turn":1}}},
              {"type":"event","event":{"type":"user/message","seq":1,"time":1,"data":{"id":"m1","role":"user","content":[{"type":"text","text":"hi"},{"type":"image","attachment":{"attachmentId":"att-1","mediaType":"image/png","bytes":10,"width":2,"height":3,"name":"a.png"}}],"source":{"kind":"user","rpcId":"mobile-1"}}}},
              {"type":"event","event":{"type":"assistant/message","seq":2,"time":2,"data":{"turn":1,"step":1,"message":{"id":"a1","role":"assistant","content":[{"type":"text","text":"hello"}],"source":{"kind":"model"}},"stream":[]}}},
              {"type":"event","event":{"type":"turn/end","seq":3,"time":3,"data":{"turn":1,"reason":{"kind":"error","error":{"message":"boom","code":"X"}}}}}
            ]
            """.trimIndent(),
        )
        val timeline = DshWebTimelineParser.parseWebTimeline(records)
        assertEquals(listOf(DshWebTimelineItem.Kind.USER, DshWebTimelineItem.Kind.ASSISTANT, DshWebTimelineItem.Kind.ERROR), timeline.map { it.kind })
        assertEquals("hi", timeline[0].text)
        assertEquals("att-1", timeline[0].attachments.single().attachmentId)
        assertEquals("a.png", timeline[0].attachments.single().name)
        assertEquals("boom", timeline[2].text)
    }

    @Test
    fun imageOnlyUserMessageStillProducesARow() {
        val records = JSONArray(
            """[{"event":{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"image","attachment":{"attachmentId":"att-2","mediaType":"image/jpeg","bytes":1,"width":1,"height":1}}],"source":{"kind":"user"}}}}]""",
        )
        val item = DshWebTimelineParser.parseWebTimeline(records).single()
        assertEquals(DshWebTimelineItem.Kind.USER, item.kind)
        assertEquals("", item.text)
        assertEquals("image/jpeg", item.attachments.single().mediaType)
    }

    @Test
    fun imageLimitsProjectionParses() {
        val limits = parseImageLimits(
            """{"maxImageBytes":20971520,"maxImagesPerMessage":4,"maxMessageImageBytes":41943040,"maxImagePixels":33554432,"maxImageDimension":8192,"mediaTypes":["image/png","image/jpeg"]}""",
        )
        assertNotNull(limits)
        assertEquals(4, limits.maxImagesPerMessage)
        assertEquals(8192, limits.maxImageDimension)
        assertEquals(listOf("image/png", "image/jpeg"), limits.mediaTypes)
        assertNull(parseImageLimits("null"))
        assertNull(parseImageLimits("{\"unrelated\":1}"))
    }

    @Test
    fun pluginInventoryParsesPhasesEnablementAndPresets() {
        val inventory = parsePluginInventory(JSONObject(
            """
            {"entries":[
               {"entryId":"a","moduleName":"@x/a","enabled":true,"fiberPhase":"active"},
               {"entryId":"b","moduleName":"@x/b","enabled":false,"fiberPhase":null},
               {"entryId":"c","moduleName":"@x/c","enabled":true,"fiberPhase":"failed"}
             ],
             "agentPresets":[{"id":"default","trust":"system","isDefault":true,"rows":[{"entryId":null,"moduleName":"@x/w","enabled":"conditional","condition":"!!js x","fiberPhase":null}]},
                             {"id":"broken","trust":"user","isDefault":false,"broken":"bad yaml","rows":[]}]}
            """.trimIndent(),
        ))
        assertEquals(3, inventory.entries.size)
        assertEquals("active", inventory.entries[0].fiberPhase)
        assertNull(inventory.entries[1].fiberPhase)
        assertFalse(inventory.entries[1].enabled)
        assertEquals("failed", inventory.entries[2].fiberPhase)
        val presets = inventory.agentPresets ?: error("presets")
        assertEquals("conditional", presets[0].rows.single().enabled)
        assertNull(presets[0].rows.single().entryId)
        assertEquals("bad yaml", presets[1].broken)
        assertNull(parsePluginInventory(JSONObject("{\"entries\":[]}")).agentPresets)
    }

    @Test
    fun promptErrorsMapAttachmentReasonsToReadableCopy() {
        val error = DshRpcError("session/attachment-invalid", "Image exceeds limit", "{\"reason\":\"IMAGE_TOO_LARGE\"}")
        assertEquals("An image exceeds the Host's per-image size limit.", dshPromptErrorMessage(error))
        val unknownReason = DshRpcError("session/attachment-invalid", "nope", "{\"reason\":\"SOMETHING_ELSE\"}")
        assertEquals("nope (SOMETHING_ELSE)", dshPromptErrorMessage(unknownReason))
        assertEquals("busy", dshPromptErrorMessage(DshRpcError("session/agent-busy", "busy")))
    }

    @Test
    fun workspaceIncrementsMutateTheBaseline() {
        val store = DshHostStore()
        store.replaceWorkspaceBaseline("""[{"workspaceId":"w1","title":"One","path":"/one","sessionIds":["s1"]}]""", emptySet())
        store.upsertWorkspace(JSONObject("""{"workspaceId":"w2","title":"Two","path":"/two","sessionIds":["s2"]}"""))
        store.upsertWorkspace(JSONObject("""{"workspaceId":"w1","title":"One!","path":"/one","sessionIds":["s1","s3"]}"""))
        assertEquals(listOf("s1", "s3"), store.workspaceSessionIds("w1"))
        assertEquals("w2", store.workspaceIdForSession("s2"))
        store.replaceSessions(listOf(
            DshSession("s1", "A", "Host", ""),
            DshSession("s2", "B", "Host", ""),
            DshSession("s3", "C", "Host", ""),
        ))
        store.replaceArchivedSessionIds(setOf("s3"))
        val visible = store.workspaceGroups(includeArchived = false)
        assertEquals(listOf("One!", "Two"), visible.map { it.title })
        assertEquals(listOf("s1"), visible[0].sessions.map { it.id })
        assertEquals(listOf("s1", "s3"), store.workspaceGroups(includeArchived = true)[0].sessions.map { it.id })
        store.removeWorkspace("w2")
        assertNull(store.workspaceIdForSession("s2"))
        assertEquals("未归类", store.workspaceGroups(includeArchived = false).last().title)
    }

    @Test
    fun removeSessionClearsEveryPartition() {
        val store = DshHostStore()
        store.replaceSessions(listOf(DshSession("s1", "A", "Host", "")))
        store.applySessionEvent("s1", 1, "turn/start", "{}")
        store.replaceQueue("s1", "[]")
        store.applyProjection("s1", "title", "A", 1)
        store.removeSession("s1")
        assertFalse(store.sessions.containsKey("s1"))
        assertFalse(store.sessionEvents.containsKey("s1"))
        assertFalse(store.queueSnapshots.containsKey("s1"))
        assertFalse(store.projections.containsKey("s1"))
    }

    @Test
    fun randomIdsAreUniqueAndPrefixed() {
        val ids = (1..50).map { dshRandomId("mobile") }.toSet()
        assertEquals(50, ids.size)
        assertTrue(ids.all { it.startsWith("mobile-") && it.length > 10 })
    }
}
