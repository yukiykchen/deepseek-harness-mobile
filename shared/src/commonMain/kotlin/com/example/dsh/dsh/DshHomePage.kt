package com.example.dsh.dsh

import com.example.dsh.base.BasePager
import com.example.dsh.base.bridgeModule
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.scrollToPosition
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextAreaView
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.KeyboardParams
import com.tencent.kuikly.core.views.ListContentView
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.ScrollParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val SESSION_CACHE_WARM_LIMIT = 7
private const val SESSION_CACHE_WARM_INTERVAL_MS = 16
private const val SESSION_CACHE_WARM_START_DELAY_MS = 600
private const val CONVERSATION_PANEL_CACHE_LIMIT = 8
private const val SCROLL_SETTLE_ATTEMPTS = 6
private val SCROLL_SETTLE_DELAYS_MS = intArrayOf(0, 16, 32, 64, 120, 200)
private const val FOLLOW_LIST_SLACK_PX = 72f

/** First usable DSH surface: local sessions, streaming Markdown, and a composer. */
@Page("home")
internal class DshHomePage : BasePager() {
    private var repository: DshRepository? = null
    private var localStore: DshLocalStore? = null
    private var engineModule: DshEngineModule? = null
    private var engineReady = false
    private var relayEngineEndpoint = ""
    private var pendingApiKey = ""
    private var connectionMode by observable(DshConnectionMode.RELAY)
    private val sshMode: Boolean
        get() = connectionMode == DshConnectionMode.SSH
    private val isRemoteHost: Boolean
        get() = connectionMode == DshConnectionMode.RELAY || connectionMode == DshConnectionMode.SSH
    private var remoteProfileId by observable(DshSessionScope.DEFAULT_REMOTE_PROFILE_ID)
    private var sshHost by observable("")
    private var sshUser by observable("")
    private var sshPort by observable("22")
    private var sshDshPort by observable("3080")
    private var sshKeyId by observable("")
    private var sshFingerprint by observable("")
    private var sshKeyLabel by observable("未导入私钥")
    private var sshKeyPassphrase by observable("")
    private var sshSettingsVisible by observable(false)
    private var sshSettingsBusy by observable(false)
    private var sshSettingsError by observable("")
    private val sessionScope: DshSessionScope
        get() = DshSessionScope(connectionMode, remoteProfileId)
    private val activeConnectionId: String
        get() = sessionScope.storageKey

    private var sessions by observableList<DshSession>()
    private val visibleSessions by observableList<DshSession>()
    private var messages by observableList<DshMessage>()
    private var conversationPanelIds by observableList<String>()
    private var activeSessionId by observable("session-1")
    private var preferBlankHomeOnNextLoad = true
    private var draft by observable("")
    private var streaming by observable(false)
    private var stopButtonVisible by observable(false)
    private var streamingAssistantContent by observable("")
    private var keyboardHeight by observable(0f)
    private var keyboardAnimation by observable(Animation.easeInOut(ANIMATION_DURATION_S))
    private var connectionLabel by observable("本地内核启动中")
    private var apiKeyDraft by observable("")
    private var credentialSetupVisible by observable(false)
    private var credentialSetupBusy by observable(false)
    private var credentialSetupError by observable("")
    private var credentialSetupTitle by observable("添加一个 API Key 开始使用")
    private var sessionDrawerVisible by observable(false)
    private var sessionDrawerAnimated by observable(false)
    private var sessionDrawerMaskAnimated by observable(false)
    private var sessionDrawerMaskAnimation by observable(Animation.linear(0f))
    private var modelPickerVisible by observable(false)
    private var modelPickerBusy by observable(false)
    private var modelPickerError by observable("")
    private var selectedModelLabel by observable("选择模型")
    private var modelOptions by observableList<DshModelOption>()
    private var attachmentMenuVisible by observable(false)
    private var voiceActive by observable(false)
    private var topBarRef: ViewRef<com.tencent.kuikly.core.views.DivView>? = null
    private var inputView: TextAreaView? = null
    private var apiKeyInputView: InputView? = null
    private var streamHandle: DshStreamHandle? = null
    private val messageScrollerRefs = mutableMapOf<String, ViewRef<ListView<*, *>>>()
    private val messageRowRefs = mutableMapOf<String, ViewRef<com.tencent.kuikly.core.views.DivView>>()
    private var historyRequestGeneration = 0
    private val sessionMessageStates = mutableMapOf<String, ObservableList<DshMessage>>()
    private val conversationListEpochs = mutableMapOf<String, Int>()
    private var conversationListEpoch by observable(0)
    private val sessionMessageReady = mutableSetOf<String>()
    private val pendingSessionSelections = mutableSetOf<String>()
    private val localReadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingLocalMessageReads = mutableSetOf<String>()
    private val sessionCacheStates = mutableMapOf<String, DshSessionCacheState>()
    private var inputFocused = false
    private var streamingAssistantId by observable("")
    // The root id guards callbacks from an old request; the visible id points
    // at the current text segment between ordered tool cards.
    private var streamingAssistantRootId = ""
    private var streamingAssistantSegment = 0
    // Last completed assistant when the current prompt was sent. Resync must
    // not graft the new stream onto that bubble.
    private var streamingTurnAnchorAssistantId = ""
    private var streamingReasoningId = ""
    private var streamingReasoningContent = ""
    private val pendingAssistantDelta = StringBuilder()
    private var assistantFlushScheduled = false
    private var scrollSettleGeneration = 0
    private var followListTail = true
    private var perfTraceSequence = 0
    private var preloadTraceSequence = 0
    private val connectionCoordinator = DshConnectionCoordinator()
    private val webDisclosureStates = mutableMapOf<String, Boolean>()
    private val webBodyDisclosureStates = mutableMapOf<String, Boolean>()
    private val webJsonNodeStates = mutableMapOf<String, Boolean>()
    private var webDisclosureRevision by observable(0)
    private var attachmentRevision by observable(0)
    private val cachedAttachmentDataUrls = mutableMapOf<String, String>()
    private val pendingAttachmentReads = mutableSetOf<String>()
    private var queueDockExpanded by observable(false)
    private val queueItems by observableList<DshQueueItem>()
    private var queueActionBusy by observable(false)
    private val jobItems by observableList<DshJobItem>()
    private var jobsPanelExpanded by observable(false)
    private var jobsNow by observable(0L)
    private var jobsClockScheduled by observable(false)
    private val workspaceGroups by observableList<DshWorkspaceGroup>()
    private val skills by observableList<DshSkill>()
    private var goalSnapshot by observable<DshGoalSnapshot?>(null)
    private var goalActionBusy by observable(false)
    private var goalActionError by observable("")
    private var queueEditingId by observable("")
    private var queueEditingText by observable("")
    private var sessionRunning by observable(false)
    private var turnElapsedMs by observable(0L)
    private var turnStatusMark: TimeMark? = null
    private var turnStatusTickerGeneration = 0
    private var turnStatusClockBucket = -1L
    private var workspaceBrowserVisible by observable(false)
    private var workspaceBrowserPath by observable("")
    private var workspaceBrowserHome by observable("")
    private var workspaceBrowserBusy by observable(false)
    private var workspaceBrowserError by observable("")
    private var workspaceBrowserNewName by observable("")
    private val workspaceDirectoryEntries by observableList<DshDirectoryEntry>()
    private var workspaceRenameTargetId by observable("")
    private var workspaceRenameDraft by observable("")
    private var workspaceDeleteTargetId by observable("")
    private var workspaceActionBusy by observable(false)
    private var workspaceActionError by observable("")
    private var pendingApproval by observable<DshPendingApproval?>(null)
    private var pendingQuestion by observable<DshPendingQuestion?>(null)
    private var interactionBusy by observable(false)
    private val selectedQuestionOptions by observableList<String>()
    private var questionCustom by observable("")
    private var questionIndex by observable(0)
    private var questionError by observable("")
    private val questionDrafts = mutableMapOf<Int, DshQuestionDraft>()

    /**
     * 系统返回键统一入口：按 z-order 关闭最顶层覆盖层，
     * 所有覆盖层都关闭后再通过 RouterModule.closePage() 结束当前页面。
     */
    internal val overlayBackCallback = object : BackPressCallback() {
        override fun handleOnBackPressed() {
            when {
                workspaceDeleteTargetId.isNotEmpty() -> {
                    workspaceDeleteTargetId = ""
                    workspaceActionError = ""
                }
                workspaceRenameTargetId.isNotEmpty() -> {
                    workspaceRenameTargetId = ""
                    workspaceActionError = ""
                }
                workspaceBrowserVisible -> workspaceBrowserVisible = false
                sshSettingsVisible -> updateSshSettingsVisibility(false)
                credentialSetupVisible -> closeCredentialSettings()
                modelPickerVisible -> modelPickerVisible = false
                attachmentMenuVisible -> attachmentMenuVisible = false
                sessionDrawerVisible -> closeSessionDrawer()
                else -> acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            }
        }
    }

    override fun created() {
        super.created()
        val startedAt = TimeSource.Monotonic.markNow()
        perfLog("startup.created.begin", startedAt)
        val databaseDir = pageData.params.optString("databaseDir")
        if (databaseDir.isNotEmpty()) {
            localStore = runCatching {
                createDshLocalStore("$databaseDir/dsh.db")
            }.getOrNull()
        }
        connectionMode = when (pageData.params.optString("connectionMode")) {
            "relay" -> DshConnectionMode.RELAY
            "ssh", "remote" -> DshConnectionMode.SSH
            else -> DshConnectionMode.RELAY
        }
        remoteProfileId = pageData.params.optString("profileId").ifEmpty { DshSessionScope.DEFAULT_REMOTE_PROFILE_ID }
        loadSshConfig()
        restoreCachedSessions()
        if (sessions.isEmpty()) {
            sessionMessageStates[activeSessionId] = messages
            ensureConversationPanel(activeSessionId)
        }
        perfLog("startup.restoreCachedSessions.done", startedAt)
        ensureConversationPanel(activeSessionId)
        preloadAllSessionMessages()
        perfLog("startup.preloadAllSessionMessages.scheduled", startedAt)
        loadApiKeyAsync()
        setTimeout(pagerId, SESSION_CACHE_WARM_START_DELAY_MS) {
            warmRecentSessionCache(scrollToEndAfterLoad = false)
        }
        setTimeout(pagerId, 0) { startConnection() }
        getBackPressHandler().addCallback(overlayBackCallback)
        perfLog("startup.created.end", startedAt)
    }

