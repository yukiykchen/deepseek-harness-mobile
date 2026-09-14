package com.example.dsh.dsh

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vforLazy
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.KeyboardParams
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.ScrollParams
import com.tencent.kuikly.core.views.SelectableOption
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

internal fun ViewContainer<*, *>.DshTurnStatus(
    visible: () -> Boolean,
    reconnecting: () -> Boolean,
    elapsedMs: () -> Long,
) {
    vif({ visible() }) {
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
                height(26f)
                marginTop(4f)
                marginBottom(8f)
            }
            Text {
                attr {
                    text(dshTurnStatusLabel(reconnecting()))
                    fontSize(14f)
                    fontWeightBold()
                    color(theme.turnStatus)
                }
            }
            vif({ elapsedMs() >= TURN_STATUS_CLOCK_AFTER_MS }) {
                Text {
                    attr {
                        text(dshFormatTurnDuration(elapsedMs()))
                        fontSize(13f)
                        color(theme.textMuted)
                        marginLeft(8f)
                    }
                }
            }
        }
    }
}

internal const val TURN_STATUS_CLOCK_AFTER_MS = 15_000L

internal fun ViewContainer<*, *>.DshNewSessionHome() {
    View {
        attr {
            absolutePositionAllZero()
            allCenter()
            zIndex(2)
            paddingLeft(28f)
            paddingRight(28f)
        }
        event {
            click { }
        }
        View {
            attr {
                flexDirectionColumn()
                alignItemsCenter()
            }
            Image {
                attr {
                    src(ImageUri.commonAssets("fish.svg"))
                    size(56f, 56f)
                    tintColor(theme.textPrimary)
                }
            }
            View {
                attr {
                    marginTop(16f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text("探索未至之境")
                        fontSize(26f)
                        fontWeightBold()
                        color(theme.textPrimary)
                    }
                }
                View {
                    attr {
                        marginLeft(8f)
                        paddingLeft(8f)
                        paddingRight(8f)
                        height(22f)
                        allCenter()
                        borderRadius(11f)
                        backgroundColor(theme.accentSoft)
                    }
                    Text {
                        attr {
                            text("预览版")
                            fontSize(11f)
                            fontWeightMedium()
                            color(theme.accent)
                        }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshConversation(
    conversationIds: () -> ObservableList<String>,
    activeConversationId: () -> String,
    messagesForSession: (String) -> ObservableList<DshMessage>,
    streaming: () -> Boolean,
    streamingMessageId: () -> String,
    streamingContent: () -> String,
    scrollerRef: (String, ViewRef<ListView<*, *>>) -> Unit,
    messageRef: (String, String, ViewRef<com.tencent.kuikly.core.views.DivView>) -> Unit,
    draft: () -> String,
    skills: () -> ObservableList<DshSkill>,
    onPickSkill: (String) -> Unit,
    keyboardHeight: () -> Float,
    stopButtonVisible: () -> Boolean,
    keyboardAnimation: () -> Animation,
    inputRef: (com.tencent.kuikly.core.base.ViewRef<InputView>) -> Unit,
    onInputFocusChange: (Boolean) -> Unit,
    onDraftChange: (String) -> Unit,
    onKeyboardHeightChange: (KeyboardParams) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onDismissKeyboard: () -> Unit,
    onUserListScroll: (ScrollParams) -> Unit,
    modelLabel: () -> String,
    attachmentMenuVisible: () -> Boolean,
    stagedAttachments: () -> ObservableList<DshStagedAttachment>,
    attachmentNotice: () -> String,
    voiceActive: () -> Boolean,
    onOpenModels: () -> Unit,
    onToggleAttachments: () -> Unit,
    onPickImages: () -> Unit,
    onCaptureImage: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onPreviewAttachment: (String) -> Unit,
    onToggleVoice: () -> Unit,
    isWebTimeline: () -> Boolean,
    isDisclosureExpanded: (String) -> Boolean,
    onToggleDisclosure: (String) -> Unit,
    isBodyDisclosureExpanded: (String) -> Boolean,
    onToggleBodyDisclosure: (String) -> Unit,
    isJsonNodeExpanded: (String, String) -> Boolean,
    onToggleJsonNode: (String, String) -> Unit,
    onCopyToolContent: (String) -> Unit,
    onLongPressMessage: (DshMessage, Float, Float) -> Unit,
    onSelectionCancelled: () -> Unit,
    batchSelectionActive: () -> Boolean,
    isMessageBatchSelected: (String) -> Boolean,
    onToggleMessageBatchSelection: (String) -> Unit,
    attachmentDataUrl: (String) -> String?,
    onOpenAttachment: (DshImageAttachmentRef) -> Unit,
    queueItems: () -> ObservableList<DshQueueItem>,
    jobItems: () -> ObservableList<DshJobItem>,
    goal: () -> DshGoalSnapshot?,
    goalActionBusy: () -> Boolean,
    goalActionError: () -> String,
    onPauseGoal: () -> Unit,
    onResumeGoal: () -> Unit,
    onEditGoal: (String, (Boolean) -> Unit) -> Unit,
    onClearGoal: () -> Unit,
    jobsPanelExpanded: () -> Boolean,
    jobsNow: () -> Long,
    onToggleJobsPanel: () -> Unit,
    queueExpanded: () -> Boolean,
    queueEditingId: () -> String,
    queueActionBusy: () -> Boolean,
    queueEditingText: () -> String,
    sessionRunning: () -> Boolean,
    isBlankConversation: () -> Boolean,
    conversationListEpoch: (String) -> Int,
    turnReconnecting: () -> Boolean,
    turnElapsedMs: () -> Long,
    onToggleQueue: () -> Unit,
    onEditQueueItem: (String) -> Unit,
    onQueueEditingTextChange: (String) -> Unit,
    onSaveQueueItem: (String) -> Unit,
    onCancelQueueItemEdit: () -> Unit,
    onRemoveQueueItem: (String) -> Unit,
    onSteerQueueItem: (String) -> Unit,
    pendingApproval: () -> DshPendingApproval?,
    pendingQuestion: () -> DshPendingQuestion?,
    interactionBusy: () -> Boolean,
    selectedQuestionOptions: () -> ObservableList<String>,
    questionCustom: () -> String,
    questionIndex: () -> Int,
    questionError: () -> String,
    onAnswerApproval: (String) -> Unit,
    onToggleQuestionOption: (String) -> Unit,
    onQuestionCustomChange: (String) -> Unit,
    onQuestionNavigate: (Int) -> Unit,
    onQuestionSkip: () -> Unit,
    onSubmitQuestion: () -> Unit,
    availableWidth: Float,
) {
    View {
        attr {
            flex(1f)
            width(availableWidth)
            flexDirectionColumn()
            backgroundColor(theme.surface)
        }
        View {
            attr {
                flex(1f)
                flexDirectionColumn()
                // Reduce the conversation viewport when the keyboard opens.
                // The header stays outside this container and the composer
                // naturally settles above the keyboard without translating
                // the list outside its clipping bounds.
                marginBottom(keyboardHeight())
                animation(keyboardAnimation(), keyboardHeight())
            }
            View {
                attr {
                flex(1f)
                width(availableWidth)
                backgroundColor(theme.surface)
            }
            vfor({ conversationIds() }) { sessionId ->
                View {
                    attr {
                        absolutePositionAllZero()
                        width(availableWidth)
                        visibility(true)
                        opacity(if (activeConversationId() == sessionId) 1f else 0f)
                        touchEnable(activeConversationId() == sessionId)
                        zIndex(if (activeConversationId() == sessionId) 1 else 0)
                    }
                    // vfor 的直接子节点不能是 vif/vbind。空 List 先挂载后 addAll
                    // 时 LazyLoop 会把增量当成「加到可见范围后面」而不建 cell。
                    vbind({ conversationListEpoch(sessionId) }) {
                        vif({ messagesForSession(sessionId).isNotEmpty() }) {
                            List {
                                ref { scrollerRef(sessionId, it) }
                                attr {
                                    absolutePositionAllZero()
                                    width(availableWidth)
                                    padding(16f, 18f, 20f, 18f)
                                    firstContentLoadMaxIndex(CHAT_INITIAL_RENDER_COUNT)
                                    preloadViewDistance(pagerData.pageViewHeight)
                                }
                                event {
                                    click { onDismissKeyboard() }
                                    dragBegin { params ->
                                        onDismissKeyboard()
                                        onUserListScroll(params)
                                    }
                                    scroll { onUserListScroll(it) }
                                    scrollEnd { onUserListScroll(it) }
                                    register("touchDown", { onDismissKeyboard() })
                                }
                                vforLazy(
                                    { messagesForSession(sessionId) },
                                    maxLoadItem = CHAT_MAX_RENDERED_MESSAGES,
                                ) { message, _, _ ->
                                    View {
                                        ref { messageRef(sessionId, message.id, it) }
                                        attr {
                                            width((availableWidth - 36f).coerceAtLeast(0f))
                                            // Task 2: the row is the selection container, so
                                            // createSelection/getSelection run against this ref.
                                            selectable(SelectableOption.ENABLE)
                                            selectionColor(theme.accent)
                                            // Attrs are cumulative: leaving backgroundColor
                                            // unset on the way out of batch mode would keep
                                            // the tint, so every branch sets it.
                                            borderRadius(10f)
                                            backgroundColor(
                                                if (batchSelectionActive() && isMessageBatchSelected(message.id)) {
                                                    theme.accentSoft
                                                } else {
                                                    Color(0x00000000)
                                                },
                                            )
                                        }
                                        event {
                                            longPress { params ->
                                                onLongPressMessage(message, params.x, params.y)
                                            }
                                            selectCancel { onSelectionCancelled() }
                                        }
                                        // In batch mode the overlay takes every tap, so a tool
                                        // card cannot expand instead of toggling its row.
                                        vif({ batchSelectionActive() }) {
                                            View {
                                                attr {
                                                    absolutePositionAllZero()
                                                    zIndex(3)
                                                    backgroundColor(Color(0x00000000))
                                                }
                                                event { click { onToggleMessageBatchSelection(message.id) } }
                                            }
                                        }
                                        DshMessageRow(
                                            message,
                                            pageStreaming = {
                                                streaming() &&
                                                    activeConversationId() == sessionId &&
                                                    streamingMessageId() == message.id
                                            },
                                            isWebTimeline = isWebTimeline(),
                                            isExpanded = { isDisclosureExpanded(message.id) },
                                            onToggle = {
                                                onToggleDisclosure(message.id)
                                            },
                                            isBodyExpanded = { isBodyDisclosureExpanded(message.id) },
                                            onToggleBody = {
                                                onToggleBodyDisclosure(message.id)
                                            },
                                            isJsonNodeExpanded = { isJsonNodeExpanded(message.id, it) },
                                            onToggleJsonNode = { onToggleJsonNode(message.id, it) },
                                            onCopyToolContent = { onCopyToolContent(it) },
                                            onLongPress = { x, y -> onLongPressMessage(message, x, y) },
                                            attachmentDataUrl = { attachmentDataUrl(it) },
                                            onOpenAttachment = onOpenAttachment,
                                            contentProvider = {
                                                val stored = messagesForSession(sessionId)
                                                    .firstOrNull { it.id == message.id }
                                                    ?.content
                                                    .orEmpty()
                                                dshDisplayedAssistantContent(
                                                    stored = stored,
                                                    live = streamingContent(),
                                                    isLiveRow = streamingMessageId() == message.id &&
                                                        activeConversationId() == sessionId,
                                                )
                                            },
                                        )
                                    }
                                }
                                View {
                                    attr {
                                        width((availableWidth - 36f).coerceAtLeast(0f))
                                    }
                                    DshTurnStatus(
                                        visible = {
                                            activeConversationId() == sessionId &&
                                                (streaming() || stopButtonVisible() || sessionRunning())
                                        },
                                        reconnecting = turnReconnecting,
                                        elapsedMs = turnElapsedMs,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            vif({
                conversationListEpoch(activeConversationId())
                isBlankConversation() &&
                    messagesForSession(activeConversationId()).isEmpty() &&
                    !streaming() &&
                    !stopButtonVisible() &&
                    !sessionRunning()
            }) {
                DshNewSessionHome()
            }
        }
        vif({ isWebTimeline() && queueItems().isNotEmpty() }) {
            DshQueueDock {
                attr {
                    items = queueItems()
                    expanded = queueExpanded()
                    editingId = queueEditingId()
                    actionBusy = queueActionBusy()
                    editingText = queueEditingText()
                    running = sessionRunning()
                    onToggle = onToggleQueue
                    onEdit = onEditQueueItem
                    onEditingTextChange = onQueueEditingTextChange
                    onSaveEdit = onSaveQueueItem
                    onCancelEdit = onCancelQueueItemEdit
                    onRemove = onRemoveQueueItem
                    onSteer = onSteerQueueItem
                }
            }
        }
        vif({ isWebTimeline() && jobItems().isNotEmpty() }) {
            DshJobsPanel {
                attr {
                    jobs = jobItems()
                    expanded = jobsPanelExpanded()
                    now = jobsNow()
                    onToggle = onToggleJobsPanel
                }
            }
        }
        vif({ isWebTimeline() && goal() != null }) {
            DshGoalBar {
                attr {
                    snapshot = goal()
                    busy = goalActionBusy()
                    error = goalActionError()
                    onPause = onPauseGoal
                    onResume = onResumeGoal
                    onEdit = onEditGoal
                    onClear = onClearGoal
                }
            }
        }
        vif({ isWebTimeline() && pendingApproval()?.sessionId == activeConversationId() }) {
            DshApprovalPanel {
                attr {
                    approval = pendingApproval()
                    busy = interactionBusy()
                    onAnswer = onAnswerApproval
                }
            }
        }
        vif({
            isWebTimeline() &&
                pendingApproval() == null &&
                pendingQuestion()?.sessionId == activeConversationId()
        }) {
            DshQuestionFlow {
                attr {
                    question = pendingQuestion()
                    val options = ObservableList<DshPendingQuestionOption>()
                    pendingQuestion()?.questions?.getOrNull(questionIndex())?.options?.let(options::addAll)
                    this.options = options
                    selected = selectedQuestionOptions()
                    custom = questionCustom()
                    index = questionIndex()
                    error = questionError()
                    busy = interactionBusy()
                    onToggleOption = onToggleQuestionOption
                    onCustomChange = onQuestionCustomChange
                    onNavigate = onQuestionNavigate
                    onSkip = onQuestionSkip
                    onSubmit = onSubmitQuestion
                }
            }
        }
            View {
                attr {
                    // COMPOSER_HEIGHT only fits the input and the toolbar, so anything
                    // stacked above them has to add its own height or it pushes the send
                    // button out of the composer.
                    height(
                        COMPOSER_HEIGHT +
                            (if (attachmentMenuVisible()) ATTACHMENT_MENU_HEIGHT else 0f) +
                            (if (stagedAttachments().isEmpty()) 0f else ATTACHMENT_STRIP_HEIGHT) +
                            (if (attachmentNotice().isEmpty()) 0f else ATTACHMENT_ERROR_HEIGHT),
                    )
                    width(availableWidth)
                    flexDirectionColumn()
                    padding(12f, 14f, 12f, 14f)
                    backgroundColor(theme.surface)
                    borderRadius(22f)
                    border(Border(1f, BorderStyle.SOLID, theme.border))
                }
                vif({
                    isWebTimeline() && draft().startsWith("/") &&
                        visibleSkillList(skills(), draft().removePrefix("/")).isNotEmpty()
                }) {
                    View {
                        attr {
                            maxHeight(132f)
                            marginBottom(6f)
                            flexDirectionColumn()
                            backgroundColor(theme.surfaceRaised)
                            borderRadius(8f)
                            border(Border(1f, BorderStyle.SOLID, theme.border))
                        }
                        vfor({ visibleSkillList(skills(), draft().removePrefix("/")) }) { skill ->
                            View {
                                attr {
                                    height(32f)
                                    flexDirectionRow()
                                    alignItemsCenter()
                                    paddingLeft(8f)
                                    paddingRight(8f)
                                }
                                event { click { onPickSkill(skill.name) } }
                                Text {
                                    attr {
                                        text("/${skill.name}")
                                        width(110f)
                                        fontSize(13f)
                                        fontWeightMedium()
                                        color(theme.success)
                                    }
                                }
                                Text {
                                    attr {
                                        text(if (skill.modelInvocable) skill.description else "用户专用 · ${skill.description}")
                                        flex(1f)
                                        lines(1)
                                        fontSize(11f)
                                        color(theme.textMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            DshAttachmentStrip(
                attachments = stagedAttachments,
                notice = attachmentNotice,
                onRemove = onRemoveAttachment,
                onRetry = onRetryAttachment,
                onPreview = onPreviewAttachment,
            )

            Input {
                ref { inputRef(it) }
                attr {
                    height(58f)
                    backgroundColor(Color(0x00FFFFFF))
                    fontSize(15f)
                    color(theme.textPrimary)
                    placeholder(
                        when {
                            voiceActive() -> "正在聆听..."
                            isBlankConversation() &&
                                messagesForSession(activeConversationId()).isEmpty() -> "描述你想要构建的内容"
                            else -> "请输入您的问题..."
                        },
                    )
                    placeholderColor(theme.placeholder)
                    returnKeyTypeSend()
                    editable(!voiceActive())
                }
                event {
                    inputFocus { onInputFocusChange(true) }
                    textDidChange { onDraftChange(it.text) }
                    keyboardHeightChange { onKeyboardHeightChange(it) }
                    inputBlur {
                        onInputFocusChange(false)
                        onKeyboardHeightChange(KeyboardParams(0f, 0.24f))
                    }
                    inputReturn { onSend() }
                }
            }

            vif({ attachmentMenuVisible() }) {
                View {
                    attr {
                        height(82f)
                        marginBottom(8f)
                        flexDirectionColumn()
                        padding(8f)
                        borderRadius(10f)
                        backgroundColor(theme.surfaceRaised)
                    }
                    DshAttachmentSourceRow(
                        icon = "image.svg",
                        title = "Photo library",
                        hint = "PNG / JPEG / WebP / GIF",
                        onClick = onPickImages,
                    )
                    DshAttachmentSourceRow(
                        icon = "camera.svg",
                        title = "Take a photo",
                        hint = "Sent as an image attachment",
                        onClick = onCaptureImage,
                    )
                }
            }

            View {
                attr {
                    height(48f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                View {
                    attr {
                        width(184f)
                        height(40f)
                        flexDirectionRow()
                        alignItemsCenter()
                        paddingLeft(12f)
                        paddingRight(9f)
                        borderRadius(20f)
                        border(Border(1f, BorderStyle.SOLID, theme.inputBorder))
                    }
                    Text {
                        attr {
                            text(modelLabel())
                            flex(1f)
                            lines(1)
                            fontSize(14f)
                            color(theme.textPrimary)
                        }
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("chevron-down.svg"))
                            size(18f, 18f)
                            tintColor(theme.textMuted)
                        }
                    }
                    DshHitButton(onOpenModels)
                }
                View { attr { flex(1f) } }
                View {
                    attr { size(40f, 40f); allCenter() }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("sliders.svg"))
                            size(22f, 22f)
                            tintColor(if (attachmentMenuVisible()) theme.accent else theme.textMuted)
                        }
                    }
                    DshHitButton(onToggleAttachments)
                }
                View {
                    attr {
                        size(48f, 48f)
                        marginLeft(6f)
                        borderRadius(24f)
                        allCenter()
                        backgroundColor(
                            when {
                                stopButtonVisible() -> theme.dangerFill
                                voiceActive() -> theme.accentMuted
                                else -> theme.accentFill
                            },
                        )
                    }
                    vif({ stopButtonVisible() }) {
                        Image {
                            attr {
                                src(ImageUri.commonAssets("square.svg"))
                                size(23f, 23f)
                            }
                        }
                    }
                    velse {
                        Image {
                            attr {
                                // Staged images are a sendable turn on their own, so the
                                // button must not fall back to the microphone for them.
                                src(
                                    ImageUri.commonAssets(
                                        if (draft().isEmpty() && stagedAttachments().isEmpty()) "mic.svg" else "send.svg",
                                    ),
                                )
                                size(23f, 23f)
                            }
                        }
                    }
                    DshHitButton {
                            when {
                                stopButtonVisible() -> onStop()
                                draft().isNotEmpty() || stagedAttachments().isNotEmpty() -> onSend()
                                else -> onToggleVoice()
                            }
                    }
                }
            }
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshMessageRow(
    message: DshMessage,
    pageStreaming: () -> Boolean,
    isWebTimeline: Boolean,
    isExpanded: () -> Boolean,
    onToggle: () -> Unit,
    isBodyExpanded: () -> Boolean = { false },
    onToggleBody: () -> Unit = {},
    isJsonNodeExpanded: (String) -> Boolean = { false },
    onToggleJsonNode: (String) -> Unit = {},
    onCopyToolContent: (String) -> Unit = {},
    onLongPress: (Float, Float) -> Unit = { _, _ -> },
    attachmentDataUrl: (String) -> String? = { null },
    onOpenAttachment: (DshImageAttachmentRef) -> Unit = {},
    contentProvider: (() -> String)? = null,
) {
    if (message.hidden) return
    val isUser = message.role == DshMessageRole.USER
    val isError = message.role == DshMessageRole.ERROR
    val renderedContent = contentProvider?.invoke() ?: message.content
    if (
        message.role == DshMessageRole.ASSISTANT &&
        !message.isReasoning &&
        pageStreaming() &&
        renderedContent.isEmpty()
    ) {
        return
    }
    if (isWebTimeline && message.isContextInjection) {
        View {
            attr {
                width(pagerData.pageViewWidth - 36f)
                marginBottom(12f)
            }
            DshDisclosureRow {
                attr {
                    title = "上下文注入"
                    iconAsset = "context.svg"
                    summary = message.toolName.orEmpty()
                    body = if (message.contextCatalog.isNotEmpty()) {
                        message.contextCatalog.joinToString("\n") { "${it.name}\n${it.description}" }
                    } else if (message.contextSections.isNotEmpty()) {
                        message.contextSections.joinToString("\n\n") {
                            "${it.title}\n${boundedContextText(it.body)}"
                        }
                    } else if (message.contextRecalls.isNotEmpty()) {
                        message.contextRecalls.joinToString("\n") {
                            "${it.label} · 保留 ${it.retainedMessages} · 省略 ${it.omittedMessages}${if (it.truncated) " · 已截断" else ""}"
                        } + "\n\n" + boundedContextText(message.contextBody)
                    } else if (message.contextInstructions.isNotEmpty()) {
                        message.contextInstructions.joinToString("\n") { "${it.path} · ${it.action}" } +
                            "\n\n" + boundedContextText(message.contextBody)
                    } else if (message.contextRelaySender.isNotEmpty()) {
                        "来自 ${message.contextRelaySender}\n\n${boundedContextText(message.contextBody)}"
                    } else {
                        boundedContextText(message.contextBody)
                    }
                    open = isExpanded()
                    expandable = message.contextCanExpand()
                    this.onToggle = onToggle
                    bodyExpanded = isBodyExpanded()
                    this.onToggleBody = onToggleBody
                    maxBodyLines = 8
                }
            }
        }
        return
    }
    if (isWebTimeline && message.attachmentId != null) {
        val attachmentId = message.attachmentId
        View {
            attr {
                width((pagerData.pageViewWidth - 36f).coerceAtLeast(0f))
                height(220f)
                marginBottom(12f)
                borderRadius(8f)
                backgroundColor(theme.surfaceRaised)
                border(Border(1f, BorderStyle.SOLID, theme.border))
                justifyContentCenter()
                alignItemsCenter()
            }
            // The bytes arrive from `session/attachment` after this row is built, so the
            // lookup has to sit in a reactive closure — a read in the body runs once.
            vif({ attachmentDataUrl(attachmentId) != null }) {
                Image {
                    attr {
                        src(attachmentDataUrl(attachmentId).orEmpty())
                        width((pagerData.pageViewWidth - 40f).coerceAtLeast(0f))
                        height(216f)
                        resizeCover()
                    }
                }
            }
            velse {
                Text {
                    attr {
                        text("图片加载中")
                        fontSize(12f)
                        color(theme.textMuted)
                    }
                }
            }
        }
        return
    }
    if (isWebTimeline && message.isReasoning) {
        View {
            attr {
                width(pagerData.pageViewWidth - 36f)
                marginBottom(12f)
            }
            DshDisclosureRow {
                attr {
                    title = "Think"
                    iconAsset = "think.svg"
                    summary = message.content.dshReasoningSummary(message.streaming)
                    body = message.content
                    open = isExpanded()
                    expandable = message.content.isNotEmpty()
                    this.onToggle = onToggle
                    bodyExpanded = isBodyExpanded()
                    this.onToggleBody = onToggleBody
                    maxBodyLines = 8
                }
            }
        }
        return
    }
    if (isWebTimeline && message.remoteTool?.kind == DshRemoteToolKind.SKILL) {
        val remoteTool = message.remoteTool
        View {
            attr {
                width((pagerData.pageViewWidth - 36f).coerceAtLeast(0f))
                marginBottom(12f)
            }
            DshDisclosureRow {
                attr {
                    title = "Skill"
                    iconAsset = "tool-skill.svg"
                    summary = remoteTool.summary
                    errorSummary = message.toolError
                    body = message.content
                    open = isExpanded()
                    expandable = message.content.isNotEmpty()
                    this.onToggle = onToggle
                    bodyExpanded = isBodyExpanded()
                    this.onToggleBody = onToggleBody
                    maxBodyLines = 8
                    chrome = true
                    running = message.toolRunning
                    this.onLongPress = onLongPress
                }
            }
        }
        return
    }
    if (isWebTimeline && message.role == DshMessageRole.TOOL) {
        val remoteTool = message.remoteTool
        val isRemoteSpecial = remoteTool?.kind == DshRemoteToolKind.ASK_QUESTION ||
            remoteTool?.kind == DshRemoteToolKind.TODO
        val rawBody = remoteTool?.output?.takeIf { it.isNotEmpty() }
            ?: remoteTool?.body?.takeIf { it.isNotEmpty() }
            ?: remoteTool?.input?.takeIf { it.isNotEmpty() }
            ?: message.content
        val toolBody = if (remoteTool?.kind == DshRemoteToolKind.ASK_QUESTION) {
            dshAskReadableBody(remoteTool.input, rawBody).ifEmpty { "已回答" }
        } else {
            rawBody
        }
        val trimmedBody = toolBody.trimStart()
        val isJson = !isRemoteSpecial &&
            (trimmedBody.startsWith("{") || trimmedBody.startsWith("["))
        val cardLabel = remoteTool?.title ?: when (message.toolCardType) {
            DshToolCardType.TERMINAL -> "Bash"
            DshToolCardType.READ -> "Read"
            DshToolCardType.DIFF -> "Diff"
            DshToolCardType.SEARCH -> "Search"
            DshToolCardType.WEB -> "Web"
            DshToolCardType.JSON -> "JSON"
            DshToolCardType.GENERIC -> message.toolName ?: "工具"
        }
        val summary = remoteTool?.summary?.takeUnless { it.dshLooksLikeJson() }
            ?: if (remoteTool?.kind == DshRemoteToolKind.ASK_QUESTION) "已完成" else
                toolBody.lineSequence().firstOrNull().orEmpty().takeUnless { it.dshLooksLikeJson() }.orEmpty()
        View {
            attr {
                width((pagerData.pageViewWidth - 36f).coerceAtLeast(0f))
                marginBottom(12f)
            }
            DshDisclosureRow {
                attr {
                    title = if (cardLabel.dshLooksLikeJson()) (remoteTool?.toolName ?: "工具") else cardLabel
                    iconAsset = remoteTool?.iconAsset() ?: message.toolCardType.iconAsset()
                    this.summary = summary
                    errorSummary = message.toolError
                    body = if (isJson) "" else toolBody
                    jsonContent = if (isJson) toolBody else ""
                    open = isExpanded()
                    expandable = true
                    this.onToggle = onToggle
                    bodyExpanded = isBodyExpanded()
                    this.onToggleBody = onToggleBody
                    maxBodyLines = 8
                    this.isJsonNodeExpanded = isJsonNodeExpanded
                    this.onToggleJsonNode = onToggleJsonNode
                    this.onLongPress = onLongPress
                    chrome = true
                    running = message.toolRunning
                }
            }
        }
        return
    }
        View {
            attr {
                flexDirectionColumn()
                alignItems(if (isUser) FlexAlign.FLEX_END else FlexAlign.FLEX_START)
                marginBottom(18f)
        }
        Text {
            attr {
                text(when (message.role) {
                    DshMessageRole.USER -> "你"
                    DshMessageRole.TOOL -> message.toolName ?: "工具"
                    DshMessageRole.ERROR -> "错误"
                    DshMessageRole.ASSISTANT -> "DeepSeek"
                })
                fontSize(11f)
                color(if (isError) theme.danger else theme.textMuted)
                marginBottom(5f)
            }
        }
        View {
            attr {
                if (!isUser && !isError) {
                    width((pagerData.pageViewWidth - 36f).coerceAtMost(620f).coerceAtLeast(0f))
                }
                maxWidth(620f)
                padding(if (isUser) 10f else 0f, if (isUser) 14f else 0f, if (isUser) 10f else 0f, if (isUser) 14f else 0f)
                borderRadius(if (isUser) 18f else 0f)
                backgroundColor(
                    when {
                        isUser -> theme.userBubble
                        isError -> theme.errorBubble
                        else -> Color(0x00FFFFFF)
                    },
                )
            }
            if (isUser || isError) {
                if (message.attachments.isNotEmpty()) {
                    DshBubbleAttachments(message.attachments, attachmentDataUrl, onOpenAttachment)
                }
                if (message.content.isNotEmpty()) {
                    Text {
                        attr {
                            text(message.content)
                            lines(Int.MAX_VALUE)
                            fontSize(15f)
                            color(if (isUser) theme.userBubbleText else theme.dangerText)
                        }
                    }
                }
            } else {
                View {
                    attr {
                        flexDirectionColumn()
                    }
                    DshMarkdown {
                        attr {
                            contentWidth = (pagerData.pageViewWidth - 36f).coerceAtLeast(0f)
                            val raw = contentProvider?.invoke() ?: message.content
                            val live = pageStreaming()
                            content = raw
                            liveContent = contentProvider
                            streamingProvider = pageStreaming
                            streaming = live
                            darkMode = theme.isDark
                        }
                    }
                    vif({ pageStreaming() && (contentProvider?.invoke() ?: message.content).isNotEmpty() }) {
                        Text {
                            attr {
                                text(DshStreamingMarkdown.CURSOR)
                                fontSize(14f)
                                color(theme.accent)
                                marginTop(2f)
                            }
                        }
                    }
                }
            }
        }
    }
}
