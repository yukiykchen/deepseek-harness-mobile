package com.example.dsh.dsh

import com.tencent.kuikly.core.reactive.handler.observable

/** Bounded attachment data scoped to the Host and session that owns it. */
internal class DshAttachmentCache(
    private val ctx: DshHomeContext,
    private val onLoaded: (sessionId: String) -> Unit,
) {
    private data class Key(val scope: String, val sessionId: String, val attachmentId: String)
    private var revision by observable(0)
    private val dataUrls = linkedMapOf<Key, String>()
    private val pending = mutableMapOf<Key, Long>()
    private var requestSequence = 0L

    fun dataUrl(sessionId: String, attachmentId: String): String? {
        revision
        val key = Key(ctx.connectionScopeId, sessionId, attachmentId)
        val cached = dataUrls.remove(key) ?: return null
        dataUrls[key] = cached
        return cached
    }

    fun load(sessionId: String, attachmentId: String) {
        val client = ctx.hostClient ?: return
        val key = Key(ctx.connectionScopeId, sessionId, attachmentId)
        if (dataUrls.containsKey(key) || pending.containsKey(key)) return
        val request = ++requestSequence
        val generation = client.currentConnectionState().generation
        pending[key] = request
        client.loadAttachment(sessionId, attachmentId) { dataUrl, error ->
            if (pending[key] != request) return@loadAttachment
            pending.remove(key)
            if (ctx.hostClient !== client || ctx.connectionScopeId != key.scope ||
                client.currentConnectionState().generation != generation) return@loadAttachment
            if (error != null || dataUrl == null) return@loadAttachment
            dataUrls[key] = dataUrl
            // An oversized single image may stay visible; evict other images first.
            while (dataUrls.size > MAX_IMAGES || (dataUrls.size > 1 && dataUrls.values.sumOf { it.length.toLong() } > MAX_DATA_URL_CHARS)) {
                dataUrls.remove(dataUrls.keys.first())
            }
            revision++
            onLoaded(sessionId)
        }
    }

    fun clear() {
        pending.clear()
        dataUrls.clear()
        revision++
    }

    private companion object {
        const val MAX_IMAGES = 32
        const val MAX_DATA_URL_CHARS = 12_000_000L
    }
}
