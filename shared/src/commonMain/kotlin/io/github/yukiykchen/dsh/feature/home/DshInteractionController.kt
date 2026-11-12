package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.framework.DshDiagnostics
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.model.DshPendingApproval
import io.github.yukiykchen.dsh.model.DshPendingQuestion
import io.github.yukiykchen.dsh.model.DshQuestionDraft
import io.github.yukiykchen.dsh.data.buildQuestionAnswer

/**
 * Approval requests and multi-step questions the Agent is waiting on.
 *
 * @param onApprovalRejected shows why an approval answer was not accepted.
 * @param onQuestionAnswered called after the Host accepted an answer, so the
 *   page can reload the timeline that now contains it.
 */

class DshInteractionController(
    private val ctx: DshHomeContext,
    private val onApprovalRejected: (label: String) -> Unit,
    private val onQuestionAnswered: (sessionId: String) -> Unit,
    private val postToPage: (() -> Unit) -> Unit = { setTimeout(ctx.pagerId, 0, it) },
) : PagerScope by ctx {
    var pendingApproval by observable<DshPendingApproval?>(null)
        private set
    var pendingQuestion by observable<DshPendingQuestion?>(null)
        private set
    var busy by observable(false)
        private set
    val selectedOptions by observableList<String>()
    var custom by observable("")
        private set
    var index by observable(0)
        private set
    var error by observable("")
        private set
    private val drafts = mutableMapOf<Int, DshQuestionDraft>()
    private val requests = DshControllerRequestScope(ctx)
    private var interactionClient: DshHostClient? = null
    private var interactionGeneration = -1L
    private var interactionSessionId = ""

    /** Drop everything that belonged to the previous session. */
    fun reset() {
        requests.invalidate()
        busy = false
        pendingApproval = null
        pendingQuestion = null
        selectedOptions.clear()
        custom = ""
        index = 0
        error = ""
        drafts.clear()
    }
    fun refresh() {
        if (requests.closed) return
        val client = ctx.hostClient ?: run { reset(); return }
        val (approval, question) = client.pendingInteractions(ctx.activeSessionId)
        val generation = client.currentConnectionState().generation
        if (interactionClient !== client || interactionGeneration != generation ||
            interactionSessionId != ctx.activeSessionId || pendingApproval != approval || pendingQuestion != question
        ) {
            reset()
        }
        interactionClient = client
        interactionGeneration = generation
        interactionSessionId = ctx.activeSessionId
        pendingApproval = approval
        pendingQuestion = question
        index = index.coerceIn(0, ((question?.questions?.size ?: 0) - 1).coerceAtLeast(0))
        if (question != null && question.questions.isEmpty()) error = "问题内容为空，请等待 Agent 重新提问"
        loadDraft(index)
        DshStreamLog.question(
            "ui.refresh session=${ctx.activeSessionId} approval=${approval?.rpcId.orEmpty()} question=${question?.rpcId.orEmpty()} qCount=${question?.questions?.size ?: 0} busy=$busy",
        )
    }
    fun answerApproval(outcome: String) {
        if (busy || requests.closed) return
        refresh()
        val approval = pendingApproval ?: return
        val request = requests.begin(approval.sessionId) ?: return
        busy = true
        request.client.respondApproval(
            rpcId = approval.rpcId,
            sessionId = approval.sessionId,
            approvalId = approval.approvalId,
            outcome = outcome,
        ) { accepted, reason ->
            postToPage {
                if (!requests.owns(request)) return@postToPage
                busy = false
                if (!requests.accepts(request) || pendingApproval != approval) return@postToPage
                if (!accepted) {
                    onApprovalRejected(failureLabel(reason))
                    return@postToPage
                }
                request.client.clearPending(approval.rpcId)
                refresh()
            }
        }
    }
    fun toggleOption(label: String) {
        if (busy || requests.closed) return
        val item = pendingQuestion?.questions?.getOrNull(index) ?: return
        if (item.options.none { it.label == label }) return
        val wasSelected = selectedOptions.contains(label)
        if (!item.multiSelect) {
            selectedOptions.clear()
            custom = ""
        }
        if (wasSelected) selectedOptions.remove(label) else selectedOptions.add(label)
        error = ""
        saveDraft()
    }
    fun updateCustom(value: String) {
        if (busy || requests.closed) return
        val item = pendingQuestion?.questions?.getOrNull(index) ?: return
        if (!item.multiSelect) selectedOptions.clear()
        custom = value
        error = ""
        saveDraft()
    }
    fun skip() {
        if (busy || requests.closed) return
        val count = pendingQuestion?.questions?.size ?: return
        if (count == 0) return
        drafts[index] = DshQuestionDraft(skipped = true)
        selectedOptions.clear()
        custom = ""
        error = ""
        if (index < count - 1) {
            index += 1
            loadDraft(index)
        } else {
            submit()
        }
    }
    fun navigate(delta: Int) {
        if (busy || requests.closed) return
        val count = pendingQuestion?.questions?.size ?: return
        if (count == 0) return
        val next = (index + delta).coerceIn(0, count - 1)
        if (next == index) return
        saveDraft()
        index = next
        error = ""
        loadDraft(next)
    }
    fun submit() {
        if (busy || requests.closed) return
        refresh()
        val client = ctx.hostClient
        if (client == null) {
            DshStreamLog.question("submit.abort not-remote-repo")
            return
        }
        val question = pendingQuestion
        if (question == null) {
            DshStreamLog.question("submit.abort no-pending-question")
            return
        }
        if (question.questions.isEmpty()) {
            error = "问题内容为空，请等待 Agent 重新提问"
            return
        }
        saveDraft()
        val missing = question.questions.indices.firstOrNull { at ->
            val draft = drafts[at] ?: DshQuestionDraft()
            draft.selected.isEmpty() && draft.custom.isBlank() && !draft.skipped
        } ?: -1
        if (missing >= 0) {
            index = missing
            loadDraft(missing)
            error = "请先选择一项，或自己写答案"
            DshStreamLog.question("submit.abort unanswered index=$missing")
            return
        }
        if (question.rpcId.isEmpty()) {
            error = "这个问题已失效，请等 Agent 重新提问"
            DshStreamLog.question("submit.abort empty-rpcId session=${question.sessionId}")
            return
        }
        error = ""
        val request = requests.begin(question.sessionId) ?: return
        busy = true
        val answer = buildQuestionAnswer(question, drafts)
        DshStreamLog.question(
            "submit.start session=${question.sessionId} rpcId=${question.rpcId} index=$index selected=${selectedOptions.size} custom=${DshDiagnostics.preview(custom)} answer=${DshDiagnostics.preview(answer.toString())}",
        )
        client.respondQuestion(
            rpcId = question.rpcId,
            sessionId = question.sessionId,
            answer = answer,
        ) { accepted, reason ->
            postToPage {
                if (!requests.owns(request)) return@postToPage
                busy = false
                if (!requests.accepts(request) || pendingQuestion != question) return@postToPage
                val stillPending = client.pendingInteractions(question.sessionId).second
                DshStreamLog.question(
                    "submit.callback accepted=$accepted reason=${DshDiagnostics.redactedError(reason)} rpcId=${question.rpcId} stillPending=${stillPending?.rpcId.orEmpty()}",
                )
                if (!accepted) {
                    error = failureLabel(reason)
                    DshStreamLog.question("submit.rejected ui-kept error=${DshDiagnostics.redactedError(error)}")
                    return@postToPage
                }
                client.clearPending(question.rpcId)
                if (pendingQuestion?.rpcId == question.rpcId) {
                    pendingQuestion = null
                    selectedOptions.clear()
                    custom = ""
                    error = ""
                    drafts.clear()
                }
                DshStreamLog.question("submit.accepted ui-hide rpcId=${question.rpcId}")
                refresh()
                if (ctx.activeSessionId == question.sessionId) onQuestionAnswered(question.sessionId)
            }
        }
    }

    private fun saveDraft() {
        val skipped = drafts[index]?.skipped == true && selectedOptions.isEmpty() && custom.isBlank()
        drafts[index] = DshQuestionDraft(selectedOptions.toList(), custom, skipped)
    }

    private fun loadDraft(at: Int) {
        val draft = drafts[at] ?: DshQuestionDraft()
        selectedOptions.clear()
        selectedOptions.addAll(draft.selected)
        custom = draft.custom
    }

    private fun failureLabel(reason: String): String = when (reason) {
        "not-pending" -> "这个问题已经失效，请等 Agent 重新提问"
        "bad-response" -> "提交未被接受，请再选一次后重试"
        "缺少请求编号" -> "这个问题已失效，请等 Agent 重新提问"
        "连接尚未就绪" -> "连接尚未就绪，请稍后再试"
        else -> reason.ifEmpty { "提交失败，请重试" }
    }

    fun dispose() {
        reset()
        requests.close()
        interactionClient = null
    }
}
