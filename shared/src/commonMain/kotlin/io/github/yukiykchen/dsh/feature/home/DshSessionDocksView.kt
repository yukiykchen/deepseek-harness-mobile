package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import kotlin.coroutines.resume
import io.github.yukiykchen.dsh.ui.DshGoalBar
import io.github.yukiykchen.dsh.ui.DshQuestionFlow
import io.github.yukiykchen.dsh.ui.DshApprovalPanel
import io.github.yukiykchen.dsh.ui.DshJobsPanel
import io.github.yukiykchen.dsh.ui.DshQueueDock
import io.github.yukiykchen.dsh.model.DshPendingQuestionOption

fun ViewContainer<*, *>.DshSessionDocks(
    queueCtl: DshQueueController,
    jobsCtl: DshJobsController,
    goalCtl: DshGoalController,
    interactionCtl: DshInteractionController,
    activeConversationId: () -> String,
    sessionRunning: () -> Boolean,
) {
    vif({ queueCtl.items.isNotEmpty() }) {
        DshQueueDock {
            attr {
                items = queueCtl.items
                expanded = queueCtl.expanded
                editingId = queueCtl.editingId
                actionBusy = queueCtl.actionBusy
                error = queueCtl.error
                editingText = queueCtl.editingText
                running = sessionRunning()
                onToggle = queueCtl::toggle
                onEdit = queueCtl::edit
                onEditingTextChange = queueCtl::updateEditingText
                onSaveEdit = queueCtl::save
                onCancelEdit = queueCtl::cancelEdit
                onRemove = queueCtl::remove
                onSteer = queueCtl::steer
            }
        }
    }
    vif({ jobsCtl.items.isNotEmpty() }) {
        DshJobsPanel {
            attr {
                jobs = jobsCtl.items
                expanded = jobsCtl.expanded
                now = jobsCtl.now
                onToggle = jobsCtl::toggle
            }
        }
    }
    vif({ goalCtl.snapshot != null }) {
        DshGoalBar {
            attr {
                snapshot = goalCtl.snapshot
                busy = goalCtl.busy
                error = goalCtl.error
                onPause = goalCtl::pause
                onResume = goalCtl::resume
                onEdit = goalCtl::edit
                onClear = goalCtl::clear
            }
        }
    }
    vif({ interactionCtl.pendingApproval?.sessionId == activeConversationId() }) {
        DshApprovalPanel {
            attr {
                approval = interactionCtl.pendingApproval
                busy = interactionCtl.busy
                onAnswer = interactionCtl::answerApproval
            }
        }
    }
    vif({
        interactionCtl.pendingApproval == null &&
            interactionCtl.pendingQuestion?.sessionId == activeConversationId()
    }) {
        DshQuestionFlow {
            attr {
                question = interactionCtl.pendingQuestion
                val options = ObservableList<DshPendingQuestionOption>()
                interactionCtl.pendingQuestion?.questions?.getOrNull(interactionCtl.index)?.options?.let(options::addAll)
                this.options = options
                selected = interactionCtl.selectedOptions
                custom = interactionCtl.custom
                index = interactionCtl.index
                error = interactionCtl.error
                busy = interactionCtl.busy
                onToggleOption = interactionCtl::toggleOption
                onCustomChange = interactionCtl::updateCustom
                onNavigate = interactionCtl::navigate
                onSkip = interactionCtl::skip
                onSubmit = interactionCtl::submit
            }
        }
    }
}
