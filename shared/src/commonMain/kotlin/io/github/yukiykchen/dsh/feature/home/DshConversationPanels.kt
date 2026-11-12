package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vforLazy
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.KeyboardParams
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.ScrollParams
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextArea
import com.tencent.kuikly.core.views.TextAreaView
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.ui.DshHitButton
import io.github.yukiykchen.dsh.ui.DshTheme
import io.github.yukiykchen.dsh.ui.CHAT_INITIAL_RENDER_COUNT
import io.github.yukiykchen.dsh.ui.CHAT_MAX_RENDERED_MESSAGES
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.dshDisplayedAssistantContent
import io.github.yukiykchen.dsh.model.DshSkill
import io.github.yukiykchen.dsh.ui.visibleSkillList

fun ViewContainer<*, *>.DshConversation(
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
    inputRef: (com.tencent.kuikly.core.base.ViewRef<TextAreaView>) -> Unit,
    onInputFocusChange: (Boolean) -> Unit,
    onDraftChange: (String) -> Unit,
    onKeyboardHeightChange: (KeyboardParams) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onDismissKeyboard: () -> Unit,
    onUserListScroll: (ScrollParams) -> Unit,
    modelLabel: () -> String,
    attachmentMenuVisible: () -> Boolean,
    onOpenModels: () -> Unit,
    onToggleAttachments: () -> Unit,
    isDisclosureExpanded: (String) -> Boolean,
    onToggleDisclosure: (String) -> Unit,
    isBodyDisclosureExpanded: (String) -> Boolean,
    onToggleBodyDisclosure: (String) -> Unit,
    isJsonNodeExpanded: (String, String) -> Boolean,
    onToggleJsonNode: (String, String) -> Unit,
    onCopyToolContent: (String) -> Unit,
    attachmentDataUrl: (String, String) -> String?,
    queue: DshQueueController,
    jobs: DshJobsController,
    goal: DshGoalController,
    interactions: DshInteractionController,
    sessionRunning: () -> Boolean,
    isBlankConversation: () -> Boolean,
    conversationListEpoch: (String) -> Int,
    turnReconnecting: () -> Boolean,
    turnElapsedMs: () -> Long,
    availableWidth: Float,
) {
    val skillCandidates = ObservableList<DshSkill>()
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
                                        }
                                        DshMessageRow(
                                            message,
                                            pageStreaming = {
                                                streaming() &&
                                                    activeConversationId() == sessionId &&
                                                    streamingMessageId() == message.id
                                            },
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
                                            attachmentDataUrl = { attachmentDataUrl(sessionId, it) },
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
        DshSessionDocks(
            queueCtl = queue,
            jobsCtl = jobs,
            goalCtl = goal,
            interactionCtl = interactions,
            activeConversationId = activeConversationId,
            sessionRunning = sessionRunning,
        )
            View {
                attr {
                    width(availableWidth)
                    flexDirectionColumn()
                    padding(12f, 14f, 12f, 14f)
                    backgroundColor(Color.WHITE)
                    borderRadius(22f)
                    border(Border(1f, BorderStyle.SOLID, DshTheme.inputBorder))
                }
                vif({
                    draft().startsWith("/") &&
                        visibleSkillList(skills(), draft().removePrefix("/"), skillCandidates).isNotEmpty()
                }) {
                    View {
                        attr {
                            maxHeight(132f)
                            marginBottom(6f)
                            flexDirectionColumn()
                            backgroundColor(DshTheme.inputSurface)
                            borderRadius(8f)
                            border(Border(1f, BorderStyle.SOLID, DshTheme.divider))
                        }
                        vfor({ visibleSkillList(skills(), draft().removePrefix("/"), skillCandidates) }) { skill ->
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
                                        color(DshTheme.success)
                                    }
                                }
                                Text {
                                    attr {
                                        text(if (skill.modelInvocable) skill.description else "用户专用 · ${skill.description}")
                                        flex(1f)
                                        lines(1)
                                        fontSize(11f)
                                        color(DshTheme.neutral)
                                    }
                                }
                            }
                        }
                    }
                }
            TextArea {
                ref { inputRef(it) }
                attr {
                    minHeight(58f)
                    maxHeight(120f)
                    text(draft())
                    backgroundColor(DshTheme.transparent)
                    fontSize(15f)
                    color(DshTheme.inputText)
                    placeholder(
                        when {
                            isBlankConversation() &&
                                messagesForSession(activeConversationId()).isEmpty() -> "描述你想要构建的内容"
                            else -> "请输入您的问题..."
                        },
                    )
                    placeholderColor(DshTheme.inputPlaceholder)
                    editable(true)
                }
                event {
                    inputFocus { onInputFocusChange(true) }
                    textDidChange { onDraftChange(it.text) }
                    keyboardHeightChange { onKeyboardHeightChange(it) }
                    inputBlur {
                        onInputFocusChange(false)
                        onKeyboardHeightChange(KeyboardParams(0f, 0.24f))
                    }
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
                        backgroundColor(DshTheme.attachmentSurface)
                    }
                    View {
                        attr {
                            height(32f)
                            flexDirectionRow()
                            alignItemsCenter()
                            paddingLeft(8f)
                        }
                        Text { attr { text("图片"); fontSize(14f); color(DshTheme.attachmentText) } }
                        View { attr { flex(1f) } }
                        Text { attr { text("PNG / JPG / WebP / GIF"); fontSize(11f); color(DshTheme.attachmentDetail) } }
                    }
                    View {
                        attr {
                            height(32f)
                            flexDirectionRow()
                            alignItemsCenter()
                            paddingLeft(8f)
                        }
                        Text { attr { text("文件"); fontSize(14f); color(DshTheme.attachmentText) } }
                        View { attr { flex(1f) } }
                        Text { attr { text("选择本地文件"); fontSize(11f); color(DshTheme.attachmentDetail) } }
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
                        border(Border(1f, BorderStyle.SOLID, DshTheme.attachmentBorder))
                    }
                    Text {
                        attr {
                            text(modelLabel())
                            flex(1f)
                            lines(1)
                            fontSize(14f)
                            color(DshTheme.promptText)
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
                                src(ImageUri.commonAssets("send.svg"))
                                size(23f, 23f)
                            }
                        }
                    }
                    DshHitButton {
                            when {
                                stopButtonVisible() -> onStop()
                                draft().isNotEmpty() -> onSend()
                                else -> onToggleAttachments()
                            }
                    }
                }
            }
            }
        }
    }
}

/**
 * Panels stacked between the message list and the composer: prompt queue,
 * background jobs, goal, and whatever approval / question the Agent waits on.
 */
