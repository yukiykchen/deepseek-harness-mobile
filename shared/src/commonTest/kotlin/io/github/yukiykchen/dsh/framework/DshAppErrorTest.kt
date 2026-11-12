package io.github.yukiykchen.dsh.framework

import kotlin.test.Test
import kotlin.test.assertEquals

class DshAppErrorTest {
    @Test
    fun cancellationIsIgnorableAndTransportFailuresAreRetryable() {
        assertEquals(DshAppErrorKind.IGNORABLE, DshAppError.classify("cancelled").kind)
        assertEquals(DshAppErrorKind.IGNORABLE, DshAppError.classify("x", generationCancelled = true).kind)
        assertEquals(DshAppErrorKind.RETRYABLE, DshAppError.classify("transport-503").kind)
    }

    @Test
    fun hostRpcFailuresRequireUserVisibleFeedback() {
        val error = DshAppError.classify("internal")
        assertEquals(DshAppErrorKind.USER_ACTIONABLE, error.kind)
        assertEquals("internal", error.code)
    }
}
