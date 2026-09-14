package com.example.dsh.dsh

import com.example.dsh.base.BasePager
import com.example.dsh.base.bridgeModule
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.datetime.DateTime
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
import com.tencent.kuikly.core.views.SelectionType
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
        get() = connectionMode != DshConnectionMode.LOCAL
    private var directBaseUrl = ""
    private var directAuthToken = ""
    private var remoteProfileId by observable(DshSessionScope.DEFAULT_REMOTE_PROFILE_ID)
    private var sshHost by observable("")
    private var sshUser by observable("")
    private var sshPort by observable("22")
    private var sshDshPort by observable("3080")
    private var sshKeyId by observable("")
    private var sshFingerprint by observable("")
    private var sshKeyLabel by observable("未导入私钥")
    private var sshKeyPassphrase by observable("")
    /** Launch token printed by `dsh web`; SSH has no plugin to discover it. */
    private var sshAuthToken by observable("")
    private var authenticator: DshStoredHostAuthenticator? = null
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
    private var hostRuntimePhase = DshHostRuntimePhase.DISCONNECTED
    private var apiKeyDraft by observable("")
    private var credentialSetupVisible by observable(false)
    private var credentialSetupBusy by observable(false)
    private var credentialSetupError by observable("")
    private var credentialSetupTitle by observable("添加一个 API Key 开始使用")
    private var appearanceVisible by observable(false)
    private var actionSheetMessageId by observable("")
    private var actionSheetCodeBlockCount by observable(0)
    private var actionSheetCodeBlocks = emptyList<DshCodeBlock>()
    private var actionSheetFormulaCount by observable(0)
    private var actionSheetFormulas = emptyList<String>()
    private var actionSheetPoint = 0f to 0f
    private var selectionMessageId by observable("")
    private var batchSelectionActive by observable(false)
    private var batchSelectedIds by observable(emptySet<String>())
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
    private var inputView: InputView? = null
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
    /** Set once this turn projected a committed `assistant/message` into its own rows. */
    private var assistantBlocksProjected = false
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
    private val stagedAttachments by observableList<DshStagedAttachment>()
    private var attachmentNotice by observable("")
    private var attachmentPickBusy = false
    private var attachmentViewerRef by observable<DshImageAttachmentRef?>(null)
    private var stagedAttachmentSequence = 0
    /** Prompt text of the turn whose images failed, so "retry" can resend it. */
    private var failedAttachmentPrompt = ""
    /** Guards the first `session/create` so a fast send cannot start a second one. */
    private var sessionCreateInFlight = false
    private var pendingSendAfterCreate = false
    private var queueDockExpanded by observable(false)
    private val queueItems by observableList<DshQueueItem>()
    private var queueActionBusy by observable(false)
    private val jobItems by observableList<DshJobItem>()
    private var jobsPanelExpanded by observable(false)
    private var jobsNow by observable(0L)
    private var jobsClockScheduled by observable(false)
    private val workspaceGroups by observableList<DshWorkspaceGroup>()
    private var sessionMenuId by observable("")
    private var renameSessionId by observable("")
    private var renameDraft by observable("")
    private var renameError by observable("")
    private var renameBusy by observable(false)
    private var renameInputView: InputView? = null
    private var archiveSessionId by observable("")
    private var archiveError by observable("")
    private var archiveBusy by observable(false)
    private var archiveListVisible by observable(false)
    private val archivedSessions by observableList<DshSession>()
    private var pluginsVisible by observable(false)
    private var pluginsLoading by observable(false)
    private var pluginsError by observable("")
    private var pluginQuery = ""
    private var pluginStatusFilter by observable<DshPluginStatus?>(null)
    private var pluginExpandedId by observable("")
    private var pluginControllableIds by observable(emptySet<String>())
    private var pluginControlBusyId by observable("")
    private var pluginConfirmEntryId by observable("")
    private var pluginConfirmAction = ""
    private var pluginConfirmMessage by observable("")
    private var pluginTotal by observable(0)
    private var pluginFailedCount by observable(0)
    /** Last snapshot the Host answered; the filtered view is derived from it. */
    private var pluginEntries = emptyList<DshPluginEntry>()
    private var pluginPresetsByModule = emptyMap<String, List<String>>()
    private val pluginRows by observableList<DshPluginEntry>()
    private val pluginStatusChips by observableList<DshPluginStatusChip>()
    private val pluginBrokenPresets by observableList<String>()
    private var logsVisible by observable(false)
    private var logQuery = ""
    private var logMinLevel by observable(DshLogLevel.DEBUG)
    private var logEventType by observable("")
    private var logSessionScoped by observable(false)
    private var logWindowMs by observable(0L)
    /** Snapshot clock: rows show their age against the moment the list was taken. */
    private var logNow by observable(0L)
    private var logTotal by observable(0)
    private var logMatched by observable(0)
    private var logDetail by observable<DshLogRecord?>(null)
    private val logRecords by observableList<DshLogRecord>()
    private val logTypeChips by observableList<DshLogTypeChip>()
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
            "direct" -> DshConnectionMode.DIRECT
            else -> DshConnectionMode.RELAY
        }
        remoteProfileId = pageData.params.optString("profileId").ifEmpty { DshSessionScope.DEFAULT_REMOTE_PROFILE_ID }
        directBaseUrl = pageData.params.optString("baseUrl").ifEmpty {
            runCatching { localStore?.loadSetting(DIRECT_BASE_URL_KEY) }.getOrNull().orEmpty()
        }
        directAuthToken = runCatching { localStore?.loadSetting(DIRECT_AUTH_TOKEN_KEY) }.getOrNull().orEmpty()
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
        setTimeout(pagerId, SESSION_CACHE_WARM_START_DELAY_MS) {
            warmRecentSessionCache(scrollToEndAfterLoad = false)
        }
        setTimeout(pagerId, 0) { startConnection() }
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
                    backgroundColor(theme.background)
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
                                backgroundColor(theme.background)
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
                                stagedAttachments = { ctx.stagedAttachments },
                                attachmentNotice = { ctx.attachmentNotice },
                                voiceActive = { ctx.voiceActive },
                                onOpenModels = { ctx.openModelPicker() },
                                onToggleAttachments = { ctx.toggleAttachmentMenu() },
                                onPickImages = { ctx.pickAttachmentImages() },
                                onCaptureImage = { ctx.captureAttachmentImage() },
                                onRemoveAttachment = { ctx.removeAttachment(it) },
                                onRetryAttachment = { ctx.retryAttachment(it) },
                                onPreviewAttachment = { ctx.previewStagedAttachment(it) },
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
                                onLongPressMessage = { message, x, y ->
                                    ctx.openMessageActions(message, x, y)
                                },
                                batchSelectionActive = { ctx.batchSelectionActive },
                                isMessageBatchSelected = { it in ctx.batchSelectedIds },
                                onToggleMessageBatchSelection = { ctx.toggleBatchSelection(it) },
                                onSelectionCancelled = { ctx.selectionMessageId = "" },
                                attachmentDataUrl = { ctx.attachmentDataUrl(it) },
                                onOpenAttachment = { ctx.openAttachmentViewer(it) },
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
                            stagedAttachments = { ctx.stagedAttachments },
                            attachmentNotice = { ctx.attachmentNotice },
                            voiceActive = { ctx.voiceActive },
                            onOpenModels = { ctx.openModelPicker() },
                            onToggleAttachments = { ctx.toggleAttachmentMenu() },
                            onPickImages = { ctx.pickAttachmentImages() },
                            onCaptureImage = { ctx.captureAttachmentImage() },
                            onRemoveAttachment = { ctx.removeAttachment(it) },
                            onRetryAttachment = { ctx.retryAttachment(it) },
                            onPreviewAttachment = { ctx.previewStagedAttachment(it) },
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
                            onLongPressMessage = { message, x, y ->
                                ctx.openMessageActions(message, x, y)
                            },
                            onSelectionCancelled = { ctx.selectionMessageId = "" },
                            attachmentDataUrl = { ctx.attachmentDataUrl(it) },
                            onOpenAttachment = { ctx.openAttachmentViewer(it) },
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
                            batchSelectionActive = { ctx.batchSelectionActive },
                            isMessageBatchSelected = { it in ctx.batchSelectedIds },
                            onToggleMessageBatchSelection = { ctx.toggleBatchSelection(it) },
                            availableWidth = ctx.pagerData.pageViewWidth,
                        )
                        ctx.perfLog("body.conversation.end wide=false")
                    }

                    vif({ ctx.sessionDrawerVisible }) {
                        View {
                            attr {
                                absolutePositionAllZero()
                                backgroundColor(theme.maskLight)
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
                        onOpenAppearance = {
                            ctx.closeSessionDrawer()
                            setTimeout(ctx.pagerId, 0) { ctx.appearanceVisible = true }
                        },
                        onOpenPlugins = { ctx.openPlugins() },
                        onOpenLogs = { ctx.openLogs() },
                        archivedCount = { ctx.archivedSessions.size },
                        onOpenArchive = { ctx.openArchiveList() },
                        onSessionMenu = { ctx.openSessionMenu(it) },
                        onNewSession = { ctx.createSession() },
                        onSelect = { id ->
                            ctx.closeSessionDrawer()
                            setTimeout(ctx.pagerId, 0) {
                                ctx.selectSession(id)
                            }
                        },
                    )
                }

                vif({ ctx.selectionMessageId.isNotEmpty() }) {
                    DshSelectionBar(
                        onCopy = { ctx.copySelection() },
                        onSelectAll = { ctx.selectAllInMessage() },
                        onDone = { ctx.endTextSelection() },
                    )
                }

                vif({ ctx.batchSelectionActive }) {
                    DshBatchSelectionBar(
                        count = { ctx.batchSelectedIds.size },
                        onCopy = { ctx.copyBatchSelection() },
                        onExport = { ctx.exportBatchSelection() },
                        onCancel = { ctx.endBatchSelection() },
                    )
                }

                vif({ ctx.actionSheetMessageId.isNotEmpty() }) {
                    DshMessageActionSheet(
                        codeBlockCount = { ctx.actionSheetCodeBlockCount },
                        formulaCount = { ctx.actionSheetFormulaCount },
                        onSelectText = { ctx.beginTextSelection() },
                        onSelectMessages = { ctx.beginBatchSelection() },
                        onCopyMessage = { ctx.copyActiveMessage() },
                        onCopyCodeBlock = { ctx.copyCodeBlock(it) },
                        onCopyFormula = { ctx.copyFormula(it) },
                        onExportConversation = { ctx.exportConversation() },
                        onExportHtml = { ctx.exportConversationAsHtml() },
                        onPrintConversation = { ctx.printConversation() },
                        onClose = { ctx.actionSheetMessageId = "" },
                    )
                }

                DshAttachmentViewer(
                    ref = { ctx.attachmentViewerRef },
                    dataUrl = { ctx.attachmentDataUrl(it) },
                    onClose = { ctx.attachmentViewerRef = null },
                )

                vif({ ctx.sessionMenuId.isNotEmpty() }) {
                    DshSessionActionSheet(
                        title = { ctx.sessionTitle(ctx.sessionMenuId) },
                        manageable = { ctx.canManageSessions() },
                        unavailableReason = { ctx.sessionManagementUnavailableReason() },
                        onRename = { ctx.beginRenameSession() },
                        onArchive = { ctx.beginArchiveSession() },
                        onClose = { ctx.closeSessionMenu() },
                    )
                }

                vif({ ctx.renameSessionId.isNotEmpty() }) {
                    DshRenameSessionModal(
                        draft = { ctx.renameDraft },
                        busy = { ctx.renameBusy },
                        error = { ctx.renameError },
                        inputRef = {
                            ctx.renameInputView = it.view
                            ctx.renameInputView?.setText(ctx.renameDraft)
                        },
                        onDraftChange = {
                            ctx.renameDraft = it
                            ctx.renameError = ""
                        },
                        onSave = { ctx.commitRenameSession() },
                        onClose = { ctx.closeRenameSession() },
                    )
                }

                vif({ ctx.archiveSessionId.isNotEmpty() }) {
                    DshArchiveConfirmModal(
                        title = { ctx.sessionTitle(ctx.archiveSessionId) },
                        busy = { ctx.archiveBusy },
                        error = { ctx.archiveError },
                        onConfirm = { ctx.commitArchiveSession() },
                        onClose = { ctx.closeArchiveSession() },
                    )
                }

                vif({ ctx.archiveListVisible }) {
                    DshArchiveListModal(
                        sessions = { ctx.archivedSessions },
                        onOpen = { ctx.openArchivedSession(it) },
                        onClose = { ctx.archiveListVisible = false },
                    )
                }

                vif({ ctx.pluginsVisible }) {
                    DshPluginsModal(
                        loading = { ctx.pluginsLoading },
                        error = { ctx.pluginsError },
                        total = { ctx.pluginTotal },
                        failedCount = { ctx.pluginFailedCount },
                        rows = { ctx.pluginRows },
                        chips = { ctx.pluginStatusChips },
                        brokenPresets = { ctx.pluginBrokenPresets },
                        statusFilter = { ctx.pluginStatusFilter },
                        expandedId = { ctx.pluginExpandedId },
                        query = { ctx.pluginQuery },
                        presetsFor = { ctx.pluginPresetsByModule[it.moduleName].orEmpty() },
                        controllableIds = { ctx.pluginControllableIds },
                        controlBusyId = { ctx.pluginControlBusyId },
                        onQueryChange = {
                            ctx.pluginQuery = it
                            ctx.refreshPluginRows()
                        },
                        onSelectStatus = {
                            ctx.pluginStatusFilter = it
                            ctx.refreshPluginRows()
                        },
                        onToggleExpand = {
                            ctx.pluginExpandedId = if (ctx.pluginExpandedId == it) "" else it
                        },
                        onControl = { entryId, action -> ctx.requestPluginControl(entryId, action) },
                        onRefresh = { ctx.loadPluginInventory() },
                        onClose = { ctx.closePlugins() },
                    )
                }

                vif({ ctx.pluginConfirmEntryId.isNotEmpty() }) {
                    DshConfirmModal(
                        title = "Confirm",
                        body = { ctx.pluginConfirmMessage },
                        confirmLabel = "Continue",
                        onConfirm = { ctx.commitPluginControl() },
                        onCancel = { ctx.pluginConfirmEntryId = "" },
                    )
                }

                vif({ ctx.logsVisible }) {
                    DshLogCenterModal(
                        records = { ctx.logRecords },
                        typeChips = { ctx.logTypeChips },
                        total = { ctx.logTotal },
                        matched = { ctx.logMatched },
                        now = { ctx.logNow },
                        minLevel = { ctx.logMinLevel },
                        eventType = { ctx.logEventType },
                        sessionScoped = { ctx.logSessionScoped },
                        windowMs = { ctx.logWindowMs },
                        sessionLabel = { ctx.logSessionFilterLabel() },
                        onQueryChange = {
                            ctx.logQuery = it
                            ctx.refreshLogs()
                        },
                        onSelectLevel = {
                            ctx.logMinLevel = it
                            ctx.refreshLogs()
                        },
                        onSelectType = {
                            ctx.logEventType = it
                            ctx.refreshLogs()
                        },
                        onToggleSession = {
                            ctx.logSessionScoped = !ctx.logSessionScoped
                            ctx.refreshLogs()
                        },
                        onSelectWindow = {
                            ctx.logWindowMs = it
                            ctx.refreshLogs()
                        },
                        onOpenRecord = { ctx.logDetail = it },
                        onRefresh = { ctx.refreshLogs() },
                        onExport = { ctx.exportLogs() },
                        onClear = { ctx.clearLogs() },
                        onClose = { ctx.closeLogs() },
                    )
                    DshLogDetailModal(
                        record = { ctx.logDetail },
                        now = { ctx.logNow },
                        onCopy = { ctx.copyLogDetail() },
                        onClose = { ctx.logDetail = null },
                    )
                }

                vif({ ctx.appearanceVisible }) {
                    DshAppearanceModal(
                        themeMode = { ctx.currentThemeMode() },
                        codeTheme = { ctx.currentCodeTheme() },
                        onSelectTheme = { ctx.applyThemeMode(it) },
                        onSelectCodeTheme = { ctx.applyCodeTheme(it) },
                        onClose = { ctx.appearanceVisible = false },
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
                        authToken = { ctx.sshAuthToken },
                        busy = { ctx.sshSettingsBusy },
                        error = { ctx.sshSettingsError },
                        onModeChange = { ctx.setConnectionMode(it) },
                        onHostChange = { ctx.sshHost = it; ctx.sshSettingsError = "" },
                        onUserChange = { ctx.sshUser = it; ctx.sshSettingsError = "" },
                        onPortChange = { ctx.sshPort = it; ctx.sshSettingsError = "" },
                        onDshPortChange = { ctx.sshDshPort = it; ctx.sshSettingsError = "" },
                        onPickKey = { ctx.pickSshKey() },
                        onPassphraseChange = { ctx.sshKeyPassphrase = it },
                        onAuthTokenChange = { ctx.sshAuthToken = it; ctx.sshSettingsError = "" },
                        onApplyAuthToken = {
                            ctx.applyAuthToken(ctx.sshAuthToken)
                            if (ctx.sshSettingsError.isEmpty()) ctx.updateSshSettingsVisibility(false)
                        },
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
                            backgroundColor(theme.mask)
                        }
                        View {
                            attr {
                                width(pagerData.pageViewWidth - 40f)
                                maxWidth(420f)
                                padding(20f)
                                borderRadius(16f)
                                backgroundColor(theme.surface)
                            }
                            Text { attr { text("重命名工作区"); fontSize(18f); fontWeightBold(); color(theme.textPrimary) } }
                            Input {
                                attr {
                                    height(38f)
                                    marginTop(14f)
                                    fontSize(14f)
                                    placeholder("工作区名称")
                                    placeholderColor(theme.placeholder)
                                    text(ctx.workspaceRenameDraft)
                                }
                                event { textDidChange { ctx.workspaceRenameDraft = it.text } }
                            }
                            vif({ ctx.workspaceActionError.isNotEmpty() }) {
                                Text { attr { text(ctx.workspaceActionError); marginTop(8f); fontSize(12f); color(theme.danger) } }
                            }
                            View {
                                attr { height(40f); marginTop(18f); flexDirectionRow(); justifyContentFlexEnd() }
                                Text {
                                    attr { text("取消"); width(78f); height(38f); textAlignCenter(); fontSize(14f); color(theme.textMuted) }
                                    event { click { ctx.workspaceRenameTargetId = ""; ctx.workspaceActionError = "" } }
                                }
                                Text {
                                    attr { text(if (ctx.workspaceActionBusy) "保存中..." else "保存"); width(78f); height(38f); marginLeft(8f); textAlignCenter(); fontSize(14f); color(theme.accent) }
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
                            backgroundColor(theme.mask)
                        }
                        View {
                            attr {
                                width(pagerData.pageViewWidth - 40f)
                                maxWidth(420f)
                                padding(20f)
                                borderRadius(16f)
                                backgroundColor(theme.surface)
                            }
                            Text { attr { text("删除工作区注册?"); fontSize(18f); fontWeightBold(); color(theme.textPrimary) } }
                            Text {
                                attr {
                                    text("只会从列表移除注册，不会删除目录、会话或日志。")
                                    marginTop(8f)
                                    fontSize(13f)
                                    lineHeight(20f)
                                    color(theme.textSecondary)
                                }
                            }
                            vif({ ctx.workspaceActionError.isNotEmpty() }) {
                                Text { attr { text(ctx.workspaceActionError); marginTop(8f); fontSize(12f); color(theme.danger) } }
                            }
                            View {
                                attr { height(40f); marginTop(18f); flexDirectionRow(); justifyContentFlexEnd() }
                                Text {
                                    attr { text("取消"); width(78f); height(38f); textAlignCenter(); fontSize(14f); color(theme.textMuted) }
                                    event { click { ctx.workspaceDeleteTargetId = ""; ctx.workspaceActionError = "" } }
                                }
                                Text {
                                    attr { text(if (ctx.workspaceActionBusy) "删除中..." else "删除注册"); width(112f); height(38f); marginLeft(8f); textAlignCenter(); fontSize(14f); color(theme.danger) }
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

    // --- Task 2: selection, copy and export ------------------------------------

    private fun openMessageActions(message: DshMessage, x: Float, y: Float) {
        endTextSelection()
        val copyable = message.role == DshMessageRole.ASSISTANT && !message.isReasoning
        val displayed = if (copyable) displayedContentFor(message) else ""
        actionSheetCodeBlocks = if (copyable) DshMessageExport.codeBlocks(displayed) else emptyList()
        actionSheetCodeBlockCount = actionSheetCodeBlocks.size
        actionSheetFormulas = if (copyable) DshLatex.sources(displayed) else emptyList()
        actionSheetFormulaCount = actionSheetFormulas.size
        actionSheetPoint = x to y
        actionSheetMessageId = message.id
    }

    /** The row as painted, so copying a streaming reply matches what is on screen. */
    private fun displayedContentFor(message: DshMessage): String = dshDisplayedAssistantContent(
        stored = sessionMessageState(activeSessionId).firstOrNull { it.id == message.id }?.content
            ?: message.content,
        live = streamingAssistantContent,
        isLiveRow = streamingAssistantId == message.id,
    )

    private fun actionSheetMessage(): DshMessage? =
        sessionMessageState(activeSessionId).firstOrNull { it.id == actionSheetMessageId }

    private fun copyActiveMessage() {
        val message = actionSheetMessage() ?: return
        val rendered = if (message.role == DshMessageRole.ASSISTANT && !message.isReasoning) {
            message.copy(content = displayedContentFor(message))
        } else {
            message
        }
        val text = DshMessageExport.message(rendered)
        actionSheetMessageId = ""
        if (text.isEmpty()) {
            bridgeModule.toast("Nothing to copy")
            return
        }
        bridgeModule.copyToPasteboard(text)
        bridgeModule.toast("Message copied")
    }

    private fun copyCodeBlock(index: Int) {
        val block = actionSheetCodeBlocks.getOrNull(index)
        actionSheetMessageId = ""
        if (block == null) return
        bridgeModule.copyToPasteboard(block.code)
        bridgeModule.toast("Code copied")
    }

    /** The LaTeX source, not the Unicode the row paints. */
    private fun copyFormula(index: Int) {
        val formula = actionSheetFormulas.getOrNull(index)
        actionSheetMessageId = ""
        if (formula == null) return
        bridgeModule.copyToPasteboard(formula)
        bridgeModule.toast("Formula copied")
    }

    private fun exportConversation() {
        val (title, rows) = exportables() ?: return
        bridgeModule.shareText(title, DshMessageExport.conversation(title, rows))
    }

    private fun exportConversationAsHtml() {
        val (title, rows) = exportables() ?: return
        bridgeModule.shareHtml(title, DshMessageExport.html(title, rows))
    }

    /** The print sheet's "Save as PDF" destination is how the transcript becomes a PDF. */
    private fun printConversation() {
        val (title, rows) = exportables() ?: return
        bridgeModule.printHtml(title, DshMessageExport.html(title, rows))
    }

    // --- Task 2 bonus: batch selection across messages -------------------------

    private fun beginBatchSelection() {
        val messageId = actionSheetMessageId
        actionSheetMessageId = ""
        endTextSelection()
        batchSelectedIds = if (messageId.isEmpty()) emptySet() else setOf(messageId)
        batchSelectionActive = true
    }

    private fun toggleBatchSelection(messageId: String) {
        batchSelectedIds = if (messageId in batchSelectedIds) {
            batchSelectedIds - messageId
        } else {
            batchSelectedIds + messageId
        }
    }

    private fun endBatchSelection() {
        batchSelectionActive = false
        batchSelectedIds = emptySet()
    }

    /** The picked rows in timeline order, so a batch export reads like the conversation. */
    private fun batchSelectedMessages(): List<DshMessage> =
        sessionMessageState(activeSessionId).filter { it.id in batchSelectedIds }

    private fun copyBatchSelection() {
        val rows = batchSelectedMessages()
        if (rows.isEmpty()) {
            bridgeModule.toast("Nothing selected")
            return
        }
        val text = rows.mapNotNull { DshMessageExport.transcriptEntry(it) }.joinToString("\n\n")
        endBatchSelection()
        bridgeModule.copyToPasteboard(text)
        bridgeModule.toast(if (rows.size == 1) "1 message copied" else "${rows.size} messages copied")
    }

    private fun exportBatchSelection() {
        val rows = batchSelectedMessages()
        if (rows.isEmpty()) {
            bridgeModule.toast("Nothing selected")
            return
        }
        val title = sessions.firstOrNull { it.id == activeSessionId }?.title.orEmpty()
            .ifEmpty { "DSH conversation" }
        endBatchSelection()
        bridgeModule.shareText(title, DshMessageExport.conversation(title, rows))
    }

    /** Closes the sheet and yields the title and rows, or null when there is nothing to send. */
    private fun exportables(): Pair<String, List<DshMessage>>? {
        val title = sessions.firstOrNull { it.id == activeSessionId }?.title.orEmpty()
        val rows = sessionMessageState(activeSessionId).toList()
        actionSheetMessageId = ""
        if (rows.isEmpty()) {
            bridgeModule.toast("This conversation is empty")
            return null
        }
        return title.ifEmpty { "DSH conversation" } to rows
    }

    private fun beginTextSelection() {
        val messageId = actionSheetMessageId
        val (x, y) = actionSheetPoint
        actionSheetMessageId = ""
        if (messageId.isEmpty()) return
        selectionMessageId = messageId
        // The modal has to be gone before the native selector takes focus.
        setTimeout(pagerId, 60) {
            selectionRowView()?.createSelection(x, y, SelectionType.WORD)
        }
    }

    private fun selectionRowView(): com.tencent.kuikly.core.views.DivView? =
        messageRowRefs[messageRowKey(activeSessionId, selectionMessageId)]?.view

    private fun copySelection() {
        val view = selectionRowView() ?: return
        view.getSelection { result ->
            val text = result.content.joinToString("\n").trim()
            if (text.isEmpty()) {
                bridgeModule.toast("Nothing selected")
                return@getSelection
            }
            bridgeModule.copyToPasteboard(text)
            bridgeModule.toast("Selection copied")
        }
    }

    private fun selectAllInMessage() {
        selectionRowView()?.createSelectionAll()
    }

    private fun endTextSelection() {
        if (selectionMessageId.isEmpty()) return
        selectionRowView()?.clearSelection()
        selectionMessageId = ""
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
                // The cold-connect path builds the lists here rather than through
                // refreshSessionsFromStore, so the archive list needs its own refresh or
                // it stays empty until an unrelated Host frame arrives.
                refreshArchivedSessions()
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
            DshConnectionMode.DIRECT -> {
                if (directBaseUrl.isBlank()) {
                    connectionLabel = "请配置直连地址"
                    openConnectionSetup()
                    return
                }
                engineReady = true
                connectionLabel = "正在直连 DSH"
                connectRemoteEngine(directBaseUrl.trimEnd('/'))
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
        sshAuthToken = profile?.authToken.orEmpty()
        sshKeyLabel = if (sshKeyId.isEmpty()) "未导入私钥" else "已导入私钥"
    }

    private fun currentSshProfile(): DshRemoteProfile = DshRemoteProfile(
        host = sshHost.trim(),
        sshPort = sshPort.toIntOrNull() ?: 22,
        username = sshUser.trim(),
        remoteDshPort = sshDshPort.toIntOrNull() ?: 3080,
        keyId = sshKeyId,
        hostFingerprint = sshFingerprint,
        authToken = sshAuthToken.trim(),
    )

    /** The user pasted a new `dsh web` token: persist it, drop the stale cookie, reconnect. */
    private fun applyAuthToken(rawToken: String) {
        val token = dshExtractLaunchToken(rawToken)
        sshAuthToken = token
        when (connectionMode) {
            DshConnectionMode.SSH -> runCatching { localStore?.saveRemoteProfile(currentSshProfile()) }
            DshConnectionMode.DIRECT -> {
                directAuthToken = token
                runCatching { localStore?.saveSetting(DIRECT_AUTH_TOKEN_KEY, token) }
            }
            else -> Unit
        }
        authenticator?.updateLaunchToken(token)
        authenticator?.onCookie("")
        (repository as? DshRemoteRepository)?.retryAuthentication()
        sshSettingsError = if (token.isEmpty()) "请粘贴 dsh web 打印的 token 或完整 URL" else ""
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
        val auth = DshStoredHostAuthenticator(
            scopeKey = activeConnectionId,
            store = localStore,
            initialToken = when (connectionMode) {
                DshConnectionMode.SSH -> dshExtractLaunchToken(sshAuthToken)
                DshConnectionMode.DIRECT -> dshExtractLaunchToken(directAuthToken)
                else -> ""
            },
            // Direct mode also tries the plugin route so the mock Host works without pasting a token.
            relayTokenAvailable = connectionMode == DshConnectionMode.RELAY || connectionMode == DshConnectionMode.DIRECT,
            mintCookie = { base, launchToken, bearer, callback ->
                bridgeModule.mintAuthCookie(base, launchToken, bearer) { cookie, error ->
                    setTimeout(pagerId, 0) { callback(cookie, error) }
                }
            },
        )
        authenticator = auth
        repository = DshRemoteRepository(
            network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME),
            webSocket = acquireModule<DshWebSocketModule>(DshWebSocketModule.MODULE_NAME),
            connection = DshHostConnection(baseUrl, token),
            auth = auth,
            pagerId = pagerId,
            onState = { state -> handleHostRuntimeState(state) },
            onSessionsChanged = { refreshSessionsFromStore() },
            onSessionError = { sessionId, message ->
                if (sessionId == activeSessionId && message.isNotEmpty()) {
                    messages.add(DshMessage("session-error-${messages.size}", DshMessageRole.ERROR, message))
                }
            },
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
            onProjection = { sessionId, key, value, _ ->
                // Titles reach the header through the session list; the goal panel has no other source.
                if (sessionId == activeSessionId && key == "goal") {
                    goalSnapshot = parseGoalProjection(value)
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
    }

    private fun handleHostRuntimeState(state: DshHostRuntimeState) {
        if (!connectionCoordinator.isActive(connectionMode)) return
        val previousPhase = hostRuntimePhase
        hostRuntimePhase = state.phase
        connectionLabel = when (state.phase) {
            DshHostRuntimePhase.CONNECTING -> state.message.ifEmpty { "正在打开远程事件流" }
            DshHostRuntimePhase.HOST_HANDSHAKE -> "正在检查远程 DSH"
            DshHostRuntimePhase.SYNCING -> "正在同步远程会话"
            DshHostRuntimePhase.READY -> "远程 DSH 已就绪"
            DshHostRuntimePhase.RECONNECTING -> reconnectLabel()
            DshHostRuntimePhase.AUTH_REQUIRED -> AUTH_REQUIRED_LABEL
            DshHostRuntimePhase.ERROR -> "远程 DSH 连接失败"
            DshHostRuntimePhase.STOPPED -> "远程 DSH 已停止"
            DshHostRuntimePhase.DISCONNECTED -> "等待远程连接"
        }
        if (state.phase == DshHostRuntimePhase.AUTH_REQUIRED) {
            // The Host wants a browser-session cookie we cannot mint: ask for the launch token.
            sshSettingsError = state.message.ifEmpty { "请粘贴 dsh web 打印的 token" }
            openConnectionSettings(preserveError = true)
        }
        // Every generation starts with an empty session baseline, so the list has to be
        // re-read on the first connect and after each reconnect or re-login.
        if (state.phase == DshHostRuntimePhase.READY && previousPhase != DshHostRuntimePhase.READY) {
            loadRepository(preferredSessionId = activeSessionId)
        }
        syncTurnStatusTicker()
    }

    /** Host-pushed list changes (added / removed / activity / archive) arrive without an RPC round trip. */
    private fun refreshSessionsFromStore() {
        val remote = repository as? DshRemoteRepository ?: return
        if (!connectionCoordinator.isActive(connectionMode)) return
        val loaded = remote.store.sessions.values.toList()
        val loadedIds = loaded.map { it.id }.toSet()
        sessions.map { it.id }.filterNot { loadedIds.contains(it) }.forEach {
            sessionMessageStates.remove(it)
            sessionCacheStates.remove(it)
            sessionMessageReady.remove(it)
            conversationPanelIds.remove(it)
        }
        val current = sessions.toList()
        if (current != loaded) {
            sessions.clear()
            sessions.addAll(loaded)
            refreshVisibleSessions()
            runCatching { localStore?.replaceSessions(activeConnectionId, loaded) }
        }
        refreshWorkspaceGroups()
        // The Host's `archived` frame lands here too, so the archive list and its count
        // follow the Host rather than a local copy.
        refreshArchivedSessions()
        loaded.forEach { session ->
            if (!sessionMessageReady.contains(session.id) && !sessionMessageStates.containsKey(session.id)) {
                sessionMessageState(session.id, loadFromDisk = false)
                sessionMessageReady.add(session.id)
            }
        }
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
    }

    private fun openCredentialSettings() {
        dismissKeyboard()
        attachmentMenuVisible = false
        credentialSetupTitle = "修改电脑端 DSH 的 API Key"
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
        runCatching { localStore?.saveRemoteProfile(currentSshProfile()) }
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
                    sshAuthToken = dshExtractLaunchToken(sshAuthToken)
                    runCatching { localStore?.saveRemoteProfile(currentSshProfile()) }
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
            DshConnectionMode.DIRECT -> Unit
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
            closeSessionDrawer()
            bridgeModule.toast("未连接到远程 DSH")
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
        sessionCreateInFlight = true
        hostRepository.createSession(currentWorkspaceId, { sessionId ->
            sessionCreateInFlight = false
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
            // A send that arrived while this create was in flight kept its draft; do
            // not clear it, or the queued turn is lost.
            if (!pendingSendAfterCreate) {
                draft = ""
                inputView?.setText("")
            }
            applyActiveSessionChrome()
            setTimeout(pagerId, 0) {
                if (activeSessionId == sessionId) {
                    loadSkills(sessionId)
                    loadModels(sessionId)
                }
                if (pendingSendAfterCreate) {
                    pendingSendAfterCreate = false
                    sendDraft()
                }
            }
        }, { error ->
            sessionCreateInFlight = false
            pendingSendAfterCreate = false
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
        loadSkills(sessionId)
        // Bound the number of live `session/follow` streams to the mounted panels.
        (repository as? DshRemoteRepository)?.trimFollows(conversationPanelIds.toSet() + sessionId)
        loadWebTimeline(sessionId, scrollToEndAfterLoad)
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
                    DshWebTimelineItem.Kind.USER -> DshMessage(
                        item.key,
                        DshMessageRole.USER,
                        item.text,
                        attachments = item.attachments,
                    )
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
            // Restore both the assistant's image blocks and the images a user turn
            // carried, by `attachmentId` through `session/attachment` (Task 3 criterion 6).
            projected.mapNotNull { it.attachmentId }.forEach { loadAttachment(sessionId, it) }
            projected.flatMap { it.attachments }
                .map { it.attachmentId }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { loadAttachment(sessionId, it) }
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
                // Only stand a row up here for a reply that never streamed and was never
                // projected from a committed assistant/message; otherwise the turn-wide
                // accumulator is appended a second time as an extra bubble.
                if (streamingAssistantId.isEmpty() && result.isNotEmpty() && !assistantBlocksProjected) {
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
                settleFailedTurn(error)
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
        DshStreamLog.i("ui.attachment.read session=$sessionId attachment=$attachmentId")
        hostRepository.loadAttachment(sessionId, attachmentId) { dataUrl, error ->
            if (error != null || dataUrl == null) {
                DshStreamLog.i(
                    "ui.attachment.read-fail attachment=$attachmentId error='${DshStreamLog.preview(error.orEmpty())}'",
                )
                pendingAttachmentReads.remove(attachmentId)
                return@loadAttachment
            }
            DshStreamLog.i("ui.attachment.read-ok attachment=$attachmentId chars=${dataUrl.length}")
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
        // One committed assistant/message is one model call, so the live row that was
        // streaming it has to close here the way it does before a tool card. Without
        // that the projection of a multi-step turn ends up with fewer rows than the
        // durable timeline, and the index-wise applyMessagesInPlace patch that follows
        // shifts content between rows (vforLazy never rebuilds the last one).
        val streamedRowSealed = splitStreamingAssistantBeforeTool()
        assistantBlocksProjected = true
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
                // A step the Host composed itself (the image echo, for one) commits text
                // that never arrived as a delta, so there is no sealed row to reuse and
                // the block has to become its own row to keep the two shapes aligned.
                "text" -> {
                    if (streamedRowSealed) continue
                    val text = block.optString("text")
                    if (text.isEmpty()) continue
                    val id = "text-${event.seq}-$index"
                    if (messages.none { it.id == id }) {
                        messages.add(DshMessage(id, DshMessageRole.ASSISTANT, text))
                    }
                }
                "reasoning", "tool-call" -> Unit
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
        refreshSessionRenderTree(activeSessionId)
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

    // ---------------------------------------------------------------- Task 4

    /** Whether `session/rename` and `workspace/archiveSession` can be reached right now. */
    private fun canManageSessions(): Boolean =
        isRemoteHost && (repository as? DshRemoteRepository)?.isProductReady() == true

    private fun sessionManagementUnavailableReason(): String = when {
        !isRemoteHost -> "Only a remote DSH Host can rename or archive conversations."
        else -> "Not connected to the Host yet. Reconnect and try again."
    }

    private fun sessionTitle(sessionId: String): String =
        sessions.firstOrNull { it.id == sessionId }?.title.orEmpty().ifEmpty { "Untitled" }

    private fun openSessionMenu(sessionId: String) {
        sessionMenuId = sessionId
    }

    private fun closeSessionMenu() {
        sessionMenuId = ""
    }

    private fun beginRenameSession() {
        val sessionId = sessionMenuId.ifEmpty { activeSessionId }
        if (sessionId.isEmpty()) return
        closeSessionMenu()
        renameSessionId = sessionId
        renameError = ""
        renameBusy = false
        renameDraft = sessionTitle(sessionId).takeUnless { it == "Untitled" || it == "尚无标题" || it == "新会话" }.orEmpty()
        setTimeout(pagerId, 0) { renameInputView?.setText(renameDraft) }
    }

    private fun closeRenameSession() {
        dismissKeyboard()
        renameSessionId = ""
        renameDraft = ""
        renameError = ""
        renameBusy = false
        renameInputView = null
    }

    /**
     * The Host is the authority on titles, so the draft goes out as typed and a
     * `session/title-invalid` answer is mapped to readable copy (Task 4 criterion 1).
     */
    private fun commitRenameSession() {
        val repository = repository as? DshRemoteRepository ?: return
        val sessionId = renameSessionId
        if (sessionId.isEmpty() || renameBusy) return
        renameBusy = true
        renameError = ""
        repository.renameSession(sessionId, renameDraft) { value, error ->
            setTimeout(pagerId, 0) {
                renameBusy = false
                if (error != null || value == null) {
                    renameError = dshSessionErrorMessage(error)
                    return@setTimeout
                }
                closeRenameSession()
                // The title projection reaches the store on its own; refreshing here makes
                // the drawer and the header show it without waiting for the next frame.
                refreshSessionsFromStore()
            }
        }
    }

    private fun beginArchiveSession() {
        val sessionId = sessionMenuId.ifEmpty { activeSessionId }
        if (sessionId.isEmpty()) return
        closeSessionMenu()
        archiveSessionId = sessionId
        archiveError = ""
        archiveBusy = false
    }

    private fun closeArchiveSession() {
        archiveSessionId = ""
        archiveError = ""
        archiveBusy = false
    }

    /**
     * `workspace/archiveSession` only adds the id to the Host's archive set. On success
     * the store's `archivedSessionIds` hides the row; if it was the open conversation the
     * page moves to the first unarchived one so it never sits on an empty error state
     * (criterion 4). On failure nothing about the list changes (criterion 5).
     */
    private fun commitArchiveSession() {
        val repository = repository as? DshRemoteRepository ?: return
        val sessionId = archiveSessionId
        if (sessionId.isEmpty() || archiveBusy) return
        archiveBusy = true
        archiveError = ""
        repository.archiveSession(sessionId) { value, error ->
            setTimeout(pagerId, 0) {
                archiveBusy = false
                if (error != null || value == null) {
                    archiveError = dshSessionErrorMessage(error)
                    return@setTimeout
                }
                closeArchiveSession()
                closeSessionDrawer()
                refreshSessionsFromStore()
                refreshArchivedSessions()
                if (activeSessionId == sessionId) moveOffArchivedSession(sessionId)
            }
        }
    }

    /**
     * Leaves an archived conversation for the first one still in the main list. The
     * fallback has to exclude the archive set as well: `sessions` is the raw Host list,
     * so without that filter archiving the last unarchived conversation would land on
     * another archived one.
     */
    private fun moveOffArchivedSession(archivedId: String) {
        val archivedIds = archivedSessions.map { it.id }.toSet() + archivedId
        val next = workspaceGroups.flatMap { it.sessions }.firstOrNull { it.id !in archivedIds }
            ?: sessions.firstOrNull { it.id !in archivedIds && !it.blank }
        if (next != null) {
            selectSession(next.id)
        } else {
            // Nothing left to show; a blank conversation beats an empty error state.
            createSession()
        }
    }

    private fun refreshArchivedSessions() {
        val repository = repository as? DshRemoteRepository ?: return
        val archived = repository.archivedSessions()
        archivedSessions.clear()
        archivedSessions.addAll(archived)
    }

    private fun openArchiveList() {
        refreshArchivedSessions()
        closeSessionDrawer()
        setTimeout(pagerId, 0) { archiveListVisible = true }
    }

    /** Archived conversations open read-through: the same durable history, still archived. */
    private fun openArchivedSession(sessionId: String) {
        archiveListVisible = false
        setTimeout(pagerId, 0) { selectSession(sessionId) }
    }

    // ------------------------------------------------------------------ plugins (Task 5)

    private fun openPlugins() {
        closeSessionDrawer()
        setTimeout(pagerId, 0) {
            pluginsVisible = true
            loadPluginInventory()
            probePluginAdmin()
        }
    }

    /**
     * The companion Host plugin advertises what it will let the phone control. Its
     * absence is the normal case and simply leaves the inventory read-only — the app
     * never probes by attempting a write and reading the error.
     */
    private fun probePluginAdmin() {
        val repository = repository as? DshRemoteRepository ?: return
        repository.probePluginAdmin { controllable ->
            setTimeout(pagerId, 0) {
                pluginControllableIds = controllable
                KLog.i("dsh-plugins", "admin.probe controllable=${controllable.size}")
            }
        }
    }

    /** First tap asks the Host, which answers `confirm-required`; the modal repeats it confirmed. */
    private fun requestPluginControl(entryId: String, action: String) {
        val repository = repository as? DshRemoteRepository ?: return
        pluginControlBusyId = entryId
        repository.controlPlugin(entryId, action, confirm = false) { error ->
            setTimeout(pagerId, 0) {
                pluginControlBusyId = ""
                if (error?.code == "confirm-required") {
                    pluginConfirmEntryId = entryId
                    pluginConfirmAction = action
                    pluginConfirmMessage = error.message
                } else {
                    bridgeModule.toast(error?.message ?: "That plugin is already in that state.")
                }
            }
        }
    }

    private fun commitPluginControl() {
        val repository = repository as? DshRemoteRepository ?: return
        val entryId = pluginConfirmEntryId
        val action = pluginConfirmAction
        pluginConfirmEntryId = ""
        if (entryId.isEmpty()) return
        pluginControlBusyId = entryId
        repository.controlPlugin(entryId, action, confirm = true) { error ->
            setTimeout(pagerId, 0) {
                pluginControlBusyId = ""
                if (error != null) {
                    bridgeModule.toast(error.message)
                    return@setTimeout
                }
                bridgeModule.toast(
                    when (action) {
                        "enable" -> "Plugin enabled"
                        "disable" -> "Plugin disabled"
                        else -> "Plugin reloaded"
                    },
                )
                // The Host has settled the fiber by the time it answers, so a plain
                // re-read shows the new phase rather than a transient one.
                loadPluginInventory()
            }
        }
    }

    /**
     * `pluginInventory/list` is a point-in-time read, so the sheet fetches on open and on
     * refresh instead of following a stream. It carries no session state, which keeps it
     * out of both list-building paths.
     */
    private fun loadPluginInventory() {
        val repository = repository as? DshRemoteRepository
        if (repository == null) {
            pluginsLoading = false
            pluginsError = "Only a remote DSH Host reports a plugin inventory."
            return
        }
        pluginsLoading = true
        pluginsError = ""
        repository.loadPluginInventory(
            onSuccess = { inventory ->
                setTimeout(pagerId, 0) {
                    pluginEntries = inventory.entries
                    pluginPresetsByModule = DshPluginCatalog.presetsEnablingModule(inventory)
                    pluginTotal = inventory.entries.size
                    pluginFailedCount = inventory.entries.count {
                        DshPluginCatalog.status(it) == DshPluginStatus.FAILED
                    }
                    pluginBrokenPresets.clear()
                    pluginBrokenPresets.addAll(DshPluginCatalog.brokenPresets(inventory))
                    pluginStatusChips.clear()
                    pluginStatusChips.addAll(DshPluginCatalog.statusChips(inventory.entries))
                    if (pluginStatusChips.none { it.status == pluginStatusFilter }) pluginStatusFilter = null
                    pluginsLoading = false
                    refreshPluginRows()
                    KLog.i("dsh-plugins", "inventory.ok entries=${pluginEntries.size} failed=$pluginFailedCount")
                }
            },
            onError = { error ->
                setTimeout(pagerId, 0) {
                    pluginsLoading = false
                    pluginsError = dshPluginErrorMessage(error)
                    KLog.i("dsh-plugins", "inventory.error code=${error.code}")
                }
            },
        )
    }

    private fun refreshPluginRows() {
        val next = DshPluginCatalog.visible(pluginEntries, pluginQuery, pluginStatusFilter)
        if (pluginExpandedId.isNotEmpty() && next.none { it.entryId == pluginExpandedId }) pluginExpandedId = ""
        pluginRows.clear()
        pluginRows.addAll(next)
    }

    /**
     * The sheet's search field is rebuilt empty on the next open, so the query and the
     * status filter reset with it — otherwise a reopened sheet shows a filtered list
     * under an empty search box.
     */
    private fun closePlugins() {
        pluginsVisible = false
        pluginExpandedId = ""
        pluginConfirmEntryId = ""
        pluginControlBusyId = ""
        pluginQuery = ""
        pluginStatusFilter = null
        refreshPluginRows()
    }

    // ------------------------------------------------------------------ log centre (Task 6)

    private fun openLogs() {
        closeSessionDrawer()
        setTimeout(pagerId, 0) {
            logsVisible = true
            refreshLogs()
        }
    }

    /**
     * Takes one snapshot of the ring buffer. The list is deliberately not live: a record
     * per stream chunk would put UI work on the streaming path, which criterion 5 asks it
     * not to. Refresh is a header action instead.
     */
    private fun refreshLogs() {
        logNow = DateTime.currentTimestamp()
        val snapshot = DshLogCenter.snapshot()
        logTotal = snapshot.size
        val matches = DshLogCenter.filtered(currentLogFilter(), logNow)
        logMatched = matches.size
        logRecords.clear()
        logRecords.addAll(matches.take(DSH_LOG_RENDER_LIMIT))
        logTypeChips.clear()
        logTypeChips.addAll(DshLogCenter.typeChips(snapshot))
    }

    private fun currentLogFilter(): DshLogFilter = DshLogFilter(
        minLevel = logMinLevel,
        eventType = logEventType,
        sessionId = if (logSessionScoped) activeSessionId else "",
        windowMs = logWindowMs,
        query = logQuery,
    )

    private fun logSessionFilterLabel(): String =
        if (logSessionScoped) "This conversation: ${sessionTitle(activeSessionId)}" else "This conversation"

    /** Local only: the Host's history and the conversation messages are untouched. */
    private fun clearLogs() {
        DshLogCenter.clear()
        logDetail = null
        refreshLogs()
        bridgeModule.toast("Local logs cleared")
    }

    /**
     * The issue-feedback bundle: environment facts plus the records currently matching the
     * filter. Every record was redacted when it was written, so the share text is too.
     */
    private fun exportLogs() {
        val now = DateTime.currentTimestamp()
        val matches = DshLogCenter.filtered(currentLogFilter(), now)
        if (matches.isEmpty()) {
            bridgeModule.toast("No records to export")
            return
        }
        bridgeModule.shareText(
            "DSH diagnostic log",
            DshLogFormat.export(matches, logEnvironment(), now),
        )
    }

    private fun logEnvironment(): List<Pair<String, String>> = listOf(
        "Connection mode" to when (connectionMode) {
            DshConnectionMode.RELAY -> "QR relay"
            DshConnectionMode.SSH -> "SSH"
            DshConnectionMode.DIRECT -> "Direct (dev)"
            DshConnectionMode.LOCAL -> "Local"
        },
        // The Host address is deliberately absent: the mode and the phase are what a
        // triager needs, and a LAN address in a shared bundle is needless exposure.
        "Connection state" to "$hostRuntimePhase",
        "Platform" to "${pagerData.platform} ${pagerData.osVersion}".trim(),
        "App version" to "${pagerData.appVersion} (build ${pagerData.nativeBuild})",
        "Filter" to "level>=${logMinLevel.label} type=${logEventType.ifEmpty { "all" }} " +
            "window=${if (logWindowMs == 0L) "all" else "${logWindowMs / 60_000}m"} " +
            "session=${if (logSessionScoped) activeSessionId else "all"}",
    )

    private fun copyLogDetail() {
        val record = logDetail ?: return
        bridgeModule.copyToPasteboard(DshLogFormat.detail(record, DateTime.currentTimestamp()))
        bridgeModule.toast("Log entry copied")
    }

    private fun closeLogs() {
        logsVisible = false
        logDetail = null
        // The search field is rebuilt empty next time, so its filter goes with it.
        logQuery = ""
        refreshLogs()
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
        DshConnectionMode.DIRECT -> "远程连接重建中"
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
        DshConnectionMode.SSH, DshConnectionMode.DIRECT -> "远程 DSH 正在同步，暂不能发送"
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
        val outgoing = stagedAttachments.toList()
        if (outgoing.any { it.state == DshAttachmentState.FAILED }) {
            attachmentNotice = outgoing.first { it.state == DshAttachmentState.FAILED }.error
                .ifEmpty { "Remove the rejected image before sending." }
            return
        }
        if ((prompt.isEmpty() && outgoing.isEmpty()) || streaming) return
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
            // loadRepository also creates a session when the Host has none, without
            // claiming activeSessionId. Sending within that window used to create a
            // second session and prompt into it, leaving the UI on the empty one.
            if (sessionCreateInFlight) {
                connectionLabel = "正在创建会话"
                pendingSendAfterCreate = true
                return
            }
            connectionLabel = "正在创建会话"
            sessionCreateInFlight = true
            pendingSendAfterCreate = true
            hostRepository.createSession(null, { sessionId ->
                sessionCreateInFlight = false
                sessions.add(DshSession(sessionId, "新会话", "Host", "", blank = true))
                refreshVisibleSessions()
                runCatching { localStore?.replaceSessions(activeConnectionId, sessions.toList()) }
                activeSessionId = sessionId
                loadModels(sessionId)
                if (pendingSendAfterCreate) {
                    pendingSendAfterCreate = false
                    sendDraft()
                }
            }, { error ->
                sessionCreateInFlight = false
                pendingSendAfterCreate = false
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
        val user = DshMessage(
            "user-${messages.size}",
            DshMessageRole.USER,
            prompt,
            attachments = outgoing.map { it.toLocalRef() },
        )
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
        assistantBlocksProjected = false
        draft = ""
        inputView?.setText("")
        streaming = true
        stopButtonVisible = true
        connectionLabel = "正在生成"
        attachmentMenuVisible = false
        attachmentNotice = ""
        markStagedAttachments(DshAttachmentState.UPLOADING, "")
        syncTurnStatusTicker()
        streamHandle = hostRepository.streamReply(
            pagerId = pagerId,
            sessionId = sessionId,
            prompt = prompt,
            images = outgoing.map { it.image },
            onAccepted = {
                // `session/prompt` returned its receipt, so the Host has validated and
                // stored the images; the bubble now carries them and the strip is done.
                markStagedAttachments(DshAttachmentState.SENT, "")
                stagedAttachments.clear()
                failedAttachmentPrompt = ""
            },
            onDelta = { delta, isReasoning ->
                if (isReasoning) queueReasoningDelta(reasoningId, delta)
                else queueAssistantDelta(assistantId, delta)
            },
            onComplete = { result ->
                if (!connectionCoordinator.isActive(connectionMode)) return@streamReply
                flushAssistantDelta()
                // Only stand a row up here for a reply that never streamed and was never
                // projected from a committed assistant/message; otherwise the turn-wide
                // accumulator is appended a second time as an extra bubble.
                if (streamingAssistantId.isEmpty() && result.isNotEmpty() && !assistantBlocksProjected) {
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
                // Nothing was stored Host-side, so the images go back to the strip with
                // the reason and the prompt is kept for a one-tap retry.
                if (stagedAttachments.isNotEmpty()) {
                    markStagedAttachments(DshAttachmentState.FAILED, error)
                    attachmentNotice = error
                    failedAttachmentPrompt = prompt
                    dropMessageAttachments(user.id)
                }
                settleFailedTurn(error)
                persistMessages(sessionId)
                connectionLabel = "已连接"
                streamHandle = null
            },
        )
    }

    /** A prompt the Host refused never produced a `user/message`, so drop its refs. */
    private fun dropMessageAttachments(messageId: String) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        messages[index] = messages[index].copy(attachments = emptyList())
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
        assistantBlocksProjected = false
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

    private fun toggleAttachmentMenu() {
        dismissKeyboard()
        voiceActive = false
        attachmentMenuVisible = !attachmentMenuVisible
    }

    private val mediaModule: DshMediaModule
        get() = acquireModule(DshMediaModule.MODULE_NAME)

    /** `imageLimits` the Host published, so both the picker and prevalidation obey it. */
    private fun hostImageLimits(): DshImageLimits? = (repository as? DshRemoteRepository)?.imageLimits()

    private fun pickAttachmentImages() {
        val limits = hostImageLimits()
        val remaining = (limits?.maxImagesPerMessage ?: DEFAULT_MAX_IMAGES) - stagedAttachments.size
        if (remaining <= 0) {
            attachmentNotice = dshAttachmentReasonMessage(
                DshAttachmentReason.TOO_MANY_IMAGES.name,
                limits,
            ).orEmpty()
            return
        }
        requestMedia { callback ->
            mediaModule.pickImages(
                remaining,
                limits?.maxImageDimension ?: 0,
                limits?.maxImageBytes ?: 0L,
                callback,
            )
        }
    }

    private fun captureAttachmentImage() {
        val limits = hostImageLimits()
        if (stagedAttachments.size >= (limits?.maxImagesPerMessage ?: DEFAULT_MAX_IMAGES)) {
            attachmentNotice = dshAttachmentReasonMessage(
                DshAttachmentReason.TOO_MANY_IMAGES.name,
                limits,
            ).orEmpty()
            return
        }
        requestMedia { callback ->
            mediaModule.captureImage(
                limits?.maxImageDimension ?: 0,
                limits?.maxImageBytes ?: 0L,
                callback,
            )
        }
    }

    private fun requestMedia(open: ((DshMediaResult) -> Unit) -> Unit) {
        if (attachmentPickBusy) return
        attachmentPickBusy = true
        attachmentMenuVisible = false
        attachmentNotice = ""
        open { result ->
            attachmentPickBusy = false
            if (result.error != DshMediaError.NONE) {
                attachmentNotice = result.message.ifEmpty { "The image could not be read." }
                return@open
            }
            result.images.forEach(::stageAttachment)
        }
    }

    /**
     * Adds one picked image to the composer. A violation of the Host's `imageLimits`
     * lands in the strip as a failed tile with the Host's own reason, so the image is
     * explained and removable before `session/prompt` is ever called.
     */
    private fun stageAttachment(image: DshOutgoingImage) {
        val limits = hostImageLimits()
        val accepted = stagedAttachments
            .filter { it.state != DshAttachmentState.FAILED }
            .map { it.image }
        val rejection = DshAttachmentPrevalidation.validate(image, accepted, limits)
        val id = "staged-${++stagedAttachmentSequence}"
        stagedAttachments.add(
            DshStagedAttachment(
                localId = id,
                image = image,
                state = if (rejection == null) DshAttachmentState.PENDING else DshAttachmentState.FAILED,
                error = rejection?.message.orEmpty(),
            ),
        )
        cachedAttachmentDataUrls[id] = "data:${image.mediaType};base64,${image.base64}"
        attachmentNotice = rejection?.message.orEmpty()
        DshStreamLog.i(
            "ui.attachment.staged id=$id type=${image.mediaType} bytes=${image.bytes} " +
                "size=${image.width}x${image.height} reason=${rejection?.reason?.name ?: "ok"}",
        )
    }

    private fun removeAttachment(localId: String) {
        val index = stagedAttachments.indexOfFirst { it.localId == localId }
        if (index < 0) return
        stagedAttachments.removeAt(index)
        cachedAttachmentDataUrls.remove(localId)
        attachmentNotice = stagedAttachments.firstOrNull { it.state == DshAttachmentState.FAILED }?.error.orEmpty()
    }

    /**
     * Re-checks a locally rejected image, or resends a turn whose `session/prompt`
     * failed. Prevalidation runs again because the limits or the rest of the strip
     * may have changed since the rejection.
     */
    private fun retryAttachment(localId: String) {
        val index = stagedAttachments.indexOfFirst { it.localId == localId }
        if (index < 0) return
        val staged = stagedAttachments[index]
        val others = stagedAttachments
            .filterIndexed { position, other -> position != index && other.state != DshAttachmentState.FAILED }
            .map { it.image }
        val rejection = DshAttachmentPrevalidation.validate(staged.image, others, hostImageLimits())
        stagedAttachments[index] = staged.copy(
            state = if (rejection == null) DshAttachmentState.PENDING else DshAttachmentState.FAILED,
            error = rejection?.message.orEmpty(),
        )
        attachmentNotice = rejection?.message.orEmpty()
        if (rejection == null && failedAttachmentPrompt.isNotEmpty() &&
            stagedAttachments.none { it.state == DshAttachmentState.FAILED }
        ) {
            draft = failedAttachmentPrompt
            failedAttachmentPrompt = ""
            sendDraft()
        }
    }

    private fun previewStagedAttachment(localId: String) {
        val staged = stagedAttachments.firstOrNull { it.localId == localId } ?: return
        attachmentViewerRef = staged.toLocalRef()
    }

    private fun openAttachmentViewer(ref: DshImageAttachmentRef) {
        if (ref.attachmentId.isNotEmpty()) loadAttachment(activeSessionId, ref.attachmentId)
        attachmentViewerRef = ref
    }

    private fun markStagedAttachments(state: DshAttachmentState, error: String) {
        stagedAttachments.toList().forEachIndexed { index, staged ->
            if (staged.state == DshAttachmentState.FAILED && state != DshAttachmentState.FAILED) return@forEachIndexed
            stagedAttachments[index] = staged.copy(state = state, error = error)
        }
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

    /**
     * Close the current text row immediately before the next tool card or committed
     * message. Returns true when a row was sealed with streamed text, so the caller
     * knows the block it is folding already has a row.
     */
    private fun splitStreamingAssistantBeforeTool(): Boolean {
        if (!streaming || streamingAssistantRootId.isEmpty()) return false
        flushAssistantDelta()
        var sealed = false
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
                    sealed = true
                }
            }
        }
        streamingAssistantId = ""
        streamingAssistantContent = ""
        streamingAssistantSegment += 1
        pendingAssistantDelta.setLength(0)
        assistantFlushScheduled = false
        return sealed
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

    /**
     * The Host commits the partial reply and logs the failure as a separate
     * `turn/end` reason, so keep both rows here: the durable timeline that
     * replaces this projection has the same shape.
     */
    private fun settleFailedTurn(error: String) {
        if (streamingAssistantContent.isEmpty()) {
            settleStreamingMessage(DshMessageRole.ERROR, error)
            return
        }
        settleStreamingMessage(DshMessageRole.ASSISTANT, "")
        messages.add(DshMessage("turn-error-${messages.size}", DshMessageRole.ERROR, error))
        realizeTailMessageCell()
    }

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
        assistantBlocksProjected = false
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
        realizeTailMessageCell()
    }

    /**
     * Same `vforLazy` caveat as [ensureLiveMessageCell]: an add at `currentEnd`
     * only grows the tail placeholder, and replacing an element is a remove plus
     * an add, so the last row has to be built explicitly after it changes.
     */
    private fun realizeTailMessageCell() {
        if (!followListTail || messages.isEmpty()) return
        val list = messageScrollerRefs[activeSessionId]?.view ?: return
        list.scrollToPosition(messages.lastIndex, 0f, false)
    }

    companion object {
        private const val AUTH_REQUIRED_LABEL = "需要 DSH 登录 token"
        internal const val DIRECT_BASE_URL_KEY = "direct_base_url"
        internal const val DIRECT_AUTH_TOKEN_KEY = "direct_auth_token"
        private const val ANIMATION_DURATION_MS = 240
        private const val ANIMATION_DURATION_S = 0.24f
        private const val STREAM_FLUSH_INTERVAL_MS = 16
        /** Cap used until the Host publishes its `imageLimits` projection. */
        private const val DEFAULT_MAX_IMAGES = 4
    }
}
