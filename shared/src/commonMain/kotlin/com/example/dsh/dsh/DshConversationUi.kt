package com.example.dsh.dsh

import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.*

/** Reactive getters keep Kuikly subscriptions live; actions expose no business controllers. */
internal class DshTimelineUi(
    val conversationIds: () -> ObservableList<String>,
    val activeConversationId: () -> String,
    val messagesForSession: (String) -> ObservableList<DshMessage>,
    val scrollerRef: (String, ViewRef<ListView<*, *>>) -> Unit,
    val messageRef: (String, String, ViewRef<com.tencent.kuikly.core.views.DivView>) -> Unit,
    val onDismissKeyboard: () -> Unit,
    val onUserListScroll: (ScrollParams) -> Unit,
    val conversationListEpoch: (String) -> Int,
    val isBlankConversation: () -> Boolean,
)

internal class DshComposerUi(
    val draft: () -> String,
    val skills: () -> ObservableList<DshSkill>,
    val onPickSkill: (String) -> Unit,
    val keyboardHeight: () -> Float,
    val keyboardAnimation: () -> Animation,
    val inputRef: (com.tencent.kuikly.core.base.ViewRef<TextAreaView>) -> Unit,
    val onInputFocusChange: (Boolean) -> Unit,
    val onDraftChange: (String) -> Unit,
    val onKeyboardHeightChange: (KeyboardParams) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val modelLabel: () -> String,
    val attachmentMenuVisible: () -> Boolean,
    val voiceActive: () -> Boolean,
    val onOpenModels: () -> Unit,
    val onToggleAttachments: () -> Unit,
    val onToggleVoice: () -> Unit,
)

internal class DshMessageActionsUi(
    val isDisclosureExpanded: (String) -> Boolean,
    val onToggleDisclosure: (String) -> Unit,
    val isBodyDisclosureExpanded: (String) -> Boolean,
    val onToggleBodyDisclosure: (String) -> Unit,
    val isJsonNodeExpanded: (String, String) -> Boolean,
    val onToggleJsonNode: (String, String) -> Unit,
    val onCopyToolContent: (String) -> Unit,
    val attachmentDataUrl: (String, String) -> String?,
)

internal class DshTurnUi(
    val streaming: () -> Boolean,
    val streamingMessageId: () -> String,
    val streamingContent: () -> String,
    val stopButtonVisible: () -> Boolean,
    val sessionRunning: () -> Boolean,
    val turnReconnecting: () -> Boolean,
    val turnElapsedMs: () -> Long,
)

internal class DshQueueUi(
    val items: () -> ObservableList<DshQueueItem>,
    val expanded: () -> Boolean,
    val editingId: () -> String,
    val actionBusy: () -> Boolean,
    val editingText: () -> String,
    val toggle: () -> Unit,
    val edit: (String) -> Unit,
    val updateEditingText: (String) -> Unit,
    val save: (String) -> Unit,
    val cancelEdit: () -> Unit,
    val remove: (String) -> Unit,
    val steer: (String) -> Unit,
)

internal class DshJobsUi(
    val items: () -> ObservableList<DshJobItem>,
    val expanded: () -> Boolean,
    val now: () -> Long,
    val toggle: () -> Unit,
)

internal class DshGoalUi(
    val snapshot: () -> DshGoalSnapshot?,
    val busy: () -> Boolean,
    val error: () -> String,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val edit: (String, (Boolean) -> Unit) -> Unit,
    val clear: () -> Unit,
)

internal class DshInteractionUi(
    val pendingApproval: () -> DshPendingApproval?,
    val pendingQuestion: () -> DshPendingQuestion?,
    val selectedOptions: () -> ObservableList<String>,
    val custom: () -> String,
    val index: () -> Int,
    val error: () -> String,
    val busy: () -> Boolean,
    val answerApproval: (String) -> Unit,
    val toggleOption: (String) -> Unit,
    val updateCustom: (String) -> Unit,
    val navigate: (Int) -> Unit,
    val skip: () -> Unit,
    val submit: () -> Unit,
)

internal class DshSessionPanelsUi(
    val queue: DshQueueUi,
    val jobs: DshJobsUi,
    val goal: DshGoalUi,
    val interactions: DshInteractionUi,
)
