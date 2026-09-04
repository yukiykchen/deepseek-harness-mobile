package com.example.dsh.dsh

import com.example.dsh.base.BasePager
import com.example.dsh.base.bridgeModule
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vforLazy
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.KeyboardParams
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.ListContentView
import com.tencent.kuikly.core.views.ListView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private fun visibleSessionList(source: ObservableList<DshSession>): ObservableList<DshSession> =
    ObservableList<DshSession>().also { result -> result.addAll(source.filterNot { it.blank }) }

private fun mainSessionList(
    source: ObservableList<DshSession>,
    archived: ObservableList<DshSession>,
): ObservableList<DshSession> {
    val archivedIds = archived.map { it.id }.toSet()
    return ObservableList<DshSession>().also { result ->
        result.addAll(source.filterNot { it.blank || archivedIds.contains(it.id) })
    }
}

internal fun dshNextUnarchivedSession(
    sessions: List<DshSession>,
    archivedIds: Set<String>,
    excludedId: String,
): DshSession? =
    sessions.firstOrNull { !it.blank && it.id != excludedId && !archivedIds.contains(it.id) }
        ?: sessions.firstOrNull { it.blank && it.id != excludedId && !archivedIds.contains(it.id) }

internal fun visibleSkillList(source: ObservableList<DshSkill>, query: String): ObservableList<DshSkill> =
    ObservableList<DshSkill>().also { result -> result.addAll(source.filter { it.name.startsWith(query) }) }

internal fun isRemoteCatalogInvalidationEvent(event: String): Boolean = event in setOf(
    "commands/change",
    "skills/change",
    "agent-preset/selected",
    "settings/document-updated",
    "credentials/updated",
    "llm/adapters-updated",
)

internal fun parseGoalProjection(raw: String): DshGoalSnapshot? {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val goal = root.optJSONObject("goal") ?: return null
    val id = goal.optString("id")
    val revision = goal.optInt("revision")
    val objective = goal.optString("objective")
    val phase = goal.optString("phase")
    if (id.isEmpty() || revision <= 0 || objective.isEmpty() || phase.isEmpty() || phase == "complete") return null
    return DshGoalSnapshot(
        id = id,
        revision = revision,
        objective = objective,
        phase = phase,
        blockedReason = goal.optJSONObject("blockedReason")?.optString("message").orEmpty(),
    )
}

internal fun DshToolCardType.iconAsset(): String = when (this) {
    DshToolCardType.TERMINAL -> "tool-terminal.svg"
    DshToolCardType.READ -> "tool-read.svg"
    DshToolCardType.DIFF -> "tool-diff.svg"
    DshToolCardType.SEARCH -> "tool-search.svg"
    DshToolCardType.WEB -> "tool-web.svg"
    DshToolCardType.JSON -> "tool-json.svg"
    DshToolCardType.GENERIC -> "tool-generic.svg"
}

/** Remote tool-name semantics choose the icon even before a result view exists. */
internal fun DshRemoteToolCallModel.iconAsset(): String = when (kind) {
    DshRemoteToolKind.BASH -> "tool-terminal.svg"
    DshRemoteToolKind.READ -> "tool-read.svg"
    DshRemoteToolKind.FILE_MUTATION -> "tool-diff.svg"
    DshRemoteToolKind.SEARCH -> "tool-search.svg"
    DshRemoteToolKind.WEB -> "tool-web.svg"
    DshRemoteToolKind.SKILL -> "tool-skill.svg"
    DshRemoteToolKind.ASK_QUESTION -> "tool-ask.svg"
    DshRemoteToolKind.TODO,
    DshRemoteToolKind.GENERIC -> cardType.iconAsset()
}

internal fun String.dshLooksLikeJson(): Boolean {
    val value = trimStart()
    return value.startsWith("{") || value.startsWith("[")
}

internal fun String.dshReasoningSummary(running: Boolean): String {
    val visible = trimEnd()
    val newline = indexOf('\n')
    if (running) {
        val lastNewline = visible.lastIndexOf('\n')
        return if (lastNewline < 0) visible else visible.substring(lastNewline + 1)
    }
    return if (newline < 0) visible else substring(0, newline)
}

internal fun contextCatalogEntries(source: JSONObject?): List<DshContextCatalogEntry> {
    if (source?.optString("form") != "catalog") return emptyList()
    val entries = source.optJSONArray("entries") ?: return emptyList()
    val result = mutableListOf<DshContextCatalogEntry>()
    for (index in 0 until entries.length()) {
        val entry = entries.optJSONObject(index) ?: continue
        val name = entry.optString("name")
        if (name.isEmpty()) return emptyList()
        result += DshContextCatalogEntry(name, entry.optString("description"))
    }
    return result.take(200)
}

internal fun contextSections(source: JSONObject?): List<DshContextSection> {
    if (source?.optString("form") != "snapshot") return emptyList()
    val sections = source.optJSONArray("sections") ?: return emptyList()
    val result = mutableListOf<DshContextSection>()
    for (index in 0 until sections.length()) {
        val section = sections.optJSONObject(index) ?: continue
        val name = section.optString("name")
        if (name.isEmpty()) return emptyList()
        result += DshContextSection(name, section.optString("text"))
    }
    return result
}

internal fun contextRecalls(source: JSONObject?): List<DshContextRecall> {
    if (source?.optString("form") != "recall") return emptyList()
    val references = source.optJSONArray("references") ?: return emptyList()
    val result = mutableListOf<DshContextRecall>()
    for (index in 0 until references.length()) {
        val reference = references.optJSONObject(index) ?: continue
        val label = reference.optString("label")
        if (label.isEmpty()) return emptyList()
        result += DshContextRecall(
            label = label,
            retainedMessages = reference.optInt("retainedMessages"),
            omittedMessages = reference.optInt("omittedMessages"),
            truncated = reference.optBoolean("truncated"),
        )
    }
    return result
}

internal fun contextInstructions(source: JSONObject?): List<DshContextInstruction> {
    if (source?.optString("form") != "instructions") return emptyList()
    val changes = source.optJSONArray("changes") ?: return emptyList()
    val result = mutableListOf<DshContextInstruction>()
    for (index in 0 until changes.length()) {
        val change = changes.optJSONObject(index) ?: continue
        val path = change.optString("path")
        val action = change.optString("action")
        if (path.isEmpty() || (action != "set" && action != "replace" && action != "remove")) return emptyList()
        result += DshContextInstruction(path, action)
    }
    return result
}

internal fun contextRelaySender(source: JSONObject?): String {
    if (source?.optString("form") != "relay") return ""
    return source.optString("senderSessionId").takeIf { it.isNotEmpty() } ?: ""
}

internal fun boundedContextText(text: String): String {
    if (text.length <= 20_000) return text
    return text.take(20_000) + "\n… 共 ${text.length} 字符"
}

internal fun buildQuestionAnswer(
    question: DshPendingQuestion,
    drafts: Map<Int, DshQuestionDraft>,
): JSONObject {
    return JSONObject().apply {
        put("answers", JSONArray().apply {
            question.questions.forEachIndexed { index, item ->
                val draft = drafts[index] ?: DshQuestionDraft()
                put(JSONObject().apply {
                    put("id", item.id)
                    put("selected", JSONArray().apply { draft.selected.forEach(::put) })
                    if (!draft.skipped && draft.custom.isNotBlank()) put("custom", draft.custom.trim())
                })
            }
        })
    }
}

