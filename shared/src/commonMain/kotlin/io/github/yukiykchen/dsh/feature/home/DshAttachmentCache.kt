package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.base.PagerScope
import io.github.yukiykchen.dsh.data.DshHostClient

/**
 * Downloaded image attachments, scoped to their Host client and session.
 *
 * Each attachment is fetched at most once at a time; a failed read can be
 * retried by the next [load]. [dataUrl] reads a reactive revision so image
 * rows re-render when a download lands.
 */

class DshAttachmentCache(
    private val ctx: DshHomeContext,
    /** Called with the session the attachment was requested for, after it is cached. */
    private val onLoaded: (sessionId: String) -> Unit,
    private val fetch: (DshHostClient, String, String, (String?, String?) -> Unit) -> Unit = { client, session, attachment, callback ->
        client.loadAttachment(session, attachment, callback)
    },
) : PagerScope by ctx {
    private data class Key(val sessionId: String, val attachmentId: String)
    private var revision by observable(0)
    private var cacheClient: DshHostClient? = null
    private val dataUrls = mutableMapOf<Key, String>()
    private val pending = mutableMapOf<Key, Any>()
    private var closed = false

    private fun currentClient(): DshHostClient? {
        val client = ctx.hostClient
        if (cacheClient !== client) {
            cacheClient = client
            dataUrls.clear()
            pending.clear()
            revision++
        }
        return client
    }

    fun dataUrl(sessionId: String, attachmentId: String): String? {
        revision
        if (closed || currentClient() == null) return null
        return dataUrls[Key(sessionId, attachmentId)]
    }

    fun load(sessionId: String, attachmentId: String) {
        if (closed || attachmentId.isEmpty()) return
        val client = currentClient() ?: return
        val key = Key(sessionId, attachmentId)
        if (dataUrls.containsKey(key) || pending.containsKey(key)) return
        val request = Any()
        pending[key] = request
        fetch(client, sessionId, attachmentId) { dataUrl, error ->
            if (closed || ctx.hostClient !== client || pending[key] !== request) return@fetch
            pending.remove(key)
            if (error != null || dataUrl.isNullOrEmpty()) return@fetch
            dataUrls[key] = dataUrl
            revision += 1
            onLoaded(sessionId)
        }
    }

    fun close() {
        closed = true
        dataUrls.clear()
        pending.clear()
        cacheClient = null
        revision++
    }

    fun forget(sessionId: String) {
        dataUrls.keys.filter { it.sessionId == sessionId }.forEach(dataUrls::remove)
        pending.keys.filter { it.sessionId == sessionId }.forEach(pending::remove)
    }
}
