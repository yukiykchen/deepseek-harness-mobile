package io.github.yukiykchen.dsh.feature.home

import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.model.DshMessageRole
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.data.DshHostClient

/** Creates the first Host session when the user sends into an empty cache. */
fun bootstrapHomeSession(
    hostClient: DshHostClient,
    isCurrent: () -> Boolean,
    onFinished: () -> Unit,
    addSession: (DshSession) -> Unit,
    refreshVisibleSessions: () -> Unit,
    persistSessions: (List<DshSession>) -> Unit,
    currentSessions: () -> List<DshSession>,
    setActiveSessionId: (String) -> Unit,
    loadModels: (String) -> Unit,
    retrySend: () -> Unit,
    setConnectionLabel: (String) -> Unit,
    addMessage: (DshMessage) -> Unit,
    messageCount: () -> Int,
) {
    setConnectionLabel("正在创建会话")
    hostClient.createSession(null, { sessionId ->
        onFinished()
        if (!isCurrent()) return@createSession
        addSession(DshSession(sessionId, "新会话", "Host", "", blank = true))
        refreshVisibleSessions()
        persistSessions(currentSessions())
        setActiveSessionId(sessionId)
        loadModels(sessionId)
        retrySend()
    }, { error ->
        onFinished()
        if (!isCurrent()) return@createSession
        setConnectionLabel("会话创建失败")
        addMessage(DshMessage(
            "send-session-error-${messageCount()}",
            DshMessageRole.ERROR,
            "无法创建会话：$error",
        ))
    })
}
