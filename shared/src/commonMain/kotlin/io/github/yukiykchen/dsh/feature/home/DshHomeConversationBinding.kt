package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.KeyboardParams
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.ScrollParams
import com.tencent.kuikly.core.views.TextAreaView
import io.github.yukiykchen.dsh.ui.isReconnectLabel
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshSkill

/** Explicit UI state passed from the home page to its conversation component. */
data class DshHomeConversationBinding(
    val conversationIds: () -> ObservableList<String>,
    val activeConversationId: () -> String,
    val messagesForSession: (String) -> ObservableList<DshMessage>,
    val streaming: () -> Boolean,
    val streamingMessageId: () -> String,
    val streamingContent: () -> String,
    val scrollerRef: (String, ViewRef<ListView<*, *>>) -> Unit,
    val messageRef: (String, String, ViewRef<DivView>) -> Unit,
    val draft: () -> String,
    val setDraft: (String) -> Unit,
    val skills: () -> ObservableList<DshSkill>,
    val keyboardHeight: () -> Float,
    val keyboardAnimation: () -> Animation,
    val stopButtonVisible: () -> Boolean,
    val inputRef: (ViewRef<TextAreaView>) -> Unit,
    val onInputFocusChange: (Boolean) -> Unit,
    val onKeyboardHeightChange: (KeyboardParams) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onDismissKeyboard: () -> Unit,
    val onUserListScroll: (ScrollParams) -> Unit,
    val modelLabel: () -> String,
    val attachmentMenuVisible: () -> Boolean,
    val setAttachmentMenuVisible: (Boolean) -> Unit,
    val onOpenModels: () -> Unit,
    val isDisclosureExpanded: (String) -> Boolean,
    val onToggleDisclosure: (String) -> Unit,
    val isBodyDisclosureExpanded: (String) -> Boolean,
    val onToggleBodyDisclosure: (String) -> Unit,
    val isJsonNodeExpanded: (String, String) -> Boolean,
    val onToggleJsonNode: (String, String) -> Unit,
    val onCopyToolContent: (String) -> Unit,
    val attachmentDataUrl: (String, String) -> String?,
    val queue: DshQueueController,
    val jobs: DshJobsController,
    val goal: DshGoalController,
    val interactions: DshInteractionController,
    val sessionRunning: () -> Boolean,
    val isBlankConversation: () -> Boolean,
    val conversationListEpoch: (String) -> Int,
    val connectionLabel: () -> String,
    val turnElapsedMs: () -> Long,
)

fun ViewContainer<*, *>.DshHomeConversation(
    binding: DshHomeConversationBinding,
    availableWidth: Float,
) {
    DshConversation(
        conversationIds = binding.conversationIds,
        activeConversationId = binding.activeConversationId,
        messagesForSession = binding.messagesForSession,
        streaming = binding.streaming,
        streamingMessageId = binding.streamingMessageId,
        streamingContent = binding.streamingContent,
        scrollerRef = binding.scrollerRef,
        messageRef = binding.messageRef,
        draft = binding.draft,
        skills = binding.skills,
        onPickSkill = { binding.setDraft("/$it ") },
        keyboardHeight = binding.keyboardHeight,
        stopButtonVisible = binding.stopButtonVisible,
        keyboardAnimation = binding.keyboardAnimation,
        inputRef = { binding.inputRef(it) },
        onInputFocusChange = binding.onInputFocusChange,
        onDraftChange = binding.setDraft,
        onKeyboardHeightChange = binding.onKeyboardHeightChange,
        onSend = binding.onSend,
        onStop = binding.onStop,
        onDismissKeyboard = binding.onDismissKeyboard,
        onUserListScroll = binding.onUserListScroll,
        modelLabel = binding.modelLabel,
        attachmentMenuVisible = binding.attachmentMenuVisible,
        onOpenModels = binding.onOpenModels,
        onToggleAttachments = {
            binding.onDismissKeyboard()
            binding.setAttachmentMenuVisible(!binding.attachmentMenuVisible())
        },
        isDisclosureExpanded = binding.isDisclosureExpanded,
        onToggleDisclosure = binding.onToggleDisclosure,
        isBodyDisclosureExpanded = binding.isBodyDisclosureExpanded,
        onToggleBodyDisclosure = binding.onToggleBodyDisclosure,
        isJsonNodeExpanded = binding.isJsonNodeExpanded,
        onToggleJsonNode = binding.onToggleJsonNode,
        onCopyToolContent = binding.onCopyToolContent,
        attachmentDataUrl = binding.attachmentDataUrl,
        queue = binding.queue,
        jobs = binding.jobs,
        goal = binding.goal,
        interactions = binding.interactions,
        sessionRunning = binding.sessionRunning,
        isBlankConversation = binding.isBlankConversation,
        conversationListEpoch = binding.conversationListEpoch,
        turnReconnecting = { isReconnectLabel(binding.connectionLabel()) },
        turnElapsedMs = binding.turnElapsedMs,
        availableWidth = availableWidth,
    )
}
