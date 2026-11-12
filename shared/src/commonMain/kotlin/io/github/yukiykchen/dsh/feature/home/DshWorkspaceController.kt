package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
import io.github.yukiykchen.dsh.model.DshRpcError
import io.github.yukiykchen.dsh.model.DshWorkspaceGroup
import io.github.yukiykchen.dsh.model.DshDirectoryEntry

/**
 * Workspace groups shown in the session drawer, plus the remote directory
 * browser and the rename / delete / reorder dialogs.
 *
 * Note: [openBrowser], [openRename], [openDelete] and [move] currently have no
 * entry point in the UI; the dialogs are mounted but nothing opens them.
 *
 * @param onBrowserOpening closes whatever overlay the browser replaces.
 * @param onWorkspaceAdopted reloads the session list after a directory became a workspace.
 */

class DshWorkspaceController(
    private val ctx: DshHomeContext,
    private val onBrowserOpening: () -> Unit,
    private val onWorkspaceAdopted: () -> Unit,
    private val postToPage: (() -> Unit) -> Unit = { setTimeout(ctx.pagerId, 0, it) },
) : PagerScope by ctx {
    val groups by observableList<DshWorkspaceGroup>()
    var browserVisible by observable(false)
        private set
    var browserPath by observable("")
        private set
    var browserHome by observable("")
        private set
    var browserBusy by observable(false)
        private set
    var browserError by observable("")
        private set
    var browserNewName by observable("")
    val directoryEntries by observableList<DshDirectoryEntry>()
    var renameTargetId by observable("")
        private set
    var renameDraft by observable("")
    var deleteTargetId by observable("")
        private set
    var actionBusy by observable(false)
        private set
    var actionError by observable("")
        private set
    private val browserRequests = DshControllerRequestScope(ctx)
    private val actionRequests = DshControllerRequestScope(ctx)

    fun refreshGroups() {
        if (actionRequests.closed) return
        val next = ctx.hostClient?.workspaceGroups().orEmpty()
        if (groups.toList() != next) {
            groups.clear()
            groups.addAll(next)
        }
    }

    /** Close the top-most workspace overlay. Returns false when none is open. */
    fun handleBack(): Boolean = when {
        deleteTargetId.isNotEmpty() -> { closeDelete(); true }
        renameTargetId.isNotEmpty() -> { closeRename(); true }
        browserVisible -> { closeBrowser(); true }
        else -> false
    }
    fun openBrowser() {
        if (browserRequests.closed) return
        browserRequests.invalidate()
        onBrowserOpening()
        browserVisible = true
        browserError = ""
        browserNewName = ""
        loadDirectory(null)
    }

    fun closeBrowser() {
        browserRequests.invalidate()
        browserVisible = false
        browserBusy = false
    }

    fun loadDirectory(path: String?) {
        if (browserRequests.closed) return
        val request = browserRequests.begin() ?: run {
            browserBusy = false
            browserError = "远程 DSH 尚未就绪"
            return
        }
        browserBusy = true
        browserError = ""
        request.client.listDirectory(path) { listing, error ->
            postToPage {
                if (!browserRequests.owns(request)) return@postToPage
                browserBusy = false
                if (!browserRequests.accepts(request)) return@postToPage
                if (error != null || listing == null) {
                    browserError = error?.message ?: "无法读取目录"
                    return@postToPage
                }
                browserPath = listing.path
                browserHome = listing.home
                directoryEntries.clear()
                directoryEntries.addAll(listing.entries.filterNot { it.hidden })
            }
        }
    }
    fun createDirectory() {
        if (browserBusy || browserRequests.closed) return
        val name = browserNewName.trim()
        if (browserPath.isEmpty() || name.isEmpty()) return
        val request = browserRequests.begin() ?: return
        browserBusy = true
        request.client.createDirectory(browserPath, name) { createdPath, error ->
            postToPage {
                if (!browserRequests.owns(request)) return@postToPage
                browserBusy = false
                if (!browserRequests.accepts(request)) return@postToPage
                if (error != null || createdPath == null) {
                    browserError = error?.message ?: "无法创建目录"
                    return@postToPage
                }
                browserNewName = ""
                loadDirectory(createdPath)
            }
        }
    }
    fun adoptCurrentDirectory() {
        if (browserBusy || browserRequests.closed || browserPath.isEmpty()) return
        val request = browserRequests.begin() ?: return
        browserBusy = true
        request.client.createWorkspace(browserPath) { _, error ->
            postToPage {
                if (!browserRequests.owns(request)) return@postToPage
                browserBusy = false
                if (!browserRequests.accepts(request)) return@postToPage
                if (error != null) {
                    browserError = error.message
                    return@postToPage
                }
                closeBrowser()
                onWorkspaceAdopted()
            }
        }
    }
    fun openRename(workspaceId: String, currentTitle: String) {
        if (actionRequests.closed) return
        actionRequests.invalidate()
        actionBusy = false
        deleteTargetId = ""
        renameTargetId = workspaceId
        renameDraft = currentTitle
        actionError = ""
    }
    fun closeRename() {
        actionRequests.invalidate()
        actionBusy = false
        renameTargetId = ""
        actionError = ""
    }
    fun saveRename() {
        val client = ctx.hostClient ?: return
        val workspaceId = renameTargetId
        val title = renameDraft.trim()
        if (workspaceId.isEmpty() || title.isEmpty()) return
        runAction({ client.renameWorkspace(workspaceId, title, it) }) {
            renameTargetId = ""
            renameDraft = ""
        }
    }
    fun openDelete(workspaceId: String) {
        if (actionRequests.closed) return
        actionRequests.invalidate()
        actionBusy = false
        renameTargetId = ""
        deleteTargetId = workspaceId
        actionError = ""
    }
    fun closeDelete() {
        actionRequests.invalidate()
        actionBusy = false
        deleteTargetId = ""
        actionError = ""
    }
    fun confirmDelete() {
        val client = ctx.hostClient ?: return
        val workspaceId = deleteTargetId
        if (workspaceId.isEmpty()) return
        runAction({ client.deleteWorkspace(workspaceId, it) }) {
            deleteTargetId = ""
        }
    }
    fun move(workspaceId: String, delta: Int) {
        val client = ctx.hostClient ?: return
        val ordered = groups.filter { it.workspaceId.isNotEmpty() }
        val index = ordered.indexOfFirst { it.workspaceId == workspaceId }
        if (index < 0) return
        val targetIndex = index + delta
        if (targetIndex < 0 || targetIndex >= ordered.size) return
        val beforeIndex = if (delta > 0) targetIndex + 1 else targetIndex
        val beforeWorkspaceId = ordered.getOrNull(beforeIndex)?.workspaceId
        runAction({ client.moveWorkspaceBefore(workspaceId, beforeWorkspaceId, it) }) {}
    }

    /** Busy/error bookkeeping shared by rename, delete and move; refreshes groups on success. */
    private fun runAction(
        call: ((JSONObject?, DshRpcError?) -> Unit) -> Unit,
        onSuccess: () -> Unit,
    ) {
        if (actionBusy || actionRequests.closed) return
        val request = actionRequests.begin() ?: return
        actionBusy = true
        actionError = ""
        call { _, error ->
            postToPage {
                if (!actionRequests.owns(request)) return@postToPage
                actionBusy = false
                if (!actionRequests.accepts(request)) return@postToPage
                if (error != null) {
                    actionError = error.message
                    return@postToPage
                }
                onSuccess()
                refreshGroups()
            }
        }
    }

    fun dispose() {
        browserRequests.close()
        actionRequests.close()
        browserVisible = false
        browserBusy = false
        actionBusy = false
        renameTargetId = ""
        deleteTargetId = ""
        directoryEntries.clear()
        groups.clear()
    }
}