    override fun pageWillDestroy() {
        stopCurrentEngine()
        localReadScope.cancel()
        super.pageWillDestroy()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val wide = pagerData.pageViewWidth >= 720f
        return {
            ctx.perfLog("body.builder.begin")
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(Color(BG))
                    paddingTop(pagerData.statusBarHeight)
                }

                View {
                    ref { ctx.topBarRef = it }
                    attr {
                        height(58f)
                        zIndex(3)
                    }
                    DshTopBar(
                        title = { ctx.sessions.firstOrNull { it.id == ctx.activeSessionId }?.title ?: "DeepSeek Harness" },
                        connection = { ctx.connectionLabel },
                    )
                }

                View {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        // Push the conversation with the drawer, leaving the
                        // dimmed right edge visible like the reference UI.
                        transform(Translate(
                            0f,
                            offsetX = if (ctx.sessionDrawerAnimated) {
                                (pagerData.pageViewWidth - 44f).coerceAtMost(340f)
                            } else {
                                0f
                            },
                        ))
                        animation(Animation.easeOut(ANIMATION_DURATION_S), ctx.sessionDrawerAnimated)
                    }
                    if (wide) {
                        ctx.perfLog("body.conversation.begin wide=true panels=${ctx.conversationPanelIds.size}")
                        View {
                            attr {
                                flex(1f)
                                flexDirectionRow()
                                backgroundColor(Color(BG))
                            }
                            vif({ ctx.isRemoteHost }) {
                                DshSessionRail(
                                    sessions = { ctx.visibleSessions },
                                    activeId = { ctx.activeSessionId },
                                    compact = false,
                                    onSelect = { id ->
                                        ctx.closeSessionDrawer()
                                        setTimeout(ctx.pagerId, 0) { ctx.selectSession(id) }
                                    },
                                )
                            }
                            val centerWidth = if (ctx.isRemoteHost) {
                                (ctx.pagerData.pageViewWidth - 236f - 280f).coerceAtLeast(360f)
                            } else {
                                ctx.pagerData.pageViewWidth
                            }
                            DshConversation(
                                conversationIds = { ctx.conversationPanelIds },
                                activeConversationId = { ctx.activeSessionId },
                                messagesForSession = { ctx.sessionMessageState(it) },
                                streaming = { ctx.streaming },
                                streamingMessageId = { ctx.streamingAssistantId },
                                streamingContent = { ctx.streamingAssistantContent },
                                scrollerRef = { id, ref -> ctx.messageScrollerRefs[id] = ref },
                                messageRef = { sessionId, messageId, ref ->
                                    ctx.messageRowRefs[ctx.messageRowKey(sessionId, messageId)] = ref
                                },
                                draft = { ctx.draft },
                                skills = { ctx.skills },
                                onPickSkill = { ctx.draft = "/$it " },
                                keyboardHeight = { ctx.keyboardHeight },
                                stopButtonVisible = { ctx.stopButtonVisible },
                                inputRef = { ctx.inputView = it.view },
                                onInputFocusChange = { ctx.inputFocused = it },
                                onDraftChange = { ctx.draft = it },
                                keyboardAnimation = { ctx.keyboardAnimation },
                                onKeyboardHeightChange = { ctx.updateKeyboard(it) },
                                onSend = { ctx.sendDraft() },
                                onStop = { ctx.stopStream() },
                                onDismissKeyboard = { ctx.dismissKeyboard() },
                                onUserListScroll = { ctx.onConversationUserScroll(it) },
                                modelLabel = { ctx.selectedModelLabel },
                                attachmentMenuVisible = { ctx.attachmentMenuVisible },
                                voiceActive = { ctx.voiceActive },
                                onOpenModels = { ctx.openModelPicker() },
                                onToggleAttachments = {
                                    ctx.dismissKeyboard()
                                    ctx.attachmentMenuVisible = !ctx.attachmentMenuVisible
                                },
                                onToggleVoice = { ctx.toggleVoice() },
                                isWebTimeline = { ctx.isRemoteHost },
                                isDisclosureExpanded = { ctx.isWebDisclosureExpanded(it) },
                                onToggleDisclosure = { ctx.toggleWebDisclosure(it) },
                                isBodyDisclosureExpanded = { ctx.isWebBodyDisclosureExpanded(it) },
                                onToggleBodyDisclosure = { ctx.toggleWebBodyDisclosure(it) },
                                isJsonNodeExpanded = { messageId, nodeId ->
                                    ctx.isWebJsonNodeExpanded(messageId, nodeId)
                                },
                                onToggleJsonNode = { messageId, nodeId ->
                                    ctx.toggleWebJsonNode(messageId, nodeId)
                                },
                                onCopyToolContent = {
                                    ctx.bridgeModule.copyToPasteboard(it)
                                    ctx.bridgeModule.toast("已复制")
                                },
                                attachmentDataUrl = { ctx.attachmentDataUrl(it) },
                                queueItems = { ctx.queueItems },
                                jobItems = { ctx.jobItems },
                                goal = { ctx.goalSnapshot },
                                goalActionBusy = { ctx.goalActionBusy },
                                goalActionError = { ctx.goalActionError },
                                onPauseGoal = { ctx.pauseGoal() },
                                onResumeGoal = { ctx.resumeGoal() },
                                onEditGoal = { text, done -> ctx.editGoal(text, done) },
                                onClearGoal = { ctx.clearGoal() },
                                jobsPanelExpanded = { ctx.jobsPanelExpanded },
                                jobsNow = { ctx.jobsNow },
                                onToggleJobsPanel = { ctx.toggleJobsPanel() },
                                queueExpanded = { ctx.queueDockExpanded },
                                queueEditingId = { ctx.queueEditingId },
                                queueActionBusy = { ctx.queueActionBusy },
                                queueEditingText = { ctx.queueEditingText },
                                sessionRunning = { ctx.sessionRunning },
                                isBlankConversation = { ctx.isBlankSession() },
                                conversationListEpoch = { ctx.conversationListEpochFor(it) },
                                turnReconnecting = { isReconnectLabel(ctx.connectionLabel) },
                                turnElapsedMs = { ctx.turnElapsedMs },
                                onToggleQueue = { ctx.queueDockExpanded = !ctx.queueDockExpanded },
                                onEditQueueItem = { ctx.editQueueItem(it) },
                                onQueueEditingTextChange = { ctx.queueEditingText = it },
                                onSaveQueueItem = { ctx.saveQueueItem(it) },
                                onCancelQueueItemEdit = { ctx.cancelQueueItemEdit() },
                                onRemoveQueueItem = { ctx.removeQueueItem(it) },
                                onSteerQueueItem = { ctx.steerQueueItem(it) },
                                pendingApproval = { ctx.pendingApproval },
                                pendingQuestion = { ctx.pendingQuestion },
                                interactionBusy = { ctx.interactionBusy },
                                selectedQuestionOptions = { ctx.selectedQuestionOptions },
                                questionCustom = { ctx.questionCustom },
                                questionIndex = { ctx.questionIndex },
                                questionError = { ctx.questionError },
                                onAnswerApproval = { ctx.answerApproval(it) },
                                onToggleQuestionOption = { ctx.toggleQuestionOption(it) },
                                onQuestionCustomChange = { ctx.updateQuestionCustom(it) },
                                onQuestionNavigate = { ctx.navigateQuestion(it) },
                                onQuestionSkip = { ctx.skipQuestion() },
                                onSubmitQuestion = { ctx.submitQuestion() },
                                availableWidth = centerWidth,
                            )
                            vif({ ctx.isRemoteHost }) {
                                DshSessionDetailsPanel(
                                    title = { ctx.sessions.firstOrNull { it.id == ctx.activeSessionId }?.title ?: "尚无标题" },
                                    cwd = { ctx.sessions.firstOrNull { it.id == ctx.activeSessionId }?.cwd ?: "" },
                                    modelLabel = { ctx.selectedModelLabel },
                                    agentPreset = { ctx.sessions.firstOrNull { it.id == ctx.activeSessionId }?.agentPreset.orEmpty() },
                                    running = { ctx.sessionRunning },
                                    queueCount = { ctx.queueItems.size },
                                    jobCount = { ctx.jobItems.size },
                                )
                            }
                        }
                        ctx.perfLog("body.conversation.end wide=true")
                    } else {
                        ctx.perfLog("body.conversation.begin wide=false panels=${ctx.conversationPanelIds.size}")
                        DshConversation(
                            conversationIds = { ctx.conversationPanelIds },
                            activeConversationId = { ctx.activeSessionId },
                            messagesForSession = { ctx.sessionMessageState(it) },
                            streaming = { ctx.streaming },
                            streamingMessageId = { ctx.streamingAssistantId },
                            streamingContent = { ctx.streamingAssistantContent },
                            scrollerRef = { id, ref -> ctx.messageScrollerRefs[id] = ref },
                            messageRef = { sessionId, messageId, ref ->
                                ctx.messageRowRefs[ctx.messageRowKey(sessionId, messageId)] = ref
                            },
                            draft = { ctx.draft },
                            skills = { ctx.skills },
                            onPickSkill = { ctx.draft = "/$it " },
                            keyboardHeight = { ctx.keyboardHeight },
                            stopButtonVisible = { ctx.stopButtonVisible },
                            inputRef = { ctx.inputView = it.view },
                            onInputFocusChange = { ctx.inputFocused = it },
                            onDraftChange = { ctx.draft = it },
                            keyboardAnimation = { ctx.keyboardAnimation },
                            onKeyboardHeightChange = { ctx.updateKeyboard(it) },
                            onSend = { ctx.sendDraft() },
                            onStop = { ctx.stopStream() },
                            onDismissKeyboard = { ctx.dismissKeyboard() },
                            onUserListScroll = { ctx.onConversationUserScroll(it) },
                            modelLabel = { ctx.selectedModelLabel },
                            attachmentMenuVisible = { ctx.attachmentMenuVisible },
                            voiceActive = { ctx.voiceActive },
                            onOpenModels = { ctx.openModelPicker() },
                            onToggleAttachments = {
                                ctx.dismissKeyboard()
                                ctx.attachmentMenuVisible = !ctx.attachmentMenuVisible
                            },
                            onToggleVoice = { ctx.toggleVoice() },
                            isWebTimeline = { ctx.isRemoteHost },
                            isDisclosureExpanded = { ctx.isWebDisclosureExpanded(it) },
                            onToggleDisclosure = { ctx.toggleWebDisclosure(it) },
                            isBodyDisclosureExpanded = { ctx.isWebBodyDisclosureExpanded(it) },
                            onToggleBodyDisclosure = { ctx.toggleWebBodyDisclosure(it) },
                            isJsonNodeExpanded = { messageId, nodeId ->
                                ctx.isWebJsonNodeExpanded(messageId, nodeId)
                            },
                            onToggleJsonNode = { messageId, nodeId ->
                                ctx.toggleWebJsonNode(messageId, nodeId)
                            },
                            onCopyToolContent = {
                                ctx.bridgeModule.copyToPasteboard(it)
                                ctx.bridgeModule.toast("已复制")
                            },
                            attachmentDataUrl = { ctx.attachmentDataUrl(it) },
                            queueItems = { ctx.queueItems },
                            jobItems = { ctx.jobItems },
                            goal = { ctx.goalSnapshot },
                            goalActionBusy = { ctx.goalActionBusy },
                            goalActionError = { ctx.goalActionError },
                            onPauseGoal = { ctx.pauseGoal() },
                            onResumeGoal = { ctx.resumeGoal() },
                            onEditGoal = { text, done -> ctx.editGoal(text, done) },
                            onClearGoal = { ctx.clearGoal() },
                            jobsPanelExpanded = { ctx.jobsPanelExpanded },
                            jobsNow = { ctx.jobsNow },
                            onToggleJobsPanel = { ctx.toggleJobsPanel() },
                            queueExpanded = { ctx.queueDockExpanded },
                            queueEditingId = { ctx.queueEditingId },
                            queueActionBusy = { ctx.queueActionBusy },
                            queueEditingText = { ctx.queueEditingText },
                            sessionRunning = { ctx.sessionRunning },
                            isBlankConversation = { ctx.isBlankSession() },
                            conversationListEpoch = { ctx.conversationListEpochFor(it) },
                            turnReconnecting = { isReconnectLabel(ctx.connectionLabel) },
                            turnElapsedMs = { ctx.turnElapsedMs },
                            onToggleQueue = { ctx.queueDockExpanded = !ctx.queueDockExpanded },
                            onEditQueueItem = { ctx.editQueueItem(it) },
                            onQueueEditingTextChange = { ctx.queueEditingText = it },
                            onSaveQueueItem = { ctx.saveQueueItem(it) },
                            onCancelQueueItemEdit = { ctx.cancelQueueItemEdit() },
                            onRemoveQueueItem = { ctx.removeQueueItem(it) },
                            onSteerQueueItem = { ctx.steerQueueItem(it) },
                            pendingApproval = { ctx.pendingApproval },
                            pendingQuestion = { ctx.pendingQuestion },
                            interactionBusy = { ctx.interactionBusy },
                            selectedQuestionOptions = { ctx.selectedQuestionOptions },
                            questionCustom = { ctx.questionCustom },
                            questionIndex = { ctx.questionIndex },
                            questionError = { ctx.questionError },
                            onAnswerApproval = { ctx.answerApproval(it) },
                            onToggleQuestionOption = { ctx.toggleQuestionOption(it) },
                            onQuestionCustomChange = { ctx.updateQuestionCustom(it) },
                            onQuestionNavigate = { ctx.navigateQuestion(it) },
                            onQuestionSkip = { ctx.skipQuestion() },
                            onSubmitQuestion = { ctx.submitQuestion() },
                            availableWidth = ctx.pagerData.pageViewWidth,
                        )
                        ctx.perfLog("body.conversation.end wide=false")
                    }

                    vif({ ctx.sessionDrawerVisible }) {
                        View {
                            attr {
                                absolutePositionAllZero()
                                backgroundColor(Color(0x55000000))
                                opacity(if (ctx.sessionDrawerMaskAnimated) 1f else 0f)
                                animation(ctx.sessionDrawerMaskAnimation, ctx.sessionDrawerMaskAnimated)
                            }
                            event { click { ctx.closeSessionDrawer() } }
                        }
                    }
                }

                vif({ ctx.sessionDrawerVisible }) {
                    DshSessionDrawer(
                        sessions = { ctx.visibleSessions },
                        workspaceGroups = { ctx.workspaceGroups },
                        isWebTimeline = { ctx.isRemoteHost },
                        activeId = { ctx.activeSessionId },
                        animated = { ctx.sessionDrawerAnimated },
                        onClose = { ctx.closeSessionDrawer() },
                        onOpenSettings = { ctx.openConnectionSettings() },
                        onNewSession = { ctx.createSession() },
                        onSelect = { id ->
                            ctx.closeSessionDrawer()
                            setTimeout(ctx.pagerId, 0) {
                                ctx.selectSession(id)
                            }
                        },
                    )
                }

                vif({ ctx.modelPickerVisible }) {
                    DshModelPicker(
                        options = { ctx.modelOptions },
                        busy = { ctx.modelPickerBusy },
                        error = { ctx.modelPickerError },
                        onClose = { ctx.modelPickerVisible = false },
                        onSelect = { ctx.selectModel(it) },
                    )
                }

