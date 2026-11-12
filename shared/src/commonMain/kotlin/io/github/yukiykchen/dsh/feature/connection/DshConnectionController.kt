package io.github.yukiykchen.dsh.feature.connection

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import io.github.yukiykchen.dsh.platform.DshRelayPhase
import io.github.yukiykchen.dsh.platform.DshRelayModule
import io.github.yukiykchen.dsh.platform.DshRelayState
import io.github.yukiykchen.dsh.platform.DshEngineModule
import io.github.yukiykchen.dsh.platform.DshSshPhase
import io.github.yukiykchen.dsh.platform.DshSshState
import io.github.yukiykchen.dsh.model.DshHostConnection
import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshSessionScope
import io.github.yukiykchen.dsh.model.DshHostRuntimePhase
import io.github.yukiykchen.dsh.model.DshHostRuntimeState
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.data.DshLocalStore
import io.github.yukiykchen.dsh.data.DshStoreCommand
import io.github.yukiykchen.dsh.data.DshStoreCommandData

/**
 * Owns the transport to the paired computer and the [DshHostClient] on top of it.
 *
 * Relay and SSH both end in a loopback HTTP endpoint; once one is ready a new
 * client is created through [createClient] and [Listener.onClientConnected]
 * fires. Every callback is checked against the [DshConnectionCoordinator]
 * generation, so a late event from a stopped transport is ignored.
 */

