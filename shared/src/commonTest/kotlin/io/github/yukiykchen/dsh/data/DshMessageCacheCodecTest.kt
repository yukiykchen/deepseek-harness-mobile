package io.github.yukiykchen.dsh.data

import io.github.yukiykchen.dsh.model.DshContextCatalogEntry
import io.github.yukiykchen.dsh.model.DshContextInstruction
import io.github.yukiykchen.dsh.model.DshContextRecall
import io.github.yukiykchen.dsh.model.DshContextSection
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshMessageRole
import io.github.yukiykchen.dsh.model.DshToolCardType
import io.github.yukiykchen.dsh.model.DshRemoteToolCallModel
import io.github.yukiykchen.dsh.model.DshRemoteToolKind
import kotlin.test.Test
import kotlin.test.assertEquals

class DshMessageCacheCodecTest {
    @Test
    fun messageCachePreservesRenderingState() {
        val original = DshMessage(
            id = "m1",
            role = DshMessageRole.TOOL,
            content = "tool output",
            streaming = true,
            toolName = "terminal",
            hidden = true,
            toolCardType = DshToolCardType.TERMINAL,
            toolRunning = true,
            toolError = true,
            isContextInjection = true,
            contextForm = "form",
            contextBody = "body",
            contextCatalog = listOf(DshContextCatalogEntry("catalog", "description")),
            contextSections = listOf(DshContextSection("title", "section")),
            contextRecalls = listOf(DshContextRecall("recall", 2, 3, true)),
            contextInstructions = listOf(DshContextInstruction("path", "action")),
            contextRelaySender = "relay",
            isReasoning = true,
            attachmentId = "attachment-1",
            toolCallId = "call-1",
            remoteTool = DshRemoteToolCallModel(
                callId = "call-1",
                toolName = "terminal",
                kind = DshRemoteToolKind.BASH,
                title = "Terminal",
                summary = "ls",
                input = "{}",
                body = "body",
                output = "files",
                error = "failed",
                running = false,
                cardType = DshToolCardType.TERMINAL,
                filePath = "/tmp/file",
                todoDone = 1,
                todoTotal = 2,
                todoActive = "working",
                todoActiveExtra = 3,
                questionAnswered = 4,
                questionTotal = 5,
            ),
        )
        assertEquals(original, dshMessageFromCache(original.toCacheJson()))
    }
}
