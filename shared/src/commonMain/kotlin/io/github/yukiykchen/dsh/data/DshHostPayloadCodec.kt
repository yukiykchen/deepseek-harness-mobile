package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.model.DshDirectoryEntry
import io.github.yukiykchen.dsh.model.DshDirectoryListing

fun parseSessionsPayload(value: JSONObject): List<DshSession> {
    val items = value.optJSONArray("items") ?: JSONArray()
    return buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("sessionId")
            if (id.isEmpty()) continue
            val projections = item.optJSONObject("projections")?.optJSONObject("values")
            add(DshSession(
                id = id,
                title = projections?.optString("title")?.takeIf { it.isNotEmpty() } ?: "尚无标题",
                workspace = "Host",
                updatedLabel = item.optLong("updatedAt").takeIf { it > 0 }?.toString().orEmpty(),
                running = item.optBoolean("running"), blank = item.optBoolean("blank"), cwd = item.optString("cwd"),
                parentSessionId = item.optString("parentSessionId").takeIf { it.isNotEmpty() },
                origin = item.optString("origin").takeIf { it.isNotEmpty() },
                agentPreset = item.optString("agentPreset").takeIf { it.isNotEmpty() },
            ))
        }
    }
}

fun parseDirectoryListingPayload(value: JSONObject): DshDirectoryListing {
    fun entries(array: JSONArray?): List<DshDirectoryEntry> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            add(DshDirectoryEntry(
                name = entry.optString("name"),
                path = entry.optString("path"),
                hidden = entry.optBoolean("hidden"),
            ))
        }
    }
    return DshDirectoryListing(
        path = value.optString("path"),
        home = value.optString("home"),
        crumbs = entries(value.optJSONArray("crumbs")),
        entries = entries(value.optJSONArray("entries")),
        truncated = value.optBoolean("truncated"),
    )
}

fun pendingInteractionRpcId(envelope: JSONObject, payload: JSONObject): String {
    val nested = payload.optJSONObject("payload")
    return listOf(
        envelope.optString("rpcId"),
        payload.optString("rpcId"),
        nested?.optString("rpcId").orEmpty(),
    ).firstOrNull { it.isNotEmpty() }.orEmpty()
}

fun dshEncodeQueryComponent(value: String): String {
    val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"
    return buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (char in allowed) append(char)
            else append('%').append(unsigned.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
