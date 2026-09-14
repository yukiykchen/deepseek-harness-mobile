package com.example.dsh.dsh

import com.tencent.kuikly.core.datetime.DateTime

internal enum class DshLogLevel(val label: String, val short: String) {
    DEBUG("Debug", "DBG"),
    INFO("Info", "INF"),
    WARN("Warn", "WRN"),
    ERROR("Error", "ERR"),
}

/**
 * Event families the app writes. Durable Session events keep their own Host names
 * (`tool/call`, `tool/result`, `turn/end`, `user/message`, …) so the log filter reads
 * the same vocabulary as the protocol.
 */
internal object DshLogEvent {
    const val CONNECTION = "connection"
    const val RECONNECT = "reconnect"
    const val RPC = "rpc"
    const val MUX = "mux"
    const val CHUNK = "assistant/chunk"
    const val PROMPT = "session/prompt"
    const val FOLLOW = "session/follow"
    const val HOST_EVENT = "host/event"
}

internal data class DshLogRecord(
    val id: Long,
    val timeMs: Long,
    val level: DshLogLevel,
    val eventType: String,
    val sessionId: String,
    /** `seq`, `rpcId` or stream id — whichever identifies this frame. */
    val ref: String,
    /** Payload size in characters; 0 when the record has no payload. */
    val sizeBytes: Int,
    /** Already redacted when the record was written. */
    val summary: String,
)

/** One event-type filter chip; an empty [eventType] is the unfiltered chip. */
internal data class DshLogTypeChip(
    val eventType: String,
    val label: String,
    val count: Int,
)

internal data class DshLogFilter(
    val minLevel: DshLogLevel = DshLogLevel.DEBUG,
    /** Empty means every event type. */
    val eventType: String = "",
    /** Empty means every conversation. */
    val sessionId: String = "",
    /** 0 means no lower bound. */
    val windowMs: Long = 0L,
    val query: String = "",
)

/**
 * Process-wide diagnostic buffer. Version 1 of the Host protocol has no log RPC, so
 * every record is written where the app observes a frame or a local state change.
 *
 * Bounded by [CAPACITY] and in memory only: the records describe one run of the app,
 * and a disk mirror would buy nothing the ring buffer does not already bound.
 * Everything here runs on Kuikly's single context-queue thread, like the rest of the
 * shared layer, so the buffer needs no locking.
 */
internal object DshLogCenter {
    const val CAPACITY = 1000

    private val buffer = ArrayList<DshLogRecord>()
    private var sequence = 0L
    private var dropped = 0L

    val size: Int get() = buffer.size
    val droppedCount: Long get() = dropped

    fun record(
        level: DshLogLevel,
        eventType: String,
        summary: String,
        sessionId: String = "",
        ref: String = "",
        sizeBytes: Int = 0,
    ) {
        buffer.add(
            DshLogRecord(
                id = ++sequence,
                timeMs = DateTime.currentTimestamp(),
                level = level,
                eventType = eventType,
                sessionId = sessionId,
                ref = ref,
                sizeBytes = sizeBytes,
                summary = DshLogRedaction.redact(summary),
            ),
        )
        while (buffer.size > CAPACITY) {
            buffer.removeAt(0)
            dropped += 1
        }
    }

    /** Clears the local buffer only; Host history and conversation messages are untouched. */
    fun clear() {
        buffer.clear()
        dropped = 0
    }

    fun snapshot(): List<DshLogRecord> = buffer.toList()

    /**
     * Chips for every event type in the buffer, named alphabetically so they keep their
     * places as records arrive.
     */
    fun typeChips(records: List<DshLogRecord>): List<DshLogTypeChip> =
        listOf(DshLogTypeChip("", "All", records.size)) +
            records.groupingBy { it.eventType }.eachCount().entries
                .sortedBy { it.key }
                .map { DshLogTypeChip(it.key, it.key, it.value) }

    /** Newest first, which is the order the log page reads in. */
    fun filtered(filter: DshLogFilter, nowMs: Long): List<DshLogRecord> =
        buffer.filter { matches(it, filter, nowMs) }.asReversed()

    fun matches(record: DshLogRecord, filter: DshLogFilter, nowMs: Long): Boolean {
        if (record.level.ordinal < filter.minLevel.ordinal) return false
        if (filter.eventType.isNotEmpty() && record.eventType != filter.eventType) return false
        if (filter.sessionId.isNotEmpty() && record.sessionId != filter.sessionId) return false
        if (filter.windowMs > 0 && record.timeMs < nowMs - filter.windowMs) return false
        val needle = filter.query.trim().lowercase()
        if (needle.isEmpty()) return true
        return record.summary.lowercase().contains(needle) ||
            record.eventType.lowercase().contains(needle) ||
            record.sessionId.lowercase().contains(needle) ||
            record.ref.lowercase().contains(needle)
    }
}

/**
 * Strips credentials before a record is stored, so the buffer, the detail view, the
 * clipboard and the export are redacted by construction rather than on the way out.
 */
internal object DshLogRedaction {
    private const val MASK = "<redacted>"

