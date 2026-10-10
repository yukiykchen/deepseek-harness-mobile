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
import com.tencent.kuikly.core.views.InputView
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
internal class DshHomePage : BasePager(), DshHomeContext {
    private var localStore: DshLocalStore? = null
    private val connection = DshConnectionController(
        scope = this,
        localStore = { localStore },
        supportsRelay = { pageData.supportsRelayBridge },
        createClient = ::createHostClient,
        listener = object : DshConnectionController.Listener {
            override fun onClientConnected() = loadRepository(preferredSessionId = activeSessionId)
            override fun onClientReconnected() = loadRepository(preferredSessionId = activeSessionId)
            override fun onTransportStateChanged() = syncTurnStatusTicker()
            override fun beforeSettingsShown() {
                dismissKeyboard()
                attachmentMenuVisible = false
            }
            override fun onSettingsVisibilityChanged(visible: Boolean) = dimSystemBars(visible)
            override fun onSettingsSaved() {
                stopCurrentEngine()
                openConnectionSetup()
            }
            override fun onSetupRequired() = openConnectionSetup()
        },
    )
    override val hostClient: DshHostClient?
        get() = connection.client
    /** Top bar status. Owned by [connection]; turn progress ("正在生成") is written here too. */
    private var connectionLabel: String
        get() = connection.statusLabel
        set(value) {
            connection.statusLabel = value
        }
    private var settingsPageVisible by observable(false)
    private val activeConnectionId: String
        get() = connection.sessionScope.storageKey

    private var sessions by observableList<DshSession>()
    private val visibleSessions by observableList<DshSession>()
    private var messages by observableList<DshMessage>()
    private var conversationPanelIds by observableList<String>()
    override var activeSessionId by observable("session-1")
        private set
    private var preferBlankHomeOnNextLoad = true
    private var draft by observable("")
    private var streaming by observable(false)
    private var stopButtonVisible by observable(false)
    private var streamingAssistantContent by observable("")
    private var keyboardHeight by observable(0f)
    private var keyboardAnimation by observable(Animation.easeInOut(ANIMATION_DURATION_S))
    private var apiKeyDraft by observable("")
    private var credentialSetupVisible by observable(false)
    private var credentialSetupBusy by observable(false)
    private var credentialSetupError by observable("")
    private var credentialSetupTitle by observable("添加一个 API Key 开始使用")
    private var sessionDrawerVisible by observable(false)
    private var sessionDrawerAnimated by observable(false)
    private var sessionDrawerMaskAnimated by observable(false)
    private var sessionDrawerMaskAnimation by observable(Animation.linear(0f))
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
    private val queue = DshQueueController(this)
    private val jobs = DshJobsController(this)
    private val goal = DshGoalController(this)
    private val models = DshModelPickerController(this)
    private val interactions = DshInteractionController(
        ctx = this,
        onApprovalRejected = { connectionLabel = it },
        onQuestionAnswered = { loadWebTimeline(it, scrollToEndAfterLoad = true) },
    )
    private val workspaces = DshWorkspaceController(
        ctx = this,
        onBrowserOpening = { closeSessionDrawer() },
        onWorkspaceAdopted = { loadRepository(preferredSessionId = activeSessionId) },
    )
    private val webDisclosureStates = mutableMapOf<String, Boolean>()
    private val webBodyDisclosureStates = mutableMapOf<String, Boolean>()
    private val webJsonNodeStates = mutableMapOf<String, Boolean>()
    private var webDisclosureRevision by observable(0)
    private var attachmentRevision by observable(0)
    private val cachedAttachmentDataUrls = mutableMapOf<String, String>()
    private val pendingAttachmentReads = mutableSetOf<String>()
    private val skills by observableList<DshSkill>()
    private var sessionRunning by observable(false)
    private var turnElapsedMs by observable(0L)
    private var turnStatusMark: TimeMark? = null
    private var turnStatusTickerGeneration = 0
    private var turnStatusClockBucket = -1L