                vif({ ctx.credentialSetupVisible }) {
                    DshCredentialSetupModal(
                        title = { ctx.credentialSetupTitle },
                        busy = { ctx.credentialSetupBusy },
                        error = { ctx.credentialSetupError },
                        inputRef = {
                            ctx.apiKeyInputView = it.view
                            ctx.apiKeyInputView?.setText(ctx.apiKeyDraft)
                        },
                        onApiKeyChange = {
                            ctx.apiKeyDraft = it
                            ctx.credentialSetupError = ""
                        },
                        onSave = { ctx.saveDeepSeekApiKey() },
                        onClose = { ctx.closeCredentialSettings() },
                    )
                }
                vif({ ctx.sshSettingsVisible }) {
                    DshConnectionSettingsModal(
                        sshMode = { ctx.sshMode },
                        host = { ctx.sshHost },
                        user = { ctx.sshUser },
                        port = { ctx.sshPort },
                        dshPort = { ctx.sshDshPort },
                        keyLabel = { ctx.sshKeyLabel },
                        keyPassphrase = { ctx.sshKeyPassphrase },
                        busy = { ctx.sshSettingsBusy },
                        error = { ctx.sshSettingsError },
                        onModeChange = { ctx.setConnectionMode(it) },
                        onHostChange = { ctx.sshHost = it; ctx.sshSettingsError = "" },
                        onUserChange = { ctx.sshUser = it; ctx.sshSettingsError = "" },
                        onPortChange = { ctx.sshPort = it; ctx.sshSettingsError = "" },
                        onDshPortChange = { ctx.sshDshPort = it; ctx.sshSettingsError = "" },
                        onPickKey = { ctx.pickSshKey() },
                        onPassphraseChange = { ctx.sshKeyPassphrase = it },
                        onTrustFingerprint = { ctx.trustSshFingerprint() },
                        onSave = { ctx.saveConnectionSettings() },
                        onClose = { ctx.updateSshSettingsVisibility(false) },
                        onOpenApiKey = {
                            ctx.updateSshSettingsVisibility(false)
                            ctx.openCredentialSettings()
                        },
                    )
                }
                vif({ ctx.workspaceBrowserVisible && ctx.isRemoteHost }) {
                    DshWorkspaceBrowserModal(
                        path = { ctx.workspaceBrowserPath },
                        home = { ctx.workspaceBrowserHome },
                        entries = { ctx.workspaceDirectoryEntries },
                        busy = { ctx.workspaceBrowserBusy },
                        error = { ctx.workspaceBrowserError },
                        newName = { ctx.workspaceBrowserNewName },
                        onDirectorySelect = { ctx.loadDirectory(it) },
                        onNewNameChange = { ctx.workspaceBrowserNewName = it },
                        onCreateDirectory = { ctx.createRemoteDirectory() },
                        onAdopt = { ctx.adoptCurrentDirectoryAsWorkspace() },
                        onClose = { ctx.workspaceBrowserVisible = false },
                    )
                }
                vif({ ctx.workspaceRenameTargetId.isNotEmpty() && ctx.isRemoteHost }) {
                    Modal(inWindow = true) {
                        attr {
                            absolutePositionAllZero()
                            allCenter()
                            paddingLeft(20f)
                            paddingRight(20f)
                            backgroundColor(Color(0x66000000))
                        }
                        View {
                            attr {
                                width(pagerData.pageViewWidth - 40f)
                                maxWidth(420f)
                                padding(20f)
                                borderRadius(16f)
                                backgroundColor(Color.WHITE)
                            }
                            Text { attr { text("重命名工作区"); fontSize(18f); fontWeightBold(); color(Color(0xFF1F2933)) } }
                            Input {
                                attr {
                                    height(38f)
                                    marginTop(14f)
                                    fontSize(14f)
                                    placeholder("工作区名称")
                                    placeholderColor(Color(0xFF98A1A9))
                                    text(ctx.workspaceRenameDraft)
                                }
                                event { textDidChange { ctx.workspaceRenameDraft = it.text } }
                            }
                            vif({ ctx.workspaceActionError.isNotEmpty() }) {
                                Text { attr { text(ctx.workspaceActionError); marginTop(8f); fontSize(12f); color(Color(0xFFBF3535)) } }
                            }
                            View {
                                attr { height(40f); marginTop(18f); flexDirectionRow(); justifyContentFlexEnd() }
                                Text {
                                    attr { text("取消"); width(78f); height(38f); textAlignCenter(); fontSize(14f); color(Color(0xFF7A838A)) }
                                    event { click { ctx.workspaceRenameTargetId = ""; ctx.workspaceActionError = "" } }
                                }
                                Text {
                                    attr { text(if (ctx.workspaceActionBusy) "保存中..." else "保存"); width(78f); height(38f); marginLeft(8f); textAlignCenter(); fontSize(14f); color(Color(0xFF4176E6)) }
                                    event { click { if (!ctx.workspaceActionBusy) ctx.saveWorkspaceRename() } }
                                }
                            }
                        }
                    }
                }
                vif({ ctx.workspaceDeleteTargetId.isNotEmpty() && ctx.isRemoteHost }) {
                    Modal(inWindow = true) {
                        attr {
                            absolutePositionAllZero()
                            allCenter()
                            paddingLeft(20f)
                            paddingRight(20f)
                            backgroundColor(Color(0x66000000))
                        }
                        View {
                            attr {
                                width(pagerData.pageViewWidth - 40f)
                                maxWidth(420f)
                                padding(20f)
                                borderRadius(16f)
                                backgroundColor(Color.WHITE)
                            }
                            Text { attr { text("删除工作区注册?"); fontSize(18f); fontWeightBold(); color(Color(0xFF1F2933)) } }
                            Text {
                                attr {
                                    text("只会从列表移除注册，不会删除目录、会话或日志。")
                                    marginTop(8f)
                                    fontSize(13f)
                                    lineHeight(20f)
                                    color(Color(0xFF68737D))
                                }
                            }
                            vif({ ctx.workspaceActionError.isNotEmpty() }) {
                                Text { attr { text(ctx.workspaceActionError); marginTop(8f); fontSize(12f); color(Color(0xFFBF3535)) } }
                            }
                            View {
                                attr { height(40f); marginTop(18f); flexDirectionRow(); justifyContentFlexEnd() }
                                Text {
                                    attr { text("取消"); width(78f); height(38f); textAlignCenter(); fontSize(14f); color(Color(0xFF7A838A)) }
                                    event { click { ctx.workspaceDeleteTargetId = ""; ctx.workspaceActionError = "" } }
                                }
                                Text {
                                    attr { text(if (ctx.workspaceActionBusy) "删除中..." else "删除注册"); width(112f); height(38f); marginLeft(8f); textAlignCenter(); fontSize(14f); color(Color(0xFFD25A5A)) }
                                    event { click { if (!ctx.workspaceActionBusy) ctx.confirmWorkspaceDelete() } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        topBarRef?.view?.event {
            click {
                this@DshHomePage.dismissKeyboard()
                this@DshHomePage.openSessionDrawer()
            }
        }
        addTaskWhenPagerUpdateLayoutFinish {
            refreshMountedSessionRenderTrees()
        }
    }

    private fun openSessionDrawer() {
        if (sessionDrawerVisible) return
        // Mount transparent first, then start drawer and mask on the same frame.
        sessionDrawerMaskAnimation = Animation.easeInOut(0.24f)
        sessionDrawerMaskAnimated = false
        sessionDrawerAnimated = false
        sessionDrawerVisible = true
        setTimeout(pagerId, 16) {
            sessionDrawerAnimated = true
            sessionDrawerMaskAnimated = true
        }
        setTimeout(pagerId, ANIMATION_DURATION_MS) {
            warmRecentSessionCache(scrollToEndAfterLoad = false)
        }
    }

    private fun closeSessionDrawer() {
        if (!sessionDrawerVisible) return
        // Reverse the opening transition: fade the mask out while the drawer closes.
        sessionDrawerMaskAnimation = Animation.easeInOut(ANIMATION_DURATION_S)
        sessionDrawerMaskAnimated = false
        sessionDrawerAnimated = false
        setTimeout(pagerId, ANIMATION_DURATION_MS) {
            sessionDrawerVisible = false
        }
    }

    private fun refreshVisibleSessions() {
        syncVisibleSessions(sessions, visibleSessions)
    }

    private fun loadRepository(preferredSessionId: String? = null) {
        val hostRepository = repository ?: return
        hostRepository.loadSessions({ loaded ->
            if (!connectionCoordinator.isActive(connectionMode)) return@loadSessions
            val loadedIds = loaded.map { it.id }.toSet()
            sessions.map { it.id }
                .filterNot { loadedIds.contains(it) }
                .forEach {
                    sessionMessageStates.remove(it)
                    sessionCacheStates.remove(it)
                    sessionMessageReady.remove(it)
                    conversationPanelIds.remove(it)
                }
            if (isRemoteHost) {
                loaded.forEach { sessionCacheStates[it.id] = DshSessionCacheState.STALE }
            }
            sessions.clear()
            sessions.addAll(loaded)
            refreshVisibleSessions()
            runCatching { localStore?.replaceSessions(activeConnectionId, loaded) }
            preloadAllSessionMessages()
            connectionLabel = if (loaded.isEmpty()) "已连接 · 无会话" else "已连接 · 正在同步远程历史"
            if (loaded.isNotEmpty()) {
                val preferBlankHome = preferBlankHomeOnNextLoad
                preferBlankHomeOnNextLoad = false
                val nextId = if (preferBlankHome) {
                    loaded.firstOrNull { it.blank }?.id
                } else {
                    loaded.firstOrNull { it.id == preferredSessionId }?.id
                        ?: loaded.firstOrNull { !it.blank }?.id
                        ?: loaded.first().id
                }
                refreshWorkspaceGroups()
                if (nextId == null) {
                    messages = ObservableList()
                    createSession()
                    return@loadSessions
                }
                activeSessionId = nextId
                sessionRunning = loaded.firstOrNull { it.id == activeSessionId }?.running == true
                refreshQueueDock()
                refreshJobsPanel()
                refreshPendingInteractions()
                loadModels(activeSessionId)
                loadHistory(activeSessionId, scrollToEndAfterLoad = false)
                if (streaming || stopButtonVisible || sessionRunning) {
                    resyncStreamingWithHost(activeSessionId, "session-list")
                }
            } else {
                preferBlankHomeOnNextLoad = false
                messages = ObservableList()
                createSession()
            }
        }, { error ->
            if (!connectionCoordinator.isActive(connectionMode)) return@loadSessions
            connectionLabel = "内核连接失败"
            restoreCachedSessions()
            if (sessions.isEmpty()) {
                messages.clear()
                messages.add(DshMessage("load-error", DshMessageRole.ERROR, error))
            } else {
                connectionLabel = "连接失败 · 已显示缓存"
            }
        })
    }

    private fun startConnection() {
        val generation = connectionCoordinator.begin(connectionMode)
        when (connectionMode) {
            DshConnectionMode.SSH -> {
                startSshEngine(generation)
                return
            }
            DshConnectionMode.RELAY -> {
                startRelayEngine(generation)
                return
            }
            DshConnectionMode.LOCAL -> {
                connectionLabel = "本地模式已独立为 DSH Local App"
                return
            }
        }
    }

    private fun loadSshConfig() {
        val profile = runCatching { localStore?.loadRemoteProfile() }.getOrNull()
        sshHost = profile?.host.orEmpty()
        sshUser = profile?.username.orEmpty()
        sshPort = profile?.sshPort?.toString() ?: "22"
        sshDshPort = profile?.remoteDshPort?.toString() ?: "3080"
        sshKeyId = profile?.keyId.orEmpty()
        sshFingerprint = profile?.hostFingerprint.orEmpty()
        sshKeyLabel = if (sshKeyId.isEmpty()) "未导入私钥" else "已导入私钥"
    }


    private fun startRelayEngine(generation: Long) {
        if (!pageData.supportsRelayBridge) {
            connectionLabel = "扫码连接目前仅支持 Android、iOS 和 HarmonyOS"
            return
        }
        connectionLabel = "正在连接扫码电脑"
        acquireModule<DshRelayModule>(DshRelayModule.MODULE_NAME).connect { state ->
            if (!isCurrent(generation, DshConnectionMode.RELAY)) return@connect
            when (state.phase) {
                DshRelayPhase.READY -> {
                    if (state.localPort <= 0 || state.localToken.isEmpty()) return@connect
                    val endpoint = "http://127.0.0.1:${state.localPort}"
                    engineReady = true
                    connectionLabel = state.message.ifEmpty { "扫码隧道已连接" }
                    if (state.hostId.isNotEmpty()) remoteProfileId = state.hostId
                    if (relayEngineEndpoint == endpoint && repository != null) return@connect
                    relayEngineEndpoint = endpoint
                    connectRemoteEngine(endpoint, state.localToken)
                }
                DshRelayPhase.ERROR -> {
                    engineReady = false
                    relayEngineEndpoint = ""
                    connectionLabel = state.message.ifEmpty { "扫码连接失败" }
                }
                DshRelayPhase.RECONNECTING -> {
                    relayEngineEndpoint = ""
                    (repository as? DshRemoteRepository)?.stop()
                    repository = null
                    connectionLabel = "扫码连接重试中"
                    syncTurnStatusTicker()
                }
                DshRelayPhase.STOPPED -> {
                    engineReady = false
                    relayEngineEndpoint = ""
                    (repository as? DshRemoteRepository)?.stop()
                    repository = null
                    connectionLabel = "扫码连接已断开"
                }
                else -> {
                    if (state.localPort <= 0) relayEngineEndpoint = ""
                    connectionLabel = state.message.ifEmpty { "正在建立扫码隧道" }
                }
            }
        }
    }

    private fun startSshEngine(generation: Long) {
        if (sshHost.isBlank() || sshUser.isBlank() || sshKeyId.isBlank()) {
            connectionLabel = "请配置 SSH 连接"
            openConnectionSettings()
            return
        }
        val module = acquireModule<DshEngineModule>(DshEngineModule.MODULE_NAME)
        engineModule = module
        connectionLabel = "正在连接 SSH"
        module.startSsh(DshSshConfig(
            host = sshHost,
            port = sshPort.toIntOrNull() ?: 22,
            username = sshUser,
            remoteDshPort = sshDshPort.toIntOrNull() ?: 3080,
            keyId = sshKeyId,
            hostFingerprint = sshFingerprint,
            keyPassphrase = sshKeyPassphrase,
        )) { state ->
            if (!isCurrent(generation, DshConnectionMode.SSH)) return@startSsh
            when (state.phase) {
                DshSshPhase.FINGERPRINT_REQUIRED -> {
                    sshFingerprint = state.message
                    sshSettingsError = "首次连接需要确认主机指纹：${state.message}"
                    openConnectionSetup()
                }
                DshSshPhase.READY -> {
                    engineReady = true
                    connectionLabel = "正在检查远程 DSH"
                    connectRemoteEngine("http://127.0.0.1:${state.localPort}")
                }
                DshSshPhase.RECONNECTING -> connectionLabel = "SSH 重连中"
                DshSshPhase.ERROR -> {
                    engineReady = false
                    connectionLabel = "SSH 连接失败"
                    sshSettingsError = state.message
                    openConnectionSetup()
                }
                DshSshPhase.STOPPED -> {
                    engineReady = false
                    repository = null
                    connectionLabel = "SSH 已断开"
                }
                else -> connectionLabel = state.message.ifEmpty { "正在连接 SSH" }
            }
        }
    }

    private fun connectRemoteEngine(baseUrl: String, token: String = "") {
        (repository as? DshRemoteRepository)?.stop()
        repository = DshRemoteRepository(
            network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME),
            webSocket = acquireModule<DshWebSocketModule>(DshWebSocketModule.MODULE_NAME),
            connection = DshHostConnection(baseUrl, token),
            pagerId = pagerId,
            onState = { state -> handleHostRuntimeState(state) },
            onQueueSnapshot = { sessionId ->
                if (sessionId == activeSessionId) {
                    refreshQueueDock()
                    refreshPendingInteractions()
                }
            },
            onJobsSnapshot = { sessionId ->
                if (sessionId == activeSessionId) refreshJobsPanel()
            },
            onSessionStatus = { sessionId, running ->
                if (sessionId == activeSessionId) {
                    val wasRunning = sessionRunning
                    sessionRunning = running
                    if (wasRunning != running) {
                        resyncStreamingWithHost(
                            sessionId,
                            if (running) "host-session-running" else "host-session-idle",
                        )
                    }
                    syncTurnStatusTicker()
                }
            },
            onProjection = { sessionId, key, value, seq ->
                if (sessionId == activeSessionId) {
                    when (key) {
                        "title" -> {
                            val title = value.trim().removeSurrounding("\"")
                            if (title.isNotEmpty()) connectionLabel = title
                        }
                        "goal" -> goalSnapshot = parseGoalProjection(value)
                    }
                }
            },
            onSessionEvent = { sessionId, event ->
                if (sessionId == activeSessionId) {
                    when (event.type) {
                        "tool/call" -> showRunningTool(event)
                        "tool/result" -> settleRunningTool(event)
                        "user/message" -> showContextInjection(event)
                        "assistant/message" -> showAssistantBlocks(event)
                    }
                }
            },
            onRemoteEvent = { event ->
                if (activeSessionId.isNotEmpty() && isRemoteCatalogInvalidationEvent(event)) {
                    loadSkills(activeSessionId)
                    loadModels(activeSessionId)
                }
            },
            onPendingInteraction = { sessionId ->
                DshStreamLog.question("ui.pending-frame session=$sessionId active=$activeSessionId")
                if (sessionId == activeSessionId) {
                    refreshPendingInteractions()
                    loadWebTimeline(sessionId, scrollToEndAfterLoad = true)
                }
            },
        )
        loadRepository(preferredSessionId = activeSessionId)
    }

    private fun handleHostRuntimeState(state: DshHostRuntimeState) {
        if (!connectionCoordinator.isActive(connectionMode)) return
        val wasReconnecting = isReconnectLabel(connectionLabel)
        connectionLabel = when (state.phase) {
            DshHostRuntimePhase.CONNECTING -> "正在打开远程事件流"
            DshHostRuntimePhase.HOST_HANDSHAKE -> "正在检查远程 DSH"
            DshHostRuntimePhase.SYNCING -> "正在同步远程会话"
            DshHostRuntimePhase.READY -> "远程 DSH 已就绪"
            DshHostRuntimePhase.RECONNECTING -> reconnectLabel()
            DshHostRuntimePhase.ERROR -> "远程 DSH 连接失败"
            DshHostRuntimePhase.STOPPED -> "远程 DSH 已停止"
            DshHostRuntimePhase.DISCONNECTED -> "等待远程连接"
        }
        if (state.phase == DshHostRuntimePhase.READY && wasReconnecting) {
            loadRepository(preferredSessionId = activeSessionId)
        }
        syncTurnStatusTicker()
    }

    private fun connectLocalEngine(apiKey: String) {
        connectionLabel = "本地内核启动中"
        repository = DshHostRepository(
            network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME),
            sse = acquireModule<DshSseModule>(DshSseModule.MODULE_NAME),
            connection = DshHostConnection(LOCAL_ENGINE_URL),
            pagerId = pagerId,
        )
        syncLocalCredential(apiKey, 0)
    }

    private fun syncLocalCredential(apiKey: String, attempt: Int) {
        val hostRepository = repository ?: return
        hostRepository.saveDeepSeekApiKey(apiKey, {
            connectionLabel = "已连接"
            loadRepository()
        }, { error ->
            if (attempt < ENGINE_CONNECT_RETRIES) {
                connectionLabel = "本地内核启动中"
                setTimeout(pagerId, ENGINE_RETRY_DELAY_MS) {
                    syncLocalCredential(apiKey, attempt + 1)
                }
            } else {
                connectionLabel = "内核启动失败"
                messages.clear()
                messages.add(DshMessage(
                    "engine-start-error",
                    DshMessageRole.ERROR,
                    "本地 DeepSeek Harness 内核暂未就绪：$error",
                ))
            }
        })
    }

    private fun saveDeepSeekApiKey() {
        val key = apiKeyDraft.trim()
        when {
            key.isEmpty() -> {
                credentialSetupError = "请输入 API Key 后继续。"
                return
            }
            key.any { it.code !in 0x21..0x7E } -> {
                credentialSetupError = "API Key 格式错误，请检查后重试。"
                return
            }
        }
        credentialSetupBusy = true
        credentialSetupError = ""
        if (sshMode) {
            val hostRepository = repository
            if (hostRepository == null) {
                credentialSetupBusy = false
                credentialSetupError = "远程 DSH 尚未就绪"
                return
            }
            hostRepository.saveDeepSeekApiKey(key, {
                setTimeout(pagerId, 0) {
                    apiKeyDraft = ""
                    apiKeyInputView?.setText("")
                    credentialSetupBusy = false
                    updateCredentialSetupVisibility(false)
                    dismissKeyboard()
                    connectionLabel = "远程 DSH 已更新"
                    loadRepository()
                }
            }, { error ->
                setTimeout(pagerId, 0) {
                    credentialSetupBusy = false
                    credentialSetupError = "无法修改电脑端 DSH：$error"
                }
            })
            return
        }
        val saved = runCatching { localStore?.saveApiKey(key) }
        if (saved.isFailure || localStore == null) {
            credentialSetupBusy = false
            credentialSetupError = saved.exceptionOrNull()?.message ?: "本地数据库不可用"
            return
        }
        apiKeyDraft = ""
        apiKeyInputView?.setText("")
        credentialSetupBusy = false
        credentialSetupError = ""
        updateCredentialSetupVisibility(false)
        dismissKeyboard()
        pendingApiKey = key
        if (engineReady) {
            connectLocalEngine(key)
        } else {
            connectionLabel = "等待本地内核启动"
        }
    }

    private fun openCredentialSettings() {
        dismissKeyboard()
        attachmentMenuVisible = false
        //closeSessionDrawer()
        credentialSetupTitle = if (sshMode) "修改电脑端 DSH 的 API Key" else "设置 DeepSeek API Key"
        credentialSetupError = ""
        apiKeyDraft = pendingApiKey
        updateCredentialSetupVisibility(true)
    }

    private fun openConnectionSettings(preserveError: Boolean = false) {
        dismissKeyboard()
        attachmentMenuVisible = false
        if (!preserveError) sshSettingsError = ""
        updateSshSettingsVisibility(true)
    }

    private fun updateSshSettingsVisibility(visible: Boolean) {
        sshSettingsVisible = visible
        if (pageData.isAndroid || pageData.isIOS) {
            bridgeModule.setSystemBarsDimmed(visible)
        }
    }

    private fun setConnectionMode(useSsh: Boolean) {
        connectionMode = if (useSsh) DshConnectionMode.SSH else DshConnectionMode.RELAY
        sshSettingsError = ""
    }

    private fun pickSshKey() {
        bridgeModule.pickSshKey { uri ->
            if (uri.isEmpty()) return@pickSshKey
            sshSettingsBusy = true
            bridgeModule.importSshKey(uri) { keyId ->
                setTimeout(pagerId, 0) {
                    sshSettingsBusy = false
                    if (keyId.isEmpty()) {
                        sshSettingsError = "无法导入 SSH 私钥"
                    } else {
                        sshKeyId = keyId
                        sshKeyLabel = "已导入私钥"
                        sshSettingsError = ""
                    }
                }
            }
        }
    }

    private fun trustSshFingerprint() {
        if (sshFingerprint.isBlank()) return
        acquireModule<DshEngineModule>(DshEngineModule.MODULE_NAME).trustSshFingerprint(sshFingerprint)
        runCatching {
            localStore?.saveRemoteProfile(DshRemoteProfile(
                host = sshHost.trim(),
                sshPort = sshPort.toIntOrNull() ?: 22,
                username = sshUser.trim(),
                remoteDshPort = sshDshPort.toIntOrNull() ?: 3080,
                keyId = sshKeyId,
                hostFingerprint = sshFingerprint,
            ))
        }
        sshSettingsError = "正在使用已确认的主机指纹连接"
    }

    private fun saveConnectionSettings() {
        if (sshMode) {
            val port = sshPort.toIntOrNull()
            val dshPort = sshDshPort.toIntOrNull()
            when {
                sshHost.isBlank() -> sshSettingsError = "请输入 SSH 主机地址"
                sshUser.isBlank() -> sshSettingsError = "请输入 SSH 用户名"
                port == null || port !in 1..65535 -> sshSettingsError = "SSH 端口无效"
                dshPort == null || dshPort !in 1..65535 -> sshSettingsError = "远程 DSH 端口无效"
                sshKeyId.isBlank() -> sshSettingsError = "请先导入 SSH 私钥"
                else -> {
                    runCatching { localStore?.saveRemoteProfile(DshRemoteProfile(
                        host = sshHost.trim(),
                        sshPort = port,
                        username = sshUser.trim(),
                        remoteDshPort = dshPort,
                        keyId = sshKeyId,
                        hostFingerprint = sshFingerprint,
                    )) }
                    runCatching { localStore?.saveLastConnectionMode(DshConnectionMode.SSH) }
                    updateSshSettingsVisibility(false)
                    stopCurrentEngine()
                    openConnectionSetup()
                }
            }
        } else {
            runCatching { localStore?.saveLastConnectionMode(DshConnectionMode.RELAY) }
            updateSshSettingsVisibility(false)
            stopCurrentEngine()
            openConnectionSetup()
        }
    }

    private fun stopCurrentEngine() {
        val mode = connectionCoordinator.activeModeOr(connectionMode)
        connectionCoordinator.stop()
        (repository as? DshRemoteRepository)?.stop()
        repository = null
        goalSnapshot = null
        goalActionBusy = false
        goalActionError = ""
        streamHandle?.cancel()
        streamHandle = null
        when (mode) {
            DshConnectionMode.RELAY -> acquireModule<DshRelayModule>(DshRelayModule.MODULE_NAME).disconnect()
            DshConnectionMode.SSH -> engineModule?.stopSsh()
            DshConnectionMode.LOCAL -> engineModule?.stop()
        }
        engineReady = false
    }

    private fun goalMutation(
        action: (DshRemoteRepository, DshGoalSnapshot, (DshRpcError?) -> Unit) -> Unit,
        onDone: (Boolean) -> Unit = {},
    ) {
        val goal = goalSnapshot ?: return
        val remote = repository as? DshRemoteRepository ?: return
        if (goalActionBusy) return
        goalActionBusy = true
        goalActionError = ""
        action(remote, goal) { error ->
            setTimeout(pagerId, 0) {
                goalActionBusy = false
                if (error != null) goalActionError = "${error.message} (${error.code})"
                else goalActionError = ""
                onDone(error == null)
            }
        }
    }

    private fun pauseGoal() = goalMutation(action = { remote, goal, callback -> remote.goalPause(activeSessionId, goal, callback) })
    private fun resumeGoal() = goalMutation(action = { remote, goal, callback -> remote.goalResume(activeSessionId, goal, callback) })
    private fun editGoal(objective: String, onDone: (Boolean) -> Unit) = goalMutation(
        action = { remote, goal, callback -> remote.goalEdit(activeSessionId, goal, objective, callback) },
        onDone = onDone,
    )
    private fun clearGoal() = goalMutation(action = { remote, goal, callback ->
        remote.goalClear(activeSessionId, goal) { error ->
            if (error == null) goalSnapshot = null
            callback(error)
        }
    })

    private fun isCurrent(generation: Long, mode: DshConnectionMode): Boolean =
        connectionCoordinator.accepts(generation, mode)

    private fun openConnectionSetup() {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
            "connection_setup",
            JSONObject().apply { put("pageName", "connection_setup") },
        )
    }

    private fun closeCredentialSettings() {
        dismissKeyboard()
        updateCredentialSetupVisibility(false)
    }

    private fun updateCredentialSetupVisibility(visible: Boolean) {
        credentialSetupVisible = visible
        if (pageData.isAndroid || pageData.isIOS) {
            bridgeModule.setSystemBarsDimmed(visible)
        }
    }

    private fun createSession() {
        val traceId = ++perfTraceSequence
        val startedAt = TimeSource.Monotonic.markNow()
        perfLog("newSession.$traceId.click", startedAt)
        val hostRepository = repository ?: run {
            if (isRemoteHost) {
                closeSessionDrawer()
                bridgeModule.toast("未连接到远程 DSH")
            } else if (pendingApiKey.isEmpty()) {
                connectionLabel = "请先配置 API Key"
                openCredentialSettings()
            } else {
                closeSessionDrawer()
                connectionLabel = "本地 DSH 尚未就绪"
            }
            return
        }
        dismissKeyboard()
        closeSessionDrawer()
        val remoteRepository = hostRepository as? DshRemoteRepository
        val currentWorkspaceId = if (isRemoteHost) {
            remoteRepository?.workspaceIdForSession(activeSessionId)
        } else {
            null
        }
        val blankSession = if (isRemoteHost) {
            remoteRepository?.blankSessionInWorkspace(currentWorkspaceId)
        } else {
            sessions.firstOrNull { it.blank }
        }
        if (blankSession != null) {
            if (blankSession.id != activeSessionId) {
                selectSession(blankSession.id)
            } else {
                applyActiveSessionChrome()
            }
            loadSkills(blankSession.id)
            setTimeout(pagerId, 0) { loadModels(blankSession.id) }
            return
        }
        perfLog("newSession.$traceId.ui.cleared", startedAt)
        perfLog("newSession.$traceId.host.create.request", startedAt)
        hostRepository.createSession(currentWorkspaceId, { sessionId ->
            perfLog("newSession.$traceId.host.create.response:$sessionId", startedAt)
            val created = DshSession(
                id = sessionId,
                title = "新会话",
                workspace = "Host",
                updatedLabel = "",
                blank = true,
            )
            // Keep the existing sessions when creating a new one. Clearing
            // this list also rewrites SQLite with only the newly created row.
            if (sessions.none { it.id == created.id }) {
                sessions.add(0, created)
                refreshVisibleSessions()
            }
            runCatching { localStore?.replaceSessions(activeConnectionId, sessions.toList()) }
            activeSessionId = sessionId
            messages = ObservableList()
            sessionMessageStates[sessionId] = messages
            sessionMessageReady.add(sessionId)
            ensureConversationPanel(sessionId)
            perfLog("newSession.$traceId.ui.ready", startedAt)
            draft = ""
            inputView?.setText("")
            applyActiveSessionChrome()
            setTimeout(pagerId, 0) {
                if (activeSessionId == sessionId) {
                    loadSkills(sessionId)
                    loadModels(sessionId)
                }
            }
        }, { error ->
            perfLog("newSession.$traceId.host.create.error:$error", startedAt)
            connectionLabel = "新会话创建失败"
            messages.add(DshMessage("session-create-error-${messages.size}", DshMessageRole.ERROR, error))
        })
    }

    private fun loadHistory(
        sessionId: String,
        scrollToEndAfterLoad: Boolean = true,
    ) {
        ++historyRequestGeneration

        // Show the selected session immediately. The Host history request is
        // remote and can take a moment, so keeping the previous list here
        // makes a session switch look stuck.
        messages = sessionMessageState(
            sessionId,
            scrollToEndAfterLoad = scrollToEndAfterLoad,
        )
        ensureConversationPanel(sessionId)
        fetchHostHistory(sessionId, scrollToEndAfterLoad)
    }

    private fun fetchHostHistory(
        sessionId: String,
        scrollToEndAfterLoad: Boolean = true,
    ) {
        if (isRemoteHost) {
            loadSkills(sessionId)
            loadWebTimeline(sessionId, scrollToEndAfterLoad)
            return
        }

        val requestGeneration = historyRequestGeneration
        val hostRepository = repository ?: return
        hostRepository.loadHistory(sessionId, { loaded ->
            if (requestGeneration != historyRequestGeneration || activeSessionId != sessionId) return@loadHistory
            sessionMessageReady.add(sessionId)
            sessionCacheStates[sessionId] = DshSessionCacheState.SYNCED
            replaceMessagesIfChanged(loaded)
            runCatching { localStore?.replaceMessages(activeConnectionId, sessionId, loaded) }
            completePendingSessionSelection(sessionId)
            realizeSessionAfterData(sessionId, scrollToEndAfterLoad)
        }, { error ->
            if (requestGeneration != historyRequestGeneration || activeSessionId != sessionId) return@loadHistory
            if (messages.isNotEmpty()) {
                if (isRemoteHost) {
                    sessionCacheStates[sessionId] = DshSessionCacheState.SYNC_FAILED
                    connectionLabel = "远程历史同步失败 · 已显示缓存"
                } else {
                    connectionLabel = "内核连接失败 · 已显示缓存"
                }
            } else {
                messages.add(DshMessage("history-error", DshMessageRole.ERROR, error))
            }
        })
    }

    private fun loadWebTimeline(
        sessionId: String,
        scrollToEndAfterLoad: Boolean = true,
        forceReplace: Boolean = false,
        afterApply: () -> Unit = {},
    ) {
        val hostRepository = repository as? DshRemoteRepository ?: return
        hostRepository.loadWebTimeline(sessionId, { items ->
            if (!isRemoteHost || activeSessionId != sessionId) return@loadWebTimeline
            val projected = items.map { item ->
                when (item.kind) {
                    DshWebTimelineItem.Kind.USER -> DshMessage(item.key, DshMessageRole.USER, item.text)
                    DshWebTimelineItem.Kind.ASSISTANT -> DshMessage(item.key, DshMessageRole.ASSISTANT, item.text)
                    DshWebTimelineItem.Kind.REASONING -> DshMessage(
                        item.key,
                        DshMessageRole.ASSISTANT,
                        item.text,
                        isReasoning = true,
                    )
                    DshWebTimelineItem.Kind.IMAGE -> DshMessage(
                        item.key,
                        DshMessageRole.ASSISTANT,
                        "",
                        attachmentId = item.attachmentId,
                    )
                    DshWebTimelineItem.Kind.UNKNOWN_BLOCK -> DshMessage(
                        item.key,
                        DshMessageRole.TOOL,
                        item.text,
                        toolName = "未知内容块",
                        toolCardType = DshToolCardType.JSON,
                    )
                    DshWebTimelineItem.Kind.ERROR -> DshMessage(item.key, DshMessageRole.ERROR, item.text)
                    DshWebTimelineItem.Kind.CONTEXT -> DshMessage(
                        item.key,
                        DshMessageRole.TOOL,
                        item.text,
                        toolName = item.sourceLabel,
                        isContextInjection = true,
                        contextBody = item.text,
                        contextForm = item.source?.optString("form").orEmpty(),
                        contextCatalog = item.source?.let(::contextCatalogEntries).orEmpty(),
                        contextSections = item.source?.let(::contextSections).orEmpty(),
                        contextRecalls = item.source?.let(::contextRecalls).orEmpty(),
                        contextInstructions = item.source?.let(::contextInstructions).orEmpty(),
                        contextRelaySender = item.source?.let(::contextRelaySender).orEmpty(),
                    )
                    DshWebTimelineItem.Kind.TOOL -> item.remoteTool?.toRemoteMessage(item.key) ?: DshMessage(
                        item.key,
                        DshMessageRole.TOOL,
                        item.cardBody.ifEmpty { listOfNotNull(item.input, item.output).joinToString("\n\n") },
                        toolName = item.cardTitle.ifEmpty { item.toolName ?: "工具" },
                        toolCardType = item.cardType,
                        toolRunning = item.running,
                        toolError = item.error != null,
                    )
                }
            }
            sessionMessageReady.add(sessionId)
            replaceMessagesIfChanged(projected, forceReplace)
            if (projected.isNotEmpty()) {
                persistMessages(sessionId)
                sessionCacheStates[sessionId] = DshSessionCacheState.SYNCED
            }
            projected.mapNotNull { it.attachmentId }.forEach { loadAttachment(sessionId, it) }
            completePendingSessionSelection(sessionId)
            realizeSessionAfterData(sessionId, scrollToEndAfterLoad)
            afterApply()
        }, { error ->
            DshStreamLog.i("ui.history-fail session=$sessionId error='${DshStreamLog.preview(error)}'")
            if (forceReplace && !sessionRunning && (streaming || stopButtonVisible)) {
                finishStreamingFromHistory(sessionId)
            }
            afterApply()
        })
    }

    private fun resyncStreamingWithHost(sessionId: String, reason: String) {
        if (!isRemoteHost || sessionId != activeSessionId) return
        DshStreamLog.i(
            "ui.resync.begin reason=$reason session=$sessionId running=$sessionRunning streaming=$streaming stop=$stopButtonVisible",
        )
        // A local prompt is already painting this turn. Reloading the web
        // timeline remounts every markdown bubble and delays the first token.
        if (reason == "host-session-running" && isLocalPromptInFlight()) {
            DshStreamLog.i(
                "ui.resync.skip-local-stream reason=$reason session=$sessionId root=$streamingAssistantRootId",
            )
            return
        }
        if (sessionRunning) {
            loadWebTimeline(sessionId, scrollToEndAfterLoad = true, forceReplace = true) {
                resumeStreamingFromHistory(sessionId, reason)
            }
        } else {
            val forceReplace = streaming || stopButtonVisible
            loadWebTimeline(sessionId, scrollToEndAfterLoad = true, forceReplace = forceReplace) {
                finishStreamingFromHistory(sessionId)
                connectionLabel = "已连接"
                DshStreamLog.i("ui.resync.settled reason=$reason session=$sessionId messages=${messages.size}")
            }
        }
    }

    private fun isLocalPromptInFlight(): Boolean =
        streaming && streamingAssistantRootId.isNotEmpty()

    private fun rebindStreamingToHistoryTail(): Boolean {
        val live = dshHistoryTailToResume(messages.toList(), streamingTurnAnchorAssistantId)
            ?: return false
        streamingAssistantId = live.id
        streamingAssistantRootId = live.id
        streamingAssistantSegment = 0
        streamingAssistantContent = live.content
        return true
    }

    private fun finishStreamingFromHistory(sessionId: String) {
        if (!(streaming || stopButtonVisible)) return
        flushAssistantDelta()
        if (rebindStreamingToHistoryTail()) {
            settleStreamingMessage(DshMessageRole.ASSISTANT, streamingAssistantContent)
        } else {
            releaseStreamingUi()
        }
        persistMessages(sessionId)
        (repository as? DshRemoteRepository)?.detachLiveStreams(sessionId)
        streamHandle = null
    }

    private fun resumeStreamingFromHistory(sessionId: String, reason: String) {
        if (sessionId != activeSessionId) return
        val rebound = rebindStreamingToHistoryTail()
        if (rebound) {
            streaming = true
            stopButtonVisible = true
            connectionLabel = "正在生成"
            val index = messages.indexOfFirst { it.id == streamingAssistantId }
            if (index >= 0) {
                messages[index] = messages[index].copy(streaming = true)
            }
        } else {
            if (streamingAssistantRootId.isEmpty()) {
                streamingAssistantRootId = "assistant-adopted-${messages.size}"
            }
            val liveStillPresent = streamingAssistantId.isNotEmpty() &&
                messages.any { it.id == streamingAssistantId }
            if (!liveStillPresent) {
                val kept = streamingAssistantContent + pendingAssistantDelta.toString()
                pendingAssistantDelta.setLength(0)
                streamingAssistantId = ""
                streamingAssistantSegment = 0
                streamingAssistantContent = ""
                if (kept.isNotEmpty()) {
                    ensureStreamingAssistantSegment()
                    streamingAssistantContent = kept
                    updateStreamingMessage(kept, streaming = true)
                }
            }
            streaming = true
            stopButtonVisible = true
            connectionLabel = "正在生成"
        }
        attachAdoptedLiveStream(sessionId)
        syncTurnStatusTicker()
        DshStreamLog.i(
            "ui.resync.resume reason=$reason rebound=$rebound id=${streamingAssistantId.ifEmpty { streamingAssistantRootId }} chars=${streamingAssistantContent.length}",
        )
    }

    private fun attachAdoptedLiveStream(sessionId: String) {
        val hostRepository = repository as? DshRemoteRepository ?: return
        streamHandle = hostRepository.adoptLiveStream(
            sessionId = sessionId,
            onDelta = { delta, isReasoning ->
                if (!connectionCoordinator.isActive(connectionMode) || activeSessionId != sessionId) return@adoptLiveStream
                if (isReasoning) {
                    val reasoningId = streamingReasoningId.ifEmpty { "$streamingAssistantRootId-reasoning" }
                    if (streamingReasoningId.isEmpty()) streamingReasoningId = reasoningId
                    queueReasoningDelta(reasoningId, delta)
                } else {
                    if (streamingAssistantRootId.isEmpty()) {
                        streamingAssistantRootId = "assistant-adopted-${messages.size}"
                    }
                    queueAssistantDelta(streamingAssistantRootId, delta)
                }
            },
            onComplete = { result ->
                if (!connectionCoordinator.isActive(connectionMode)) return@adoptLiveStream
                flushAssistantDelta()
                if (streamingAssistantId.isEmpty() && result.isNotEmpty()) {
                    ensureStreamingAssistantSegment()
                }
                val completedContent = streamingAssistantContent.ifEmpty { result }
                DshStreamLog.i(
                    "ui.complete session=$sessionId resultChars=${result.length} liveChars=${streamingAssistantContent.length} preview='${DshStreamLog.preview(completedContent)}'",
                )
                settleStreamingMessage(DshMessageRole.ASSISTANT, completedContent)
                persistMessages(sessionId)
                connectionLabel = "已连接"
                streamHandle = null
            },
            onError = { error ->
                if (!connectionCoordinator.isActive(connectionMode)) return@adoptLiveStream
                if (dshIsTransportInterrupt("", error)) {
                    DshStreamLog.i("ui.adopt-interrupt session=$sessionId message='${DshStreamLog.preview(error)}'")
                    return@adoptLiveStream
                }
                flushAssistantDelta()
                ensureStreamingAssistantSegment()
                DshStreamLog.i("ui.error session=$sessionId message='${DshStreamLog.preview(error)}'")
                settleStreamingMessage(DshMessageRole.ERROR, error)
                persistMessages(sessionId)
                connectionLabel = "已连接"
                streamHandle = null
            },
        )
    }

    private fun loadSkills(sessionId: String) {
        if (!isRemoteHost) {
            skills.clear()
            return
        }
        val remote = repository as? DshRemoteRepository ?: return
        skills.clear()
        remote.loadSkills(sessionId, onSuccess = { loaded ->
            if (!isRemoteHost || activeSessionId != sessionId) return@loadSkills
            skills.clear()
            skills.addAll(loaded)
        })
    }

    private fun loadAttachment(sessionId: String, attachmentId: String) {
        if (attachmentDataUrl(attachmentId) != null || !pendingAttachmentReads.add(attachmentId)) return
        val hostRepository = repository as? DshRemoteRepository ?: return
        hostRepository.loadAttachment(sessionId, attachmentId) { dataUrl, error ->
            if (error != null || dataUrl == null) {
                pendingAttachmentReads.remove(attachmentId)
                return@loadAttachment
            }
            cachedAttachmentDataUrls[attachmentId] = dataUrl
            attachmentRevision += 1
            val next = sessionMessageState(sessionId).toList()
            if (activeSessionId == sessionId) replaceMessagesIfChanged(next)
            else sessionMessageStates[sessionId] = ObservableList<DshMessage>().also { it.addAll(next) }
        }
    }

    private fun showRunningTool(event: DshRawSessionEvent) {
        val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return
        val model = DshRemoteToolCallModels.fromLiveCall(payload) ?: return
        val id = "tool-${event.seq}"
        if (messages.any { it.id == id }) return
        // The Host emits tool/call after the assistant block that introduced
        // it. Seal that block before appending its card so the list follows the
        // actual event order instead of grouping all cards at the turn end.
        splitStreamingAssistantBeforeTool()
        messages.add(model.toRemoteMessage(id))
        refreshSessionRenderTree(activeSessionId)
        scrollMessagesToEnd()
    }

    private fun showContextInjection(event: DshRawSessionEvent) {
        val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return
        val data = dshWireEvent(payload).optJSONObject("data") ?: return
        val source = data.optJSONObject("source") ?: return
        if (source.optString("kind") == "user") return
        val id = "context-${event.seq}"
        if (messages.any { it.id == id }) return
        val content = data.optJSONArray("content") ?: return
        val text = buildString {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }.trim()
        if (text.isEmpty()) return
        messages.add(DshMessage(
            id = id,
            role = DshMessageRole.TOOL,
            content = text,
            toolName = contextSummary(source),
            isContextInjection = true,
            contextBody = text,
            contextForm = source.optString("form"),
            contextCatalog = contextCatalogEntries(source),
            contextSections = contextSections(source),
            contextRecalls = contextRecalls(source),
            contextInstructions = contextInstructions(source),
            contextRelaySender = contextRelaySender(source),
        ))
        scrollMessagesToEnd()
    }

    private fun showAssistantBlocks(event: DshRawSessionEvent) {
        val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return
        val data = dshWireEvent(payload).optJSONObject("data") ?: return
        val blocks = (data.optJSONObject("message") ?: data).optJSONArray("content") ?: return
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "image" -> {
                    val attachmentId = block.optJSONObject("attachment")?.optString("attachmentId").orEmpty()
                    if (attachmentId.isEmpty()) continue
                    val id = "image-${event.seq}-$index"
                    if (messages.none { it.id == id }) {
                        messages.add(DshMessage(
                            id = id,
                            role = DshMessageRole.ASSISTANT,
                            content = "",
                            attachmentId = attachmentId,
                        ))
                    }
                    loadAttachment(activeSessionId, attachmentId)
                }
                "text", "reasoning", "tool-call" -> Unit
                else -> {
                    val id = "block-${event.seq}-$index"
                    if (messages.none { it.id == id }) {
                        messages.add(DshMessage(
                            id = id,
                            role = DshMessageRole.TOOL,
                            content = block.toString(),
                            toolName = "未知内容块",
                            toolCardType = DshToolCardType.JSON,
                        ))
                    }
                }
            }
        }
        scrollMessagesToEnd()
    }