internal fun DshMessage.contextCanExpand(): Boolean {
    return content.isNotEmpty() ||
        contextCatalog.isNotEmpty() ||
        contextSections.isNotEmpty() ||
        contextRecalls.isNotEmpty() ||
        contextInstructions.isNotEmpty() ||
        contextRelaySender.isNotEmpty()
}

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
    private var messages by observableList<DshMessage>()
    private var conversationPanelIds by observableList<String>()
    private var activeSessionId by observable("session-1")
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
    private var inputView: InputView? = null
    private var apiKeyInputView: InputView? = null
    private var streamHandle: DshStreamHandle? = null
    private val messageScrollerRefs = mutableMapOf<String, ViewRef<ListView<*, *>>>()
    private val messageRowRefs = mutableMapOf<String, ViewRef<com.tencent.kuikly.core.views.DivView>>()
    private var historyRequestGeneration = 0
    private val sessionMessageStates = mutableMapOf<String, ObservableList<DshMessage>>()
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
    private var streamingReasoningId = ""
    private var streamingReasoningContent = ""
    private val pendingAssistantDelta = StringBuilder()
    private var assistantFlushScheduled = false
    private var scrollSettleGeneration = 0
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
    private val archivedSessions by observableList<DshSession>()
    private var archivedSessionsVisible by observable(false)
    private var activeSessionArchived by observable(false)
    private var sessionManageTargetId by observable("")
    private var sessionRenameTargetId by observable("")
    private var sessionRenameDraft by observable("")
    private var sessionArchiveTargetId by observable("")
    private var sessionActionBusy by observable(false)
    private var sessionActionError by observable("")
    private val skills by observableList<DshSkill>()
    private var goalSnapshot by observable<DshGoalSnapshot?>(null)
    private var goalActionBusy by observable(false)
    private var goalActionError by observable("")
    private var queueEditingId by observable("")
    private var queueEditingText by observable("")
    private var sessionRunning by observable(false)
    private var turnElapsedMs by observable(0L)
    private var turnShimmerOn by observable(false)
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
                                    sessions = { mainSessionList(ctx.sessions, ctx.archivedSessions) },
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
                                turnReconnecting = { isReconnectLabel(ctx.connectionLabel) },
                                turnElapsedMs = { ctx.turnElapsedMs },
                                turnShimmerOn = { ctx.turnShimmerOn },
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
                                    archived = { ctx.activeSessionArchived },
                                    onRename = { ctx.openSessionRename(ctx.activeSessionId) },
                                    onArchive = { ctx.openSessionArchive(ctx.activeSessionId) },
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
                            turnReconnecting = { isReconnectLabel(ctx.connectionLabel) },
                            turnElapsedMs = { ctx.turnElapsedMs },
                            turnShimmerOn = { ctx.turnShimmerOn },
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
                        sessions = { ctx.sessions },
                        workspaceGroups = { ctx.workspaceGroups },
                        archivedSessions = { ctx.archivedSessions },
                        isWebTimeline = { ctx.isRemoteHost },
                        activeId = { ctx.activeSessionId },
                        animated = { ctx.sessionDrawerAnimated },
                        onClose = { ctx.closeSessionDrawer() },
                        onOpenSettings = { ctx.openConnectionSettings() },
                        onNewSession = { ctx.createSession() },
                        onOpenArchived = { ctx.archivedSessionsVisible = true },
                        onManage = {
                            ctx.archivedSessionsVisible = false
                            ctx.openSessionManage(it)
                        },
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
                vif({ ctx.sessionManageTargetId.isNotEmpty() && ctx.isRemoteHost }) {
                    DshSessionManageModal(
                        title = {
                            ctx.sessions.firstOrNull { it.id == ctx.sessionManageTargetId }?.title
                                ?: "会话"
                        },
                        archived = {
                            ctx.archivedSessions.any { it.id == ctx.sessionManageTargetId }
                        },
                        onRename = {
                            val id = ctx.sessionManageTargetId
                            ctx.sessionManageTargetId = ""
                            ctx.openSessionRename(id)
                        },
                        onArchive = {
                            val id = ctx.sessionManageTargetId
                            ctx.sessionManageTargetId = ""
                            ctx.openSessionArchive(id)
                        },
                        onClose = { ctx.sessionManageTargetId = "" },
                    )
                }
                vif({ ctx.sessionRenameTargetId.isNotEmpty() && ctx.isRemoteHost }) {
                    DshSessionRenameModal(
                        draft = { ctx.sessionRenameDraft },
                        busy = { ctx.sessionActionBusy },
                        error = { ctx.sessionActionError },
                        onDraftChange = {
                            ctx.sessionRenameDraft = it
                            ctx.sessionActionError = ""
                        },
                        onSave = { ctx.saveSessionRename() },
                        onClose = { ctx.closeSessionActionModals() },
                    )
                }
                vif({ ctx.sessionArchiveTargetId.isNotEmpty() && ctx.isRemoteHost }) {
                    DshSessionArchiveModal(
                        title = {
                            ctx.sessions.firstOrNull { it.id == ctx.sessionArchiveTargetId }?.title
                                ?: "此会话"
                        },
                        busy = { ctx.sessionActionBusy },
                        error = { ctx.sessionActionError },
                        onConfirm = { ctx.confirmSessionArchive() },
                        onClose = { ctx.closeSessionActionModals() },
                    )
                }
                vif({ ctx.archivedSessionsVisible && ctx.isRemoteHost }) {
                    DshArchivedSessionsModal(
                        sessions = { ctx.archivedSessions },
                        activeId = { ctx.activeSessionId },
                        onSelect = { id ->
                            ctx.archivedSessionsVisible = false
                            ctx.activeSessionArchived = true
                            ctx.closeSessionDrawer()
                            setTimeout(ctx.pagerId, 0) { ctx.selectSession(id) }
                        },
                        onManage = {
                            ctx.archivedSessionsVisible = false
                            ctx.openSessionManage(it)
                        },
                        onClose = { ctx.archivedSessionsVisible = false },
                    )
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
            runCatching { localStore?.replaceSessions(activeConnectionId, loaded) }
            preloadAllSessionMessages()
            refreshWorkspaceGroups()
            connectionLabel = if (loaded.isEmpty()) "已连接 · 无会话" else "已连接 · 正在同步远程历史"
            if (loaded.isNotEmpty()) {
                val archivedIds = archivedSessions.map { it.id }.toSet()
                val preferred = loaded.firstOrNull {
                    it.id == preferredSessionId &&
                        (activeSessionArchived || !archivedIds.contains(it.id))
                }
                val selected = preferred
                    ?: dshNextUnarchivedSession(loaded, archivedIds, excludedId = "")
                    ?: loaded.first()
                activeSessionId = selected.id
                activeSessionArchived = archivedIds.contains(selected.id)
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
                messages.clear()
                messages.add(
                    DshMessage(
                        id = "no-session",
                        role = DshMessageRole.ERROR,
                        content = "当前还没有会话，打开左上角菜单后点击“新会话”即可开始。",
                    ),
                )
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
        if (!pageData.isAndroid && !pageData.isIOS) {
            connectionLabel = "扫码连接目前仅支持 Android 和 iOS"
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
            onArchivedSessionsChanged = { handleArchivedSessionsChanged() },
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
            activeSessionArchived = false
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
            }
            runCatching { localStore?.replaceSessions(activeConnectionId, sessions.toList()) }
            activeSessionId = sessionId
            activeSessionArchived = false
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
        val requestGeneration = ++historyRequestGeneration

        // Show the selected session immediately. The Host history request is
        // remote and can take a moment, so keeping the previous list here
        // makes a session switch look stuck.
        messages = sessionMessageState(
            sessionId,
            scrollToEndAfterLoad = scrollToEndAfterLoad,
        )
        ensureConversationPanel(sessionId)

        if (isRemoteHost) {
            loadSkills(sessionId)
            loadWebTimeline(sessionId, scrollToEndAfterLoad)
            return
        }

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
        if (sessionRunning) {
            loadWebTimeline(sessionId, scrollToEndAfterLoad = true, forceReplace = true) {
                resumeStreamingFromHistory(sessionId, reason)
            }
        } else {
            loadWebTimeline(sessionId, scrollToEndAfterLoad = true, forceReplace = true) {
                finishStreamingFromHistory(sessionId)
                connectionLabel = "已连接"
                DshStreamLog.i("ui.resync.settled reason=$reason session=$sessionId messages=${messages.size}")
            }
        }
    }

    private fun rebindStreamingToHistoryTail(): Boolean {
        val live = messages.lastOrNull { it.role == DshMessageRole.ASSISTANT && !it.isReasoning } ?: return false
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
        if (rebindStreamingToHistoryTail()) {
            streaming = true
            stopButtonVisible = true
            connectionLabel = "正在生成"
            val index = messages.indexOfFirst { it.id == streamingAssistantId }
            if (index >= 0) {
                messages[index] = messages[index].copy(streaming = true)
            }
        } else {
            streamingAssistantRootId = "assistant-adopted-${messages.size}"
            streamingAssistantId = ""
            streamingAssistantSegment = 0
            streamingAssistantContent = ""
            streaming = true
            stopButtonVisible = true
            connectionLabel = "正在生成"
        }
        attachAdoptedLiveStream(sessionId)
        syncTurnStatusTicker()
        DshStreamLog.i(
            "ui.resync.resume reason=$reason id=${streamingAssistantId} chars=${streamingAssistantContent.length}",
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
                    if (streamingAssistantId.isEmpty()) ensureStreamingAssistantSegment()
                    queueAssistantDelta(streamingAssistantId, delta)
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
                loadWebTimeline(sessionId, scrollToEndAfterLoad = false)
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
                loadWebTimeline(sessionId, scrollToEndAfterLoad = false)
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
            archivedSessions.clear()
            activeSessionArchived = false
            return
        }
        val repository = repository as? DshRemoteRepository ?: return
        val groups = repository.workspaceGroups()
        workspaceGroups.clear()
        workspaceGroups.addAll(groups)
        archivedSessions.clear()
        archivedSessions.addAll(repository.archivedSessions())
    }

    private fun handleArchivedSessionsChanged() {
        val repository = repository as? DshRemoteRepository ?: return
        refreshWorkspaceGroups()
        if (sessionActionBusy && sessionArchiveTargetId == activeSessionId) return
        if (activeSessionArchived || !repository.store.archivedSessionIds.contains(activeSessionId)) return
        val next = dshNextUnarchivedSession(
            sessions = sessions.toList(),
            archivedIds = repository.store.archivedSessionIds,
            excludedId = activeSessionId,
        )
        if (next != null) selectSession(next.id) else createSession()
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

    private fun openSessionManage(sessionId: String) {
        if (!isRemoteHost || sessions.none { it.id == sessionId }) return
        sessionManageTargetId = sessionId
    }

    private fun openSessionRename(sessionId: String) {
        val current = sessions.firstOrNull { it.id == sessionId } ?: return
        if (!isRemoteHost) return
        sessionRenameTargetId = sessionId
        sessionRenameDraft = current.title.takeUnless { it == "尚无标题" || it == "新会话" }.orEmpty()
        sessionActionError = ""
    }

    private fun saveSessionRename() {
        val repository = repository as? DshRemoteRepository ?: return
        val sessionId = sessionRenameTargetId
        if (sessionId.isEmpty() || sessionActionBusy) return
        val title = sessionRenameDraft.trim()
        if (title.isEmpty()) {
            sessionActionError = "会话名称不能为空"
            return
        }
        sessionActionBusy = true
        sessionActionError = ""
        repository.renameSession(sessionId, title) { _, error ->
            setTimeout(pagerId, 0) {
                sessionActionBusy = false
                if (error != null) {
                    sessionActionError = if (error.code == "title-invalid") {
                        "会话名称不能为空"
                    } else {
                        error.message
                    }
                    return@setTimeout
                }
                val updated = repository.store.sessions[sessionId]
                val index = sessions.indexOfFirst { it.id == sessionId }
                if (updated != null && index >= 0) sessions[index] = updated
                refreshWorkspaceGroups()
                closeSessionActionModals()
            }
        }
    }

    private fun openSessionArchive(sessionId: String) {
        if (!isRemoteHost || archivedSessions.any { it.id == sessionId }) return
        sessionArchiveTargetId = sessionId
        sessionActionError = ""
    }

    private fun confirmSessionArchive() {
        val repository = repository as? DshRemoteRepository ?: return
        val sessionId = sessionArchiveTargetId
        if (sessionId.isEmpty() || sessionActionBusy) return
        sessionActionBusy = true
        sessionActionError = ""
        repository.archiveSession(sessionId) { _, error ->
            setTimeout(pagerId, 0) {
                sessionActionBusy = false
                if (error != null) {
                    sessionActionError = error.message
                    return@setTimeout
                }
                val wasActive = activeSessionId == sessionId
                val next = dshNextUnarchivedSession(
                    sessions = sessions.toList(),
                    archivedIds = repository.store.archivedSessionIds,
                    excludedId = sessionId,
                )
                if (wasActive) activeSessionArchived = true
                closeSessionActionModals()
                refreshWorkspaceGroups()
                if (wasActive) {
                    if (next != null) selectSession(next.id)
                    else createSession()
                }
            }
        }
    }

    private fun closeSessionActionModals() {
        if (sessionActionBusy) return
        sessionManageTargetId = ""
        sessionRenameTargetId = ""
        sessionRenameDraft = ""
        sessionArchiveTargetId = ""
        sessionActionError = ""
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
        val firstSessionId = cached.first().id
        val firstMessages = runCatching { store.loadMessages(activeConnectionId, firstSessionId) }
            .getOrDefault(emptyList())
            .filterNot { it.isRuntimeContextSnapshot() }
        activeSessionId = firstSessionId
        val state = sessionMessageStates[firstSessionId] ?: ObservableList()
        if (state.size == 1 && state.firstOrNull()?.id == "api-key-required") state.clear()
        if (state.isEmpty() && firstMessages.isNotEmpty()) state.addAll(firstMessages)
        sessionMessageStates[firstSessionId] = state
        sessionMessageReady.add(firstSessionId)
        if (isRemoteHost) {
            sessionCacheStates[firstSessionId] = DshSessionCacheState.STALE
        }
        messages = state
        ensureConversationPanel(firstSessionId)
    }

    private fun loadApiKeyAsync() {
        val store = localStore
        if (store == null) {
            showCredentialSetupIfNeeded("")
            return
        }
        localReadScope.launch {
            val apiKey = runCatching { store.loadApiKey() }.getOrDefault("")
            setTimeout(pagerId, 0) {
                pendingApiKey = apiKey
                if (sshMode) {
                    connectionLabel = "等待 SSH 连接"
                } else if (apiKey.isEmpty()) {
                    showCredentialSetupIfNeeded(apiKey)
                } else if (engineReady && repository == null && connectionMode == DshConnectionMode.LOCAL) {
                    connectLocalEngine(apiKey)
                }
            }
        }
    }

    private fun showCredentialSetupIfNeeded(apiKey: String) {
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
        if (!conversationPanelIds.contains(id) || !messageScrollerRefs.containsKey(id)) {
            // Mount the target ListView first. Changing activeSessionId in the
            // same frame would make the new panel visible before its native
            // render tree and Markdown children exist.
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
        activeSessionArchived =
            (repository as? DshRemoteRepository)?.store?.archivedSessionIds?.contains(id) == true
        sessionRunning = sessions.firstOrNull { it.id == id }?.running == true
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
            turnShimmerOn = false
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
                turnShimmerOn = false
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
            val shimmer = ((elapsed / 1_800L) % 2L) == 1L
            if (turnShimmerOn != shimmer) turnShimmerOn = shimmer
            setTimeout(pagerId, if (showClock) 1_000 else 200) { tick() }
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
                    if (state.isEmpty() && loaded.isNotEmpty()) {
                        state.addAll(loaded)
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
                if (state.isEmpty() && loaded.isNotEmpty()) {
                    state.addAll(loaded)
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
                runCatching { localStore?.replaceSessions(activeConnectionId, sessions.toList()) }
                activeSessionId = sessionId
                activeSessionArchived = false
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
        messages.add(user)
        // DSH ChatView keeps the assistant node out of the flow until the
        // first token. The turn-status row ("Deep diving...") occupies that
        // gap so LazyLoop never has to realize an empty markdown bubble.
        sessionMessageStates[sessionId] = messages
        scrollMessagesToMessage(user.id)
        streamingAssistantId = ""
        streamingAssistantRootId = assistantId
        streamingAssistantSegment = 0
        streamingReasoningId = reasoningId
        streamingReasoningContent = ""
        streamingAssistantContent = ""
        pendingAssistantDelta.setLength(0)
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
                if (isRemoteHost) loadWebTimeline(sessionId, scrollToEndAfterLoad = false)
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
                if (isRemoteHost) loadWebTimeline(sessionId, scrollToEndAfterLoad = false)
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
        scrollMessagesToEnd()
    }

    private fun flushAssistantDelta() {
        if (streamingAssistantId.isEmpty() || pendingAssistantDelta.isEmpty()) return
        streamingAssistantContent += pendingAssistantDelta.toString()
        pendingAssistantDelta.setLength(0)
        DshStreamLog.i(
            "ui.flush id=$streamingAssistantId chars=${streamingAssistantContent.length} preview='${DshStreamLog.preview(streamingAssistantContent)}'",
        )
        updateStreamingMessage(streamingAssistantContent, streaming = true)
        refreshSessionRenderTree(activeSessionId)
        // Follow the assistant while SSE produces new content. The initial
        // send still anchors on the user's message until the first delta.
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
        streamingAssistantContent = ""
        messages.add(DshMessage(id, DshMessageRole.ASSISTANT, "", streaming = true))
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
                if (current.content.isEmpty()) {
                    messages.removeAt(index)
                } else {
                    messages[index] = current.copy(streaming = false)
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
    }

    private fun finalizeStreamingReasoning() {
        if (streamingReasoningId.isEmpty()) return
        val index = messages.indexOfFirst { it.id == streamingReasoningId }
        if (index >= 0) {
            messages[index] = messages[index].copy(streaming = false, isReasoning = true)
        }
    }

    private fun scrollMessagesToEnd() {
        val generation = ++scrollSettleGeneration
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
        if (generation != scrollSettleGeneration) return
        scrollMessagesToEndAfterLayout()
        if (attempt >= SCROLL_SETTLE_ATTEMPTS) return
        setTimeout(pagerId, SCROLL_SETTLE_DELAYS_MS[attempt]) {
            addTaskWhenPagerUpdateLayoutFinish {
                settleScrollToEnd(generation, attempt + 1)
            }
        }
    }

    private fun scrollMessagesToEndAfterLayout() {
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
                    streamingAssistantId = ""
                    streamingAssistantRootId = ""
                    streamingAssistantSegment = 0
                    if (streamingAssistantContent == finalContent) {
                        streamingAssistantContent = ""
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
        if (messages.toList() == filtered) return
        DshStreamLog.i(
            "ui.replace-messages from=${messages.size} to=${filtered.size} streaming=$streaming force=$force preview='${DshStreamLog.preview(filtered.lastOrNull()?.content.orEmpty())}'",
        )
        messages.clear()
        messages.addAll(filtered)
        sessionMessageStates[activeSessionId] = messages
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

private fun ViewContainer<*, *>.DshConnectionSettingsModal(
    sshMode: () -> Boolean,
    host: () -> String,
    user: () -> String,
    port: () -> String,
    dshPort: () -> String,
    keyLabel: () -> String,
    keyPassphrase: () -> String,
    busy: () -> Boolean,
    error: () -> String,
    onModeChange: (Boolean) -> Unit,
    onHostChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onDshPortChange: (String) -> Unit,
    onPickKey: () -> Unit,
    onPassphraseChange: (String) -> Unit,
    onTrustFingerprint: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onOpenApiKey: () -> Unit,
) {
    Modal(inWindow = true) {
        attr { absolutePositionAllZero(); allCenter(); backgroundColor(Color(0x66000000)); padding(20f) }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(440f)
                flexDirectionColumn()
                padding(22f)
                borderRadius(16f)
                backgroundColor(Color.WHITE)
            }
            View {
                attr { height(32f); flexDirectionRow(); alignItemsCenter() }
                Text { attr { text("连接设置"); flex(1f); fontSize(20f); fontWeightBold(); color(Color(0xFF1F2933)) } }
                View { attr { size(32f, 32f); allCenter() }; Image { attr { src(ImageUri.commonAssets("x.svg")); size(20f, 20f) } }; DshHitButton { if (!busy()) onClose() } }
            }
            Text { attr { text("选择 Agent 运行位置"); marginTop(16f); fontSize(13f); color(Color(0xFF68737D)) } }
            View {
                attr { height(42f); marginTop(8f); flexDirectionRow(); borderRadius(8f); backgroundColor(Color(0xFFF1F3F5)); padding(4f) }
                View {
                    attr { flex(1f); height(34f); flexDirectionRow(); alignItemsCenter(); justifyContentCenter(); backgroundColor(Color(if (!sshMode()) 0xFFFFFFFF else 0x00FFFFFF)); borderRadius(6f) }
                    Text { attr { text("扫码连接"); fontSize(13f); color(Color(if (!sshMode()) 0xFF4176E6 else 0xFF68737D)) } }
                    event { click { onModeChange(false) } }
                }
                View {
                    attr { flex(1f); height(34f); flexDirectionRow(); alignItemsCenter(); justifyContentCenter(); backgroundColor(Color(if (sshMode()) 0xFFFFFFFF else 0x00FFFFFF)); borderRadius(6f) }
                    Text { attr { text("SSH 连接电脑"); fontSize(13f); color(Color(if (sshMode()) 0xFF4176E6 else 0xFF68737D)) } }
                    event { click { onModeChange(true) } }
                }
            }
            vif({ !sshMode() }) {
                Text { attr { text("扫码模式连接电脑上的 DSH。返回连接页可重新扫码或更换电脑。"); marginTop(16f); fontSize(14f); lineHeight(21f); color(Color(0xFF68737D)) } }
                View {
                    attr { height(40f); marginTop(16f); flexDirectionRow(); justifyContentFlexEnd() }
                    Button { attr { width(132f); height(40f); borderRadius(8f); backgroundColor(Color(0xFF4176E6)); titleAttr { text("返回连接页"); fontSize(14f); color(Color.WHITE) } }; event { click { if (!busy()) onSave() } } }
                }
            }
            velse {
                DshConnectionInput("SSH 主机", host, "例如 100.86.12.34 或 computer.example.com", onHostChange)
                DshConnectionInput("SSH 用户名", user, "例如 alex", onUserChange)
                View { attr { flexDirectionRow(); marginTop(12f) }; DshConnectionInput("SSH 端口", port, "22", onPortChange, 0.5f); DshConnectionInput("远程 DSH 端口", dshPort, "3080", onDshPortChange, 0.5f, 10f) }
                View {
                    attr { height(44f); marginTop(12f); flexDirectionRow(); alignItemsCenter(); paddingLeft(12f); paddingRight(10f); borderRadius(8f); backgroundColor(Color(0xFFF1F3F5)) }
                    Text { attr { text(keyLabel()); flex(1f); fontSize(13f); color(Color(0xFF4F565C)) } }
                    Text { attr { text(if (busy()) "导入中..." else "选择私钥"); fontSize(13f); color(Color(0xFF4176E6)) }; event { click { if (!busy()) onPickKey() } } }
                }
                DshConnectionInput("私钥口令（如有）", keyPassphrase, "仅本次连接使用", onPassphraseChange, password = true)
                vif({ error().startsWith("首次连接需要确认主机指纹：") }) {
                    View {
                        attr { marginTop(10f); padding(10f); borderRadius(8f); backgroundColor(Color(0xFFFFF7E6)) }
                        Text { attr { text("请确认这是你电脑的 SSH 主机指纹。确认后会保存，指纹变化时连接将被拒绝。"); fontSize(12f); lineHeight(18f); color(Color(0xFF7A5B16)) } }
                        Text { attr { text("信任此指纹并连接"); marginTop(8f); fontSize(13f); color(Color(0xFF4176E6)) }; event { click { if (!busy()) onTrustFingerprint() } } }
                    }
                }
                vif({ error().isNotEmpty() && !error().startsWith("首次连接需要确认主机指纹：") }) {
                    Text { attr { text(error()); marginTop(8f); fontSize(12f); lineHeight(18f); color(Color(0xFFBF3535)) } }
                }
                View { attr { marginTop(18f); height(40f); flexDirectionRow(); justifyContentFlexEnd() }; Button { attr { width(132f); height(40f); borderRadius(8f); backgroundColor(Color(if (busy()) 0xFFB7C8FE else 0xFF4176E6)); titleAttr { text(if (busy()) "连接中..." else "保存并连接"); fontSize(14f); color(Color.WHITE) } }; event { click { if (!busy()) onSave() } } } }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshConnectionInput(
    title: String,
    value: () -> String,
    hint: String,
    onChange: (String) -> Unit,
    flexValue: Float = 1f,
    marginLeft: Float = 0f,
    password: Boolean = false,
) {
    View {
        attr { flex(flexValue); marginLeft(marginLeft); flexDirectionColumn() }
        Text { attr { text(title); marginTop(10f); fontSize(12f); color(Color(0xFF68737D)) } }
        View {
            attr { height(40f); marginTop(5f); borderRadius(8f); border(Border(1f, BorderStyle.SOLID, Color(0xFFD9DEE3))); backgroundColor(Color(0xFFF9FAFB)); paddingLeft(10f); paddingRight(10f) }
            Input {
                ref { it.view?.setText(value()) }
                attr { flex(1f); fontSize(14f); color(Color(0xFF222C35)); placeholder(hint); placeholderColor(Color(0xFF98A1A9)); returnKeyTypeDone(); if (password) keyboardTypePassword() }
                event { textDidChange { onChange(it.text) } }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshCredentialSetupModal(
    title: () -> String,
    busy: () -> Boolean,
    error: () -> String,
    inputRef: (ViewRef<InputView>) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
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
                flexDirectionColumn()
                padding(24f)
                borderRadius(18f)
                backgroundColor(Color.WHITE)
            }
            View {
                attr {
                    height(32f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text(title())
                        flex(1f)
                        fontSize(20f)
                        fontWeightBold()
                        color(Color(0xFF1F2933))
                    }
                }
                View {
                    attr {
                        size(32f, 32f)
                        allCenter()
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("x.svg"))
                            size(20f, 20f)
                        }
                    }
                    DshHitButton { if (!busy()) onClose() }
                }
            }
            Text {
                attr {
                    text(if (title().contains("电脑端")) "确认后将修改电脑端 DSH 的凭据。" else "配置 DeepSeek 官方模型，即可开始使用。")
                    marginTop(8f)
                    fontSize(14f)
                    lineHeight(21f)
                    color(Color(0xFF6B7680))
                }
            }
            Text {
                attr {
                    text("API Key")
                    marginTop(22f)
                    fontSize(13f)
                    fontWeightMedium()
                    color(Color(0xFF343E47))
                }
            }
            View {
                attr {
                    height(46f)
                    marginTop(8f)
                    borderRadius(8f)
                    border(Border(1f, BorderStyle.SOLID, Color(
                        if (error().isEmpty()) 0xFFD9DEE3 else 0xFFD44949,
                    )))
                    backgroundColor(Color(0xFFF9FAFB))
                    paddingLeft(12f)
                    paddingRight(12f)
                }
                Input {
                    ref { inputRef(it) }
                    attr {
                        flex(1f)
                        fontSize(15f)
                        color(Color(0xFF222C35))
                        placeholder("输入 DeepSeek API Key")
                        placeholderColor(Color(0xFF98A1A9))
                        keyboardTypePassword()
                        returnKeyTypeDone()
                        autofocus(true)
                        editable(!busy())
                    }
                    event {
                        textDidChange { onApiKeyChange(it.text) }
                        inputReturn { if (!busy()) onSave() }
                    }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text {
                    attr {
                        text(error())
                        marginTop(8f)
                        fontSize(12f)
                        lineHeight(18f)
                        color(Color(0xFFBF3535))
                    }
                }
            }
            View {
                attr {
                    marginTop(24f)
                    height(40f)
                    flexDirectionRow()
                    justifyContentFlexEnd()
                }
                Button {
                    attr {
                        width(132f)
                        height(40f)
                        borderRadius(8f)
                        backgroundColor(Color(if (busy()) 0xFFB7C8FE else 0xFF4176E6))
                        titleAttr {
                            text(if (busy()) "保存中..." else "保存并继续")
                            fontSize(14f)
                            color(Color.WHITE)
                        }
                    }
                    event { click { if (!busy()) onSave() } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshSessionDrawer(
    sessions: () -> ObservableList<DshSession>,
    workspaceGroups: () -> ObservableList<DshWorkspaceGroup>,
    archivedSessions: () -> ObservableList<DshSession>,
    isWebTimeline: () -> Boolean,
    activeId: () -> String,
    animated: () -> Boolean,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit,
    onOpenArchived: () -> Unit,
    onManage: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionRow()
            backgroundColor(Color(0x00000000))
        }
        View {
            attr {
                width((pagerData.pageViewWidth - 44f).coerceAtMost(340f))
                height(pagerData.pageViewHeight)
                flexDirectionColumn()
                paddingTop(pagerData.statusBarHeight + 10f)
                paddingLeft(14f)
                paddingRight(14f)
                paddingBottom(18f)
                backgroundColor(Color(0xFFF9FAFB))
                transform(Translate(if (animated()) 0f else -1f, 0f))
                animation(Animation.easeOut(0.24f), animated())
            }
            View {
                attr {
                    height(48f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Image {
                    attr {
                        src(ImageUri.commonAssets("wordmark.svg"))
                        width(118f)
                        height(28f)
                    }
                }
                View { attr { flex(1f) } }
                View {
                    attr { size(38f, 38f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(22f, 22f) } }
                    event { click { onClose() } }
                }
            }
            View {
                attr {
                    height(42f)
                    marginTop(8f)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(12f)
                    paddingRight(12f)
                    borderRadius(9f)
                    backgroundColor(Color(0xFFF1F3F5))
                }
                Image { attr { src(ImageUri.commonAssets("plus.svg")); size(20f, 20f) } }
                Text {
                    attr {
                        text("新会话")
                        marginLeft(10f)
                        fontSize(14f)
                        fontWeightMedium()
                        color(Color(0xFF32373C))
                    }
                }
                event { click { onNewSession() } }
            }
            View {
                attr {
                    height(42f)
                    marginTop(8f)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(12f)
                    paddingRight(12f)
                    borderRadius(9f)
                    backgroundColor(Color(0x00000000))
                }
                Image { attr { src(ImageUri.commonAssets("sliders.svg")); size(20f, 20f) } }
                Text {
                    attr {
                        text("设置")
                        marginLeft(10f)
                        fontSize(14f)
                        fontWeightMedium()
                        color(Color(0xFF555D64))
                    }
                }
                event { click { onOpenSettings() } }
            }
            Text {
                attr {
                    text("会话")
                    marginTop(20f)
                    marginBottom(8f)
                    fontSize(12f)
                    color(Color(0xFF8B9298))
                }
            }
            vif({ isWebTimeline() }) {
                View {
                    attr {
                        height(40f)
                        marginBottom(8f)
                        flexDirectionRow()
                        alignItemsCenter()
                        paddingLeft(12f)
                        paddingRight(12f)
                        borderRadius(9f)
                        backgroundColor(tokens.surfaceVariant)
                    }
                    Text {
                        attr {
                            text("已归档会话")
                            flex(1f)
                            fontSize(13f)
                            color(tokens.secondaryText)
                        }
                    }
                    Text {
                        attr {
                            text("${archivedSessions().size}")
                            fontSize(12f)
                            color(tokens.tertiaryText)
                        }
                    }
                    event { click { onOpenArchived() } }
                }
            }
            vif({ !isWebTimeline() }) {
                Text {
                    attr {
                        text("重命名与归档仅在远程 Host 模式可用")
                        marginBottom(8f)
                        fontSize(11f)
                        color(tokens.tertiaryText)
                    }
                }
            }
            Scroller {
                attr { flex(1f) }
                vif({ !isWebTimeline() }) {
                    vfor({ visibleSessionList(sessions()) }) { session ->
                        DshSessionDrawerRow(
                            title = session.title,
                            subtitle = session.workspace,
                            active = activeId() == session.id,
                            running = session.running,
                            onSelect = { onSelect(session.id) },
                        )
                    }
                }
                vif({ isWebTimeline() }) {
                    vfor({ workspaceGroups() }) { group ->
                        View {
                            attr {
                                marginTop(10f)
                                marginBottom(6f)
                                flexDirectionColumn()
                            }
                            Text {
                                attr {
                                    text(group.title + if (group.path.isEmpty()) "" else " · ${group.path}")
                                    lines(1)
                                    fontSize(12f)
                                    fontWeightMedium()
                                    color(Color(0xFF7A838A))
                                }
                            }
                            group.sessions.forEach { session ->
                                DshSessionDrawerRow(
                                    title = session.title,
                                    subtitle = if (session.cwd.isEmpty()) group.title else session.cwd,
                                    active = activeId() == session.id,
                                    running = session.running,
                                    onManage = { onManage(session.id) },
                                    onSelect = { onSelect(session.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
        View {
            attr {
                flex(1f)
                height(pagerData.pageViewHeight)
            }
            event { click { onClose() } }
        }
    }
}

private fun ViewContainer<*, *>.DshSessionDrawerRow(
    title: String,
    subtitle: String,
    active: Boolean,
    running: Boolean,
    onManage: (() -> Unit)? = null,
    onSelect: () -> Unit,
) {
    View {
        attr {
            height(48f)
            marginBottom(4f)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(12f)
            paddingRight(10f)
            borderRadius(9f)
            backgroundColor(Color(if (active) 0xFFE3E6EA else 0x00FFFFFF))
        }
        View {
            attr {
                size(7f, 7f)
                borderRadius(4f)
                backgroundColor(Color(if (running) 0xFF4176E6 else 0xFFADB2B8))
            }
        }
        View {
            attr {
                flex(1f)
                marginLeft(10f)
                flexDirectionColumn()
                justifyContentCenter()
            }
            Text {
                attr {
                    text(title)
                    lines(1)
                    fontSize(14f)
                    color(Color(0xFF2B3136))
                }
            }
            Text {
                attr {
                    text(subtitle)
                    lines(1)
                    marginTop(2f)
                    fontSize(10f)
                    color(Color(0xFF969DA3))
                }
            }
            if (onManage != null) {
                event { click { onSelect() } }
            }
        }
        if (onManage == null) {
            event { click { onSelect() } }
        } else {
            Text {
                attr {
                    text("管理")
                    width(42f)
                    height(32f)
                    textAlignCenter()
                    fontSize(11f)
                    color(tokens.primary)
                }
                event { click { onManage() } }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshSessionManageModal(
    title: () -> String,
    archived: () -> Boolean,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            allCenter()
            paddingLeft(20f)
            paddingRight(20f)
            backgroundColor(tokens.scrim)
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(420f)
                padding(20f)
                borderRadius(16f)
                backgroundColor(tokens.surface)
            }
            Text {
                attr {
                    text(title())
                    fontSize(18f)
                    fontWeightBold()
                    color(tokens.primaryText)
                    lines(2)
                }
            }
            Text {
                attr {
                    text("重命名")
                    height(42f)
                    marginTop(18f)
                    textAlignCenter()
                    fontSize(14f)
                    color(tokens.primary)
                    backgroundColor(tokens.surfaceVariant)
                    borderRadius(8f)
                }
                event { click { onRename() } }
            }
            vif({ !archived() }) {
                Text {
                    attr {
                        text("归档")
                        height(42f)
                        marginTop(10f)
                        textAlignCenter()
                        fontSize(14f)
                        color(tokens.error.foreground)
                        backgroundColor(tokens.surfaceVariant)
                        borderRadius(8f)
                    }
                    event { click { onArchive() } }
                }
            }
            Text {
                attr {
                    text("取消")
                    height(40f)
                    marginTop(12f)
                    textAlignCenter()
                    fontSize(14f)
                    color(tokens.secondaryText)
                }
                event { click { onClose() } }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshSessionRenameModal(
    draft: () -> String,
    busy: () -> Boolean,
    error: () -> String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            allCenter()
            paddingLeft(20f)
            paddingRight(20f)
            backgroundColor(tokens.scrim)
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(420f)
                padding(20f)
                borderRadius(16f)
                backgroundColor(tokens.surface)
            }
            Text {
                attr {
                    text("重命名会话")
                    fontSize(18f)
                    fontWeightBold()
                    color(tokens.primaryText)
                }
            }
            Input {
                attr {
                    height(40f)
                    marginTop(14f)
                    fontSize(14f)
                    color(tokens.primaryText)
                    placeholder("输入会话名称")
                    placeholderColor(tokens.tertiaryText)
                    text(draft())
                    returnKeyTypeDone()
                }
                event {
                    textDidChange { onDraftChange(it.text) }
                    inputReturn { if (!busy()) onSave() }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text {
                    attr {
                        text(error())
                        marginTop(8f)
                        fontSize(12f)
                        lineHeight(18f)
                        color(tokens.error.foreground)
                    }
                }
            }
            View {
                attr {
                    height(40f)
                    marginTop(18f)
                    flexDirectionRow()
                    justifyContentFlexEnd()
                }
                Text {
                    attr {
                        text("取消")
                        width(78f)
                        height(38f)
                        textAlignCenter()
                        fontSize(14f)
                        color(tokens.secondaryText)
                    }
                    event { click { if (!busy()) onClose() } }
                }
                Text {
                    attr {
                        text(if (busy()) "保存中..." else "保存")
                        width(88f)
                        height(38f)
                        marginLeft(8f)
                        textAlignCenter()
                        fontSize(14f)
                        color(if (busy()) tokens.primaryDisabled else tokens.primary)
                    }
                    event { click { if (!busy()) onSave() } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshSessionArchiveModal(
    title: () -> String,
    busy: () -> Boolean,
    error: () -> String,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            allCenter()
            paddingLeft(20f)
            paddingRight(20f)
            backgroundColor(tokens.scrim)
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(420f)
                padding(20f)
                borderRadius(16f)
                backgroundColor(tokens.surface)
            }
            Text {
                attr {
                    text("归档“${title()}”？")
                    fontSize(18f)
                    fontWeightBold()
                    color(tokens.primaryText)
                    lines(2)
                }
            }
            Text {
                attr {
                    text("归档只会把此会话从主列表隐藏，不是永久删除。日志和工作区记账仍会保留，可在“已归档会话”中查看完整历史。")
                    marginTop(10f)
                    fontSize(13f)
                    lineHeight(20f)
                    color(tokens.secondaryText)
                }
            }
            vif({ error().isNotEmpty() }) {
                Text {
                    attr {
                        text(error())
                        marginTop(8f)
                        fontSize(12f)
                        lineHeight(18f)
                        color(tokens.error.foreground)
                    }
                }
            }
            View {
                attr {
                    height(40f)
                    marginTop(18f)
                    flexDirectionRow()
                    justifyContentFlexEnd()
                }
                Text {
                    attr {
                        text("取消")
                        width(78f)
                        height(38f)
                        textAlignCenter()
                        fontSize(14f)
                        color(tokens.secondaryText)
                    }
                    event { click { if (!busy()) onClose() } }
                }
                Text {
                    attr {
                        text(if (busy()) "归档中..." else "确认归档")
                        width(104f)
                        height(38f)
                        marginLeft(8f)
                        textAlignCenter()
                        fontSize(14f)
                        color(if (busy()) tokens.disabled.foreground else tokens.error.foreground)
                    }
                    event { click { if (!busy()) onConfirm() } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshArchivedSessionsModal(
    sessions: () -> ObservableList<DshSession>,
    activeId: () -> String,
    onSelect: (String) -> Unit,
    onManage: (String) -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            allCenter()
            paddingLeft(16f)
            paddingRight(16f)
            backgroundColor(tokens.scrim)
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 32f)
                maxWidth(520f)
                height((pagerData.pageViewHeight - 80f).coerceAtMost(620f))
                padding(18f)
                borderRadius(16f)
                backgroundColor(tokens.background)
            }
            View {
                attr {
                    height(44f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text("已归档会话")
                        flex(1f)
                        fontSize(18f)
                        fontWeightBold()
                        color(tokens.primaryText)
                    }
                }
                Text {
                    attr {
                        text("关闭")
                        width(52f)
                        height(36f)
                        textAlignCenter()
                        fontSize(13f)
                        color(tokens.primary)
                    }
                    event { click { onClose() } }
                }
            }
            Text {
                attr {
                    text("这些会话仅从主列表隐藏，历史记录仍完整保留。")
                    marginBottom(12f)
                    fontSize(12f)
                    color(tokens.secondaryText)
                }
            }
            vif({ sessions().isEmpty() }) {
                Text {
                    attr {
                        text("暂无已归档会话")
                        marginTop(24f)
                        textAlignCenter()
                        fontSize(14f)
                        color(tokens.tertiaryText)
                    }
                }
            }
            vif({ sessions().isNotEmpty() }) {
                List {
                    attr { flex(1f) }
                    vforLazy({ sessions() }) { session, _, _ ->
                        View {
                            attr { height(52f) }
                            DshSessionDrawerRow(
                                title = session.title,
                                subtitle = session.cwd.ifEmpty { "Host" },
                                active = activeId() == session.id,
                                running = session.running,
                                onManage = { onManage(session.id) },
                                onSelect = { onSelect(session.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshModelPicker(
    options: () -> ObservableList<DshModelOption>,
    busy: () -> Boolean,
    error: () -> String,
    onClose: () -> Unit,
    onSelect: (DshModelOption) -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionColumn()
            justifyContentFlexEnd()
            backgroundColor(Color(0x55000000))
        }
        View {
            attr { flex(1f) }
            event { click { onClose() } }
        }
        View {
            attr {
                height((pagerData.pageViewHeight * 0.62f).coerceAtMost(540f))
                flexDirectionColumn()
                padding(18f)
                borderRadius(20f)
                backgroundColor(Color.WHITE)
            }
            View {
                attr { height(40f); flexDirectionRow(); alignItemsCenter() }
                Text {
                    attr {
                        text("选择模型")
                        fontSize(18f)
                        fontWeightBold()
                        color(Color(0xFF252B30))
                    }
                }
                View { attr { flex(1f) } }
                View {
                    attr { size(36f, 36f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(21f, 21f) } }
                    event { click { onClose() } }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text {
                    attr {
                        text(error())
                        marginTop(6f)
                        marginBottom(6f)
                        fontSize(12f)
                        color(Color(0xFFBF3535))
                    }
                }
            }
            vif({ busy() && options().isEmpty() }) {
                Text {
                    attr {
                        text("正在加载模型...")
                        marginTop(24f)
                        fontSize(14f)
                        color(Color(0xFF7D858C))
                    }
                }
            }
            Scroller {
                attr { flex(1f); marginTop(8f) }
                vfor({ options() }) { option ->
                    View {
                        attr {
                            minHeight(58f)
                            marginBottom(6f)
                            flexDirectionRow()
                            alignItemsCenter()
                            padding(10f, 12f, 10f, 12f)
                            borderRadius(10f)
                            backgroundColor(Color(if (option.selected) 0xFFF0F3FA else 0xFFF8F8F9))
                        }
                        View {
                            attr { flex(1f); flexDirectionColumn() }
                            Text {
                                attr {
                                    text(option.name)
                                    fontSize(14f)
                                    fontWeightMedium()
                                    color(Color(0xFF2C3237))
                                }
                            }
                            Text {
                                attr {
                                    text(option.providerName + if (option.description.isEmpty()) "" else " · ${option.description}")
                                    marginTop(3f)
                                    lines(1)
                                    fontSize(11f)
                                    color(Color(0xFF8B939A))
                                }
                            }
                        }
                        if (option.selected) {
                            Text { attr { text("✓"); fontSize(17f); color(Color(0xFF4176E6)) } }
                        }
                        event { click { if (!busy()) onSelect(option) } }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshTopBar(
    title: () -> String,
    connection: () -> String,
) {
    View {
        attr {
            height(58f)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(12f)
            paddingRight(14f)
            backgroundColor(Color.WHITE)
            borderBottom(Border(1f, BorderStyle.SOLID, Color(0xFFEBEEF2)))
        }
        View {
            attr { size(38f, 38f); allCenter() }
            Image {
                attr {
                    src(ImageUri.commonAssets("menu.svg"))
                    size(26f, 26f)
                }
            }
        }
        Text {
            attr {
                text(title())
                marginLeft(10f)
                flex(1f)
                fontSize(17f)
                fontWeightMedium()
                color(Color(0xFF0F1115))
                lines(1)
            }
        }
        View {
            attr {
                val ready = isConnectionReadyLabel(connection())
                height(22f)
                marginLeft(8f)
                paddingLeft(8f)
                paddingRight(8f)
                borderRadius(11f)
                backgroundColor(Color(if (ready) 0xFFE8F7EE else 0xFFF3F5F7))
                justifyContentCenter()
                alignItemsCenter()
            }
            Text {
                attr {
                    val ready = isConnectionReadyLabel(connection())
                    text(if (ready) "已连接" else topBarConnectingText(connection()))
                    fontSize(11f)
                    lines(1)
                    color(Color(if (ready) 0xFF1F8A4C else 0xFF6B7785))
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshSessionRail(
    sessions: () -> ObservableList<DshSession>,
    activeId: () -> String,
    compact: Boolean,
    onSelect: (String) -> Unit,
) {
    View {
        attr {
            if (compact) {
                height(92f)
                flexDirectionRow()
            } else {
                width(236f)
                flexDirectionColumn()
            }
            backgroundColor(Color(0xFFF7F7F8))
            padding(14f)
        }
        Text {
            attr {
                text("会话")
                fontSize(13f)
                color(Color(0xFF6F7378))
                marginBottom(9f)
            }
        }
        if (compact) {
            Scroller {
                attr {
                    flex(1f)
                    flexDirectionRow()
                }
                vfor({ sessions() }) { session ->
                    DshSessionButton(session, activeId() == session.id, onSelect)
                }
            }
        } else {
            Scroller {
                attr { flex(1f) }
                vfor({ sessions() }) { session ->
                    DshSessionButton(session, activeId() == session.id, onSelect)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshSessionButton(
    session: DshSession,
    active: Boolean,
    onSelect: (String) -> Unit,
) {
    Button {
        attr {
            height(48f)
            width(if (active) 220f else 220f)
            marginBottom(4f)
            borderRadius(7f)
            backgroundColor(Color(if (active) 0xFFE4EDFD else 0x00000000))
            titleAttr {
                text(session.title)
                color(Color(if (active) 0xFF4176E6 else 0xFF3E4247))
                fontSize(13f)
            }
        }
        event { click { onSelect(session.id) } }
    }
}

private fun ViewContainer<*, *>.DshSessionDetailsPanel(
    title: () -> String,
    cwd: () -> String,
    modelLabel: () -> String,
    agentPreset: () -> String,
    running: () -> Boolean,
    queueCount: () -> Int,
    jobCount: () -> Int,
    archived: () -> Boolean,
    onRename: () -> Unit,
    onArchive: () -> Unit,
) {
    View {
        attr {
            width(280f)
            height(pagerData.pageViewHeight)
            flexDirectionColumn()
            padding(16f)
            backgroundColor(Color(0xFFF7F9FA))
            border(Border(1f, BorderStyle.SOLID, Color(0xFFE5E8EB)))
        }
        Text {
            attr {
                text("Session")
                fontSize(12f)
                color(Color(0xFF7A8790))
            }
        }
        Text {
            attr {
                text(title())
                marginTop(6f)
                fontSize(17f)
                fontWeightSemiBold()
                color(Color(0xFF1F2933))
                lines(2)
            }
        }
        View {
            attr {
                height(1f)
                marginTop(14f)
                backgroundColor(Color(0xFFE5E8EB))
            }
        }
        DshDetailRow("状态", if (running()) "运行中" else "空闲")
        DshDetailRow("模型", modelLabel())
        vif({ agentPreset().isNotEmpty() }) {
            DshDetailRow("Agent Preset", agentPreset())
        }
        DshDetailRow("队列", "${queueCount()} 条")
        DshDetailRow("后台任务", "${jobCount()} 个")
        vif({ cwd().isNotEmpty() }) {
            DshDetailRow("目录", cwd())
        }
        View { attr { flex(1f) } }
        Text {
            attr {
                text("重命名会话")
                height(40f)
                textAlignCenter()
                fontSize(13f)
                color(tokens.primary)
                backgroundColor(tokens.surfaceVariant)
                borderRadius(8f)
            }
            event { click { onRename() } }
        }
        vif({ !archived() }) {
            Text {
                attr {
                    text("归档会话")
                    height(40f)
                    marginTop(10f)
                    textAlignCenter()
                    fontSize(13f)
                    color(tokens.error.foreground)
                    backgroundColor(tokens.surfaceVariant)
                    borderRadius(8f)
                }
                event { click { onArchive() } }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshDetailRow(
    label: String,
    value: String,
) {
    View {
        attr {
            minHeight(44f)
            marginTop(10f)
            flexDirectionColumn()
            justifyContentCenter()
        }
        Text {
            attr {
                text(label)
                fontSize(11f)
                color(Color(0xFF8B9298))
            }
        }
        Text {
            attr {
                text(value)
                marginTop(2f)
                fontSize(13f)
                color(Color(0xFF343E47))
                lines(2)
            }
        }
    }
}

private fun ViewContainer<*, *>.DshWorkspaceBrowserModal(
    path: () -> String,
    home: () -> String,
    entries: () -> ObservableList<DshDirectoryEntry>,
    busy: () -> Boolean,
    error: () -> String,
    newName: () -> String,
    onDirectorySelect: (String) -> Unit,
    onNewNameChange: (String) -> Unit,
    onCreateDirectory: () -> Unit,
    onAdopt: () -> Unit,
    onClose: () -> Unit,
) {
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
                maxWidth(560f)
                maxHeight(pagerData.pageViewHeight - 80f)
                flexDirectionColumn()
                padding(18f)
                borderRadius(16f)
                backgroundColor(Color.WHITE)
            }
            View {
                attr { height(36f); flexDirectionRow(); alignItemsCenter() }
                Text {
                    attr {
                        text(if (path().isEmpty()) home() else path())
                        flex(1f)
                        lines(1)
                        fontSize(17f)
                        fontWeightBold()
                        color(Color(0xFF1F2933))
                    }
                }
                View { attr { size(32f, 32f); allCenter() }; Image { attr { src(ImageUri.commonAssets("x.svg")); size(20f, 20f) } }; DshHitButton { onClose() } }
            }
            Scroller {
                attr {
                    flex(1f)
                    marginTop(12f)
                    borderRadius(8f)
                    backgroundColor(Color(0xFFF7F9FA))
                }
                vfor({ entries() }) { entry ->
                    View {
                        attr {
                            height(42f)
                            flexDirectionRow()
                            alignItemsCenter()
                            paddingLeft(10f)
                            paddingRight(10f)
                        }
                        Text {
                            attr {
                                text(entry.name)
                                flex(1f)
                                lines(1)
                                fontSize(14f)
                                color(Color(0xFF343E47))
                            }
                        }
                        event { click { if (!busy()) onDirectorySelect(entry.path) } }
                    }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text { attr { text(error()); marginTop(8f); fontSize(12f); color(Color(0xFFBF3535)) } }
            }
            Input {
                attr {
                    height(38f)
                    marginTop(10f)
                    fontSize(14f)
                    placeholder("新目录名称")
                    placeholderColor(Color(0xFF98A1A9))
                }
                event { textDidChange { onNewNameChange(it.text) } }
            }
            View {
                attr { height(42f); marginTop(12f); flexDirectionRow(); justifyContentFlexEnd() }
                Text {
                    attr {
                        text(if (busy()) "处理中..." else "新建目录")
                        width(88f)
                        height(38f)
                        textAlignCenter()
                        fontSize(13f)
                        color(Color(0xFF7A838A))
                    }
                    event { click { if (!busy()) onCreateDirectory() } }
                }
                Text {
                    attr {
                        text(if (busy()) "处理中..." else "使用此目录")
                        width(112f)
                        height(38f)
                        marginLeft(8f)
                        textAlignCenter()
                        fontSize(13f)
                        color(Color(0xFF4176E6))
                    }
                    event { click { if (!busy()) onAdopt() } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshTurnStatus(
    visible: () -> Boolean,
    reconnecting: () -> Boolean,
    elapsedMs: () -> Long,
    shimmerOn: () -> Boolean,
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
                    color(Color(if (shimmerOn()) TURN_STATUS_HIGHLIGHT else TURN_STATUS_BLUE))
                    animation(Animation.linear(1.8f), shimmerOn())
                }
            }
            vif({ elapsedMs() >= TURN_STATUS_CLOCK_AFTER_MS }) {
                Text {
                    attr {
                        text(dshFormatTurnDuration(elapsedMs()))
                        fontSize(13f)
                        color(Color(0xFF8A9399))
                        marginLeft(8f)
                    }
                }
            }
        }
    }
}

private const val TURN_STATUS_BLUE = 0xFF4D6BFE
private const val TURN_STATUS_HIGHLIGHT = 0xFFC5D4FF
private const val TURN_STATUS_CLOCK_AFTER_MS = 15_000L

private fun ViewContainer<*, *>.DshConversation(
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
    modelLabel: () -> String,
    attachmentMenuVisible: () -> Boolean,
    voiceActive: () -> Boolean,
    onOpenModels: () -> Unit,
    onToggleAttachments: () -> Unit,
    onToggleVoice: () -> Unit,
    isWebTimeline: () -> Boolean,
    isDisclosureExpanded: (String) -> Boolean,
    onToggleDisclosure: (String) -> Unit,
    isBodyDisclosureExpanded: (String) -> Boolean,
    onToggleBodyDisclosure: (String) -> Unit,
    isJsonNodeExpanded: (String, String) -> Boolean,
    onToggleJsonNode: (String, String) -> Unit,
    onCopyToolContent: (String) -> Unit,
    attachmentDataUrl: (String) -> String?,
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
    turnReconnecting: () -> Boolean,
    turnElapsedMs: () -> Long,
    turnShimmerOn: () -> Boolean,
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
            backgroundColor(Color.WHITE)
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
                backgroundColor(Color.WHITE)
            }
            vfor({ conversationIds() }) { sessionId ->
                List {
                    ref { scrollerRef(sessionId, it) }
                    attr {
                        absolutePositionAllZero()
                        width(availableWidth)
                        padding(16f, 18f, 20f, 18f)
                        firstContentLoadMaxIndex(CHAT_INITIAL_RENDER_COUNT)
                        preloadViewDistance(pagerData.pageViewHeight)
                        // Keep cached conversation lists mounted so the first
                        // switch only changes opacity and z-order instead of
                        // creating a native ListView/Markdown tree on demand.
                        visibility(true)
                        opacity(if (activeConversationId() == sessionId) 1f else 0f)
                        touchEnable(activeConversationId() == sessionId)
                        zIndex(if (activeConversationId() == sessionId) 1 else 0)
                    }
                    event {
                        click { onDismissKeyboard() }
                        dragBegin { onDismissKeyboard() }
                        register("touchDown", { onDismissKeyboard() })
                    }
                    // Kuikly: vfor/vforLazy 的直接子节点必须是普通 View，不能是 vif/vfor。
                    vforLazy(
                        { messagesForSession(sessionId) },
                        maxLoadItem = CHAT_MAX_RENDERED_MESSAGES,
                    ) { message, _, _ ->
                        View {
                            ref { messageRef(sessionId, message.id, it) }
                            attr {
                                width((availableWidth - 36f).coerceAtLeast(0f))
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
                                attachmentDataUrl = { attachmentDataUrl(it) },
                                contentProvider = {
                                    if (streamingMessageId() != message.id) {
                                        message.content
                                    } else if (streaming() && activeConversationId() == sessionId) {
                                        streamingContent().ifEmpty { message.content }
                                    } else {
                                        message.content.ifEmpty { streamingContent() }
                                    }
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
                            shimmerOn = turnShimmerOn,
                        )
                    }
                }
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
                    height(COMPOSER_HEIGHT)
                    width(availableWidth)
                    flexDirectionColumn()
                    padding(12f, 14f, 12f, 14f)
                    backgroundColor(Color.WHITE)
                    borderRadius(22f)
                    border(Border(1f, BorderStyle.SOLID, Color(0xFFE1E5EE)))
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
                            backgroundColor(Color(0xFFF7F9FB))
                            borderRadius(8f)
                            border(Border(1f, BorderStyle.SOLID, Color(0xFFE1E7ED)))
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
                                        color(Color(0xFF2F6F4F))
                                    }
                                }
                                Text {
                                    attr {
                                        text(if (skill.modelInvocable) skill.description else "用户专用 · ${skill.description}")
                                        flex(1f)
                                        lines(1)
                                        fontSize(11f)
                                        color(Color(0xFF727D84))
                                    }
                                }
                            }
                        }
                    }
                }
            Input {
                ref { inputRef(it) }
                attr {
                    height(58f)
                    backgroundColor(Color(0x00FFFFFF))
                    fontSize(15f)
                    color(Color(0xFF28323C))
                    placeholder(if (voiceActive()) "正在聆听..." else "请输入您的问题...")
                    placeholderColor(Color(0xFF91A0AA))
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
                        backgroundColor(Color(0xFFF5F6F7))
                    }
                    View {
                        attr {
                            height(32f)
                            flexDirectionRow()
                            alignItemsCenter()
                            paddingLeft(8f)
                        }
                        Text { attr { text("图片"); fontSize(14f); color(Color(0xFF3B4147)) } }
                        View { attr { flex(1f) } }
                        Text { attr { text("PNG / JPG / WebP / GIF"); fontSize(11f); color(Color(0xFF9098A0)) } }
                    }
                    View {
                        attr {
                            height(32f)
                            flexDirectionRow()
                            alignItemsCenter()
                            paddingLeft(8f)
                        }
                        Text { attr { text("文件"); fontSize(14f); color(Color(0xFF3B4147)) } }
                        View { attr { flex(1f) } }
                        Text { attr { text("选择本地文件"); fontSize(11f); color(Color(0xFF9098A0)) } }
                    }
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
                        border(Border(1f, BorderStyle.SOLID, Color(0xFFCFD3D6)))
                    }
                    Text {
                        attr {
                            text(modelLabel())
                            flex(1f)
                            lines(1)
                            fontSize(14f)
                            color(Color(0xFF31363B))
                        }
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("chevron-down.svg"))
                            size(18f, 18f)
                        }
                    }
                    DshHitButton(onOpenModels)
                }
                View { attr { flex(1f) } }
                View {
                    attr { size(40f, 40f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("sliders.svg")); size(22f, 22f) } }
                }
                View {
                    attr {
                        size(48f, 48f)
                        marginLeft(6f)
                        borderRadius(24f)
                        allCenter()
                        backgroundColor(Color(
                            when {
                                stopButtonVisible() -> 0xFFE05252
                                voiceActive() -> 0xFF679EFE
                                else -> 0xFF4176E6
                            },
                        ))
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
                                src(ImageUri.commonAssets(if (draft().isEmpty()) "mic.svg" else "send.svg"))
                                size(23f, 23f)
                            }
                        }
                    }
                    DshHitButton {
                            when {
                                stopButtonVisible() -> onStop()
                                draft().isNotEmpty() -> onSend()
                                else -> onToggleVoice()
                            }
                    }
                }
            }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshMessageRow(
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
    attachmentDataUrl: (String) -> String? = { null },
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
        val dataUrl = attachmentDataUrl(message.attachmentId)
        View {
            attr {
                width((pagerData.pageViewWidth - 36f).coerceAtLeast(0f))
                height(220f)
                marginBottom(12f)
                borderRadius(8f)
                backgroundColor(Color(0xFFF6F8FA))
                border(Border(1f, BorderStyle.SOLID, Color(0xFFE4E8EC)))
                justifyContentCenter()
                alignItemsCenter()
            }
            if (dataUrl != null) {
                Image {
                    attr {
                        src(dataUrl)
                        width((pagerData.pageViewWidth - 40f).coerceAtLeast(0f))
                        height(216f)
                        resizeCover()
                    }
                }
            } else {
                Text {
                    attr {
                        text("图片加载中")
                        fontSize(12f)
                        color(Color(0xFF7A838A))
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
                color(Color(if (isError) 0xFFC23B3B else 0xFF84939D))
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
                backgroundColor(Color(
                    when {
                        isUser -> 0xFFEDF3FE
                        isError -> 0xFFFFEEEE
                        else -> 0x00FFFFFF
                    },
                ))
            }
            if (isUser || isError) {
                Text {
                    attr {
                        text(message.content)
                        lines(Int.MAX_VALUE)
                        fontSize(15f)
                        color(Color(if (isUser) 0xFF34415B else 0xFFB53232))
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
                            darkMode = false
                        }
                    }
                    vif({ pageStreaming() && (contentProvider?.invoke() ?: message.content).isNotEmpty() }) {
                        Text {
                            attr {
                                text(DshStreamingMarkdown.CURSOR)
                                fontSize(14f)
                                color(Color(0xFF4176E6))
                                marginTop(2f)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshHitButton(onClick: () -> Unit) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(Color(0x00000000))
        }
        event { click { onClick() } }
    }
}

private fun isConnectionReadyLabel(label: String): Boolean {
    return label.startsWith("已连接") ||
        label.endsWith("已连接") ||
        label.endsWith("已就绪") ||
        label == "连接成功"
}

private fun isReconnectLabel(label: String): Boolean {
    return label == "远程连接重建中" ||
        label == "扫码连接重建中" ||
        label == "扫码连接重试中" ||
        label == "本地 DSH 连接重建中"
}

private fun topBarConnectingText(label: String): String {
    val value = label.trim()
    if (value.isEmpty()) return "连接中"
    return value
}

private const val COMPOSER_HEIGHT = 142f
private const val CHAT_INITIAL_RENDER_COUNT = 48
private const val CHAT_MAX_RENDERED_MESSAGES = 128
private const val SESSION_CACHE_WARM_LIMIT = 7
private const val SESSION_CACHE_WARM_INTERVAL_MS = 16
private const val SESSION_CACHE_WARM_START_DELAY_MS = 600
private const val CONVERSATION_PANEL_CACHE_LIMIT = 8
private const val SCROLL_SETTLE_ATTEMPTS = 6
private val SCROLL_SETTLE_DELAYS_MS = intArrayOf(0, 16, 32, 64, 120, 200)
