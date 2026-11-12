package io.github.yukiykchen.dsh.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshInteractionControllerTest {
    @Test
    fun emptyQuestionsDoNotCrashOrSubmitAnEmptyAnswer() {
        val f = Fixture()
        f.page.question(count = 0)
        f.controller.refresh()
        val count = f.page.transport.requests.size
        f.controller.navigate(1)
        f.controller.skip()
        f.controller.submit()
        assertEquals(0, f.controller.index)
        assertTrue(f.controller.error.isNotEmpty())
        assertFalse(f.controller.busy)
        assertEquals(count, f.page.transport.requests.size)
    }

    @Test
    fun skippingTheLastQuestionSubmitsAndRetainsTheSkipMarker() {
        val f = Fixture()
        f.controller.skip()
        assertTrue(f.controller.busy)
        assertEquals("", f.controller.error)
        val request = f.page.transport.requests.last()
        val answer = request.body.optJSONObject("result")!!.optJSONObject("value")!!.optJSONObject("answer")!!
        assertEquals(0, answer.optJSONArray("answers")!!.optJSONObject(0)!!.optJSONArray("selected")!!.length())
        request.receipt(true)
        f.page.flush()
        assertNull(f.controller.pendingQuestion)
        assertEquals(listOf("s1"), f.answered)
    }

    @Test
    fun skippedEarlierQuestionStaysSkippedAfterNavigatingBackAndForward() {
        val f = Fixture()
        f.page.question(count = 2)
        f.controller.refresh()
        f.controller.skip()
        f.controller.navigate(-1)
        f.controller.navigate(1)
        f.controller.skip()
        assertTrue(f.controller.busy)
        assertEquals("", f.controller.error)
    }

    @Test
    fun duplicateQuestionItemsAreValidatedByTheirPosition() {
        val f = Fixture()
        f.page.question(count = 2, duplicateItems = true)
        f.controller.refresh()
        f.controller.toggleOption("Yes")
        f.controller.submit()
        assertEquals(1, f.controller.index)
        assertTrue(f.controller.error.isNotEmpty())
        assertFalse(f.controller.busy)
    }

    @Test
    fun aNewQuestionDoesNotInheritSelectionsOrCustomText() {
        val f = Fixture()
        f.controller.updateCustom("old answer")
        f.page.question("question-2")
        f.controller.refresh()
        assertEquals("", f.controller.custom)
        assertTrue(f.controller.selectedOptions.isEmpty())
        f.controller.submit()
        assertFalse(f.controller.busy)
        assertTrue(f.controller.error.isNotEmpty())
    }

    @Test
    fun anOldRejectedResponseCannotReleaseOrRepaintANewQuestionSubmission() {
        val f = Fixture()
        f.controller.toggleOption("Yes")
        f.controller.submit()
        val old = f.page.transport.requests.last()
        f.page.question("question-2")
        f.controller.refresh()
        f.controller.toggleOption("Yes")
        f.controller.submit()
        val current = f.page.transport.requests.last()
        old.receipt(false, "bad-response")
        f.page.flush()
        assertTrue(f.controller.busy)
        assertEquals("", f.controller.error)
        assertEquals("question-2", f.controller.pendingQuestion?.rpcId)
        current.receipt(true)
        f.page.flush()
        assertNull(f.controller.pendingQuestion)
    }

    @Test
    fun sessionSwitchRejectsCallbacksForThePreviousConversation() {
        val f = Fixture()
        f.controller.toggleOption("Yes")
        f.controller.submit()
        val old = f.page.transport.requests.last()
        f.page.sessionId = "s2"
        f.page.question("question-2")
        f.controller.refresh()
        f.controller.updateCustom("new answer")
        old.receipt(false, "bad-response")
        f.page.flush()
        assertEquals("new answer", f.controller.custom)
        assertEquals("", f.controller.error)
        assertFalse(f.controller.busy)
        assertTrue(f.answered.isEmpty())
    }

    @Test
    fun acceptedApprovalIsClearedAndCannotBeSubmittedTwice() {
        val f = Fixture()
        f.page.approval()
        f.controller.refresh()
        val count = f.page.transport.requests.size
        f.controller.answerApproval("allowed-once")
        f.controller.answerApproval("allowed-once")
        assertEquals(count + 1, f.page.transport.requests.size)
        f.page.transport.requests.last().receipt(true)
        f.page.flush()
        assertNull(f.controller.pendingApproval)
        assertFalse(f.controller.busy)
        assertTrue(f.rejected.isEmpty())
    }

    @Test
    fun rejectedQuestionKeepsItsDraftAndCanBeRetried() {
        val f = Fixture()
        f.controller.toggleOption("Yes")
        f.controller.submit()
        f.page.transport.requests.last().receipt(false, "bad-response")
        f.page.flush()
        assertEquals(listOf("Yes"), f.controller.selectedOptions.toList())
        assertTrue(f.controller.error.isNotEmpty())
        assertFalse(f.controller.busy)
        f.controller.submit()
        assertTrue(f.controller.busy)
        f.page.transport.requests.last().receipt(true)
        f.page.flush()
        assertNull(f.controller.pendingQuestion)
    }

    @Test
    fun busySubmissionBlocksDraftChangesAndRepeatedActions() {
        val f = Fixture()
        f.controller.toggleOption("Yes")
        f.controller.submit()
        val count = f.page.transport.requests.size
        f.controller.toggleOption("Yes")
        f.controller.updateCustom("late")
        f.controller.skip()
        f.controller.navigate(1)
        f.controller.submit()
        assertEquals(listOf("Yes"), f.controller.selectedOptions.toList())
        assertEquals("", f.controller.custom)
        assertEquals(count, f.page.transport.requests.size)
    }

    @Test
    fun singleSelectCanBeDeselectedAndUnknownOptionsAreIgnored() {
        val f = Fixture()
        f.controller.toggleOption("Yes")
        f.controller.toggleOption("Yes")
        f.controller.toggleOption("unknown")
        assertTrue(f.controller.selectedOptions.isEmpty())
    }

    @Test
    fun disposalRejectsAlreadyPostedAnswersAndNewCommands() {
        val f = Fixture()
        f.controller.toggleOption("Yes")
        f.controller.submit()
        f.page.transport.requests.last().receipt(true)
        f.controller.dispose()
        f.page.flush()
        f.controller.refresh()
        f.controller.submit()
        assertNull(f.controller.pendingQuestion)
        assertFalse(f.controller.busy)
        assertTrue(f.answered.isEmpty())
    }

    private class Fixture {
        val page = ControllerTestFixture()
        val answered = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val controller = DshInteractionController(page.ctx, { rejected.add(it) }, { answered.add(it) }, page::post)
        init { page.question(); controller.refresh() }
    }
}