    private fun settleRunningTool(event: DshRawSessionEvent) {
        val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return
        val eventData = dshWireEvent(payload).optJSONObject("data") ?: return
        val message = eventData.optJSONObject("message")
        val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
        val callId = resultBlock?.optString("toolCallId")
            ?: message?.optJSONObject("source")?.optString("callId")
            ?: eventData.optString("callId")
        if (callId.isEmpty()) return
        val index = messages.indexOfFirst { it.role == DshMessageRole.TOOL && it.toolCallId == callId }
        if (index < 0) return
        val previous = messages[index].remoteTool ?: return
        val model = DshRemoteToolCallModels.settleLiveResult(previous, payload) ?: return
        messages[index] = model.toRemoteMessage(messages[index].id)
    }

    private fun attachmentDataUrl(attachmentId: String): String? {
        attachmentRevision // Read the reactive revision so image rows rerender after downloads.
        return cachedAttachmentDataUrls[attachmentId]
    }

    private fun refreshQueueDock() {
        if (!isRemoteHost) {
            queueItems.clear()
            return
        }
        val repository = repository as? DshRemoteRepository ?: return
        val items = repository.queue(activeSessionId)
        queueItems.clear()
        queueItems.addAll(items)
        if (items.isEmpty()) {
            queueDockExpanded = false
            cancelQueueItemEdit()
        } else if (queueEditingId.isNotEmpty() && items.none { it.id == queueEditingId }) {
            cancelQueueItemEdit()
        }
    }

