package io.github.yukiykchen.dsh.feature.home

import io.github.yukiykchen.dsh.data.createDshLocalStore
import io.github.yukiykchen.dsh.data.closeSafely
import io.github.yukiykchen.dsh.platform.supportsRelayBridge
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.*
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.random.Random
import io.github.yukiykchen.dsh.ui.syncVisibleSessions
import io.github.yukiykchen.dsh.ui.effectiveKeyboardHeight
import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.framework.bridgeModule
import io.github.yukiykchen.dsh.framework.dshPerfLog
import io.github.yukiykchen.dsh.platform.DshWebSocketModule
import io.github.yukiykchen.dsh.platform.BasePager
import io.github.yukiykchen.dsh.model.DshHostConnection
import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshSessionScope
import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.model.DshMessageRole
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.feature.connection.DshConnectionController
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.data.DshLocalStore

@Page("home")
class DshHomePage : BasePager(), DshHomeContext {
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
            override fun onSettingsSaved(passphrase: String) {
                stopCurrentEngine()
                actions.openConnectionSetup(passphrase)
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
    private var keyboardAnimation by observable(Animation.easeInOut(DSH_HOME_ANIMATION_DURATION_S))
    private var sessionDrawerVisible by observable(false)
    private var sessionDrawerAnimated by observable(false)
    private var sessionDrawerMaskAnimated by observable(false)
    private var sessionDrawerMaskAnimation by observable(Animation.linear(0f))
    private var attachmentMenuVisible by observable(false)
    private var topBarRef: ViewRef<com.tencent.kuikly.core.views.DivView>? = null
    private var inputView: TextAreaView? = null
    private var repositoryRequestGeneration = 0
    private var pageClosed = false
    private var sessionCreationPending = false
    private val pendingSessionSelections = mutableSetOf<String>()
    private var inputFocused = false
    private var perfTraceSequence = 0
    private var localMessageSequence = 0L
    private val localMessagePrefix = Random.Default.nextLong().toULong().toString(16)
    private val sessionCoordinator = DshHomeSessionCoordinator(
        panelLimit = CONVERSATION_PANEL_CACHE_LIMIT,
        cacheWarmLimit = SESSION_CACHE_WARM_LIMIT,
    )
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
            override fun detachStreams(sessionId: String) { hostClient?.detachLiveStreams(sessionId) }
        },
    )
    private val disclosures = DshDisclosureStore(this) { scroller.refresh(activeSessionId) }
    private val attachments = DshAttachmentCache(this, onLoaded = { sessionId ->
        val next = sessionStore.state(sessionId).toList()
        if (activeSessionId == sessionId) replaceMessagesIfChanged(next)
        else sessionStore.put(sessionId, ObservableList<DshMessage>().also { it.addAll(next) })
    })
    private val liveEvents = DshLiveEventPresenter(
        messages = { messages },
        splitBeforeTool = { turn.splitBeforeTool() },
        refresh = { scroller.refresh(activeSessionId) },
        scrollToEnd = { scroller.scrollToEnd() },
        loadAttachment = { attachments.load(activeSessionId, it) },
    )
    private val history = DshHomeHistoryController(
        ctx = this,
        onTimeline = { sessionId, projected, scrollToEnd, forceReplace ->
            sessionStore.markReady(sessionId)
            replaceMessagesIfChanged(projected, forceReplace)
            if (projected.isNotEmpty()) sessionStore.persist(sessionId, messages)
            projected.mapNotNull { it.attachmentId }.forEach { attachments.load(sessionId, it) }
            completePendingSessionSelection(sessionId)
            realizeSessionAfterData(sessionId, scrollToEnd)
        },
        onFailure = { sessionId, forceReplace ->
            if (forceReplace && !sessionRunning && (turn.streaming || turn.stopButtonVisible)) {
                turn.finishFromHistory(sessionId)
            }
        },
    )
    private var sessionRunning by observable(false)

    private val drawerAnimator = DshSessionDrawerAnimator(
        scope = this,
        drawerVisible = { sessionDrawerVisible },
        setDrawerVisible = { sessionDrawerVisible = it },
        setDrawerAnimated = { sessionDrawerAnimated = it },
        setMaskAnimated = { sessionDrawerMaskAnimated = it },
        setMaskAnimation = { sessionDrawerMaskAnimation = it },
        warmCache = { warmRecentSessionCache(scrollToEndAfterLoad = false) },
    )
    private val actions: DshHomePageActions by lazy { DshHomePageActions(
        pageData = { pageData },
        bridge = { bridgeModule },
        openPage = { name, data ->
            acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(name, data)
        },
        dismissKeyboard = { dismissKeyboard() },
        setAttachmentMenuVisible = { attachmentMenuVisible = it },
        setSettingsPageVisible = { settingsPageVisible = it },
        credentials = credentials,
        connection = connection,
        goal = goal,
        turn = turn,
    ) }
    /** Close the topmost overlay before navigating away from the page. */
    internal val overlayBackCallback = object : BackPressCallback() {
        override fun handleOnBackPressed() {
            when {
                workspaces.handleBack() -> Unit
                connection.settingsVisible -> connection.closeSettings()
                settingsPageVisible -> closeSettingsPage()
                credentials.visible -> closeCredentialSettings()
                models.visible -> models.close()
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
        connection.sshForm.passphrase = pageData.params.optString("keyPassphrase")
        pageData.params.put("keyPassphrase", "")
        restoreCachedSessions()
        if (sessions.isEmpty()) {
            messages = ObservableList()
            createSession()
        }
        dshPerfLog("startup.restoreCachedSessions.done", startedAt)
        ensureConversationPanel(activeSessionId)
        sessionStore.preload(sessions.map { it.id })
        dshPerfLog("startup.preloadAllSessionMessages.scheduled", startedAt)
        setTimeout(pagerId, SESSION_CACHE_WARM_START_DELAY_MS) {
            warmRecentSessionCache(scrollToEndAfterLoad = false)
        }
        setTimeout(pagerId, 0) { if (!pageClosed) connection.start() }
        getBackPressHandler().addCallback(overlayBackCallback)
        dshPerfLog("startup.created.end", startedAt)
    }

    override fun pageWillDestroy() {
        pageClosed = true
        history.dispose()
        models.dispose()
        credentials.dispose()
        interactions.dispose()
        queue.dispose()
        goal.dispose()
        jobs.dispose()
        workspaces.dispose()
        drawerAnimator.dispose()
        actions.stopCurrentEngine(disposing = true)
        attachments.close()
        sessionStore.close()
        localStore.closeSafely()
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
                    backgroundColor(Color(DSH_HOME_BG))
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

                DshHomeContent(
                    backgroundColor = DSH_HOME_BG,
                    animationDurationSeconds = DSH_HOME_ANIMATION_DURATION_S,
                    wide = wide,
                    pageWidth = ctx.pagerData.pageViewWidth,
                    pagerId = ctx.pagerId,
                    panelCount = { ctx.conversationPanelIds.size },
                    sessions = { ctx.sessions },
                    visibleSessions = { ctx.visibleSessions },
                    activeSessionId = { ctx.activeSessionId },
                    sessionDrawerVisible = { ctx.sessionDrawerVisible },
                    sessionDrawerAnimated = { ctx.sessionDrawerAnimated },
                    sessionDrawerMaskAnimated = { ctx.sessionDrawerMaskAnimated },
                    sessionDrawerMaskAnimation = { ctx.sessionDrawerMaskAnimation },
                    workspaceGroups = { ctx.workspaces.groups },
                    modelLabel = { ctx.models.selectedLabel },
                    sessionRunning = { ctx.sessionRunning },
                    queueCount = { ctx.queue.items.size },
                    jobCount = { ctx.jobs.items.size },
                    homeConversation = { width -> ctx.homeConversation(this, width) },
                    closeSessionDrawer = { ctx.closeSessionDrawer() },
                    selectSession = { id -> ctx.selectSession(id) },
                    openSettingsPage = { ctx.openSettingsPage() },
                    createSession = { ctx.createSession() },
                )

                DshHomeOverlays(
                    settingsPageVisible = { ctx.settingsPageVisible },
                    closeSettingsPage = { ctx.closeSettingsPage() },
                    closeCredentialSettings = { ctx.closeCredentialSettings() },
                    openCredentialSettings = { ctx.openCredentialSettings() },
                    connection = ctx.connection,
                    models = ctx.models,
                    credentials = ctx.credentials,
                    workspaces = ctx.workspaces,
            stopCurrentEngine = { ctx.stopCurrentEngine() },
                )
            }
        }
    }

    /** The conversation column is identical in the wide and narrow layouts; only its width differs. */
    private fun homeConversation(container: ViewContainer<*, *>, availableWidth: Float) {
        container.DshHomeConversation(
            DshHomeConversationBinding(
                conversationIds = { conversationPanelIds },
                activeConversationId = { activeSessionId },
                messagesForSession = { sessionStore.state(it) },
                streaming = { turn.streaming },
                streamingMessageId = { turn.liveId },
                streamingContent = { turn.liveContent },
                scrollerRef = { id, ref -> scroller.bindScroller(id, ref) },
                messageRef = { sessionId, messageId, ref -> scroller.bindRow(sessionId, messageId, ref) },
                draft = { draft },
                setDraft = { draft = it },
                skills = { history.skills },
                keyboardHeight = { keyboardHeight },
                keyboardAnimation = { keyboardAnimation },
                stopButtonVisible = { turn.stopButtonVisible },
                inputRef = { inputView = it.view },
                onInputFocusChange = { inputFocused = it },
                onKeyboardHeightChange = { updateKeyboard(it) },
                onSend = { sendDraft() },
                onStop = { stopStream() },
                onDismissKeyboard = { dismissKeyboard() },
                onUserListScroll = { scroller.onUserScroll(it) },
                modelLabel = { models.selectedLabel },
                attachmentMenuVisible = { attachmentMenuVisible },
                setAttachmentMenuVisible = { attachmentMenuVisible = it },
                onOpenModels = { openModelPicker() },
                isDisclosureExpanded = { disclosures.isExpanded(it) },
                onToggleDisclosure = { disclosures.toggle(it) },
                isBodyDisclosureExpanded = { disclosures.isBodyExpanded(it) },
                onToggleBodyDisclosure = { disclosures.toggleBody(it) },
                isJsonNodeExpanded = { messageId, nodeId -> disclosures.isJsonNodeExpanded(messageId, nodeId) },
                onToggleJsonNode = { messageId, nodeId -> disclosures.toggleJsonNode(messageId, nodeId) },
                onCopyToolContent = {
                    bridgeModule.copyToPasteboard(it)
                    bridgeModule.toast("已复制")
                },
                attachmentDataUrl = { sessionId, id -> attachments.dataUrl(sessionId, id) },
                queue = queue,
                jobs = jobs,
                goal = goal,
                interactions = interactions,
                sessionRunning = { sessionRunning },
                isBlankConversation = { isBlankSession() },
                conversationListEpoch = { sessionStore.epochFor(it) },
                connectionLabel = { connectionLabel },
                turnElapsedMs = { turnStatus.elapsedMs },
            ),
            availableWidth,
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

    private fun openSessionDrawer() = drawerAnimator.open()

    private fun closeSessionDrawer() = drawerAnimator.close()

    private fun refreshVisibleSessions() = syncVisibleSessions(sessions, visibleSessions)

    private fun loadRepository(preferredSessionId: String? = null) {
        val hostRepository = hostClient ?: return
        val generation = hostRepository.currentConnectionState().generation
        val request = ++repositoryRequestGeneration
        hostRepository.loadSessions({ loaded ->
            if (!acceptsRequest(hostRepository, generation) || request != repositoryRequestGeneration) return@loadSessions
            val loadedIds = loaded.map { it.id }.toSet()
            sessions.map { it.id }
                .filterNot { loadedIds.contains(it) }
                .forEach {
                    sessionStore.forget(it)
                    conversationPanelIds.remove(it)
                    scroller.forget(it)
                    attachments.forget(it)
                }
            sessions.clear()
            sessions.addAll(loaded)
            refreshVisibleSessions()
            localStore?.replaceSessionsAsync(activeConnectionId, loaded)
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
            if (!acceptsRequest(hostRepository, generation) || request != repositoryRequestGeneration) return@loadSessions
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
    private fun createHostClient(endpoint: DshHostConnection): DshHostClient {
        val clientGeneration = connection.clientGeneration
        val callbacks = createHomeHostCallbacks(
            activeSessionId = { activeSessionId },
            handleRuntimeState = { state -> connection.handleRuntimeState(state) },
            refreshQueue = { queue.refresh() },
            refreshInteractions = { interactions.refresh() },
            refreshJobs = { jobs.refresh() },
            sessionRunning = { sessionRunning },
            setSessionRunning = { sessionRunning = it },
            resyncStreaming = { sessionId, reason -> resyncStreamingWithHost(sessionId, reason) },
            syncTurnStatus = { turnStatus.sync() },
            setConnectionLabel = { connectionLabel = it },
            applyGoalProjection = { goal.applyProjection(it) },
            showRunningTool = liveEvents::showRunningTool,
            settleRunningTool = liveEvents::settleRunningTool,
            showContextInjection = liveEvents::showContextInjection,
            showAssistantBlocks = liveEvents::showAssistantBlocks,
            loadSkills = { loadSkills(it) },
            loadModels = { models.load(it) },
            loadWebTimeline = { loadWebTimeline(it, scrollToEndAfterLoad = true) },
            acceptsClient = { !pageClosed && connection.isActive() && connection.clientGeneration == clientGeneration },
        )
        return DshHostClient(
            network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME),
            webSocket = acquireModule<DshWebSocketModule>(DshWebSocketModule.MODULE_NAME),
            connection = endpoint,
            pagerId = pagerId,
            onState = callbacks.onState,
            onQueueSnapshot = callbacks.onQueueSnapshot,
            onJobsSnapshot = callbacks.onJobsSnapshot,
            onSessionStatus = callbacks.onSessionStatus,
            onProjection = callbacks.onProjection,
            onSessionEvent = callbacks.onSessionEvent,
            onRemoteEvent = callbacks.onRemoteEvent,
            onPendingInteraction = callbacks.onPendingInteraction,
        ).also { it.start() }
    }

    private fun openCredentialSettings() = actions.openCredentialSettings()

    private fun openSettingsPage() = actions.openSettingsPage()

    private fun closeSettingsPage() = actions.closeSettingsPage()

    private fun stopCurrentEngine() = actions.stopCurrentEngine()

    private fun openConnectionSetup() = actions.openConnectionSetup()

    private fun closeCredentialSettings() = actions.closeCredentialSettings()

    private fun dimSystemBars(dimmed: Boolean) = actions.dimSystemBars(dimmed)

    private fun createSession() {
        if (pageClosed || sessionCreationPending) return
        val traceId = ++perfTraceSequence
        val startedAt = TimeSource.Monotonic.markNow()
        dshPerfLog("newSession.$traceId.click", startedAt)
        val hostRepository = hostClient ?: run {
            closeSessionDrawer()
            bridgeModule.toast("未连接到远程 DSH")
            return
        }
        val generation = hostRepository.currentConnectionState().generation
        val originSessionId = activeSessionId
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
        sessionCreationPending = true
        hostRepository.createSession(currentWorkspaceId, { sessionId ->
            sessionCreationPending = false
            if (!acceptsRequest(hostRepository, generation)) return@createSession
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
            localStore?.replaceSessionsAsync(activeConnectionId, sessions.toList())
            if (activeSessionId != originSessionId) return@createSession
            turn.detachForSessionSwitch()
            sessionStore.put(activeSessionId, messages)
            activeSessionId = sessionId
            messages = ObservableList()
            sessionStore.put(sessionId, messages)
            sessionStore.markReady(sessionId)
            ensureConversationPanel(sessionId)
            dshPerfLog("newSession.$traceId.ui.ready", startedAt)
            draft = ""
            applyActiveSessionChrome()
            setTimeout(pagerId, 0) {
                if (activeSessionId == sessionId) {
                    loadSkills(sessionId)
                    models.load(sessionId)
                }
            }
        }, { error ->
            sessionCreationPending = false
            if (!acceptsRequest(hostRepository, generation)) return@createSession
            dshPerfLog("newSession.$traceId.host.create.error:$error", startedAt)
            connectionLabel = "新会话创建失败"
            messages.add(DshMessage(nextLocalMessageId("session-create-error"), DshMessageRole.ERROR, error))
        })
    }

    private fun loadHistory(
        sessionId: String,
        scrollToEndAfterLoad: Boolean = true,
    ) {
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
    ) = history.loadTimeline(sessionId, scrollToEndAfterLoad, forceReplace, afterApply)

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

    private fun loadSkills(sessionId: String) = history.loadSkills(sessionId)

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
        val readyIds = if (sessionStore.isReady(id)) setOf(id) else emptySet()
        when (sessionCoordinator.selectionResult(id, activeSessionId, readyIds, conversationPanelIds.toSet())) {
            DshHomeSessionCoordinator.SelectionResult.READY -> Unit
            DshHomeSessionCoordinator.SelectionResult.WAIT_FOR_DATA -> {
            pendingSessionSelections.add(id)
            dshPerfLog("switch.$traceId.wait-data", startedAt)
            return
            }
            DshHomeSessionCoordinator.SelectionResult.WAIT_FOR_PANEL -> {
            ensureConversationPanel(id)
            addTaskWhenPagerUpdateLayoutFinish {
                dshPerfLog("switch.$traceId.panel.layout-finished", startedAt)
                if (activeSessionId != id) selectSession(id)
            }
            return
            }
        }
        selectMountedSession(id, traceId, startedAt)
    }

    private fun selectMountedSession(id: String, traceId: Int = 0, startedAt: TimeMark? = null) {
        if (id == activeSessionId) return
        dshPerfLog("switch.$traceId.mounted.begin", startedAt)
        scroller.refresh(id)
        turn.detachForSessionSwitch()
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
        history.invalidateHistory()
        sessionStore.loadFromDisk(id)
        fetchHostHistory(id)
        setTimeout(pagerId, 0) {
            if (activeSessionId == id) models.load(id)
        }
        draft = ""
        applyActiveSessionChrome()
        dshPerfLog("switch.$traceId.end", startedAt)
    }

    private fun isBlankSession(sessionId: String = activeSessionId): Boolean =
        sessions.firstOrNull { it.id == sessionId }?.blank == true

    private fun applyActiveSessionChrome() = refreshHomeSessionControllers(interactions, goal, queue, jobs)

    private fun refreshMountedSessionRenderTrees() = conversationPanelIds.toList().forEach { scroller.refresh(it) }

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

    private fun completePendingSessionSelection(sessionId: String) {
        if (!pendingSessionSelections.remove(sessionId)) return
        setTimeout(pagerId, 0) {
            if (activeSessionId != sessionId) selectSession(sessionId)
        }
    }

    private fun warmRecentSessionCache(
        sessionIds: kotlin.collections.List<String> = sessionCoordinator.sessionsToWarm(
            sessions.map { it.id }, activeSessionId, conversationPanelIds.toSet(),
        ),
        index: Int = 0,
        scrollToEndAfterLoad: Boolean = true,
    ) {
        if (pageClosed || index >= sessionIds.size) return
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
        sessionCoordinator.evictionTarget(conversationPanelIds.toList(), activeSessionId)?.let { evictedId ->
            conversationPanelIds.remove(evictedId)
            scroller.forget(evictedId)
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
                nextLocalMessageId("send-engine-error"),
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
            if (sessionCreationPending) return
            sessionCreationPending = true
            val generation = hostRepository.currentConnectionState().generation
            bootstrapHomeSession(
                hostClient = hostRepository,
                isCurrent = { acceptsRequest(hostRepository, generation) },
                onFinished = { sessionCreationPending = false },
                addSession = { sessions.add(it) },
                refreshVisibleSessions = { refreshVisibleSessions() },
                persistSessions = { rows -> localStore?.replaceSessionsAsync(activeConnectionId, rows) },
                currentSessions = { sessions.toList() },
                setActiveSessionId = { activeSessionId = it },
                loadModels = { models.load(it) },
                retrySend = { sendDraft() },
                setConnectionLabel = { connectionLabel = it },
                addMessage = { messages.add(it) },
                messageCount = { messages.size },
            )
            return
        }
        val sessionId = activeSessionId
        val user = DshMessage(nextLocalMessageId("user"), DshMessageRole.USER, prompt)
        val assistantId = nextLocalMessageId("assistant")
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
        keyboardAnimation = Animation.easeInOut(DSH_HOME_ANIMATION_DURATION_S)
        keyboardHeight = effectiveKeyboardHeight(params.height)
        // Closing the keyboard after send must not undo the scroll to the
        // newly sent user message. Scroll to the end only when the composer
        // is opening while no response is being anchored.
        if (keyboardHeight > 0f && !turn.streaming) scroller.scrollToEnd()
    }

    private fun effectiveKeyboardHeight(rawHeight: Float): Float =
        effectiveKeyboardHeight(rawHeight, pagerData)

    private fun openModelPicker() {
        if (sessions.isEmpty()) return
        dismissKeyboard()
        attachmentMenuVisible = false
        models.open()
    }

    private fun replaceMessagesIfChanged(next: List<DshMessage>, force: Boolean = false) {
        replaceMessagesIfChanged(messages, next, turn.streaming, force, activeSessionId, sessionStore)
    }

    private fun acceptsRequest(client: DshHostClient, generation: Long): Boolean =
        !pageClosed && hostClient === client && client.currentConnectionState().generation == generation

    private fun nextLocalMessageId(prefix: String): String = "$prefix-$localMessagePrefix-${++localMessageSequence}"

}