    /**
     * 系统返回键统一入口：按 z-order 关闭最顶层覆盖层，
     * 所有覆盖层都关闭后再通过 RouterModule.closePage() 结束当前页面。
     */
    internal val overlayBackCallback = object : BackPressCallback() {
        override fun handleOnBackPressed() {
            when {
                workspaces.handleBack() -> Unit
                connection.settingsVisible -> connection.closeSettings()
                credentialSetupVisible -> closeCredentialSettings()
                models.visible -> models.visible = false
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
        connection.configure(
            mode = when (pageData.params.optString("connectionMode")) {
                "ssh", "remote" -> DshConnectionMode.SSH
                else -> DshConnectionMode.RELAY
            },
            profileId = pageData.params.optString("profileId").ifEmpty { DshSessionScope.DEFAULT_REMOTE_PROFILE_ID },
        )
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
        setTimeout(pagerId, 0) { connection.start() }
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
                            DshSessionRail(
                                sessions = { ctx.visibleSessions },
                                activeId = { ctx.activeSessionId },
                                compact = false,
                                onSelect = { id ->
                                    ctx.closeSessionDrawer()
                                    setTimeout(ctx.pagerId, 0) { ctx.selectSession(id) }
                                },
                            )
                            val centerWidth = (ctx.pagerData.pageViewWidth - 236f - 280f).coerceAtLeast(360f)
                            ctx.homeConversation(this, centerWidth)
                            DshSessionDetailsPanel(
                                title = { ctx.sessions.firstOrNull { it.id == ctx.activeSessionId }?.title ?: "尚无标题" },
                                cwd = { ctx.sessions.firstOrNull { it.id == ctx.activeSessionId }?.cwd ?: "" },
                                modelLabel = { ctx.models.selectedLabel },
                                agentPreset = { ctx.sessions.firstOrNull { it.id == ctx.activeSessionId }?.agentPreset.orEmpty() },
                                running = { ctx.sessionRunning },
                                queueCount = { ctx.queue.items.size },
                                jobCount = { ctx.jobs.items.size },
                            )
                        }
                        ctx.perfLog("body.conversation.end wide=true")
                    } else {
                        ctx.perfLog("body.conversation.begin wide=false panels=${ctx.conversationPanelIds.size}")
                        ctx.homeConversation(this, ctx.pagerData.pageViewWidth)
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
                        workspaceGroups = { ctx.workspaces.groups },
                        activeId = { ctx.activeSessionId },
                        animated = { ctx.sessionDrawerAnimated },
                        onClose = { ctx.closeSessionDrawer() },
                        onOpenSettings = { ctx.openSettingsPage() },
                        onNewSession = { ctx.createSession() },
                        onSelect = { id ->
                            ctx.closeSessionDrawer()
                            setTimeout(ctx.pagerId, 0) {
                                ctx.selectSession(id)
                            }
                        },
                    )
                }