    private fun refreshJobsPanel() {
        if (!isRemoteHost) {
            jobItems.clear()
            return
        }
        val repository = repository as? DshRemoteRepository ?: return
        val items = repository.jobs(activeSessionId)
        jobItems.clear()
        jobItems.addAll(items)
        if (items.isEmpty()) jobsPanelExpanded = false
        if (jobsPanelExpanded) {
            jobsNow = bridgeModule.currentTimeStamp()
            scheduleJobsClock()
        }
    }

    private fun toggleJobsPanel() {
        jobsPanelExpanded = !jobsPanelExpanded
        if (jobsPanelExpanded) {
            jobsNow = bridgeModule.currentTimeStamp()
            scheduleJobsClock()
        }
    }

    private fun scheduleJobsClock() {
        if (!jobsPanelExpanded || jobsClockScheduled || jobItems.none { it.status == "running" || it.status == "stopping" }) return
        jobsClockScheduled = true
        setTimeout(pagerId, 1_000) {
            jobsClockScheduled = false
            if (!jobsPanelExpanded) return@setTimeout
            jobsNow = bridgeModule.currentTimeStamp()
            scheduleJobsClock()
        }
    }

    private fun refreshWorkspaceGroups() {
        if (!isRemoteHost) {
            workspaceGroups.clear()
            return
        }
        val repository = repository as? DshRemoteRepository ?: return
        val groups = repository.workspaceGroups()
        workspaceGroups.clear()
        workspaceGroups.addAll(groups)
    }

    private fun refreshPendingInteractions() {
        if (!isRemoteHost) {
            pendingApproval = null
            pendingQuestion = null
            selectedQuestionOptions.clear()
            questionCustom = ""
            questionIndex = 0
            questionError = ""
            questionDrafts.clear()
            DshStreamLog.question("ui.refresh skipped local-mode")
            return
        }
        val repository = repository as? DshRemoteRepository ?: return
        val (approval, question) = repository.pendingInteractions(activeSessionId)
        pendingApproval = approval
        pendingQuestion = question
        questionIndex = questionIndex.coerceIn(0, (question?.questions?.size ?: 1) - 1)
        loadQuestionDraft(questionIndex)
        DshStreamLog.question(
            "ui.refresh session=$activeSessionId approval=${approval?.rpcId.orEmpty()} question=${question?.rpcId.orEmpty()} qCount=${question?.questions?.size ?: 0} busy=$interactionBusy",
        )
    }

    private fun answerApproval(outcome: String) {
        val repository = repository as? DshRemoteRepository ?: return
        val approval = pendingApproval ?: return
        interactionBusy = true
        repository.respondApproval(
            rpcId = approval.rpcId,
            sessionId = approval.sessionId,
            approvalId = approval.approvalId,
            outcome = outcome,
        ) { accepted, reason ->
            setTimeout(pagerId, 0) {
                interactionBusy = false
                if (!accepted) {
                    connectionLabel = interactionFailureLabel(reason)
                    return@setTimeout
                }
                refreshPendingInteractions()
            }
        }
    }

    private fun toggleQuestionOption(label: String) {
        val item = pendingQuestion?.questions?.getOrNull(questionIndex) ?: return
        if (!item.multiSelect) {
            selectedQuestionOptions.clear()
            questionCustom = ""
        }
        if (selectedQuestionOptions.contains(label)) selectedQuestionOptions.remove(label)
        else selectedQuestionOptions.add(label)
        questionError = ""
        questionDrafts[questionIndex] = DshQuestionDraft(selectedQuestionOptions.toList(), questionCustom)
    }

    private fun updateQuestionCustom(value: String) {
        val item = pendingQuestion?.questions?.getOrNull(questionIndex) ?: return
        if (!item.multiSelect) selectedQuestionOptions.clear()
        questionCustom = value
        questionError = ""
        questionDrafts[questionIndex] = DshQuestionDraft(selectedQuestionOptions.toList(), questionCustom)
    }

    private fun skipQuestion() {
        val count = pendingQuestion?.questions?.size ?: return
        questionDrafts[questionIndex] = DshQuestionDraft(skipped = true)
        selectedQuestionOptions.clear()
        questionCustom = ""
        questionError = ""
        if (questionIndex < count - 1) {
            questionIndex += 1
            loadQuestionDraft(questionIndex)
        } else {
            submitQuestion()
        }
    }

    private fun navigateQuestion(delta: Int) {
        val count = pendingQuestion?.questions?.size ?: return
        val next = (questionIndex + delta).coerceIn(0, count - 1)
        if (next == questionIndex) return
        questionDrafts[questionIndex] = DshQuestionDraft(selectedQuestionOptions.toList(), questionCustom)
        questionIndex = next
        questionError = ""
        loadQuestionDraft(next)
    }

    private fun loadQuestionDraft(index: Int) {
        val draft = questionDrafts[index] ?: DshQuestionDraft()
        selectedQuestionOptions.clear()
        selectedQuestionOptions.addAll(draft.selected)
        questionCustom = draft.custom
    }

    private fun submitQuestion() {
        val repository = repository as? DshRemoteRepository
        if (repository == null) {
            DshStreamLog.question("submit.abort not-remote-repo")
            return
        }
        val question = pendingQuestion
        if (question == null) {
            DshStreamLog.question("submit.abort no-pending-question")
            return
        }
        questionDrafts[questionIndex] = DshQuestionDraft(selectedQuestionOptions.toList(), questionCustom)
        val missing = question.questions.indexOfFirst { item ->
            val draft = questionDrafts[question.questions.indexOf(item)] ?: DshQuestionDraft()
            draft.selected.isEmpty() && draft.custom.isBlank() && !draft.skipped
        }
        if (missing >= 0) {
            questionIndex = missing
            loadQuestionDraft(missing)
            questionError = "请先选择一项，或自己写答案"
            DshStreamLog.question("submit.abort unanswered index=$missing")
            return
        }
        if (question.rpcId.isEmpty()) {
            questionError = "这个问题已失效，请等 Agent 重新提问"
            DshStreamLog.question("submit.abort empty-rpcId session=${question.sessionId}")
            return
        }
        questionError = ""
        interactionBusy = true
        val answer = buildQuestionAnswer(question, questionDrafts)
        DshStreamLog.question(
            "submit.start session=${question.sessionId} rpcId=${question.rpcId} index=$questionIndex selected=${selectedQuestionOptions.toList()} custom='${DshStreamLog.preview(questionCustom)}' answer='${DshStreamLog.preview(answer.toString(), 400)}'",
        )
        repository.respondQuestion(
            rpcId = question.rpcId,
            sessionId = question.sessionId,
            answer = answer,
        ) { accepted, reason ->
            setTimeout(pagerId, 0) {
                val stillPending = repository.pendingInteractions(question.sessionId).second
                DshStreamLog.question(
                    "submit.callback accepted=$accepted reason='$reason' rpcId=${question.rpcId} stillPending=${stillPending?.rpcId.orEmpty()} active=$activeSessionId",
                )
                interactionBusy = false
                if (!accepted) {
                    questionError = interactionFailureLabel(reason)
                    DshStreamLog.question("submit.rejected ui-kept error='$questionError'")
                    return@setTimeout
                }
                repository.clearPending(question.rpcId)
                if (pendingQuestion?.rpcId == question.rpcId) {
                    pendingQuestion = null
                    selectedQuestionOptions.clear()
                    questionCustom = ""
                    questionError = ""
                    questionDrafts.clear()
                }
                DshStreamLog.question("submit.accepted ui-hide rpcId=${question.rpcId}")
                refreshPendingInteractions()
                if (activeSessionId == question.sessionId) {
                    loadWebTimeline(question.sessionId, scrollToEndAfterLoad = true)
                }
            }
        }
    }

    private fun interactionFailureLabel(reason: String): String = when (reason) {
        "not-pending" -> "这个问题已经失效，请等 Agent 重新提问"
        "bad-response" -> "提交未被接受，请再选一次后重试"
        "缺少请求编号" -> "这个问题已失效，请等 Agent 重新提问"
        "连接尚未就绪" -> "连接尚未就绪，请稍后再试"
        else -> reason.ifEmpty { "提交失败，请重试" }
    }

