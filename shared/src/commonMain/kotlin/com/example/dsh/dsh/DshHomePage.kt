package com.example.dsh.dsh

import com.example.dsh.base.BasePager
import com.example.dsh.base.bridgeModule
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.TextAreaView
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.KeyboardParams
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val SESSION_CACHE_WARM_LIMIT = 7
private const val SESSION_CACHE_WARM_INTERVAL_MS = 16
private const val SESSION_CACHE_WARM_START_DELAY_MS = 600
private const val CONVERSATION_PANEL_CACHE_LIMIT = 8

/** First usable DSH surface: local sessions, streaming Markdown, and a composer. */
@Page("home")
internal class DshHomePage : BasePager(), DshHomeContext {
    private var localStore: DshLocalStore? = null
    private val connection: DshConnectionController = DshConnectionController(
        scope = this,
        localStore = { localStore },
        supportsRelay = { pageData.supportsRelayBridge },
        createClient = ::createHostClient,
        listener = object : DshConnectionController.Listener {
            override fun onClientConnected() = loadRepository(preferredSessionId = activeSessionId)
            override fun onClientReconnected() = loadRepository(preferredSessionId = activeSessionId)
            override fun onTransportStateChanged() = turnStatus.sync()
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
    private var keyboardHeight by observable(0f)
    private var keyboardAnimation by observable(Animation.easeInOut(ANIMATION_DURATION_S))
    private var sessionDrawerVisible by observable(false)
    private var sessionDrawerAnimated by observable(false)
    private var sessionDrawerMaskAnimated by observable(false)
    private var sessionDrawerMaskAnimation by observable(Animation.linear(0f))
    private var attachmentMenuVisible by observable(false)
    private var voiceActive by observable(false)
    private var topBarRef: ViewRef<com.tencent.kuikly.core.views.DivView>? = null
    private var inputView: TextAreaView? = null
    private var historyRequestGeneration = 0
    private val pendingSessionSelections = mutableSetOf<String>()
    private var inputFocused = false
    private var perfTraceSequence = 0
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
    private val credentials = DshCredentialController(
        ctx = this,
        onVisibilityChanged = { dimSystemBars(it) },
        onSaved = {
            dismissKeyboard()
            connectionLabel = "远程 DSH 已更新"
            loadRepository()
        },
    )
    private val turnStatus: DshTurnStatusTicker = DshTurnStatusTicker(this) { turn.streaming || turn.stopButtonVisible || sessionRunning }
    private val sessionStore = DshSessionMessageStore(
        scope = this,
        localStore = { localStore },
        connectionId = { activeConnectionId },
        isBlank = { isBlankSession(it) },
        listener = object : DshSessionMessageStore.Listener {
            override fun onDiskLoaded(sessionId: String, preload: Boolean, scrollToEndAfterLoad: Boolean) {
                if (!preload || conversationPanelIds.size < CONVERSATION_PANEL_CACHE_LIMIT) {
                    ensureConversationPanel(sessionId)
                }
                realizeSessionAfterData(sessionId, scrollToEndAfterLoad)
                completePendingSessionSelection(sessionId)
            }

            override fun onReady(sessionId: String) = completePendingSessionSelection(sessionId)
        },
    )
    private val scroller: DshConversationScroller = DshConversationScroller(this, { messages }, { turn.liveId })
    private val turn: DshStreamingTurnController = DshStreamingTurnController(
        ctx = this,
        messages = { messages },
        scroller = scroller,
        listener = object : DshStreamingTurnController.Listener {
            override fun isConnectionActive() = connection.isActive()
            override fun onStatusLabel(label: String) {
                connectionLabel = label
            }
            override fun onActivityChanged() = turnStatus.sync()
            override fun persist(sessionId: String) = sessionStore.persist(sessionId, messages)
        },
    )
    private val disclosures = DshDisclosureStore(this) { scroller.refresh(activeSessionId) }
    private val attachments = DshAttachmentCache(this) { sessionId ->
        val next = sessionStore.state(sessionId).toList()
        if (activeSessionId == sessionId) replaceMessagesIfChanged(next)
        else sessionStore.put(sessionId, ObservableList<DshMessage>().also { it.addAll(next) })
    }
    private val skills by observableList<DshSkill>()
    private var sessionRunning by observable(false)

    /**
     * 系统返回键统一入口：按 z-order 关闭最顶层覆盖层，
     * 所有覆盖层都关闭后再通过 RouterModule.closePage() 结束当前页面。
     */
    internal val overlayBackCallback = object : BackPressCallback() {
        override fun handleOnBackPressed() {
            when {
                workspaces.handleBack() -> Unit
                connection.settingsVisible -> connection.closeSettings()
                credentials.visible -> closeCredentialSettings()
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
        dshPerfLog("startup.created.begin", startedAt)
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
            sessionStore.put(activeSessionId, messages)
            ensureConversationPanel(activeSessionId)
        }
        dshPerfLog("startup.restoreCachedSessions.done", startedAt)
        ensureConversationPanel(activeSessionId)
        sessionStore.preload(sessions.map { it.id })
        dshPerfLog("startup.preloadAllSessionMessages.scheduled", startedAt)
        setTimeout(pagerId, SESSION_CACHE_WARM_START_DELAY_MS) {
            warmRecentSessionCache(scrollToEndAfterLoad = false)
        }
        setTimeout(pagerId, 0) { connection.start() }
        getBackPressHandler().addCallback(overlayBackCallback)
        dshPerfLog("startup.created.end", startedAt)
    }

    override fun pageWillDestroy() {
        stopCurrentEngine()
        sessionStore.close()
        super.pageWillDestroy()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val wide = pagerData.pageViewWidth >= 720f
        return {
            dshPerfLog("body.builder.begin")
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
                        dshPerfLog("body.conversation.begin wide=true panels=${ctx.conversationPanelIds.size}")
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
                        dshPerfLog("body.conversation.end wide=true")
                    } else {
                        dshPerfLog("body.conversation.begin wide=false panels=${ctx.conversationPanelIds.size}")
                        ctx.homeConversation(this, ctx.pagerData.pageViewWidth)
                        dshPerfLog("body.conversation.end wide=false")
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

                vif({ ctx.credentials.visible }) {
                    DshCredentialSetupModal(
                        title = { ctx.credentials.title },
                        busy = { ctx.credentials.busy },
                        error = { ctx.credentials.error },
                        inputRef = { ctx.credentials.bindInput(it.view) },
                        onApiKeyChange = { ctx.credentials.updateDraft(it) },
                        onSave = { ctx.credentials.save() },
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
            messagesForSession = { ctx.sessionStore.state(it) },
            streaming = { ctx.turn.streaming },
            streamingMessageId = { ctx.turn.liveId },
            streamingContent = { ctx.turn.liveContent },
            scrollerRef = { id, ref -> ctx.scroller.bindScroller(id, ref) },
            messageRef = { sessionId, messageId, ref -> ctx.scroller.bindRow(sessionId, messageId, ref) },
            draft = { ctx.draft },
            skills = { ctx.skills },
            onPickSkill = { ctx.draft = "/$it " },
            keyboardHeight = { ctx.keyboardHeight },
            stopButtonVisible = { ctx.turn.stopButtonVisible },
            inputRef = { ctx.inputView = it.view },
            onInputFocusChange = { ctx.inputFocused = it },
            onDraftChange = { ctx.draft = it },
            keyboardAnimation = { ctx.keyboardAnimation },
            onKeyboardHeightChange = { ctx.updateKeyboard(it) },
            onSend = { ctx.sendDraft() },
            onStop = { ctx.stopStream() },
            onDismissKeyboard = { ctx.dismissKeyboard() },
            onUserListScroll = { ctx.scroller.onUserScroll(it) },
            modelLabel = { ctx.models.selectedLabel },
            attachmentMenuVisible = { ctx.attachmentMenuVisible },
            voiceActive = { ctx.voiceActive },
            onOpenModels = { ctx.openModelPicker() },
            onToggleAttachments = {
                ctx.dismissKeyboard()
                ctx.attachmentMenuVisible = !ctx.attachmentMenuVisible
            },
            onToggleVoice = { ctx.toggleVoice() },
            isDisclosureExpanded = { ctx.disclosures.isExpanded(it) },
            onToggleDisclosure = { ctx.disclosures.toggle(it) },
            isBodyDisclosureExpanded = { ctx.disclosures.isBodyExpanded(it) },
            onToggleBodyDisclosure = { ctx.disclosures.toggleBody(it) },
            isJsonNodeExpanded = { messageId, nodeId ->
                ctx.disclosures.isJsonNodeExpanded(messageId, nodeId)
            },
            onToggleJsonNode = { messageId, nodeId ->
                ctx.disclosures.toggleJsonNode(messageId, nodeId)
            },
            onCopyToolContent = {
                ctx.bridgeModule.copyToPasteboard(it)
                ctx.bridgeModule.toast("已复制")
            },
            attachmentDataUrl = { ctx.attachments.dataUrl(it) },
            queue = ctx.queue,
            jobs = ctx.jobs,
            goal = ctx.goal,
            interactions = ctx.interactions,
            sessionRunning = { ctx.sessionRunning },
            isBlankConversation = { ctx.isBlankSession() },
            conversationListEpoch = { ctx.sessionStore.epochFor(it) },
            turnReconnecting = { isReconnectLabel(ctx.connectionLabel) },
            turnElapsedMs = { ctx.turnStatus.elapsedMs },
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
                    sessionStore.forget(it)
                    conversationPanelIds.remove(it)
                }
            sessions.clear()
            sessions.addAll(loaded)
            refreshVisibleSessions()
            runCatching { localStore?.replaceSessions(activeConnectionId, loaded) }
            sessionStore.preload(sessions.map { it.id })
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
                if (turn.streaming || turn.stopButtonVisible || sessionRunning) {
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
                    turnStatus.sync()
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

    private fun openCredentialSettings() {
        dismissKeyboard()
        attachmentMenuVisible = false
        credentials.open()
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
        turn.detach()
    }

    private fun openConnectionSetup() {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
            "connection_setup",
            JSONObject().apply { put("pageName", "connection_setup") },
        )
    }

    private fun closeCredentialSettings() {
        dismissKeyboard()
        credentials.close()
    }

    private fun dimSystemBars(dimmed: Boolean) {
        if (pageData.isAndroid || pageData.isIOS) {
            bridgeModule.setSystemBarsDimmed(dimmed)
        }
    }

    private fun createSession() {
        val traceId = ++perfTraceSequence
        val startedAt = TimeSource.Monotonic.markNow()
        dshPerfLog("newSession.$traceId.click", startedAt)
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
        dshPerfLog("newSession.$traceId.ui.cleared", startedAt)
        dshPerfLog("newSession.$traceId.host.create.request", startedAt)
        hostRepository.createSession(currentWorkspaceId, { sessionId ->
            dshPerfLog("newSession.$traceId.host.create.response:$sessionId", startedAt)
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
            sessionStore.put(sessionId, messages)
            sessionStore.markReady(sessionId)
            ensureConversationPanel(sessionId)
            dshPerfLog("newSession.$traceId.ui.ready", startedAt)
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
            dshPerfLog("newSession.$traceId.host.create.error:$error", startedAt)
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
        messages = sessionStore.state(
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
            val projected = items.map { it.toMessage() }
            sessionStore.markReady(sessionId)
            replaceMessagesIfChanged(projected, forceReplace)
            if (projected.isNotEmpty()) {
                sessionStore.persist(sessionId, messages)
            }
            projected.mapNotNull { it.attachmentId }.forEach { attachments.load(sessionId, it) }
            completePendingSessionSelection(sessionId)
            realizeSessionAfterData(sessionId, scrollToEndAfterLoad)
            afterApply()
        }, { error ->
            DshStreamLog.i("ui.history-fail session=$sessionId error='${DshStreamLog.preview(error)}'")
            if (forceReplace && !sessionRunning && (turn.streaming || turn.stopButtonVisible)) {
                turn.finishFromHistory(sessionId)
            }
            afterApply()
        })
    }

    private fun resyncStreamingWithHost(sessionId: String, reason: String) {
        if (sessionId != activeSessionId) return
        DshStreamLog.i(
            "ui.resync.begin reason=$reason session=$sessionId running=$sessionRunning streaming=${turn.streaming} stop=${turn.stopButtonVisible}",
        )
        // A local prompt is already painting this turn. Reloading the web
        // timeline remounts every markdown bubble and delays the first token.
        if (reason == "host-session-running" && turn.isLocalPromptInFlight()) {
            DshStreamLog.i(
                "ui.resync.skip-local-stream reason=$reason session=$sessionId live=${turn.liveId}",
            )
            return
        }
        if (sessionRunning) {
            loadWebTimeline(sessionId, scrollToEndAfterLoad = true, forceReplace = true) {
                turn.resumeFromHistory(sessionId, reason)
            }
        } else {
            val forceReplace = turn.streaming || turn.stopButtonVisible
            loadWebTimeline(sessionId, scrollToEndAfterLoad = true, forceReplace = forceReplace) {
                turn.finishFromHistory(sessionId)
                connectionLabel = "已连接"
                DshStreamLog.i("ui.resync.settled reason=$reason session=$sessionId messages=${messages.size}")
            }
        }
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

    private fun showRunningTool(event: DshRawSessionEvent) {
        val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return
        val model = DshRemoteToolCallModels.fromLiveCall(payload) ?: return
        val id = "tool-${event.seq}"
        if (messages.any { it.id == id }) return
        // The Host emits tool/call after the assistant block that introduced
        // it. Seal that block before appending its card so the list follows the
        // actual event order instead of grouping all cards at the turn end.
        turn.splitBeforeTool()
        messages.add(model.toRemoteMessage(id))
        scroller.refresh(activeSessionId)
        scroller.scrollToEnd()
    }

    private fun showContextInjection(event: DshRawSessionEvent) {
        val message = dshContextInjectionMessage(event) ?: return
        if (messages.any { it.id == message.id }) return
        messages.add(message)
        scroller.scrollToEnd()
    }

    private fun showAssistantBlocks(event: DshRawSessionEvent) {
        val rows = dshAssistantBlockMessages(event) ?: return
        for (row in rows) {
            if (messages.none { it.id == row.id }) messages.add(row)
            row.attachmentId?.let { attachments.load(activeSessionId, it) }
        }
        scroller.scrollToEnd()
    }

    private fun settleRunningTool(event: DshRawSessionEvent) {
        val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return
        val callId = dshToolResultCallId(payload)
        if (callId.isEmpty()) return
        val index = messages.indexOfFirst { it.role == DshMessageRole.TOOL && it.toolCallId == callId }
        if (index < 0) return
        val previous = messages[index].remoteTool ?: return
        val model = DshRemoteToolCallModels.settleLiveResult(previous, payload) ?: return
        messages[index] = model.toRemoteMessage(messages[index].id)
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
            val state = sessionStore.state(homeId, loadFromDisk = false)
            state.clear()
            sessionStore.markReady(homeId)
            messages = state
            ensureConversationPanel(homeId)
            return
        }
        val state = ObservableList<DshMessage>()
        messages = state
        sessionStore.put(activeSessionId, state)
        sessionStore.markReady(activeSessionId)
        ensureConversationPanel(activeSessionId)
    }

    private fun selectSession(id: String) {
        val traceId = ++perfTraceSequence
        val startedAt = TimeSource.Monotonic.markNow()
        dshPerfLog("switch.$traceId.request:$id", startedAt)
        dismissKeyboard()
        if (id == activeSessionId) {
            dshPerfLog("switch.$traceId.same-session", startedAt)
            return
        }
        if (!sessionStore.isReady(id)) {
            pendingSessionSelections.add(id)
            dshPerfLog("switch.$traceId.wait-data", startedAt)
            return
        }
        if (!conversationPanelIds.contains(id)) {
            ensureConversationPanel(id)
            addTaskWhenPagerUpdateLayoutFinish {
                dshPerfLog("switch.$traceId.panel.layout-finished", startedAt)
                if (activeSessionId != id) selectSession(id)
            }
            return
        }
        selectMountedSession(id, traceId, startedAt)
    }

    private fun selectMountedSession(id: String, traceId: Int = 0, startedAt: TimeMark? = null) {
        if (id == activeSessionId) return
        dshPerfLog("switch.$traceId.mounted.begin", startedAt)
        scroller.refresh(id)
        turn.cancelForSessionSwitch()
        sessionStore.put(activeSessionId, messages)
        val nextMessages = sessionStore.state(id, loadFromDisk = false)
        ensureConversationPanel(id)
        messages = nextMessages
        activeSessionId = id
        dshPerfLog("switch.$traceId.active-state-swapped", startedAt)
        scroller.scrollToEnd()
        addTaskWhenPagerUpdateLayoutFinish {
            scroller.refresh(id)
            dshPerfLog("switch.$traceId.layout.realized", startedAt)
            if (activeSessionId == id) scroller.scrollToEnd()
        }
        // Invalidate any in-flight request for the previous session before
        // starting the new one, so an old response cannot repaint this view.
        historyRequestGeneration++
        sessionStore.loadFromDisk(id)
        fetchHostHistory(id)
        setTimeout(pagerId, 0) {
            if (activeSessionId == id) models.load(id)
        }
        draft = ""
        inputView?.setText("")
        applyActiveSessionChrome()
        dshPerfLog("switch.$traceId.end", startedAt)
    }

    private fun isBlankSession(sessionId: String = activeSessionId): Boolean =
        sessions.firstOrNull { it.id == sessionId }?.blank == true

    private fun applyActiveSessionChrome() {
        interactions.reset()
        goal.clearSnapshot()
        queue.refresh()
        jobs.refresh()
        interactions.refresh()
    }


    private fun refreshMountedSessionRenderTrees() {
        conversationPanelIds.toList().forEach { scroller.refresh(it) }
    }

    private fun realizeSessionAfterData(
        sessionId: String,
        scrollToEndAfterLoad: Boolean = true,
    ) {
        scroller.refresh(sessionId)
        addTaskWhenPagerUpdateLayoutFinish {
            scroller.refresh(sessionId)
            if (scrollToEndAfterLoad && activeSessionId == sessionId) scroller.scrollToEnd()
        }
        setTimeout(pagerId, 16) {
            scroller.refresh(sessionId)
            if (scrollToEndAfterLoad && activeSessionId == sessionId) scroller.scrollToEnd()
        }
    }

    private fun loadCachedHistory(sessionId: String) {
        messages = sessionStore.state(sessionId, loadFromDisk = false)
        ensureConversationPanel(sessionId)
        sessionStore.loadFromDisk(sessionId)
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
        sessionStore.state(
            sessionIds[index],
            loadFromDisk = true,
            scrollToEndAfterLoad = scrollToEndAfterLoad,
        )
        if (sessionStore.isReady(sessionIds[index])) {
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
                scroller.forget(evictedId)
            }
        }
        conversationPanelIds.add(sessionId)
    }

    private fun sendDraft() {
        dismissKeyboard()
        val prompt = draft.trim()
        if (prompt.isEmpty() || turn.streaming) return
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
        val wasEmpty = messages.isEmpty()
        messages.add(user)
        // DSH ChatView keeps the assistant node out of the flow until the
        // first token. The turn-status row ("Deep diving...") occupies that
        // gap so LazyLoop never has to realize an empty markdown bubble.
        sessionStore.put(sessionId, messages)
        if (wasEmpty) sessionStore.remount(sessionId)
        scroller.pinTail()
        scroller.scrollToMessage(user.id)
        draft = ""
        inputView?.setText("")
        turn.send(hostRepository, sessionId, prompt, assistantId)
    }

    private fun stopStream() {
        if (!turn.stopButtonVisible) return
        dismissKeyboard()
        turn.stop()
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
        if (keyboardHeight > 0f && !turn.streaming) scroller.scrollToEnd()
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

    private fun replaceMessagesIfChanged(next: List<DshMessage>, force: Boolean = false) {
        val filtered = next.filterNot { it.isRuntimeContextSnapshot() }
        if (turn.streaming && !force) {
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
            "ui.replace-messages from=${current.size} to=${filtered.size} streaming=${turn.streaming} force=$force remount=$remount preview='${DshStreamLog.preview(filtered.lastOrNull()?.content.orEmpty())}'",
        )
        applyMessagesInPlace(filtered)
        sessionStore.put(activeSessionId, messages)
        if (remount) sessionStore.remount(activeSessionId)
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
    }
}
