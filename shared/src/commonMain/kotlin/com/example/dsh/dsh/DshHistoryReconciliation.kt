package com.example.dsh.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Preserve events accepted while a history RPC was in flight without replaying its prefix. */
internal fun dshReconcileHistory(
    history: JSONArray,
    live: List<DshRawSessionEvent>,
    requestStartSeq: Int,
): JSONArray {
    val entries = buildList {
        for (index in 0 until history.length()) history.optJSONObject(index)?.let(::add)
    }
    val watermark = entries.maxOfOrNull { dshWireEvent(it).optInt("seq", -1) }
        ?.takeIf { it >= 0 } ?: requestStartSeq
    val tail = live.filter { it.seq > watermark }
        .distinctBy { it.seq }
        .sortedBy { it.seq }
        .mapNotNull { runCatching { JSONObject(it.raw) }.getOrNull() }
    return JSONArray().apply { (entries + tail).forEach { put(it) } }
}