    private fun editQueueItem(itemId: String) {
        val item = queueItems.firstOrNull { it.id == itemId } ?: return
        val text = item.text ?: return
        queueDockExpanded = true
        queueEditingId = itemId
        queueEditingText = text
    }

    private fun saveQueueItem(itemId: String) {
        val repository = repository as? DshRemoteRepository ?: return
        val text = queueEditingText.trim()
        if (queueActionBusy || itemId != queueEditingId || text.isEmpty()) return
        queueActionBusy = true
        repository.updateQueue(
            sessionId = activeSessionId,
            itemId = itemId,
            action = JSONObject().apply {
                put("kind", "edit")
                put("content", JSONArray().apply { put(JSONObject().apply { put("type", "text"); put("text", text) }) })
            },
        ) { _, _ ->
            setTimeout(pagerId, 0) {
                queueActionBusy = false
                cancelQueueItemEdit()
                refreshQueueDock()
            }
        }
    }

    private fun cancelQueueItemEdit() {
        queueEditingId = ""
        queueEditingText = ""
    }

    private fun removeQueueItem(itemId: String) {
        updateQueueItem(itemId, JSONObject().apply { put("kind", "remove") })
    }

    private fun steerQueueItem(itemId: String) {
        updateQueueItem(itemId, JSONObject().apply { put("kind", "steer") })
    }

    private fun updateQueueItem(itemId: String, action: JSONObject) {
        val repository = repository as? DshRemoteRepository ?: return
        if (queueActionBusy) return
        queueActionBusy = true
        repository.updateQueue(
            sessionId = activeSessionId,
            itemId = itemId,
            action = action,
        ) { _, _ ->
            setTimeout(pagerId, 0) {
                queueActionBusy = false
                refreshQueueDock()
            }
        }
    }

    private fun renameActiveSession() {
        val repository = repository as? DshRemoteRepository ?: return
        val current = sessions.firstOrNull { it.id == activeSessionId } ?: return
        val title = current.title.takeIf { it != "尚无标题" && it != "新会话" } ?: ""
        if (title.isBlank()) return
        repository.renameSession(activeSessionId, title) { _, _ ->
            setTimeout(pagerId, 0) { loadRepository(preferredSessionId = activeSessionId) }
        }
    }

    private fun archiveActiveSession() {
        val repository = repository as? DshRemoteRepository ?: return
        repository.archiveSession(activeSessionId) { _, _ ->
            setTimeout(pagerId, 0) {
                loadRepository(preferredSessionId = null)
                refreshWorkspaceGroups()
            }
        }
    }

    private fun forkActiveSession() {
        val repository = repository as? DshRemoteRepository ?: return
        val lastSeq = repository.store.sessionLastSeq[activeSessionId]
        repository.forkSession(activeSessionId, lastSeq) { value, error ->
            if (error != null || value == null) {
                setTimeout(pagerId, 0) {
                    messages.add(DshMessage(
                        "fork-error-${messages.size}",
                        DshMessageRole.ERROR,
                        error?.message ?: "session.fork failed",
                    ))
                }
                return@forkSession
            }
            val childSessionId = value.optString("sessionId")
            setTimeout(pagerId, 0) {
                if (childSessionId.isNotEmpty()) loadRepository(preferredSessionId = childSessionId)
            }
        }
    }

