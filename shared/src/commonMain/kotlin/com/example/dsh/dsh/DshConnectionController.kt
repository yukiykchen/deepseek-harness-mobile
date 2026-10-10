package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.reactive.handler.observable

/**
 * Owns the transport to the paired computer and the [DshHostClient] on top of it.
 *
 * Relay and SSH both end in a loopback HTTP endpoint; once one is ready a new
 * client is created through [createClient] and [Listener.onClientConnected]
 * fires. Every callback is checked against the [DshConnectionCoordinator]
 * generation, so a late event from a stopped transport is ignored.
 */
internal class DshConnectionController(
    scope: PagerScope,
    private val localStore: () -> DshLocalStore?,
    private val supportsRelay: () -> Boolean,
    private val createClient: (DshHostConnection) -> DshHostClient,
    private val listener: Listener,
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
        fun onSettingsSaved()

        /** SSH needs user input (fingerprint or error) on the setup page. */
        fun onSetupRequired()
    }

    var mode by observable(DshConnectionMode.RELAY)
        private set
    var remoteProfileId by observable(DshSessionScope.DEFAULT_REMOTE_PROFILE_ID)
        private set
    /** Connection status shown in the top bar; the page also writes turn progress here. */
    var statusLabel by observable("本地内核启动中")
    var settingsVisible by observable(false)
        private set
    val sshForm = DshSshProfileForm(scope)

    var client: DshHostClient? = null
        private set

    val sshMode: Boolean
        get() = mode == DshConnectionMode.SSH

    val sessionScope: DshSessionScope
        get() = DshSessionScope(mode, remoteProfileId)

    private val coordinator = DshConnectionCoordinator()
    private var engineModule: DshEngineModule? = null
    private var relayEndpoint = ""

    fun configure(mode: DshConnectionMode, profileId: String) {
        this.mode = mode
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
        client?.stop()
        client = null
        when (active) {
            DshConnectionMode.RELAY -> relayModule().disconnect()
            DshConnectionMode.SSH -> engineModule?.stopSsh()
        }
    }

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
        val wasReconnecting = isReconnectLabel(statusLabel)
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
        if (state.phase == DshHostRuntimePhase.READY && wasReconnecting) {
            listener.onClientReconnected()
        }
        listener.onTransportStateChanged()
    }

    // region Settings modal

    fun openSettings(preserveError: Boolean = false) {
        listener.beforeSettingsShown()
        if (!preserveError) sshForm.error = ""
        showSettings(true)
    }

    fun closeSettings() = showSettings(false)

    fun selectMode(useSsh: Boolean) {
        mode = if (useSsh) DshConnectionMode.SSH else DshConnectionMode.RELAY
        sshForm.error = ""
    }

    fun trustFingerprint() {
        val fingerprint = sshForm.fingerprint
        if (fingerprint.isBlank()) return
        sshModule().trustSshFingerprint(fingerprint)
        runCatching { localStore()?.saveRemoteProfile(sshForm.toProfile()) }
        sshForm.error = "正在使用已确认的主机指纹连接"
    }

    fun saveSettings() {
        if (sshMode) {
            val profile = sshForm.validate() ?: return
            runCatching { localStore()?.saveRemoteProfile(profile) }
            runCatching { localStore()?.saveLastConnectionMode(DshConnectionMode.SSH) }
        } else {
            runCatching { localStore()?.saveLastConnectionMode(DshConnectionMode.RELAY) }
        }
        showSettings(false)
        listener.onSettingsSaved()
    }

    private fun showSettings(visible: Boolean) {
        settingsVisible = visible
        listener.onSettingsVisibilityChanged(visible)
    }

    // endregion

    private fun relayModule(): DshRelayModule =
        PagerManager.getPager(pagerId).acquireModule(DshRelayModule.MODULE_NAME)

    private fun sshModule(): DshEngineModule =
        PagerManager.getPager(pagerId).acquireModule(DshEngineModule.MODULE_NAME)

    private fun isCurrent(generation: Long, mode: DshConnectionMode): Boolean =
        coordinator.accepts(generation, mode)

    private fun startRelay(generation: Long) {
        if (!supportsRelay()) {
            statusLabel = "扫码连接目前仅支持 Android、iOS 和 HarmonyOS"
            return
        }
        statusLabel = "正在连接扫码电脑"
        relayModule().connect { state ->
            if (!isCurrent(generation, DshConnectionMode.RELAY)) return@connect
            when (state.phase) {
                DshRelayPhase.READY -> {
                    if (state.localPort <= 0 || state.localToken.isEmpty()) return@connect
                    val endpoint = "http://127.0.0.1:${state.localPort}"
                    statusLabel = state.message.ifEmpty { "扫码隧道已连接" }
                    if (state.hostId.isNotEmpty()) remoteProfileId = state.hostId
                    if (relayEndpoint == endpoint && client != null) return@connect
                    relayEndpoint = endpoint
                    connectHost(endpoint, state.localToken)
                }
                DshRelayPhase.ERROR -> {
                    relayEndpoint = ""
                    statusLabel = state.message.ifEmpty { "扫码连接失败" }
                }
                DshRelayPhase.RECONNECTING -> {
                    relayEndpoint = ""
                    client?.stop()
                    client = null
                    statusLabel = "扫码连接重试中"
                    listener.onTransportStateChanged()
                }
                DshRelayPhase.STOPPED -> {
                    relayEndpoint = ""
                    client?.stop()
                    client = null
                    statusLabel = "扫码连接已断开"
                }
                else -> {
                    if (state.localPort <= 0) relayEndpoint = ""
                    statusLabel = state.message.ifEmpty { "正在建立扫码隧道" }
                }
            }
        }
    }

    private fun startSsh(generation: Long) {
        if (!sshForm.isConfigured) {
            statusLabel = "请配置 SSH 连接"
            openSettings()
            return
        }
        val module = sshModule()
        engineModule = module
        statusLabel = "正在连接 SSH"
        module.startSsh(sshForm.toSshConfig()) { state ->
            if (!isCurrent(generation, DshConnectionMode.SSH)) return@startSsh
            when (state.phase) {
                DshSshPhase.FINGERPRINT_REQUIRED -> {
                    sshForm.fingerprint = state.message
                    sshForm.error = "首次连接需要确认主机指纹：${state.message}"
                    listener.onSetupRequired()
                }
                DshSshPhase.READY -> {
                    statusLabel = "正在检查远程 DSH"
                    connectHost("http://127.0.0.1:${state.localPort}")
                }
                DshSshPhase.RECONNECTING -> statusLabel = "SSH 重连中"
                DshSshPhase.ERROR -> {
                    statusLabel = "SSH 连接失败"
                    sshForm.error = state.message
                    listener.onSetupRequired()
                }
                DshSshPhase.STOPPED -> {
                    client = null
                    statusLabel = "SSH 已断开"
                }
                else -> statusLabel = state.message.ifEmpty { "正在连接 SSH" }
            }
        }
    }

    private fun connectHost(baseUrl: String, token: String = "") {
        client?.stop()
        client = createClient(DshHostConnection(baseUrl, token))
        listener.onClientConnected()
    }
}
