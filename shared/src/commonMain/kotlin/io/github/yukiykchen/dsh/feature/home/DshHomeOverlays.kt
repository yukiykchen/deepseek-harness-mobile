package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Modal
import io.github.yukiykchen.dsh.feature.connection.DshConnectionController

/** Modal overlays rendered on top of the home conversation. */
fun ViewContainer<*, *>.DshHomeOverlays(
    settingsPageVisible: () -> Boolean,
    closeSettingsPage: () -> Unit,
    closeCredentialSettings: () -> Unit,
    openCredentialSettings: () -> Unit,
    connection: DshConnectionController,
    models: DshModelPickerController,
    credentials: DshCredentialController,
    workspaces: DshWorkspaceController,
    stopCurrentEngine: () -> Unit,
) {
    vif({ models.visible }) {
        DshModelPicker(
            options = { models.options },
            busy = { models.busy },
            error = { models.error },
            onClose = models::close,
            onSelect = { models.select(it) },
        )
    }

    vif({ credentials.visible }) {
        DshCredentialSetupModal(
            title = { credentials.title },
            busy = { credentials.busy },
            error = { credentials.error() },
            draft = { credentials.draft },
            onApiKeyChange = { credentials.updateDraft(it) },
            onSave = { credentials.save() },
            onClose = { closeCredentialSettings() },
        )
    }
    vif({ settingsPageVisible() }) {
        DshSettingsPage(
            connectionModeLabel = { if (connection.sshMode) "SSH" else "扫码" },
            onClose = { closeSettingsPage() },
            onOpenConnection = { connection.openSettings() },
            onDisconnect = stopCurrentEngine,
        )
    }
    val form = connection.sshForm
    vif({ connection.settingsVisible }) {
        DshConnectionSettingsModal(
            sshMode = { connection.sshMode },
            host = { form.host },
            user = { form.user },
            port = { form.sshPort },
            dshPort = { form.dshPort },
            keyLabel = { form.keyLabel },
            keyPassphrase = { form.passphrase },
            busy = { form.busy },
            error = { form.error },
            onModeChange = { connection.selectMode(it) },
            onHostChange = { form.host = it; form.error = "" },
            onUserChange = { form.user = it; form.error = "" },
            onPortChange = { form.sshPort = it; form.error = "" },
            onDshPortChange = { form.dshPort = it; form.error = "" },
            onPickKey = { form.pickKey() },
            onPassphraseChange = { form.passphrase = it },
            onTrustFingerprint = { connection.trustFingerprint() },
            onSave = { connection.saveSettings() },
            onClose = { connection.closeSettings() },
            onOpenApiKey = {
                connection.closeSettings()
                openCredentialSettings()
            },
        )
    }
    vif({ workspaces.browserVisible }) {
        DshWorkspaceBrowserModal(
            path = { workspaces.browserPath },
            home = { workspaces.browserHome },
            entries = { workspaces.directoryEntries },
            busy = { workspaces.browserBusy },
            error = { workspaces.browserError },
            newName = { workspaces.browserNewName },
            onDirectorySelect = { workspaces.loadDirectory(it) },
            onNewNameChange = { workspaces.browserNewName = it },
            onCreateDirectory = { workspaces.createDirectory() },
            onAdopt = { workspaces.adoptCurrentDirectory() },
            onClose = workspaces::closeBrowser,
        )
    }
    vif({ workspaces.renameTargetId.isNotEmpty() }) {
        DshWorkspaceRenameModal(
            draft = { workspaces.renameDraft },
            busy = { workspaces.actionBusy },
            error = { workspaces.actionError },
            onDraftChange = { workspaces.renameDraft = it },
            onSave = { workspaces.saveRename() },
            onClose = { workspaces.closeRename() },
        )
    }
    vif({ workspaces.deleteTargetId.isNotEmpty() }) {
        DshWorkspaceDeleteModal(
            busy = { workspaces.actionBusy },
            error = { workspaces.actionError },
            onConfirm = { workspaces.confirmDelete() },
            onClose = { workspaces.closeDelete() },
        )
    }
}