    private val rules = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{6,}") to "$1$MASK",
        Regex("(?i)(dsh-auth-[A-Za-z0-9_-]+\\s*=\\s*)[^;,\\s'\"]+") to "$1$MASK",
        Regex(
            "(?i)\\b(authorization|cookie|set-cookie|token|launchtoken|clienttoken|hosttoken|" +
                "localtoken|authtoken|apikey|api_key|secret|password|ticket)" +
                "([\"']?\\s*[:=]\\s*[\"']?)([^\\s,;'\"})]+)",
        ) to "$1$2$MASK",
        // Any long unbroken base64-ish run: attachment bytes, cookies, minted tickets.
        Regex("[A-Za-z0-9+/=]{64,}") to "<base64 $MASK>",
    )

    /** Two rules can both fire on one header ("Authorization: Bearer …"); collapse the masks. */
    private val repeatedMask = Regex("$MASK(\\s+$MASK)+")

    fun redact(text: String): String {
        var result = text
        rules.forEach { (pattern, replacement) -> result = pattern.replace(result, replacement) }
        return repeatedMask.replace(result, MASK)
    }
}

/** Rendering for the log page, the clipboard and the export bundle. */
internal object DshLogFormat {
    /** `HH:MM:SS.mmm`, UTC — the App tells the Host `clientTimeZone: "UTC"` too. */
    fun clock(timeMs: Long): String {
        val ms = ((timeMs % 1000) + 1000) % 1000
        val secondOfDay = floorDiv(timeMs, 1000).let { ((it % 86_400) + 86_400) % 86_400 }
        val h = secondOfDay / 3600
        val m = (secondOfDay % 3600) / 60
        val s = secondOfDay % 60
        return "${pad2(h)}:${pad2(m)}:${pad2(s)}.${pad3(ms)}"
    }

    fun iso(timeMs: Long): String {
        val days = floorDiv(timeMs, 86_400_000)
        val (year, month, day) = civilFromDays(days)
        return "$year-${pad2(month.toLong())}-${pad2(day.toLong())}T${clock(timeMs)}Z"
    }

    /** Relative age, which is what correlates a record with what just happened on screen. */
    fun age(timeMs: Long, nowMs: Long): String {
        val delta = (nowMs - timeMs).coerceAtLeast(0L)
        return when {
            delta < 1_000 -> "now"
            delta < 60_000 -> "${delta / 1_000}s ago"
            delta < 3_600_000 -> "${delta / 60_000}m ${pad2((delta % 60_000) / 1_000)}s ago"
            else -> "${delta / 3_600_000}h ${pad2((delta % 3_600_000) / 60_000)}m ago"
        }
    }

    fun line(record: DshLogRecord): String = buildString {
        append(iso(record.timeMs))
        append("  ")
        append(record.level.short)
        append("  ")
        append(record.eventType)
        if (record.sessionId.isNotEmpty()) append("  session=${record.sessionId}")
        if (record.ref.isNotEmpty()) append("  ref=${record.ref}")
        if (record.sizeBytes > 0) append("  size=${dshFormatBytes(record.sizeBytes.toLong())}")
        append("  ")
        append(record.summary)
    }

    fun detail(record: DshLogRecord, nowMs: Long): String = buildString {
        appendLine("Time: ${iso(record.timeMs)} (${age(record.timeMs, nowMs)})")
        appendLine("Level: ${record.level.label}")
        appendLine("Event: ${record.eventType}")
        if (record.sessionId.isNotEmpty()) appendLine("Session: ${record.sessionId}")
        if (record.ref.isNotEmpty()) appendLine("Ref: ${record.ref}")
        if (record.sizeBytes > 0) appendLine("Payload: ${dshFormatBytes(record.sizeBytes.toLong())}")
        append(record.summary)
    }

    /**
     * The issue-feedback bundle: the environment facts a report needs, then the records
     * currently on screen. Already redacted, because the records were redacted on write.
     */
    fun export(records: List<DshLogRecord>, environment: List<Pair<String, String>>, nowMs: Long): String =
        buildString {
            appendLine("# DSH App diagnostic log")
            appendLine()
            appendLine("- Exported: ${iso(nowMs)}")
            environment.forEach { (label, value) -> appendLine("- $label: $value") }
            appendLine("- Entries: ${records.size} of ${DshLogCenter.size} kept (ring buffer ${DshLogCenter.CAPACITY})")
            if (DshLogCenter.droppedCount > 0) appendLine("- Dropped by the ring buffer: ${DshLogCenter.droppedCount}")
            appendLine()
            appendLine("```text")
            records.asReversed().forEach { appendLine(line(it)) }
            appendLine("```")
        }

    private fun pad2(value: Long): String = if (value < 10) "0$value" else value.toString()

    private fun pad3(value: Long): String = when {
        value < 10 -> "00$value"
        value < 100 -> "0$value"
        else -> value.toString()
    }

    private fun floorDiv(value: Long, divisor: Long): Long {
        val q = value / divisor
        return if (value % divisor != 0L && (value xor divisor) < 0) q - 1 else q
    }

    /** Days since the epoch → civil date (Hinnant's algorithm). */
    private fun civilFromDays(days: Long): Triple<Long, Long, Long> {
        val z = days + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val year = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        return Triple(if (month <= 2) year + 1 else year, month, day)
    }
}
