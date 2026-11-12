package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.framework.setTimeout
import io.github.yukiykchen.dsh.ui.DshTheme
import io.github.yukiykchen.dsh.framework.dshPerfLog
import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.model.DshWorkspaceGroup

/** Conversation area, drawer mask, and session drawer for the home page. */
fun ViewContainer<*, *>.DshHomeContent(
    backgroundColor: Long,
    animationDurationSeconds: Float,
    wide: Boolean,
    pageWidth: Float,
    pagerId: String,
    panelCount: () -> Int,
    sessions: () -> ObservableList<DshSession>,
    visibleSessions: () -> ObservableList<DshSession>,
    activeSessionId: () -> String,
    sessionDrawerVisible: () -> Boolean,
    sessionDrawerAnimated: () -> Boolean,
    sessionDrawerMaskAnimated: () -> Boolean,
    sessionDrawerMaskAnimation: () -> Animation,
    workspaceGroups: () -> ObservableList<DshWorkspaceGroup>,
    modelLabel: () -> String,
    sessionRunning: () -> Boolean,
    queueCount: () -> Int,
    jobCount: () -> Int,
    homeConversation: ViewContainer<*, *>.(Float) -> Unit,
    closeSessionDrawer: () -> Unit,
    selectSession: (String) -> Unit,
    openSettingsPage: () -> Unit,
    createSession: () -> Unit,
) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            transform(Translate(
                0f,
                offsetX = if (sessionDrawerAnimated()) {
                    (pageWidth - 44f).coerceAtMost(340f)
                } else {
                    0f
                },
            ))
            animation(Animation.easeOut(animationDurationSeconds), sessionDrawerAnimated())
        }
        if (wide) {
            dshPerfLog("body.conversation.begin wide=true panels=${panelCount()}")
            View {
                attr {
                    flex(1f)
                    flexDirectionRow()
                    backgroundColor(Color(backgroundColor))
                }
                DshSessionRail(
                    sessions = visibleSessions,
                    activeId = activeSessionId,
                    compact = false,
                    onSelect = { id ->
                        closeSessionDrawer()
                        setTimeout(pagerId, 0) { selectSession(id) }
                    },
                )
                homeConversation((pageWidth - 236f - 280f).coerceAtLeast(360f))
                DshSessionDetailsPanel(
                    title = { sessions().firstOrNull { it.id == activeSessionId() }?.title ?: "尚无标题" },
                    cwd = { sessions().firstOrNull { it.id == activeSessionId() }?.cwd ?: "" },
                    modelLabel = modelLabel,
                    agentPreset = { sessions().firstOrNull { it.id == activeSessionId() }?.agentPreset.orEmpty() },
                    running = sessionRunning,
                    queueCount = queueCount,
                    jobCount = jobCount,
                )
            }
            dshPerfLog("body.conversation.end wide=true")
        } else {
            dshPerfLog("body.conversation.begin wide=false panels=${panelCount()}")
            homeConversation(pageWidth)
            dshPerfLog("body.conversation.end wide=false")
        }

        vif(sessionDrawerVisible) {
            View {
                attr {
                    absolutePositionAllZero()
                    backgroundColor(DshTheme.drawerScrim)
                    opacity(if (sessionDrawerMaskAnimated()) 1f else 0f)
                    animation(sessionDrawerMaskAnimation(), sessionDrawerMaskAnimated())
                }
                event { click { closeSessionDrawer() } }
            }
        }
    }

    vif(sessionDrawerVisible) {
        DshSessionDrawer(
            workspaceGroups = workspaceGroups,
            activeId = activeSessionId,
            animated = sessionDrawerAnimated,
            onClose = closeSessionDrawer,
            onOpenSettings = openSettingsPage,
            onNewSession = createSession,
            onSelect = { id ->
                closeSessionDrawer()
                setTimeout(pagerId, 0) { selectSession(id) }
            },
        )
    }
}
