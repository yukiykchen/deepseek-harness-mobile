package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout

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
internal class DshWorkspaceController(
    private val ctx: DshHomeContext,
    private val onBrowserOpening: () -> Unit,
    private val onWorkspaceAdopted: () -> Unit,
) : PagerScope by ctx {
    val groups by observableList<DshWorkspaceGroup>()

    var browserVisible by observable(false)
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

    fun refreshGroups() {
        val client = ctx.hostClient ?: return
        val next = client.workspaceGroups()
        groups.clear()
        groups.addAll(next)
    }

    /** Close the top-most workspace overlay. Returns false when none is open. */
    fun handleBack(): Boolean = when {
        deleteTargetId.isNotEmpty() -> { closeDelete(); true }
        renameTargetId.isNotEmpty() -> { closeRename(); true }
        browserVisible -> { browserVisible = false; true }
        else -> false
    }

    fun openBrowser() {
        onBrowserOpening()
        browserVisible = true
        browserError = ""
        browserNewName = ""
        loadDirectory(null)
    }

    fun loadDirectory(path: String?) {
        val client = ctx.hostClient ?: return
        browserBusy = true
        browserError = ""
        client.listDirectory(path) { listing, error ->
            setTimeout(pagerId, 0) {
                browserBusy = false
                if (error != null || listing == null) {
                    browserError = error?.message ?: "无法读取目录"
                    return@setTimeout
                }
                browserPath = listing.path
                browserHome = listing.home
                directoryEntries.clear()
                directoryEntries.addAll(listing.entries.filterNot { it.hidden })
            }
        }
    }

    fun createDirectory() {
        val client = ctx.hostClient ?: return
        val name = browserNewName.trim()
        if (browserPath.isEmpty() || name.isEmpty()) return
        browserBusy = true
        client.createDirectory(browserPath, name) { createdPath, error ->
            setTimeout(pagerId, 0) {
                browserBusy = false
                if (error != null || createdPath == null) {
                    browserError = error?.message ?: "无法创建目录"
                    return@setTimeout
                }
                browserNewName = ""
                loadDirectory(createdPath)
            }
        }
    }

    fun adoptCurrentDirectory() {
        val client = ctx.hostClient ?: return
        if (browserPath.isEmpty()) return
        browserBusy = true
        client.createWorkspace(browserPath) { _, error ->
            setTimeout(pagerId, 0) {
                browserBusy = false
                if (error != null) {
                    browserError = error.message
                    return@setTimeout
                }
                browserVisible = false
                onWorkspaceAdopted()
            }
        }
    }

    fun openRename(workspaceId: String, currentTitle: String) {
        renameTargetId = workspaceId
        renameDraft = currentTitle
        actionError = ""
    }

    fun closeRename() {
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
        deleteTargetId = workspaceId
        actionError = ""
    }

    fun closeDelete() {
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
        val beforeWorkspaceId = if (targetIndex == ordered.lastIndex) null else ordered[targetIndex].workspaceId
        runAction({ client.moveWorkspaceBefore(workspaceId, beforeWorkspaceId, it) }) {}
    }

    /** Busy/error bookkeeping shared by rename, delete and move; refreshes groups on success. */
    private fun runAction(
        call: ((JSONObject?, DshRpcError?) -> Unit) -> Unit,
        onSuccess: () -> Unit,
    ) {
        actionBusy = true
        actionError = ""
        call { _, error ->
            setTimeout(pagerId, 0) {
                actionBusy = false
                if (error != null) {
                    actionError = error.message
                    return@setTimeout
                }
                onSuccess()
                refreshGroups()
            }
        }
    }
}