    private fun exportActiveSession() {
        val repository = repository as? DshRemoteRepository ?: return
        val url = repository.sessionExportUrl(activeSessionId)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
            "link_view",
            JSONObject().apply {
                put("pageName", "link_view")
                put("url", url)
            },
        )
    }

    private fun openWorkspaceBrowser() {
        if (!isRemoteHost) return
        closeSessionDrawer()
        workspaceBrowserVisible = true
        workspaceBrowserError = ""
        workspaceBrowserNewName = ""
        loadDirectory(null)
    }

    private fun loadDirectory(path: String?) {
        val repository = repository as? DshRemoteRepository ?: return
        workspaceBrowserBusy = true
        workspaceBrowserError = ""
        repository.listDirectory(path) { listing, error ->
            setTimeout(pagerId, 0) {
                workspaceBrowserBusy = false
                if (error != null || listing == null) {
                    workspaceBrowserError = error?.message ?: "无法读取目录"
                    return@setTimeout
                }
                workspaceBrowserPath = listing.path
                workspaceBrowserHome = listing.home
                workspaceDirectoryEntries.clear()
                workspaceDirectoryEntries.addAll(listing.entries.filterNot { it.hidden })
            }
        }
    }

    private fun createRemoteDirectory() {
        val repository = repository as? DshRemoteRepository ?: return
        val name = workspaceBrowserNewName.trim()
        if (workspaceBrowserPath.isEmpty() || name.isEmpty()) return
        workspaceBrowserBusy = true
        repository.createDirectory(workspaceBrowserPath, name) { createdPath, error ->
            setTimeout(pagerId, 0) {
                workspaceBrowserBusy = false
                if (error != null || createdPath == null) {
                    workspaceBrowserError = error?.message ?: "无法创建目录"
                    return@setTimeout
                }
                workspaceBrowserNewName = ""
                loadDirectory(createdPath)
            }
        }
    }

    private fun adoptCurrentDirectoryAsWorkspace() {
        val repository = repository as? DshRemoteRepository ?: return
        if (workspaceBrowserPath.isEmpty()) return
        workspaceBrowserBusy = true
        repository.createWorkspace(workspaceBrowserPath) { _, error ->
            setTimeout(pagerId, 0) {
                workspaceBrowserBusy = false
                if (error != null) {
                    workspaceBrowserError = error.message
                    return@setTimeout
                }
                workspaceBrowserVisible = false
                loadRepository(preferredSessionId = activeSessionId)
            }
        }
    }

    private fun openWorkspaceRename(workspaceId: String, currentTitle: String) {
        workspaceRenameTargetId = workspaceId
        workspaceRenameDraft = currentTitle
        workspaceActionError = ""
    }

    private fun saveWorkspaceRename() {
        val repository = repository as? DshRemoteRepository ?: return
        val workspaceId = workspaceRenameTargetId
        val title = workspaceRenameDraft.trim()
        if (workspaceId.isEmpty() || title.isEmpty()) return
        workspaceActionBusy = true
        workspaceActionError = ""
        repository.renameWorkspace(workspaceId, title) { _, error ->
            setTimeout(pagerId, 0) {
                workspaceActionBusy = false
                if (error != null) {
                    workspaceActionError = error.message
                    return@setTimeout
                }
                workspaceRenameTargetId = ""
                workspaceRenameDraft = ""
                refreshWorkspaceGroups()
            }
        }
    }

    private fun openWorkspaceDelete(workspaceId: String) {
        workspaceDeleteTargetId = workspaceId
        workspaceActionError = ""
    }

    private fun confirmWorkspaceDelete() {
        val repository = repository as? DshRemoteRepository ?: return
        val workspaceId = workspaceDeleteTargetId
        if (workspaceId.isEmpty()) return
        workspaceActionBusy = true
        workspaceActionError = ""
        repository.deleteWorkspace(workspaceId) { _, error ->
            setTimeout(pagerId, 0) {
                workspaceActionBusy = false
                if (error != null) {
                    workspaceActionError = error.message
                    return@setTimeout
                }
                workspaceDeleteTargetId = ""
                refreshWorkspaceGroups()
            }
        }
    }

    private fun moveWorkspace(workspaceId: String, delta: Int) {
        val repository = repository as? DshRemoteRepository ?: return
        val ordered = workspaceGroups.filter { it.workspaceId.isNotEmpty() }
        val index = ordered.indexOfFirst { it.workspaceId == workspaceId }
        if (index < 0) return
        val targetIndex = index + delta
        if (targetIndex < 0 || targetIndex >= ordered.size) return
        val beforeWorkspaceId = if (targetIndex == ordered.lastIndex) {
            null
        } else {
            ordered[targetIndex].workspaceId
        }
        workspaceActionBusy = true
        workspaceActionError = ""
        repository.moveWorkspaceBefore(workspaceId, beforeWorkspaceId) { _, error ->
            setTimeout(pagerId, 0) {
                workspaceActionBusy = false
                if (error != null) {
                    workspaceActionError = error.message
                    return@setTimeout
                }
                refreshWorkspaceGroups()
            }
        }
    }

    private fun restoreCachedSessions() {
        val store = localStore ?: return
        val cached = runCatching { store.loadSessions(activeConnectionId) }.getOrDefault(emptyList())
        if (cached.isEmpty()) return
        sessions.clear()
        sessions.addAll(cached)
        refreshVisibleSessions()
        val homeId = cached.firstOrNull { it.blank }?.id
        if (homeId != null) {
            activeSessionId = homeId
            val state = sessionMessageStates[homeId] ?: ObservableList()
            state.clear()
            sessionMessageStates[homeId] = state
            sessionMessageReady.add(homeId)
            messages = state
            ensureConversationPanel(homeId)
            return
        }
        val state = ObservableList<DshMessage>()
        messages = state
        sessionMessageStates[activeSessionId] = state
        sessionMessageReady.add(activeSessionId)
        ensureConversationPanel(activeSessionId)
    }

    private fun loadApiKeyAsync() {
        if (isRemoteHost) return
        val store = localStore
        if (store == null) {
            showCredentialSetupIfNeeded("")
            return
        }
        localReadScope.launch {
            val apiKey = runCatching { store.loadApiKey() }.getOrDefault("")
            setTimeout(pagerId, 0) {
                pendingApiKey = apiKey
                if (apiKey.isEmpty()) {
                    showCredentialSetupIfNeeded(apiKey)
                } else if (engineReady && repository == null && connectionMode == DshConnectionMode.LOCAL) {
                    connectLocalEngine(apiKey)
                }
            }
        }
    }

    private fun showCredentialSetupIfNeeded(apiKey: String) {
        if (isRemoteHost) return
        if (pendingApiKey.isNotEmpty() || apiKey.isNotEmpty()) return
        connectionLabel = "等待配置"
        updateCredentialSetupVisibility(true)
        if (messages.none { it.id == "api-key-required" }) {
            messages.add(
                DshMessage(
                    id = "api-key-required",
                    role = DshMessageRole.ASSISTANT,
                    content = "输入 DeepSeek API Key 后即可开始使用本地 Agent。",
                ),
            )
        }
    }

    private fun selectSession(id: String) {
        val traceId = ++perfTraceSequence
        val startedAt = TimeSource.Monotonic.markNow()
        perfLog("switch.$traceId.request:$id", startedAt)
        dismissKeyboard()
        if (id == activeSessionId) {
            perfLog("switch.$traceId.same-session", startedAt)
            return
        }
        if (!sessionMessageReady.contains(id)) {
            pendingSessionSelections.add(id)
            perfLog("switch.$traceId.wait-data", startedAt)
            return
        }
        if (!conversationPanelIds.contains(id)) {
            ensureConversationPanel(id)
            addTaskWhenPagerUpdateLayoutFinish {
                perfLog("switch.$traceId.panel.layout-finished", startedAt)
                if (activeSessionId != id) selectSession(id)
            }
            return
        }
        selectMountedSession(id, traceId, startedAt)
    }

    private fun selectMountedSession(id: String, traceId: Int = 0, startedAt: TimeMark? = null) {
        if (id == activeSessionId) return
        perfLog("switch.$traceId.mounted.begin", startedAt)
        refreshSessionRenderTree(id)
        cancelStreamingForSessionSwitch()
        sessionMessageStates[activeSessionId] = messages
        val nextMessages = sessionMessageState(id, loadFromDisk = false)
        ensureConversationPanel(id)
        messages = nextMessages
        activeSessionId = id
        perfLog("switch.$traceId.active-state-swapped", startedAt)
        scrollMessagesToEnd()
        addTaskWhenPagerUpdateLayoutFinish {
            refreshSessionRenderTree(id)
            perfLog("switch.$traceId.layout.realized", startedAt)
            if (activeSessionId == id) scrollMessagesToEnd()
        }
        // Invalidate any in-flight request for the previous session before
        // starting the new one, so an old response cannot repaint this view.
        historyRequestGeneration++
        loadMessagesFromDisk(id)
        fetchHostHistory(id)
        setTimeout(pagerId, 0) {
            if (activeSessionId == id) loadModels(id)
        }
        draft = ""
        inputView?.setText("")
        applyActiveSessionChrome()
        perfLog("switch.$traceId.end", startedAt)
    }

    private fun isWebDisclosureExpanded(id: String): Boolean {
        webDisclosureRevision
        return webDisclosureStates[id] == true
    }

    private fun toggleWebDisclosure(id: String) {
        val next = webDisclosureStates[id] != true
        webDisclosureStates[id] = next
        if (!next) {
            webBodyDisclosureStates.remove(id)
            webJsonNodeStates.keys.filter { it.startsWith("$id:") }.toList().forEach(webJsonNodeStates::remove)
        }
        webDisclosureRevision += 1
        refreshSessionRenderTree(activeSessionId)
    }

    private fun isWebBodyDisclosureExpanded(id: String): Boolean {
        webDisclosureRevision
        return webBodyDisclosureStates[id] == true
    }

    private fun toggleWebBodyDisclosure(id: String) {
        webBodyDisclosureStates[id] = webBodyDisclosureStates[id] != true
        webDisclosureRevision += 1
        refreshSessionRenderTree(activeSessionId)
    }

    private fun isWebJsonNodeExpanded(messageId: String, nodeId: String): Boolean {
        webDisclosureRevision
        return webJsonNodeStates["$messageId:$nodeId"] == true
    }

    private fun toggleWebJsonNode(messageId: String, nodeId: String) {
        val key = "$messageId:$nodeId"
        webJsonNodeStates[key] = webJsonNodeStates[key] != true
        webDisclosureRevision += 1
        refreshSessionRenderTree(activeSessionId)
    }

    private fun isBlankSession(sessionId: String = activeSessionId): Boolean =
        sessions.firstOrNull { it.id == sessionId }?.blank == true

    private fun conversationListEpochFor(sessionId: String): Int {
        conversationListEpoch
        return conversationListEpochs[sessionId] ?: 0
    }

    private fun remountConversationList(sessionId: String) {
        conversationListEpochs[sessionId] = (conversationListEpochs[sessionId] ?: 0) + 1
        conversationListEpoch += 1
    }

    private fun applyActiveSessionChrome() {
        pendingApproval = null
        pendingQuestion = null
        selectedQuestionOptions.clear()
        questionCustom = ""
        questionIndex = 0
        questionError = ""
        questionDrafts.clear()
        goalSnapshot = null
        if (!isRemoteHost) {
            queueItems.clear()
            jobItems.clear()
            return
        }
        refreshQueueDock()
        refreshJobsPanel()
        refreshPendingInteractions()
    }

    private fun reconnectLabel(): String = when (connectionMode) {
        DshConnectionMode.SSH -> "远程连接重建中"
        DshConnectionMode.RELAY -> "扫码连接重建中"
        DshConnectionMode.LOCAL -> "本地 DSH 连接重建中"
    }

    private fun isTurnStatusActive(): Boolean =
        streaming || stopButtonVisible || sessionRunning

    private fun syncTurnStatusTicker() {
        if (!isTurnStatusActive()) {
            turnStatusTickerGeneration += 1
            turnStatusMark = null
            turnElapsedMs = 0
            turnStatusClockBucket = -1L
            return
        }
        if (turnStatusMark == null) {
            turnStatusMark = TimeSource.Monotonic.markNow()
        }
        val token = ++turnStatusTickerGeneration
        fun tick() {
            if (token != turnStatusTickerGeneration) return
            if (!isTurnStatusActive()) {
                turnStatusMark = null
                turnElapsedMs = 0
                turnStatusClockBucket = -1L
                return
            }
            val elapsed = turnStatusMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
            val showClock = elapsed >= TURN_STATUS_CLOCK_AFTER_MS
            val clockBucket = if (showClock) elapsed / 1_000L else 0L
            if (clockBucket != turnStatusClockBucket) {
                turnStatusClockBucket = clockBucket
                turnElapsedMs = elapsed
            }
            val wait = if (showClock) 1_000L else (TURN_STATUS_CLOCK_AFTER_MS - elapsed).coerceAtLeast(200L)
            setTimeout(pagerId, wait.toInt()) { tick() }
        }
        tick()
    }

    private fun syncBusyLabel(): String = when (connectionMode) {
        DshConnectionMode.SSH -> "远程 DSH 正在同步，暂不能发送"
        DshConnectionMode.RELAY -> "扫码连接正在同步，暂不能发送"
        DshConnectionMode.LOCAL -> "本地 DSH 正在同步，暂不能发送"
    }

    private fun refreshMountedSessionRenderTrees() {
        conversationPanelIds.toList().forEach { refreshSessionRenderTree(it) }
    }

    private fun refreshSessionRenderTree(sessionId: String) {
        val list = messageScrollerRefs[sessionId]?.view ?: return
        (list.contentView as? ListContentView)?.createRenderViewsOnVisibleRect()
    }

    private fun perfLog(stage: String, startedAt: TimeMark? = null) {
        val elapsed = startedAt?.elapsedNow()?.inWholeMilliseconds?.let { " +${it}ms" } ?: ""
        // BridgeModule.log is asynchronous on Android and can be printed
        // seconds after the event. KLog keeps the timing trace on Kuikly's
        // logging path so Logcat timestamps remain meaningful.
        KLog.i("DshPerf", "[DshPerf] $stage$elapsed")
    }

    private fun sessionRenderLog(message: String) {
        KLog.i("DshSessionRender", "[DshSessionRender] $message")
    }

    private fun realizeSessionAfterData(
        sessionId: String,
        scrollToEndAfterLoad: Boolean = true,
    ) {
        refreshSessionRenderTree(sessionId)
        addTaskWhenPagerUpdateLayoutFinish {
            refreshSessionRenderTree(sessionId)
            if (scrollToEndAfterLoad && activeSessionId == sessionId) scrollMessagesToEnd()
        }
        setTimeout(pagerId, 16) {
            refreshSessionRenderTree(sessionId)
            if (scrollToEndAfterLoad && activeSessionId == sessionId) scrollMessagesToEnd()
        }
    }

    private fun loadCachedHistory(sessionId: String) {
        messages = sessionMessageState(sessionId, loadFromDisk = false)
        ensureConversationPanel(sessionId)
        loadMessagesFromDisk(sessionId)
    }

    private fun sessionMessageState(
        sessionId: String,
        loadFromDisk: Boolean = true,
        scrollToEndAfterLoad: Boolean = true,
    ): ObservableList<DshMessage> {
        sessionMessageStates[sessionId]?.let { return it }
        val state = ObservableList<DshMessage>()
        sessionMessageStates[sessionId] = state
        if (loadFromDisk) loadMessagesFromDisk(sessionId, scrollToEndAfterLoad)
        return state
    }

    /**
     * Warm every known conversation after the session index is available.
     * Reads are serialized through one background coroutine because the local
     * SQLite driver is shared by the page and should not be queried concurrently.
     */
    private fun preloadAllSessionMessages() {
        val preloadId = ++preloadTraceSequence
        val queuedAt = TimeSource.Monotonic.markNow()
        val sessionIds = sessions.toList().map { it.id }
        perfLog("preload.$preloadId.queued sessions=${sessionIds.size}", queuedAt)
        // Load data first. Do not mount empty ListViews: LazyLoop initializes
        // its visible range from the initial list and may not realize the
        // first items when the list is populated later.
        sessionIds.forEach { sessionMessageState(it, loadFromDisk = false) }
        val store = localStore ?: run {
            sessionIds.forEach {
                sessionMessageReady.add(it)
                completePendingSessionSelection(it)
            }
            return
        }
        val pending = sessionIds
            .filterNot { sessionMessageReady.contains(it) }
            .filter { pendingLocalMessageReads.add(it) }
        if (pending.isEmpty()) {
            perfLog("preload.$preloadId.nothing-pending", queuedAt)
            return
        }
        perfLog("preload.$preloadId.pending count=${pending.size}", queuedAt)
        localReadScope.launch {
            perfLog("preload.$preloadId.coroutine.started", queuedAt)
            pending.forEach { sessionId ->
                val readStartedAt = TimeSource.Monotonic.markNow()
                perfLog("preload.$preloadId.sqlite.begin:$sessionId", queuedAt)
                val loaded = runCatching { store.loadMessages(activeConnectionId, sessionId) }
                    .getOrDefault(emptyList())
                    .filterNot { it.isRuntimeContextSnapshot() }
                val queryFinishedAt = TimeSource.Monotonic.markNow()
                val queryMs = readStartedAt.elapsedNow().inWholeMilliseconds
                perfLog(
                    "preload.$preloadId.sqlite.end:$sessionId messages=${loaded.size} query=${queryMs}ms",
                    queuedAt,
                )
                setTimeout(pagerId, 0) {
                    val uiCallbackAt = TimeSource.Monotonic.markNow()
                    pendingLocalMessageReads.remove(sessionId)
                    val state = sessionMessageStates[sessionId] ?: return@setTimeout
                    sessionMessageReady.add(sessionId)
                    val uiWaitMs = queryFinishedAt.elapsedNow().inWholeMilliseconds
                    perfLog(
                        "preload.$preloadId.ui.callback:$sessionId uiWait=${uiWaitMs}ms callbackDelay=${uiCallbackAt.elapsedNow().inWholeMilliseconds}ms",
                        queuedAt,
                    )
                    perfLog(
                        "sessionData.disk.done:$sessionId messages=${loaded.size} query=${queryMs}ms uiWait=${uiWaitMs}ms",
                        readStartedAt,
                    )
                    if (state.isEmpty() && loaded.isNotEmpty() &&
                        sessions.firstOrNull { it.id == sessionId }?.blank != true
                    ) {
                        state.addAll(loaded)
                        remountConversationList(sessionId)
                        perfLog("sessionData.ui.applied:$sessionId messages=${loaded.size}")
                    }
                    if (conversationPanelIds.size < CONVERSATION_PANEL_CACHE_LIMIT) {
                        ensureConversationPanel(sessionId)
                    }
                    realizeSessionAfterData(sessionId, scrollToEndAfterLoad = false)
                    perfLog("preload.$preloadId.ui.applied:$sessionId", queuedAt)
                    completePendingSessionSelection(sessionId)
                }
            }
            perfLog("preload.$preloadId.coroutine.finished", queuedAt)
        }
    }

    private fun loadMessagesFromDisk(
        sessionId: String,
        scrollToEndAfterLoad: Boolean = true,
    ) {
        if (localStore == null || !pendingLocalMessageReads.add(sessionId)) return
        val readQueuedAt = TimeSource.Monotonic.markNow()
        perfLog("sessionRead.queued:$sessionId", readQueuedAt)
        localReadScope.launch {
            val readStartedAt = TimeSource.Monotonic.markNow()
            perfLog("sessionRead.coroutine.started:$sessionId", readQueuedAt)
            perfLog("sessionRead.sqlite.begin:$sessionId", readQueuedAt)
            val loaded = runCatching { localStore?.loadMessages(activeConnectionId, sessionId).orEmpty() }
                    .getOrDefault(emptyList())
                    .filterNot { it.isRuntimeContextSnapshot() }
                val queryFinishedAt = TimeSource.Monotonic.markNow()
            val queryMs = readStartedAt.elapsedNow().inWholeMilliseconds
            perfLog("sessionRead.sqlite.end:$sessionId messages=${loaded.size} query=${queryMs}ms", readQueuedAt)
            setTimeout(pagerId, 0) {
                pendingLocalMessageReads.remove(sessionId)
                val state = sessionMessageStates[sessionId] ?: return@setTimeout
                sessionMessageReady.add(sessionId)
                val uiWaitMs = queryFinishedAt.elapsedNow().inWholeMilliseconds
                perfLog("sessionRead.ui.callback:$sessionId uiWait=${uiWaitMs}ms", readQueuedAt)
                perfLog(
                    "sessionData.disk.done:$sessionId messages=${loaded.size} query=${queryMs}ms uiWait=${uiWaitMs}ms",
                    readStartedAt,
                )
                // A remote history response or a new local prompt wins over
                // a disk snapshot that finishes later. The state is keyed by
                // session ID, so an inactive session can be updated safely.
                if (state.isEmpty() && loaded.isNotEmpty() &&
                    sessions.firstOrNull { it.id == sessionId }?.blank != true
                ) {
                    state.addAll(loaded)
                    remountConversationList(sessionId)
                    perfLog("sessionData.ui.applied:$sessionId messages=${loaded.size}")
                }
                ensureConversationPanel(sessionId)
                realizeSessionAfterData(sessionId, scrollToEndAfterLoad)
                completePendingSessionSelection(sessionId)
            }
        }
    }

    private fun completePendingSessionSelection(sessionId: String) {
        if (!pendingSessionSelections.remove(sessionId)) return
        setTimeout(pagerId, 0) {
            if (activeSessionId != sessionId) selectSession(sessionId)
        }
    }

    private fun warmRecentSessionCache(
        sessionIds: kotlin.collections.List<String> = sessions.asSequence()
            .map { it.id }
            .filter { it != activeSessionId && !conversationPanelIds.contains(it) }
            .take(SESSION_CACHE_WARM_LIMIT)
            .toList(),
        index: Int = 0,
        scrollToEndAfterLoad: Boolean = true,
    ) {
        if (index >= sessionIds.size) return
        sessionMessageState(
            sessionIds[index],
            loadFromDisk = true,
            scrollToEndAfterLoad = scrollToEndAfterLoad,
        )
        if (sessionMessageReady.contains(sessionIds[index])) {
            ensureConversationPanel(sessionIds[index])
        }
        setTimeout(pagerId, SESSION_CACHE_WARM_INTERVAL_MS) {
            warmRecentSessionCache(sessionIds, index + 1, scrollToEndAfterLoad)
        }
    }

    private fun ensureConversationPanel(sessionId: String) {
        if (conversationPanelIds.contains(sessionId)) return
        if (conversationPanelIds.size >= CONVERSATION_PANEL_CACHE_LIMIT) {
            val evictIndex = conversationPanelIds.indexOfFirst { it != activeSessionId }
            if (evictIndex >= 0) {
                val evictedId = conversationPanelIds.removeAt(evictIndex)
                messageScrollerRefs.remove(evictedId)
            }
        }
        conversationPanelIds.add(sessionId)
    }

    private fun sendDraft() {
        dismissKeyboard()
        val prompt = draft.trim()
        if (prompt.isEmpty() || streaming) return
        val hostRepository = repository as? DshRemoteRepository
        if (hostRepository == null) {
            connectionLabel = "本地内核尚未连接"
            messages.add(DshMessage(
                "send-engine-error-${messages.size}",
                DshMessageRole.ERROR,
                "本地 Harness 尚未连接，请稍候再试。",
            ))
            return
        }
        if (!hostRepository.isProductReady()) {
            connectionLabel = syncBusyLabel()
            return
        }
        if (sessions.isEmpty()) {
            connectionLabel = "正在创建会话"
            hostRepository.createSession(null, { sessionId ->
                sessions.add(DshSession(sessionId, "新会话", "Host", "", blank = true))
                refreshVisibleSessions()
                runCatching { localStore?.replaceSessions(activeConnectionId, sessions.toList()) }
                activeSessionId = sessionId
                loadModels(sessionId)
                sendDraft()
            }, { error ->
                connectionLabel = "会话创建失败"
                messages.add(DshMessage(
                    "send-session-error-${messages.size}",
                    DshMessageRole.ERROR,
                    "无法创建会话：$error",
                ))
            })
            return
        }
        val sessionId = activeSessionId
        val user = DshMessage("user-${messages.size}", DshMessageRole.USER, prompt)
        val assistantId = "assistant-${messages.size}"
        val reasoningId = "$assistantId-reasoning"
        val wasEmpty = messages.isEmpty()
        messages.add(user)
        // DSH ChatView keeps the assistant node out of the flow until the
        // first token. The turn-status row ("Deep diving...") occupies that
        // gap so LazyLoop never has to realize an empty markdown bubble.
        sessionMessageStates[sessionId] = messages
        if (wasEmpty) remountConversationList(sessionId)
        pinFollowListTail()
        scrollMessagesToMessage(user.id)
        streamingTurnAnchorAssistantId = messages.lastOrNull(::dshIsLiveAssistantText)?.id.orEmpty()
        streamingAssistantId = ""
        streamingAssistantRootId = assistantId
        streamingAssistantSegment = 0
        streamingReasoningId = reasoningId
        streamingReasoningContent = ""
        streamingAssistantContent = ""
        pendingAssistantDelta.setLength(0)
        assistantFlushScheduled = false
        draft = ""
        inputView?.setText("")
        streaming = true
        stopButtonVisible = true
        connectionLabel = "正在生成"
        syncTurnStatusTicker()
        streamHandle = hostRepository.streamReply(
            pagerId = pagerId,
            sessionId = sessionId,
            prompt = prompt,
            onDelta = { delta, isReasoning ->
                if (isReasoning) queueReasoningDelta(reasoningId, delta)
                else queueAssistantDelta(assistantId, delta)
            },
            onComplete = { result ->
                if (!connectionCoordinator.isActive(connectionMode)) return@streamReply
                flushAssistantDelta()
                if (streamingAssistantId.isEmpty() && result.isNotEmpty()) {
                    ensureStreamingAssistantSegment()
                }
                // A turn may contain several assistant text blocks separated by
                // tool calls. The current segment already contains the final
                // block; using the turn-wide accumulator here would move all
                // earlier text back into this last row.
                val completedContent = streamingAssistantContent.ifEmpty { result }
                DshStreamLog.i(
                    "ui.complete session=$sessionId resultChars=${result.length} liveChars=${streamingAssistantContent.length} preview='${DshStreamLog.preview(completedContent)}'",
                )
                settleStreamingMessage(DshMessageRole.ASSISTANT, completedContent)
                persistMessages(sessionId)
                connectionLabel = "已连接"
                streamHandle = null
            },
            onError = { error ->
                if (!connectionCoordinator.isActive(connectionMode)) return@streamReply
                if (dshIsTransportInterrupt("", error)) {
                    DshStreamLog.i("ui.prompt-interrupt session=$sessionId message='${DshStreamLog.preview(error)}'")
                    return@streamReply
                }
                flushAssistantDelta()
                ensureStreamingAssistantSegment()
                DshStreamLog.i("ui.error session=$sessionId message='${DshStreamLog.preview(error)}'")
                settleStreamingMessage(DshMessageRole.ERROR, error)
                persistMessages(sessionId)
                connectionLabel = "已连接"
                streamHandle = null
            },
        )
    }

    private fun stopStream() {
        if (!stopButtonVisible) return
        dismissKeyboard()
        streamHandle?.cancel()
        streamHandle = null
        flushAssistantDelta()
        ensureStreamingAssistantSegment()
        val stoppedContent = streamingAssistantContent + "\n\n*已停止*"
        sessionRenderLog("stream.stop.begin session=$activeSessionId messages=${messages.size} chars=${stoppedContent.length}")
        settleStreamingMessage(DshMessageRole.ASSISTANT, stoppedContent)
        persistMessages(activeSessionId)
        connectionLabel = "已连接"
        sessionRenderLog("stream.stop.state-finalized session=$activeSessionId messages=${messages.size}")
    }

    private fun cancelStreamingForSessionSwitch() {
        if (!streaming && !stopButtonVisible) return
        streamHandle?.cancel()
        streamHandle = null
        val partial = streamingAssistantContent + pendingAssistantDelta.toString()
        if (streamingAssistantId.isNotEmpty()) {
            updateStreamingMessage(partial, streaming = false)
        }
        finalizeStreamingReasoning()
        streamingAssistantId = ""
        streamingAssistantRootId = ""
        streamingAssistantSegment = 0
        streamingReasoningId = ""
        streamingReasoningContent = ""
        pendingAssistantDelta.setLength(0)
        streamingAssistantContent = ""
        assistantFlushScheduled = false
        streamingTurnAnchorAssistantId = ""
        streaming = false
        stopButtonVisible = false
        syncTurnStatusTicker()
    }

    private fun dismissKeyboard() {
        if (!inputFocused && keyboardHeight <= 0f) return
        inputFocused = false
        inputView?.blur()
        bridgeModule.closeKeyboard()
        keyboardHeight = 0f
    }

    private fun updateKeyboard(params: KeyboardParams) {
        keyboardAnimation = Animation.easeInOut(ANIMATION_DURATION_S)
        keyboardHeight = effectiveKeyboardHeight(params.height)
        // Closing the keyboard after send must not undo the scroll to the
        // newly sent user message. Scroll to the end only when the composer
        // is opening while no response is being anchored.
        if (keyboardHeight > 0f && !streaming) scrollMessagesToEnd()
    }

    private fun effectiveKeyboardHeight(rawHeight: Float): Float {
        if (rawHeight <= 0f) return 0f
        // Kuikly's Android watcher already reports IME height minus the
        // navigation bar. Subtracting the safe area here would lift the
        // composer a second time and leave a visible gap above the keyboard.
        return if (pagerData.isAndroid) {
            rawHeight
        } else {
            (rawHeight - pagerData.safeAreaInsets.bottom).coerceAtLeast(0f)
        }
    }

    private fun loadModels(sessionId: String) {
        val hostRepository = repository ?: return
        hostRepository.loadModels(sessionId, { loaded ->
            if (activeSessionId != sessionId) return@loadModels
            selectedModelLabel = loaded.current.name
            modelOptions.clear()
            modelOptions.addAll(loaded.options)
            modelPickerBusy = false
            modelPickerError = if (loaded.routable) "" else "当前模型不可用，请选择其他模型。"
        }, { error ->
            if (activeSessionId != sessionId) return@loadModels
            modelPickerBusy = false
            modelPickerError = error
        })
    }

    private fun openModelPicker() {
        if (sessions.isEmpty()) return
        dismissKeyboard()
        attachmentMenuVisible = false
        modelPickerVisible = true
        modelPickerBusy = true
        modelPickerError = ""
        loadModels(activeSessionId)
    }

    private fun selectModel(option: DshModelOption) {
        val hostRepository = repository ?: return
        modelPickerBusy = true
        modelPickerError = ""
        hostRepository.selectModel(activeSessionId, option, { selected ->
            selectedModelLabel = selected.name
            modelPickerBusy = false
            modelPickerVisible = false
            val currentOptions = modelOptions.toList()
            modelOptions.clear()
            modelOptions.addAll(currentOptions.map {
                it.copy(selected = it.provider == selected.provider && it.model == selected.model)
            })
        }, { error ->
            modelPickerBusy = false
            modelPickerError = error
        })
    }

    private fun toggleVoice() {
        dismissKeyboard()
        attachmentMenuVisible = false
        voiceActive = !voiceActive
        connectionLabel = if (voiceActive) "正在聆听" else "已连接"
    }

    private fun queueAssistantDelta(id: String, delta: String) {
        if (delta.isEmpty()) return
        if (!streaming || streamingAssistantRootId != id) return
        ensureStreamingAssistantSegment()
        pendingAssistantDelta.append(delta)
        val firstPaint = streamingAssistantContent.isEmpty()
        if (assistantFlushScheduled && !firstPaint) return
        assistantFlushScheduled = true
        setTimeout(pagerId, if (firstPaint) 0 else STREAM_FLUSH_INTERVAL_MS) {
            assistantFlushScheduled = false
            flushAssistantDelta()
        }
    }

    private fun queueReasoningDelta(id: String, delta: String) {
        if (delta.isEmpty() || streamingReasoningId != id) return
        streamingReasoningContent += delta
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) {
            messages[index] = messages[index].copy(
                content = streamingReasoningContent,
                streaming = true,
                isReasoning = true,
            )
        } else {
            messages.add(DshMessage(id, DshMessageRole.ASSISTANT, streamingReasoningContent, streaming = true, isReasoning = true))
        }
        realizeVisibleMessages()
        if (followListTail) scrollMessagesToEnd()
    }

    private fun flushAssistantDelta() {
        if (streamingAssistantId.isEmpty() || pendingAssistantDelta.isEmpty()) return
        streamingAssistantContent += pendingAssistantDelta.toString()
        pendingAssistantDelta.setLength(0)
        DshStreamLog.i(
            "ui.flush id=$streamingAssistantId chars=${streamingAssistantContent.length} preview='${DshStreamLog.preview(streamingAssistantContent)}'",
        )
        // Keep the ObservableList row stable while tokens arrive. `messages[i] =
        // copy()` is remove+add; LazyLoop treats an append at currentEnd as
        // "behind the visible range" and will not build the cell until scroll.
        // DshMarkdown already reads `streamingAssistantContent` via liveContent.
        insertLiveAssistantRow()
        ensureLiveMessageCell()
        refreshSessionRenderTree(activeSessionId)
        scrollMessagesToEnd()
    }

    /**
     * A live assistant response is an ordered sequence of text segments and
     * tool cards. Start a new row lazily after a tool card so the next delta is
     * placed after that card instead of being appended to the old row.
     */
    private fun ensureStreamingAssistantSegment() {
        if (streamingAssistantId.isNotEmpty()) return
        if (streamingAssistantRootId.isEmpty()) return
        val id = if (streamingAssistantSegment == 0) {
            streamingAssistantRootId
        } else {
            "$streamingAssistantRootId-segment-${streamingAssistantSegment}"
        }
        streamingAssistantId = id
        if (streamingAssistantContent.isEmpty() && pendingAssistantDelta.isEmpty()) {
            // Inserting an empty assistant into a brand-new List (only the user
            // bubble) is "add behind currentEnd". LazyLoop will not build that
            // cell until a real scroll, and DshMessageRow also skips mounting
            // Markdown when the first paint is empty. Wait for the first flush.
            return
        }
        insertLiveAssistantRow()
    }

    private fun insertLiveAssistantRow() {
        val id = streamingAssistantId
        if (id.isEmpty() || messages.any { it.id == id }) return
        // Keep content empty until settle. The first-flush snapshot must not
        // become the display source; DshMarkdown reads the live buffer.
        messages.add(DshMessage(id, DshMessageRole.ASSISTANT, "", streaming = true))
        ensureLiveMessageCell()
    }

    /**
     * vforLazy only creates items inside `[currentStart, currentEnd)`. Appending
     * the first assistant after the list was mounted with a single user bubble
     * lands at `currentEnd`. `setContentOffset` is a no-op when content is
     * shorter than the viewport (new session, first turn), so the cell never
     * appears until the user drags. `scrollToPosition` is what actually builds it.
     */
    private fun ensureLiveMessageCell() {
        if (!followListTail) return
        val id = streamingAssistantId
        if (id.isEmpty()) return
        if (messageRowRefs[messageRowKey(activeSessionId, id)]?.view != null) return
        val list = messageScrollerRefs[activeSessionId]?.view ?: return
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return
        DshStreamLog.i("ui.realize-live-cell id=$id index=$index size=${messages.size}")
        list.scrollToPosition(index, 0f, false)
    }

    /** Close the current text row immediately before the next tool card. */
    private fun splitStreamingAssistantBeforeTool() {
        if (!streaming || streamingAssistantRootId.isEmpty()) return
        flushAssistantDelta()
        val id = streamingAssistantId
        if (id.isNotEmpty()) {
            val index = messages.indexOfFirst { it.id == id }
            if (index >= 0) {
                val current = messages[index]
                val text = current.content.ifEmpty { streamingAssistantContent }
                if (text.isEmpty()) {
                    messages.removeAt(index)
                } else {
                    messages[index] = current.copy(content = text, streaming = false)
                    realizeVisibleMessages()
                }
            }
        }
        streamingAssistantId = ""
        streamingAssistantContent = ""
        streamingAssistantSegment += 1
        pendingAssistantDelta.setLength(0)
        assistantFlushScheduled = false
    }

    private fun updateStreamingMessage(content: String, streaming: Boolean, isReasoning: Boolean = false) {
        val index = messages.indexOfFirst { it.id == streamingAssistantId }
        if (index < 0) return
        messages[index] = messages[index].copy(
            content = content,
            streaming = streaming,
            isReasoning = isReasoning,
        )
        if (index >= messages.size - 1) realizeVisibleMessages()
    }

    private fun finalizeStreamingReasoning() {
        if (streamingReasoningId.isEmpty()) return
        val index = messages.indexOfFirst { it.id == streamingReasoningId }
        if (index >= 0) {
            messages[index] = messages[index].copy(streaming = false, isReasoning = true)
        }
    }

    private fun scrollMessagesToEnd() {
        if (!followListTail) return
        val generation = ++scrollSettleGeneration
        ensureLiveMessageCell()
        realizeVisibleMessages()
        addTaskWhenPagerUpdateLayoutFinish {
            settleScrollToEnd(generation, 0)
        }
    }

    private fun scrollMessagesToMessage(messageId: String) {
        val generation = ++scrollSettleGeneration
        addTaskWhenPagerUpdateLayoutFinish {
            settleScrollToMessage(messageId, generation, 0)
        }
    }

    /**
     * Markdown and LazyLoop can add/layout children over several frames.
     * Re-apply the bottom offset while that burst settles, otherwise the first
     * offset is calculated from a shorter content height and the user sees the
     * list walk down a few screens after launch.
     */
    private fun settleScrollToEnd(generation: Int, attempt: Int) {
        if (generation != scrollSettleGeneration || !followListTail) return
        ensureLiveMessageCell()
        realizeVisibleMessages()
        scrollMessagesToEndAfterLayout()
        if (attempt >= SCROLL_SETTLE_ATTEMPTS) return
        setTimeout(pagerId, SCROLL_SETTLE_DELAYS_MS[attempt]) {
            addTaskWhenPagerUpdateLayoutFinish {
                settleScrollToEnd(generation, attempt + 1)
            }
        }
    }

    private fun realizeVisibleMessages() {
        val scroller = messageScrollerRefs[activeSessionId]?.view ?: return
        val content = scroller.contentView as? ListContentView ?: return
        content.flexNode.markDirty()
        content.createRenderViewsOnVisibleRect()
    }

    private fun onConversationUserScroll(params: ScrollParams) {
        val maxOffset = (params.contentHeight - params.viewHeight).coerceAtLeast(0f)
        val nearBottom = params.offsetY >= maxOffset - FOLLOW_LIST_SLACK_PX
        if (nearBottom) {
            followListTail = true
            return
        }
        if (params.isDragging) cancelFollowListTail()
    }

    private fun cancelFollowListTail() {
        followListTail = false
        scrollSettleGeneration += 1
    }

    private fun pinFollowListTail() {
        followListTail = true
    }

    private fun scrollMessagesToEndAfterLayout() {
        if (!followListTail) return
        val scroller = messageScrollerRefs[activeSessionId]?.view ?: return
        val contentHeight = scroller.contentView?.flexNode?.layoutFrame?.height ?: return
        val viewportHeight = scroller.flexNode?.layoutFrame?.height ?: return
        scroller.setContentOffset(0f, (contentHeight - viewportHeight).coerceAtLeast(0f), animated = false)
    }

    private fun settleScrollToMessage(messageId: String, generation: Int, attempt: Int) {
        if (generation != scrollSettleGeneration) return
        val row = messageRowRefs[messageRowKey(activeSessionId, messageId)]?.view
        val rowY = row?.flexNode?.layoutFrame?.y
        if (rowY != null) {
            messageScrollerRefs[activeSessionId]?.view?.setContentOffset(
                0f,
                rowY.coerceAtLeast(0f),
                animated = false,
            )
        }
        if (attempt >= SCROLL_SETTLE_ATTEMPTS) return
        setTimeout(pagerId, SCROLL_SETTLE_DELAYS_MS[attempt]) {
            addTaskWhenPagerUpdateLayoutFinish {
                settleScrollToMessage(messageId, generation, attempt + 1)
            }
        }
    }

    private fun messageRowKey(sessionId: String, messageId: String): String = "$sessionId:$messageId"

    private fun settleStreamingMessage(role: DshMessageRole, content: String) {
        val id = streamingAssistantId
        if (id.isNotEmpty()) {
            val sessionId = activeSessionId
            val finalContent = content.ifEmpty { streamingAssistantContent }
            finalizeStreamingReasoning()
            val index = messages.indexOfFirst { it.id == id }
            if (index >= 0) {
                messages[index] = messages[index].copy(
                    role = role,
                    content = finalContent,
                    streaming = false,
                )
            } else {
                messages.add(DshMessage(id, role, finalContent, streaming = false))
            }
            realizeVisibleMessages()
            DshStreamLog.i(
                "ui.settle id=$id role=$role index=$index chars=${finalContent.length} preview='${DshStreamLog.preview(finalContent)}'",
            )
            streamingReasoningId = ""
            streamingReasoningContent = ""
            pendingAssistantDelta.setLength(0)
            stopButtonVisible = false
            streaming = false
            streamingAssistantContent = finalContent
            syncTurnStatusTicker()
            addTaskWhenPagerUpdateLayoutFinish {
                if (activeSessionId != sessionId) return@addTaskWhenPagerUpdateLayoutFinish
                if (!streaming && streamingAssistantId == id) {
                    val stored = messages.firstOrNull { it.id == id }?.content.orEmpty()
                    if (stored.length >= finalContent.length) {
                        streamingAssistantId = ""
                        streamingAssistantRootId = ""
                        streamingAssistantSegment = 0
                        streamingTurnAnchorAssistantId = ""
                        if (streamingAssistantContent == finalContent) {
                            streamingAssistantContent = ""
                        }
                    }
                }
                refreshSessionRenderTree(sessionId)
                sessionRenderLog("stream.render.layout session=$sessionId messages=${messages.size}")
                setTimeout(pagerId, 16) {
                    if (activeSessionId != sessionId) return@setTimeout
                    addTaskWhenPagerUpdateLayoutFinish {
                        if (activeSessionId != sessionId) return@addTaskWhenPagerUpdateLayoutFinish
                        refreshSessionRenderTree(sessionId)
                        sessionRenderLog("stream.render.refresh session=$sessionId messages=${messages.size}")
                    }
                }
            }
            return
        }
        releaseStreamingUi()
    }

    private fun releaseStreamingUi() {
        streamingAssistantId = ""
        streamingAssistantRootId = ""
        streamingAssistantSegment = 0
        streamingTurnAnchorAssistantId = ""
        streamingReasoningId = ""
        streamingReasoningContent = ""
        pendingAssistantDelta.setLength(0)
        streaming = false
        stopButtonVisible = false
        streamingAssistantContent = ""
        syncTurnStatusTicker()
    }

    private fun persistMessages(sessionId: String) {
        val snapshot = messages.toList()
        sessionMessageStates[sessionId] = messages
        runCatching { localStore?.replaceMessages(activeConnectionId, sessionId, snapshot) }
    }

    private fun replaceMessagesIfChanged(next: List<DshMessage>, force: Boolean = false) {
        val filtered = next.filterNot { it.isRuntimeContextSnapshot() }
        if (streaming && isRemoteHost && !force) {
            // History is a snapshot that can arrive while the current turn is
            // still being projected. Replacing the observable list here drops
            // optimistic text segments and their in-order tool cards.
            DshStreamLog.i(
                "ui.replace-messages deferred-during-stream from=${messages.size} to=${filtered.size}",
            )
            return
        }
        val current = messages.toList()
        if (current == filtered) return
        if (dshMessagesVisuallyEqual(current, filtered)) {
            DshStreamLog.i(
                "ui.replace-messages skip-visual-equal from=${current.size} force=$force",
            )
            return
        }
        val remount = current.isEmpty() && filtered.isNotEmpty()
        DshStreamLog.i(
            "ui.replace-messages from=${current.size} to=${filtered.size} streaming=$streaming force=$force remount=$remount preview='${DshStreamLog.preview(filtered.lastOrNull()?.content.orEmpty())}'",
        )
        applyMessagesInPlace(filtered)
        sessionMessageStates[activeSessionId] = messages
        if (remount) remountConversationList(activeSessionId)
    }

    private fun applyMessagesInPlace(next: List<DshMessage>) {
        val shared = minOf(messages.size, next.size)
        for (index in 0 until shared) {
            if (messages[index] != next[index]) messages[index] = next[index]
        }
        when {
            next.size < messages.size -> {
                for (index in messages.lastIndex downTo next.size) {
                    messages.removeAt(index)
                }
            }
            next.size > messages.size -> {
                messages.addAll(next.subList(messages.size, next.size))
            }
        }
    }

    companion object {
        private const val BG = 0xFFF7F9FA
        private const val LOCAL_ENGINE_URL = "http://127.0.0.1:3080"
        private const val ENGINE_CONNECT_RETRIES = 60
        private const val ENGINE_RETRY_DELAY_MS = 1_000
        private const val ANIMATION_DURATION_MS = 240
        private const val ANIMATION_DURATION_S = 0.24f
        private const val STREAM_FLUSH_INTERVAL_MS = 16
    }
}
