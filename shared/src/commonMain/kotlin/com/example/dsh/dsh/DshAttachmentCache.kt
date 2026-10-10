package com.example.dsh.dsh

import com.tencent.kuikly.core.reactive.handler.observable

/**
 * Downloaded image attachments as data URLs, shared by every session.
 *
 * Each attachment is fetched at most once at a time; a failed read can be
 * retried by the next [load]. [dataUrl] reads a reactive revision so image
 * rows re-render when a download lands.
 */
internal class DshAttachmentCache(
    private val ctx: DshHomeContext,
    /** Called with the session the attachment was requested for, after it is cached. */
    private val onLoaded: (sessionId: String) -> Unit,
) : DshHomeContext by ctx {
    private var revision by observable(0)
    private val dataUrls = mutableMapOf<String, String>()
    private val pending = mutableSetOf<String>()

    fun dataUrl(attachmentId: String): String? {
        revision
        return dataUrls[attachmentId]
    }

    fun load(sessionId: String, attachmentId: String) {
        if (dataUrl(attachmentId) != null || !pending.add(attachmentId)) return
        val client = hostClient ?: return
        client.loadAttachment(sessionId, attachmentId) { dataUrl, error ->
            if (error != null || dataUrl == null) {
                pending.remove(attachmentId)
                return@loadAttachment
            }
            dataUrls[attachmentId] = dataUrl
            revision += 1
            onLoaded(sessionId)
        }
    }
}
