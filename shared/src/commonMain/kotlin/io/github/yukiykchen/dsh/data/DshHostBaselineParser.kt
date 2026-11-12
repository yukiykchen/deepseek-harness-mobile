package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.model.DshHostStore
import io.github.yukiykchen.dsh.model.DshSession

internal fun parseWorkspaceBaseline(value: JSONObject, store: DshHostStore) {
    val archived = value.optJSONArray("archivedSessionIds")
    val archivedIds = buildSet {
        if (archived != null) for (index in 0 until archived.length()) {
            archived.optString(index)?.takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
    store.replaceWorkspaceBaseline(value.optJSONArray("items")?.toString() ?: "[]", archivedIds)
}

internal fun parseSessionBaseline(value: JSONObject, store: DshHostStore): List<DshSession> {
    val sessions = parseSessionsPayload(value)
    val items = value.optJSONArray("items") ?: JSONArray()
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val values = item.optJSONObject("projections")?.optJSONObject("values") ?: continue
        val keys = values.keys() ?: continue
        while (keys.hasNext()) {
            val key = keys.next()
            store.applyProjection(item.optString("sessionId"), key, values.optString(key), -1)
        }
    }
    store.replaceSessions(sessions)
    return sessions
}
