package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshMessageExportTest {

    private fun assistant(id: String, content: String) = DshMessage(
        id = id,
        role = DshMessageRole.ASSISTANT,
        content = content,
    )

    @Test
    fun codeBlocksReturnsFencedContentWithoutTheFence() {
        val blocks = DshMessageExport.codeBlocks(
            """
            Intro text.

            ```kotlin
            fun main() {
                println("hi")
            }
            ```

            Between.

            ~~~
            plain
            ~~~
            """.trimIndent(),
        )
        assertEquals(2, blocks.size)
        assertEquals("kotlin", blocks[0].language)
        assertEquals("fun main() {\n    println(\"hi\")\n}", blocks[0].code)
        assertEquals("", blocks[1].language)
        assertEquals("plain", blocks[1].code)
    }

    @Test
    fun codeBlocksKeepsAnUnclosedFenceSoStreamingRepliesCanBeCopied() {
        val blocks = DshMessageExport.codeBlocks("```sh\nnpm test\n")
        assertEquals(1, blocks.size)
        assertEquals("sh", blocks[0].language)
        assertEquals("npm test", blocks[0].code)
    }

    @Test
    fun codeBlocksIgnoresBackticksInsideAFence() {
        val blocks = DshMessageExport.codeBlocks("````\n```\nnested\n```\n````")
        assertEquals(1, blocks.size)
        assertEquals("```\nnested\n```", blocks[0].code)
    }

    @Test
    fun codeBlocksReturnsNothingForInlineCode() {
        assertEquals(emptyList(), DshMessageExport.codeBlocks("use `coerceIn` instead"))
    }

    @Test
    fun messageCopiesTheBodyWithoutUiCopy() {
        assertEquals("Hello there", DshMessageExport.message(assistant("m1", "Hello there\n")))
    }

    @Test
    fun messageRendersAToolCardWithNameSummaryAndStatus() {
        val tool = DshRemoteToolCallModel(
            callId = "call_1",
            toolName = "grep",
            kind = DshRemoteToolKind.SEARCH,
            title = "Grep",
            summary = "TODO",
            input = "",
            body = "",
            output = "no matches in src/missing",
            error = "no matches in src/missing",
            running = false,
        )
        val text = DshMessageExport.message(
            DshMessage(
                id = "m2",
                role = DshMessageRole.TOOL,
                content = "",
                toolError = true,
                remoteTool = tool,
            ),
        )
        assertEquals(
            "## Tool · Grep — TODO (failed)\n\n```\nno matches in src/missing\n```",
            text,
        )
    }

    @Test
    fun messageRendersARunningToolCard() {
        val tool = DshRemoteToolCallModel(
            callId = "call_2",
            toolName = "bash",
            kind = DshRemoteToolKind.BASH,
            title = "Bash",
            summary = "List the source directory",
            input = "ls -la src",
            body = "ls -la src",
            running = true,
        )
        val text = DshMessageExport.message(
            DshMessage(id = "m3", role = DshMessageRole.TOOL, content = "", toolRunning = true, remoteTool = tool),
        )
        assertTrue(text.startsWith("## Tool · Bash — List the source directory (running)"), text)
    }

    @Test
    fun messageRendersAttachmentReferences() {
        val text = DshMessageExport.message(
            DshMessage(
                id = "m4",
                role = DshMessageRole.USER,
                content = "look at this",
                attachments = listOf(
                    DshImageAttachmentRef("att_1", "image/png", 2048, 64, 64, "sample.png"),
                ),
            ),
        )
        assertEquals("look at this\n\n[image: sample.png, 64×64, 2 KB, attachment att_1]", text)
    }

    @Test
    fun messageMarksAnAttachmentTheHostHasNotStoredYet() {
        val text = DshMessageExport.message(
            DshMessage(
                id = "m5",
                role = DshMessageRole.USER,
                content = "",
                attachments = listOf(
                    DshImageAttachmentRef("", "image/jpeg", 4096, 800, 600, "photo.jpg", localId = "staged-1"),
                ),
            ),
        )
        assertEquals("[image: photo.jpg, 800×600, 4 KB, not yet stored by the Host]", text)
    }

    @Test
    fun conversationKeepsTimelineOrderAndSkipsHiddenRows() {
        val markdown = DshMessageExport.conversation(
            "tool",
            listOf(
                DshMessage(id = "1", role = DshMessageRole.USER, content = "tool"),
                assistant("2", "Let me inspect the project first."),
                DshMessage(
                    id = "3",
                    role = DshMessageRole.TOOL,
                    content = "total 24",
                    remoteTool = DshRemoteToolCallModel(
                        callId = "c", toolName = "bash", kind = DshRemoteToolKind.BASH,
                        title = "Bash", summary = "List the source directory",
                        input = "", body = "", output = "total 24", running = false,
                    ),
                ),
                DshMessage(id = "4", role = DshMessageRole.ASSISTANT, content = "hidden", hidden = true),
                DshMessage(id = "5", role = DshMessageRole.ERROR, content = "Provider returned 429"),
            ),
        )
        assertEquals(
            """
            # tool

            ## You

            tool

            ## DeepSeek

            Let me inspect the project first.

            ## Tool · Bash — List the source directory (completed)

            ```
            total 24
            ```

            ## Error

            Provider returned 429
            """.trimIndent() + "\n",
            markdown,
        )
    }

    @Test
    fun emptyRowsAreDroppedFromTheTranscript() {
        assertEquals(null, DshMessageExport.transcriptEntry(assistant("m5", "   ")))
        assertEquals(null, DshMessageExport.transcriptEntry(assistant("m6", "x").copy(hidden = true)))
    }

    // --- HTML export ----------------------------------------------------------

    private fun htmlOf(vararg rows: DshMessage) = DshMessageExport.html("Release notes", rows.toList())

    @Test
    fun htmlIsASelfContainedDocument() {
        val html = htmlOf(assistant("m1", "Hello"))
        assertTrue(html.startsWith("<!DOCTYPE html>"), "needs a doctype")
        assertTrue(html.contains("<title>Release notes</title>"))
        assertTrue(html.contains("<style>"), "styling must be inlined, not linked")
        assertTrue(html.trimEnd().endsWith("</html>"))
    }

    @Test
    fun htmlEscapesMarkupInTheBody() {
        val html = htmlOf(assistant("m1", "Use <script>alert(\"x\")</script> & co"))
        assertTrue(html.contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; co"))
        assertFalse(html.contains("<script>"), "message text must never become live markup")
    }

    @Test
    fun htmlKeepsFencedCodeInAPreBlock() {
        val html = htmlOf(assistant("m1", "Intro\n\n```kotlin\nval a = 1 < 2\n```\n\nOutro"))
        assertTrue(html.contains("<pre><code>val a = 1 &lt; 2</code></pre>"))
        assertTrue(html.contains("<p>Intro</p>"))
        assertTrue(html.contains("<p>Outro</p>"))
    }

    @Test
    fun htmlLabelsEachRoleAndKeepsOrder() {
        val html = htmlOf(
            DshMessage(id = "1", role = DshMessageRole.USER, content = "ask"),
            assistant("2", "answer"),
            DshMessage(id = "3", role = DshMessageRole.ERROR, content = "boom"),
        )
        val user = html.indexOf("class=\"turn user\"")
        val assistant = html.indexOf("class=\"turn assistant\"")
        val error = html.indexOf("class=\"turn error\"")
        assertTrue(user in 0 until assistant && assistant < error, "rows must keep timeline order")
        assertTrue(html.contains("<h2>You</h2>") && html.contains("<h2>Error</h2>"))
    }

    @Test
    fun htmlDropsTheSameRowsAsTheMarkdownExport() {
        val html = htmlOf(assistant("m1", "kept"), assistant("m2", "hidden").copy(hidden = true))
        assertTrue(html.contains("kept"))
        assertFalse(html.contains("hidden"))
    }
}