                vif({ ctx.models.visible }) {
                    DshModelPicker(
                        options = { ctx.models.options },
                        busy = { ctx.models.busy },
                        error = { ctx.models.error },
                        onClose = { ctx.models.visible = false },
                        onSelect = { ctx.models.select(it) },
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
                vif({ ctx.settingsPageVisible }) {
                    DshSettingsPage(
                        connectionModeLabel = { if (ctx.connection.sshMode) "SSH" else "扫码" },
                        onClose = { ctx.closeSettingsPage() },
                        onOpenConnection = { ctx.connection.openSettings() },
                    )
                }
                val connection = ctx.connection
                val form = connection.sshForm
                vif({ connection.settingsVisible }) {
                    DshConnectionSettingsModal(
                        sshMode = { connection.sshMode },
                        host = { form.host },
                        user = { form.user },
                        port = { form.sshPort },
                        dshPort = { form.dshPort },
                        keyLabel = { form.keyLabel },
                        keyPassphrase = { form.passphrase },
                        busy = { form.busy },
                        error = { form.error },
                        onModeChange = { connection.selectMode(it) },
                        onHostChange = { form.host = it; form.error = "" },
                        onUserChange = { form.user = it; form.error = "" },
                        onPortChange = { form.sshPort = it; form.error = "" },
                        onDshPortChange = { form.dshPort = it; form.error = "" },
                        onPickKey = { form.pickKey() },
                        onPassphraseChange = { form.passphrase = it },
                        onTrustFingerprint = { connection.trustFingerprint() },
                        onSave = { connection.saveSettings() },
                        onClose = { connection.closeSettings() },
                        onOpenApiKey = {
                            connection.closeSettings()
                            ctx.openCredentialSettings()
                        },
                    )
                }
                val workspaces = ctx.workspaces
                vif({ workspaces.browserVisible }) {
                    DshWorkspaceBrowserModal(
                        path = { workspaces.browserPath },
                        home = { workspaces.browserHome },
                        entries = { workspaces.directoryEntries },
                        busy = { workspaces.browserBusy },
                        error = { workspaces.browserError },
                        newName = { workspaces.browserNewName },
                        onDirectorySelect = { workspaces.loadDirectory(it) },
                        onNewNameChange = { workspaces.browserNewName = it },
                        onCreateDirectory = { workspaces.createDirectory() },
                        onAdopt = { workspaces.adoptCurrentDirectory() },
                        onClose = { workspaces.browserVisible = false },
                    )
                }
                vif({ workspaces.renameTargetId.isNotEmpty() }) {
                    DshWorkspaceRenameModal(
                        draft = { workspaces.renameDraft },
                        busy = { workspaces.actionBusy },
                        error = { workspaces.actionError },
                        onDraftChange = { workspaces.renameDraft = it },
                        onSave = { workspaces.saveRename() },
                        onClose = { workspaces.closeRename() },
                    )
                }
                vif({ workspaces.deleteTargetId.isNotEmpty() }) {
                    DshWorkspaceDeleteModal(
                        busy = { workspaces.actionBusy },
                        error = { workspaces.actionError },
                        onConfirm = { workspaces.confirmDelete() },
                        onClose = { workspaces.closeDelete() },
                    )
                }
            }
        }
    }

    /** The conversation column is identical in the wide and narrow layouts; only its width differs. */
    private fun homeConversation(container: ViewContainer<*, *>, availableWidth: Float) {
        val ctx = this
        container.DshConversation(
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
            modelLabel = { ctx.models.selectedLabel },
            attachmentMenuVisible = { ctx.attachmentMenuVisible },
            voiceActive = { ctx.voiceActive },
            onOpenModels = { ctx.openModelPicker() },
            onToggleAttachments = {
                ctx.dismissKeyboard()
                ctx.attachmentMenuVisible = !ctx.attachmentMenuVisible
            },
            onToggleVoice = { ctx.toggleVoice() },
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
            queue = ctx.queue,
            jobs = ctx.jobs,
            goal = ctx.goal,
            interactions = ctx.interactions,
            sessionRunning = { ctx.sessionRunning },
            isBlankConversation = { ctx.isBlankSession() },
            conversationListEpoch = { ctx.conversationListEpochFor(it) },
            turnReconnecting = { isReconnectLabel(ctx.connectionLabel) },
            turnElapsedMs = { ctx.turnElapsedMs },
            availableWidth = availableWidth,
        )
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
        val hostRepository = hostClient ?: return
        hostRepository.loadSessions({ loaded ->
            if (!connection.isActive()) return@loadSessions
            val loadedIds = loaded.map { it.id }.toSet()
            sessions.map { it.id }
                .filterNot { loadedIds.contains(it) }
                .forEach {
                    sessionMessageStates.remove(it)
                    sessionCacheStates.remove(it)
                    sessionMessageReady.remove(it)
                    conversationPanelIds.remove(it)
                }
            loaded.forEach { sessionCacheStates[it.id] = DshSessionCacheState.STALE }
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
                workspaces.refreshGroups()
                if (nextId == null) {
                    messages = ObservableList()
                    createSession()
                    return@loadSessions
                }
                activeSessionId = nextId
                sessionRunning = loaded.firstOrNull { it.id == activeSessionId }?.running == true
                queue.refresh()
                jobs.refresh()
                interactions.refresh()
                models.load(activeSessionId)
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
            if (!connection.isActive()) return@loadSessions
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


    /** Build the client for a freshly opened transport and route its events into the page. */
    private fun createHostClient(endpoint: DshHostConnection): DshHostClient = DshHostClient(
            network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME),
            webSocket = acquireModule<DshWebSocketModule>(DshWebSocketModule.MODULE_NAME),
            connection = endpoint,
            pagerId = pagerId,
            onState = { state -> connection.handleRuntimeState(state) },
            onQueueSnapshot = { sessionId ->
                if (sessionId == activeSessionId) {
                    queue.refresh()
                    interactions.refresh()
                }
            },
            onJobsSnapshot = { sessionId ->
                if (sessionId == activeSessionId) jobs.refresh()
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
                        "goal" -> goal.applyProjection(value)
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
                    models.load(activeSessionId)
                }
            },
            onPendingInteraction = { sessionId ->
                DshStreamLog.question("ui.pending-frame session=$sessionId active=$activeSessionId")
                if (sessionId == activeSessionId) {
                    interactions.refresh()
                    loadWebTimeline(sessionId, scrollToEndAfterLoad = true)
                }
            },
    )

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
        val hostRepository = hostClient
        if (hostRepository == null) {
            credentialSetupError = "远程 DSH 尚未就绪"
            return
        }
        credentialSetupBusy = true
        credentialSetupError = ""
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
        //closeSessionDrawer()
        credentialSetupTitle = "修改电脑端 DSH 的 API Key"
        credentialSetupError = ""
        apiKeyDraft = ""
        updateCredentialSetupVisibility(true)
    }

    private fun openSettingsPage() {
        dismissKeyboard()
        attachmentMenuVisible = false
        settingsPageVisible = true
        dimSystemBars(true)
    }

    private fun closeSettingsPage() {
        settingsPageVisible = false
        dimSystemBars(false)
    }

    private fun stopCurrentEngine() {
        connection.stop()
        goal.reset()
        streamHandle?.cancel()
        streamHandle = null
    }

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
        dimSystemBars(visible)
    }

    private fun dimSystemBars(dimmed: Boolean) {
        if (pageData.isAndroid || pageData.isIOS) {
            bridgeModule.setSystemBarsDimmed(dimmed)
        }
    }

    private fun createSession() {
        val traceId = ++perfTraceSequence
        val startedAt = TimeSource.Monotonic.markNow()
        perfLog("newSession.$traceId.click", startedAt)
        val hostRepository = hostClient ?: run {
            closeSessionDrawer()
            bridgeModule.toast("未连接到远程 DSH")
            return
        }
        dismissKeyboard()
        closeSessionDrawer()
        val currentWorkspaceId = hostRepository.workspaceIdForSession(activeSessionId)
        val blankSession = hostRepository.blankSessionInWorkspace(currentWorkspaceId)
        if (blankSession != null) {
            if (blankSession.id != activeSessionId) {
                selectSession(blankSession.id)
            } else {
                applyActiveSessionChrome()
            }
            loadSkills(blankSession.id)
            setTimeout(pagerId, 0) { models.load(blankSession.id) }
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
                    models.load(sessionId)
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
        loadSkills(sessionId)
        loadWebTimeline(sessionId, scrollToEndAfterLoad)
    }

    private fun loadWebTimeline(
        sessionId: String,
        scrollToEndAfterLoad: Boolean = true,
        forceReplace: Boolean = false,
        afterApply: () -> Unit = {},
    ) {
        val hostRepository = hostClient ?: return
        hostRepository.loadWebTimeline(sessionId, { items ->
            if (activeSessionId != sessionId) return@loadWebTimeline
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
        if (sessionId != activeSessionId) return
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
        hostClient?.detachLiveStreams(sessionId)
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
        val hostRepository = hostClient ?: return
        streamHandle = hostRepository.adoptLiveStream(
            sessionId = sessionId,
            onDelta = { delta, isReasoning ->
                if (!connection.isActive() || activeSessionId != sessionId) return@adoptLiveStream
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
                if (!connection.isActive()) return@adoptLiveStream
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
                if (!connection.isActive()) return@adoptLiveStream
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
        val remote = hostClient ?: return
        skills.clear()
        remote.loadSkills(sessionId, onSuccess = { loaded ->
            if (activeSessionId != sessionId) return@loadSkills
            skills.clear()
            skills.addAll(loaded)
        })
    }

    private fun loadAttachment(sessionId: String, attachmentId: String) {
        if (attachmentDataUrl(attachmentId) != null || !pendingAttachmentReads.add(attachmentId)) return
        val hostRepository = hostClient ?: return
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


    private fun renameActiveSession() {
        val client = hostClient ?: return
        val current = sessions.firstOrNull { it.id == activeSessionId } ?: return
        val title = current.title.takeIf { it != "尚无标题" && it != "新会话" } ?: ""
        if (title.isBlank()) return
        client.renameSession(activeSessionId, title) { _, _ ->
            setTimeout(pagerId, 0) { loadRepository(preferredSessionId = activeSessionId) }
        }
    }

    private fun archiveActiveSession() {
        val client = hostClient ?: return
        client.archiveSession(activeSessionId) { _, _ ->
            setTimeout(pagerId, 0) {
                loadRepository(preferredSessionId = null)
                workspaces.refreshGroups()
            }
        }
    }

    private fun forkActiveSession() {
        val client = hostClient ?: return
        val lastSeq = client.store.sessionLastSeq[activeSessionId]
        client.forkSession(activeSessionId, lastSeq) { value, error ->
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
        val client = hostClient ?: return
        val url = client.sessionExportUrl(activeSessionId)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
            "link_view",
            JSONObject().apply {
                put("pageName", "link_view")
                put("url", url)
            },
        )
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
            if (activeSessionId == id) models.load(id)
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
        interactions.reset()
        goal.clearSnapshot()
        queue.refresh()
        jobs.refresh()
        interactions.refresh()
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
        val hostRepository = hostClient
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
            connectionLabel = connection.syncBusyLabel()
            return
        }
        if (sessions.isEmpty()) {
            connectionLabel = "正在创建会话"
            hostRepository.createSession(null, { sessionId ->
                sessions.add(DshSession(sessionId, "新会话", "Host", "", blank = true))
                refreshVisibleSessions()
                runCatching { localStore?.replaceSessions(activeConnectionId, sessions.toList()) }
                activeSessionId = sessionId
                models.load(sessionId)
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
                if (!connection.isActive()) return@streamReply
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
                if (!connection.isActive()) return@streamReply
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

    private fun openModelPicker() {
        if (sessions.isEmpty()) return
        dismissKeyboard()
        attachmentMenuVisible = false
        models.open()
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
        if (streaming && !force) {
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
        private const val ANIMATION_DURATION_MS = 240
        private const val ANIMATION_DURATION_S = 0.24f
        private const val STREAM_FLUSH_INTERVAL_MS = 16
    }
}
