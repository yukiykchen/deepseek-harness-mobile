package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout

/**
 * Approval requests and multi-step questions the Agent is waiting on.
 *
 * @param onApprovalRejected shows why an approval answer was not accepted.
 * @param onQuestionAnswered called after the Host accepted an answer, so the
 *   page can reload the timeline that now contains it.
 */
internal class DshInteractionController(
    private val ctx: DshHomeContext,
    private val onApprovalRejected: (label: String) -> Unit,
    private val onQuestionAnswered: (sessionId: String) -> Unit,
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

    /** Drop everything that belonged to the previous session. */
    fun reset() {
        pendingApproval = null
        pendingQuestion = null
        selectedOptions.clear()
        custom = ""
        index = 0
        error = ""
        drafts.clear()
    }

    fun refresh() {
        val client = ctx.hostClient ?: return
        val (approval, question) = client.pendingInteractions(ctx.activeSessionId)
        pendingApproval = approval
        pendingQuestion = question
        index = index.coerceIn(0, (question?.questions?.size ?: 1) - 1)
        loadDraft(index)
        DshStreamLog.question(
            "ui.refresh session=${ctx.activeSessionId} approval=${approval?.rpcId.orEmpty()} question=${question?.rpcId.orEmpty()} qCount=${question?.questions?.size ?: 0} busy=$busy",
        )
    }

    fun answerApproval(outcome: String) {
        val client = ctx.hostClient ?: return
        val approval = pendingApproval ?: return
        busy = true
        client.respondApproval(
            rpcId = approval.rpcId,
            sessionId = approval.sessionId,
            approvalId = approval.approvalId,
            outcome = outcome,
        ) { accepted, reason ->
            setTimeout(pagerId, 0) {
                busy = false
                if (!accepted) {
                    onApprovalRejected(failureLabel(reason))
                    return@setTimeout
                }
                refresh()
            }
        }
    }

    fun toggleOption(label: String) {
        val item = pendingQuestion?.questions?.getOrNull(index) ?: return
        if (!item.multiSelect) {
            selectedOptions.clear()
            custom = ""
        }
        if (selectedOptions.contains(label)) selectedOptions.remove(label)
        else selectedOptions.add(label)
        error = ""
        saveDraft()
    }

    fun updateCustom(value: String) {
        val item = pendingQuestion?.questions?.getOrNull(index) ?: return
        if (!item.multiSelect) selectedOptions.clear()
        custom = value
        error = ""
        saveDraft()
    }

    fun skip() {
        val count = pendingQuestion?.questions?.size ?: return
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
        val count = pendingQuestion?.questions?.size ?: return
        val next = (index + delta).coerceIn(0, count - 1)
        if (next == index) return
        saveDraft()
        index = next
        error = ""
        loadDraft(next)
    }

    fun submit() {
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
        saveDraft()
        val missing = question.questions.indexOfFirst { item ->
            val draft = drafts[question.questions.indexOf(item)] ?: DshQuestionDraft()
            draft.selected.isEmpty() && draft.custom.isBlank() && !draft.skipped
        }
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
        busy = true
        val answer = buildQuestionAnswer(question, drafts)
        DshStreamLog.question(
            "submit.start session=${question.sessionId} rpcId=${question.rpcId} index=$index selected=${selectedOptions.toList()} custom='${DshStreamLog.preview(custom)}' answer='${DshStreamLog.preview(answer.toString(), 400)}'",
        )
        client.respondQuestion(
            rpcId = question.rpcId,
            sessionId = question.sessionId,
            answer = answer,
        ) { accepted, reason ->
            setTimeout(pagerId, 0) {
                val stillPending = client.pendingInteractions(question.sessionId).second
                DshStreamLog.question(
                    "submit.callback accepted=$accepted reason='$reason' rpcId=${question.rpcId} stillPending=${stillPending?.rpcId.orEmpty()} active=${ctx.activeSessionId}",
                )
                busy = false
                if (!accepted) {
                    error = failureLabel(reason)
                    DshStreamLog.question("submit.rejected ui-kept error='$error'")
                    return@setTimeout
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
        drafts[index] = DshQuestionDraft(selectedOptions.toList(), custom)
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
}