class DshConnectionController(
    scope: PagerScope,
    val localStore: () -> DshLocalStore?,
    val supportsRelay: () -> Boolean,
    val createClient: (DshHostConnection) -> DshHostClient,
    val listener: Listener,
) : PagerScope by scope {
    interface Listener {
        /** A new client is ready; load the session list through it. */
        fun onClientConnected()

        /** The Host came back after a reconnect; resync the session list. */
        fun onClientReconnected()

        /** Transport or Host state changed in a way that affects the turn status row. */
        fun onTransportStateChanged()

        /** The settings modal is about to show; close the keyboard and menus. */
        fun beforeSettingsShown()
        fun onSettingsVisibilityChanged(visible: Boolean)

        /** Connection settings were saved; the page should restart through the setup page. */
        fun onSettingsSaved(passphrase: String)

        /** SSH needs user input (fingerprint or error) on the setup page. */
        fun onSetupRequired()
    }
    var mode by observable(DshConnectionMode.RELAY)
        private set
    private var settingsMode by observable(DshConnectionMode.RELAY)
    var remoteProfileId by observable(DshSessionScope.DEFAULT_REMOTE_PROFILE_ID)
        private set
    /** Connection status shown in the top bar; the page also writes turn progress here. */
    var statusLabel by observable("本地内核启动中")
    var settingsVisible by observable(false)
        private set
    val sshForm = DshSshProfileForm(scope)
    var client: DshHostClient? = null
        private set
    var clientGeneration = 0L
        private set
    private var lastReadyGeneration: Long? = null
    val sshMode: Boolean
        get() = settingsMode == DshConnectionMode.SSH
    val sessionScope: DshSessionScope
        get() = DshSessionScope(mode, remoteProfileId)

    val coordinator = DshConnectionCoordinator()
    var engineModule: DshEngineModule? = null
    var relayEndpoint = ""
    private var relayToken = ""
    private var relayGeneration = -1L
    private var sshGeneration = -1L
    fun configure(mode: DshConnectionMode, profileId: String) {
        this.mode = mode
        settingsMode = mode
        remoteProfileId = profileId
        sshForm.load(runCatching { localStore()?.loadRemoteProfile() }.getOrNull())
    }

    /** False once the connection was stopped or switched; drop late callbacks then. */
    fun isActive(): Boolean = coordinator.isActive(mode)
    fun start() {
        val generation = coordinator.begin(mode)
        when (mode) {
            DshConnectionMode.SSH -> startSsh(generation)
            DshConnectionMode.RELAY -> startRelay(generation)
        }
    }
    fun stop() {
        val active = coordinator.activeModeOr(mode)
        coordinator.stop()
        clearClient()
        relayEndpoint = ""
        relayToken = ""
        sshForm.cancelSelection()
        when (active) {
            DshConnectionMode.RELAY -> relayModule().disconnect()
            DshConnectionMode.SSH -> engineModule?.stopSsh()
        }
    }
    fun dispose() { stop(); sshForm.dispose() }
    fun reconnectLabel(): String = when (mode) {
        DshConnectionMode.SSH -> "远程连接重建中"
        DshConnectionMode.RELAY -> "扫码连接重建中"
    }
    fun syncBusyLabel(): String = when (mode) {
        DshConnectionMode.SSH -> "远程 DSH 正在同步，暂不能发送"
        DshConnectionMode.RELAY -> "扫码连接正在同步，暂不能发送"
    }
    fun handleRuntimeState(state: DshHostRuntimeState) {
        if (!isActive()) return
        statusLabel = when (state.phase) {
            DshHostRuntimePhase.CONNECTING -> "正在打开远程事件流"
            DshHostRuntimePhase.HOST_HANDSHAKE -> "正在检查远程 DSH"
            DshHostRuntimePhase.SYNCING -> "正在同步远程会话"
            DshHostRuntimePhase.READY -> "远程 DSH 已就绪"
            DshHostRuntimePhase.RECONNECTING -> reconnectLabel()
            DshHostRuntimePhase.ERROR -> "远程 DSH 连接失败"
            DshHostRuntimePhase.STOPPED -> "远程 DSH 已停止"
            DshHostRuntimePhase.DISCONNECTED -> "等待远程连接"
        }
        if (state.phase == DshHostRuntimePhase.READY) {
            val previous = lastReadyGeneration
            lastReadyGeneration = state.generation
            if (previous != null && previous != state.generation) listener.onClientReconnected()
        }
        listener.onTransportStateChanged()
    }

    // region Settings modal
    fun openSettings(preserveError: Boolean = false) {
        settingsMode = mode
        if (!preserveError) sshForm.load(runCatching { localStore()?.loadRemoteProfile() }.getOrNull())
        listener.beforeSettingsShown()
        if (!preserveError) sshForm.error = ""
        showSettings(true)
    }
    fun closeSettings() {
        settingsMode = mode
        showSettings(false)
    }
    fun selectMode(useSsh: Boolean) {
        sshForm.cancelSelection()
        settingsMode = if (useSsh) DshConnectionMode.SSH else DshConnectionMode.RELAY
        sshForm.error = ""
    }
    fun trustFingerprint() {
        val fingerprint = sshForm.fingerprint
        if (fingerprint.isBlank()) return
        sshModule().trustSshFingerprint(fingerprint)
        localStore()?.submitAsync(
            DshStoreCommand.SAVE_SSH_PROFILE,
            DshStoreCommandData(remoteProfile = sshForm.toProfile()),
            { it() },
        ) {}
        sshForm.error = "正在使用已确认的主机指纹连接"
    }
    fun saveSettings() {
        if (sshForm.closed || sshForm.busy) return
        val passphrase = if (sshMode) sshForm.passphrase else ""
        if (sshMode) {
            val profile = sshForm.validate() ?: return
            val local = localStore() ?: run { sshForm.error = "无法保存 SSH 配置"; return }
            runCatching {
                local.submitAsync(
                    DshStoreCommand.SAVE_SSH_PROFILE,
                    DshStoreCommandData(remoteProfile = profile),
                    { it() },
                ) { error -> if (error != null) sshForm.error = "无法保存 SSH 配置" }
            }.onFailure { sshForm.error = "无法保存 SSH 配置"; return }
            sshForm.markKeySaved(profile.keyId)
        } else {
            val local = localStore() ?: run { sshForm.error = "无法保存连接配置"; return }
            runCatching {
                local.submitAsync(DshStoreCommand.SAVE_RELAY_MODE, DshStoreCommandData(), { it() }) { error ->
                    if (error != null) sshForm.error = "无法保存连接配置"
                }
            }.onFailure { sshForm.error = "无法保存连接配置"; return }
        }
        showSettings(false)
        listener.onSettingsSaved(passphrase)
    }

    fun showSettings(visible: Boolean) {
        if (!visible) sshForm.cancelSelection()
        settingsVisible = visible
        listener.onSettingsVisibilityChanged(visible)
    }

    // endregion

    fun relayModule(): DshRelayModule =
        PagerManager.getPager(pagerId).acquireModule(DshRelayModule.MODULE_NAME)

    fun sshModule(): DshEngineModule =
        PagerManager.getPager(pagerId).acquireModule(DshEngineModule.MODULE_NAME)

    fun isCurrent(generation: Long, mode: DshConnectionMode): Boolean =
        coordinator.accepts(generation, mode)

    fun startRelay(generation: Long) {
        if (!supportsRelay()) {
            statusLabel = "扫码连接目前仅支持 Android、iOS 和 HarmonyOS"
            return
        }
        statusLabel = "正在连接扫码电脑"
        relayGeneration = -1L
        relayModule().connect { state -> handleRelayState(generation, state) }
    }

    internal fun handleRelayState(generation: Long, state: DshRelayState) {
        if (!isCurrent(generation, DshConnectionMode.RELAY) || (state.generation > 0 && state.generation < relayGeneration)) return
        if (state.generation > 0) relayGeneration = state.generation
        when (state.phase) {
            DshRelayPhase.READY -> {
                if (state.localPort !in 1..65535 || state.localToken.isEmpty()) return
                val endpoint = "http://127.0.0.1:${state.localPort}"
                statusLabel = state.message.ifEmpty { "扫码隧道已连接" }
                if (state.hostId.isNotEmpty()) remoteProfileId = state.hostId
                if (relayEndpoint == endpoint && relayToken == state.localToken && client != null) return
                relayEndpoint = endpoint
                relayToken = state.localToken
                connectHost(endpoint, state.localToken)
            }
            DshRelayPhase.ERROR, DshRelayPhase.RECONNECTING, DshRelayPhase.STOPPED -> {
                relayEndpoint = ""
                relayToken = ""
                clearClient()
                statusLabel = when (state.phase) {
                    DshRelayPhase.ERROR -> state.message.ifEmpty { "扫码连接失败" }
                    DshRelayPhase.STOPPED -> "扫码连接已断开"
                    else -> "扫码连接重试中"
                }
                listener.onTransportStateChanged()
            }
            else -> {
                if (state.localPort <= 0) { relayEndpoint = ""; relayToken = "" }
                statusLabel = state.message.ifEmpty { "正在建立扫码隧道" }
            }
        }
    }

    fun startSsh(generation: Long) {
        if (!sshForm.isConfigured) {
            statusLabel = "请配置 SSH 连接"
            openSettings()
            return
        }
        val module = sshModule()
        engineModule = module
        statusLabel = "正在连接 SSH"
        sshGeneration = -1L
        module.startSsh(sshForm.toSshConfig()) { state -> handleSshState(generation, state) }
    }

    internal fun handleSshState(generation: Long, state: DshSshState) {
        // Older native status messages omit their generation; a zero-generation stop is still actionable.
        if (!isCurrent(generation, DshConnectionMode.SSH) || (state.generation > 0 && state.generation < sshGeneration)) return
        if (state.generation > 0) sshGeneration = state.generation
        when (state.phase) {
            DshSshPhase.FINGERPRINT_REQUIRED -> {
                clearClient()
                sshForm.fingerprint = state.message
                sshForm.error = "首次连接需要确认主机指纹：${state.message}"
                listener.onTransportStateChanged()
                listener.onSetupRequired()
            }
            DshSshPhase.READY -> {
                if (state.localPort !in 1..65535) return
                statusLabel = "正在检查远程 DSH"
                connectHost("http://127.0.0.1:${state.localPort}")
            }
            DshSshPhase.RECONNECTING, DshSshPhase.ERROR, DshSshPhase.STOPPED -> {
                clearClient()
                statusLabel = when (state.phase) {
                    DshSshPhase.ERROR -> "SSH 连接失败"
                    DshSshPhase.STOPPED -> "SSH 已断开"
                    else -> "SSH 重连中"
                }
                listener.onTransportStateChanged()
                if (state.phase == DshSshPhase.ERROR) { sshForm.error = state.message; listener.onSetupRequired() }
            }
            else -> statusLabel = state.message.ifEmpty { "正在连接 SSH" }
        }
    }

    fun connectHost(baseUrl: String, token: String = "") {
        clientGeneration++
        lastReadyGeneration = null
        client?.stop()
        client = createClient(DshHostConnection(baseUrl, token))
        listener.onClientConnected()
    }

    private fun clearClient() {
        clientGeneration++
        lastReadyGeneration = null
        client?.stop()
        client = null
    }
}
