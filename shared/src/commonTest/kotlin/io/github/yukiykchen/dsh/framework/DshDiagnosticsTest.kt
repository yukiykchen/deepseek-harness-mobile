package io.github.yukiykchen.dsh.framework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshDiagnosticsTest {
    @Test
    fun productionPreviewsNeverContainBusinessContent() {
        DshDiagnostics.configure(false)
        val secret = "sk-sensitive-key-and-user-prompt"
        val preview = DshDiagnostics.preview(secret)
        assertFalse(preview.contains("sk-sensitive"))
        assertEquals("chars=${secret.length}", preview)
        assertEquals("error=${secret.length}", DshDiagnostics.redactedError(secret))
    }

    @Test
    fun verboseDiagnosticsCanIncludeBoundedPreview() {
        DshDiagnostics.configure(true)
        try {
            val preview = DshDiagnostics.preview("hello world", 5)
            assertTrue(preview.startsWith("hello"))
            assertTrue(preview.contains("(+6)"))
        } finally {
            DshDiagnostics.configure(false)
        }
    }
}
