package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshLogCenterTest {

    private fun record(
        id: Long = 1,
        timeMs: Long = 1_800_000_000_000,
        level: DshLogLevel = DshLogLevel.INFO,
        eventType: String = DshLogEvent.CONNECTION,
        sessionId: String = "",
        ref: String = "",
        sizeBytes: Int = 0,
        summary: String = "connection.phase=READY",
    ) = DshLogRecord(id, timeMs, level, eventType, sessionId, ref, sizeBytes, summary)

    // ---------------------------------------------------------------- redaction

    @Test
    fun redactionStripsBearerTokensCookiesAndNamedSecrets() {
        assertEquals(
            "Authorization: <redacted>",
            DshLogRedaction.redact("Authorization: Bearer sk-abc123def456ghi"),
        )
        assertEquals(
            "cookie=<redacted>",
            DshLogRedaction.redact("cookie=dsh-auth-VPhEEcLK=v1.body.signature"),
        )
        assertEquals(
            "auth.mint token=<redacted> ok",
            DshLogRedaction.redact("auth.mint token=dev-token-9f2a ok"),
        )
        assertEquals(
            "{\"clientToken\":\"<redacted>\"}",
            DshLogRedaction.redact("{\"clientToken\":\"ct-7781a\"}"),
        )
        assertTrue(DshLogRedaction.redact("hostToken: abc12345").endsWith("<redacted>"))
    }

    @Test
    fun redactionCollapsesLongBase64Runs() {
        val base64 = "A".repeat(40) + "B".repeat(40)
        val redacted = DshLogRedaction.redact("image data=$base64 end")
        assertFalse(redacted.contains(base64))
        assertTrue(redacted.contains("base64"))
        // A short id is not a secret and must survive.
        assertEquals("seq=42 rpcId=dsh-g1-12", DshLogRedaction.redact("seq=42 rpcId=dsh-g1-12"))
    }

    @Test
    fun recordsAreRedactedOnTheWayIn() {
        DshLogCenter.clear()
        DshLogCenter.record(DshLogLevel.INFO, DshLogEvent.CONNECTION, "hdr Authorization: Bearer sk-secret-value")
        val stored = DshLogCenter.snapshot().single()
        assertFalse(stored.summary.contains("sk-secret-value"))
        // Detail view and export read the stored text, so both are redacted by construction.
        assertFalse(DshLogFormat.detail(stored, stored.timeMs).contains("sk-secret-value"))
        DshLogCenter.clear()
    }

    // ---------------------------------------------------------------- ring buffer

    @Test
    fun bufferIsBoundedAndDropsOldestFirst() {
        DshLogCenter.clear()
        repeat(DshLogCenter.CAPACITY + 25) { index ->
            DshLogCenter.record(DshLogLevel.DEBUG, DshLogEvent.CHUNK, "chunk #$index")
        }
        assertEquals(DshLogCenter.CAPACITY, DshLogCenter.size)
        assertEquals(25L, DshLogCenter.droppedCount)
        assertTrue(DshLogCenter.snapshot().first().summary.endsWith("#25"))
        DshLogCenter.clear()
        assertEquals(0, DshLogCenter.size)
        assertEquals(0L, DshLogCenter.droppedCount)
    }

    // ---------------------------------------------------------------- filtering

    @Test
    fun filterCombinesLevelTypeSessionWindowAndKeyword() {
        val now = 1_800_000_060_000
        val row = record(
            timeMs = now - 10_000,
            level = DshLogLevel.INFO,
            eventType = "tool/call",
            sessionId = "seed-kotlin",
            ref = "17",
            summary = "follow.event session=seed-kotlin type=tool/call seq=17",
        )
        assertTrue(DshLogCenter.matches(row, DshLogFilter(), now))
        assertTrue(DshLogCenter.matches(row, DshLogFilter(minLevel = DshLogLevel.INFO), now))
        assertFalse(DshLogCenter.matches(row, DshLogFilter(minLevel = DshLogLevel.WARN), now))
        assertTrue(DshLogCenter.matches(row, DshLogFilter(eventType = "tool/call"), now))
        assertFalse(DshLogCenter.matches(row, DshLogFilter(eventType = "turn/end"), now))
        assertTrue(DshLogCenter.matches(row, DshLogFilter(sessionId = "seed-kotlin"), now))
        assertFalse(DshLogCenter.matches(row, DshLogFilter(sessionId = "other"), now))
        assertTrue(DshLogCenter.matches(row, DshLogFilter(windowMs = 30_000), now))
        assertFalse(DshLogCenter.matches(row, DshLogFilter(windowMs = 5_000), now))
        // Keyword covers the summary, the type, the session and the ref.
        assertTrue(DshLogCenter.matches(row, DshLogFilter(query = "TOOL/CALL"), now))
        assertTrue(DshLogCenter.matches(row, DshLogFilter(query = "17"), now))
        assertFalse(DshLogCenter.matches(row, DshLogFilter(query = "attachment"), now))
    }

    @Test
    fun typeChipsAreAlphabeticalWithAnAllChipFirst() {
        val chips = DshLogCenter.typeChips(
            listOf(
                record(eventType = "turn/end"),
                record(eventType = DshLogEvent.CHUNK),
                record(eventType = DshLogEvent.CHUNK),
                record(eventType = "tool/call"),
            ),
        )
        assertEquals("", chips.first().eventType)
        assertEquals(4, chips.first().count)
        assertEquals(
            listOf(DshLogEvent.CHUNK, "tool/call", "turn/end"),
            chips.drop(1).map { it.eventType },
        )
        assertEquals(2, chips.first { it.eventType == DshLogEvent.CHUNK }.count)
    }

    // ---------------------------------------------------------------- formatting

    @Test
    fun timestampsRenderAsUtcClockAndIsoDate() {
        assertEquals("00:00:00.000", DshLogFormat.clock(0))
        assertEquals("1970-01-01T00:00:00.000Z", DshLogFormat.iso(0))
        // 2026-09-13T01:04:11.912Z
        assertEquals("2026-09-13T01:04:11.912Z", DshLogFormat.iso(1_789_261_451_912))
        assertEquals("01:04:11.912", DshLogFormat.clock(1_789_261_451_912))
    }

    @Test
    fun agesReadAsRelativeTime() {
        val now = 1_800_000_000_000
        assertEquals("now", DshLogFormat.age(now - 400, now))
        assertEquals("12s ago", DshLogFormat.age(now - 12_000, now))
        assertEquals("2m 05s ago", DshLogFormat.age(now - 125_000, now))
        assertEquals("1h 02m ago", DshLogFormat.age(now - 3_720_000, now))
    }

    @Test
    fun exportCarriesTheEnvironmentAndOneLinePerRecordOldestFirst() {
        DshLogCenter.clear()
        val now = 1_800_000_000_000
        val rows = listOf(
            record(id = 2, timeMs = now - 1_000, eventType = "turn/end", sessionId = "s1", ref = "9", summary = "second"),
            record(id = 1, timeMs = now - 2_000, summary = "first"),
        )
        val text = DshLogFormat.export(rows, listOf("Connection mode" to "Direct (dev)"), now)
        assertTrue(text.startsWith("# DSH App diagnostic log"))
        assertTrue(text.contains("- Connection mode: Direct (dev)"))
        assertTrue(text.contains("ring buffer ${DshLogCenter.CAPACITY}"))
        val body = text.substringAfter("```text").substringBefore("```")
        // The list is newest first on screen; the export reads chronologically.
        assertTrue(body.indexOf("first") < body.indexOf("second"))
        assertTrue(body.contains("session=s1  ref=9"))
    }
}
