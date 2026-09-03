package com.example.dsh.dsh

import com.example.dsh.base.BasePager
import com.example.dsh.base.bridgeModule
import com.example.dsh.theme.tokens
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.timer.setTimeout

/** First page shown by the app. It only selects a host and never starts an engine. */
@Page("connection_setup")
internal class DshConnectionSetupPage : BasePager() {
    private var connectionMode by observable(DshConnectionMode.RELAY)
    private val sshMode: Boolean
        get() = connectionMode == DshConnectionMode.SSH
    private var relayPaired by observable(false)
    private var relayHostName by observable("")
    private var relayHostId by observable("")
    private var relayOrigin by observable("")
    private var relayMessage by observable("")
    private var host by observable("")
    private var user by observable("")
    private var sshPort by observable("22")
    private var dshPort by observable("3080")
    private var sshFingerprint by observable("")
    private var keyId by observable("")
    private var keyLabel by observable("未导入 SSH 私钥")
    private var busy by observable(false)
    private var error by observable("")
    private var fingerprintPending by observable("")
    private var localStore: DshLocalStore? = null
    private var engineModule: DshEngineModule? = null
    private var probeRepository: DshRepository? = null

    override fun created() {
        super.created()
        val databaseDir = pageData.params.optString("databaseDir")
        val prefs = prefs()
        val legacyMode = prefs.getItem(LEGACY_MODE_KEY)
        val legacyHost = prefs.getItem(LEGACY_HOST_KEY)
        val legacyUser = prefs.getItem(LEGACY_USER_KEY)
        val legacyKey = prefs.getItem(LEGACY_KEY_ID_KEY)
        val legacyProfile = if (legacyHost.isNotBlank() && legacyUser.isNotBlank() && legacyKey.isNotBlank()) {
            DshLegacyRemoteProfile(
                mode = DshConnectionMode.SSH,
                host = legacyHost,
                sshPort = prefs.getItem(LEGACY_SSH_PORT_KEY).toIntOrNull() ?: 22,
                username = legacyUser,
                remoteDshPort = prefs.getItem(LEGACY_DSH_PORT_KEY).toIntOrNull() ?: 3080,
                keyId = legacyKey,
                hostFingerprint = prefs.getItem(LEGACY_FINGERPRINT_KEY),
            )
        } else {
            null
        }
        localStore = if (databaseDir.isEmpty()) null else runCatching {
            createDshLocalStore("$databaseDir/dsh.db", legacyProfile)
        }.getOrNull()
        val store = localStore
        connectionMode = runCatching { store?.loadLastConnectionMode() }.getOrNull()
            ?.takeUnless { it == DshConnectionMode.LOCAL }
            ?: DshConnectionMode.RELAY
        val relay = runCatching { store?.loadRelayProfile() }.getOrNull()
        relayPaired = relay != null
        relayHostId = relay?.hostId.orEmpty()
        relayHostName = relay?.hostName.orEmpty()
        relayOrigin = relay?.relayOrigin.orEmpty()
        var profile = runCatching { store?.loadRemoteProfile() }.getOrNull()
        if (profile == null && legacyProfile != null) {
            runCatching { store?.migrateLegacyRemoteProfile(legacyProfile) }
            profile = runCatching { store?.loadRemoteProfile() }.getOrNull()
        }
        if (profile != null && legacyMode == "ssh") {
            connectionMode = DshConnectionMode.SSH
            runCatching { store?.saveLastConnectionMode(DshConnectionMode.SSH) }
        }
        if (profile != null) clearLegacyPreferences()
        host = profile?.host.orEmpty()
        user = profile?.username.orEmpty()
        sshPort = profile?.sshPort?.toString() ?: "22"
        dshPort = profile?.remoteDshPort?.toString() ?: "3080"
        keyId = profile?.keyId.orEmpty()
        sshFingerprint = profile?.hostFingerprint.orEmpty()
        keyLabel = if (keyId.isEmpty()) "未导入 SSH 私钥" else "已导入 SSH 私钥"
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    autoDarkEnable(false)
                    paddingTop(pagerData.statusBarHeight)
                    backgroundColor(tokens.background)
                }
                View {
                    attr {
                        height(58f)
                        flexDirectionRow()
                        alignItemsCenter()
                        paddingLeft(20f)
                        paddingRight(20f)
                        backgroundColor(tokens.surface)
                        borderBottom(Border(1f, BorderStyle.SOLID, tokens.divider))
                    }
                    Image { attr { src(ImageUri.commonAssets("wordmark.svg")); width(118f); height(24f); tintColor(tokens.primaryText) } }
                }
                View {
                    attr {
                        flex(1f)
                        paddingLeft(20f)
                        paddingRight(20f)
                        paddingTop(40f)
                        flexDirectionColumn()
                    }
                    Text { attr { text("连接 DSH"); fontSize(28f); fontWeightBold(); color(tokens.primaryText) } }
                    Text { attr { text("选择电脑上的 Agent"); marginTop(10f); fontSize(15f); color(tokens.secondaryText) } }
                    View {
                        attr { height(48f); marginTop(24f); flexDirectionRow(); padding(4f); borderRadius(10f); backgroundColor(tokens.surfaceVariant) }
                        DshSetupModeButton("扫码连接", { ctx.connectionMode == DshConnectionMode.RELAY }, { ctx.connectionMode = DshConnectionMode.RELAY; ctx.error = "" })
                        DshSetupModeButton("SSH", { ctx.connectionMode == DshConnectionMode.SSH }, { ctx.connectionMode = DshConnectionMode.SSH; ctx.error = "" })
                    }
                    vif({ ctx.connectionMode == DshConnectionMode.RELAY }) {
                        vif({ !ctx.relayPaired }) {
                            Text { attr { text("扫描电脑 Settings > Remote Access 中的二维码。首版只保存一台电脑。"); marginTop(16f); fontSize(14f); lineHeight(21f); color(tokens.secondaryText) } }
                        }
                        vif({ ctx.relayPaired }) {
                            Text { attr { text(ctx.relayHostName.ifEmpty { "已配对电脑" }); marginTop(16f); fontSize(16f); fontWeightBold(); color(tokens.primaryText) } }
                            Text { attr { text(ctx.relayOrigin); marginTop(6f); fontSize(13f); color(tokens.secondaryText) } }
                            Text { attr { text(ctx.relayMessage.ifEmpty { "已保存配对，连接后进入聊天" }); marginTop(8f); fontSize(13f); color(tokens.secondaryText) } }
                        }
                        View {
                            attr { height(46f); marginTop(16f); flexDirectionRow(); alignItemsCenter(); justifyContentCenter(); borderRadius(8f); backgroundColor(tokens.primary) }
                            Text { attr { text(if (ctx.busy) "处理中..." else if (ctx.relayPaired) "重新扫码" else "扫描电脑二维码"); fontSize(15f); color(tokens.onPrimary) } }
                            event { click { if (!ctx.busy) ctx.scanRelayQr() } }
                        }
                        vif({ ctx.relayPaired }) {
                            Text {
                                attr { text("移除这台电脑"); marginTop(12f); fontSize(14f); color(tokens.error.foreground) }
                                event { click { if (!ctx.busy) ctx.forgetRelay() } }
                            }
                        }
                    }
                    vif({ ctx.connectionMode == DshConnectionMode.SSH }) {
                        DshSetupInput("SSH 主机", { ctx.host }, "例如 Tailscale IP 或域名") { ctx.host = it; ctx.error = "" }
                        DshSetupInput("SSH 用户名", { ctx.user }, "例如 alex") { ctx.user = it; ctx.error = "" }
                        View {
                            attr { flexDirectionRow(); marginTop(4f) }
                            DshSetupInput("SSH 端口", { ctx.sshPort }, "22", 0.5f) { ctx.sshPort = it; ctx.error = "" }
                            DshSetupInput("远程 DSH 端口", { ctx.dshPort }, "3080", 0.5f, 12f) { ctx.dshPort = it; ctx.error = "" }
                        }
                        View {
                            attr { height(46f); marginTop(12f); flexDirectionRow(); alignItemsCenter(); paddingLeft(12f); paddingRight(12f); borderRadius(8f); backgroundColor(tokens.surface); border(Border(1f, BorderStyle.SOLID, tokens.divider)) }
                            Text { attr { text(ctx.keyLabel); flex(1f); fontSize(14f); color(tokens.secondaryText) } }
                            Text { attr { text(if (ctx.busy) "导入中..." else "导入私钥"); fontSize(14f); color(tokens.primary) }; event { click { if (!ctx.busy) ctx.pickKey() } } }
                        }
                    }
                    vif({ ctx.error.isNotEmpty() }) {
                        Text { attr { text(ctx.error); marginTop(12f); fontSize(13f); lineHeight(19f); color(tokens.error.foreground) } }
                    }
                    vif({ ctx.fingerprintPending.isNotEmpty() }) {
                        Text {
                            attr { text("确认并继续使用此 SSH 主机指纹"); marginTop(10f); fontSize(13f); color(tokens.primary) }
                            event { click { if (!ctx.busy) ctx.trustFingerprint() } }
                        }
                    }
                    View { attr { flex(1f) } }
                    Button {
                        attr {
                            height(48f)
                            marginBottom(24f)
                            borderRadius(10f)
                            backgroundColor(if (ctx.busy) tokens.primaryDisabled else tokens.primary)
                            titleAttr { text(when (ctx.connectionMode) {
                                DshConnectionMode.SSH -> "保存并连接电脑"
                                DshConnectionMode.RELAY -> if (ctx.relayPaired) "连接已配对电脑" else "请先扫码"
                                DshConnectionMode.LOCAL -> "请改用 DSH Local"
                            }); fontSize(15f); color(tokens.onPrimary) }
                        }
                        event { click { if (!ctx.busy) ctx.continueToHost() } }
                    }
                }
            }
        }
    }


    private fun scanRelayQr() {
        if (!pageData.isAndroid && !pageData.isIOS) {
            error = "扫码连接目前仅支持 Android 和 iOS"
            return
        }
        busy = true
        error = ""
        acquireModule<DshRelayModule>(DshRelayModule.MODULE_NAME).scanAndPair { result ->
            setTimeout(pagerId, 0) {
                busy = false
                if (!result.ok) {
                    error = result.message.ifEmpty { "扫码配对失败" }
                    return@setTimeout
                }
                relayPaired = true
                relayHostId = result.hostId
                relayHostName = result.hostName.ifEmpty { "电脑" }
                relayOrigin = result.relayOrigin
                relayMessage = "配对成功"
                error = ""
                runCatching {
                    localStore?.saveRelayProfile(
                        DshRelayProfile(result.hostId, result.hostName.ifEmpty { "电脑" }, result.relayOrigin, result.pairedAt),
                    )
                    localStore?.saveLastConnectionMode(DshConnectionMode.RELAY)
                }
            }
        }
    }

    private fun forgetRelay() {
        acquireModule<DshRelayModule>(DshRelayModule.MODULE_NAME).forget { _ ->
            setTimeout(pagerId, 0) {
                runCatching { localStore?.clearRelayProfile() }
                relayPaired = false
                relayHostId = ""
                relayHostName = ""
                relayOrigin = ""
                relayMessage = ""
                error = ""
            }
        }
    }

    private fun pickKey() {
        busy = true
        bridgeModule.pickSshKey { uri ->
            if (uri.isEmpty()) {
                busy = false
                return@pickSshKey
            }
            bridgeModule.importSshKey(uri) { imported ->
                setTimeout(pagerId, 0) {
                    busy = false
                    if (imported.isEmpty()) error = "无法导入 SSH 私钥"
                    else {
                        keyId = imported
                        keyLabel = "已导入 SSH 私钥"
                        error = ""
                    }
                }
            }
        }
    }

    private fun continueToHost() {
        fingerprintPending = ""
        if (connectionMode == DshConnectionMode.LOCAL) {
            error = "本地模式已独立为 DSH Local App"
            return
        }
        if (connectionMode == DshConnectionMode.RELAY) {
            if (!relayPaired) {
                error = "请先扫描电脑二维码"
                return
            }
            runCatching { localStore?.saveLastConnectionMode(DshConnectionMode.RELAY) }
            openHome()
            return
        }
        val ssh = sshPort.toIntOrNull()
        val dsh = dshPort.toIntOrNull()
        if (!pageData.isAndroid && !pageData.isIOS) {
            error = "远程 SSH 模式目前仅支持 Android 和 iOS"
            return
        }
        when {
            host.isBlank() -> error = "请输入 SSH 主机地址"
            user.isBlank() -> error = "请输入 SSH 用户名"
            ssh == null || ssh !in 1..65535 -> error = "SSH 端口无效"
            dsh == null || dsh !in 1..65535 -> error = "远程 DSH 端口无效"
            keyId.isBlank() -> error = "请先导入 SSH 私钥"
            else -> {
                busy = true
                bridgeModule.validateSshKey(keyId) { valid ->
                    setTimeout(pagerId, 0) {
                        if (!valid) {
                            busy = false
                            error = "SSH 私钥不存在或格式无法识别"
                            return@setTimeout
                        }
                        val profile = DshRemoteProfile(
                            host = host.trim(), sshPort = ssh, username = user.trim(),
                            remoteDshPort = dsh, keyId = keyId, hostFingerprint = sshFingerprint,
                        )
                        runCatching { localStore?.saveRemoteProfile(profile) }
                        runCatching { localStore?.saveLastConnectionMode(DshConnectionMode.SSH) }
                        probeRemote(profile)
                    }
                }
            }
        }
    }

    private fun probeRemote(profile: DshRemoteProfile) {
        val module = acquireModule<DshEngineModule>(DshEngineModule.MODULE_NAME)
        engineModule = module
        error = "正在连接 SSH 并检查远程 DSH"
        module.startSsh(DshSshConfig(
            host = profile.host,
            port = profile.sshPort,
            username = profile.username,
            remoteDshPort = profile.remoteDshPort,
            keyId = profile.keyId,
            hostFingerprint = profile.hostFingerprint,
        )) { state ->
            when (state.phase) {
                DshSshPhase.FINGERPRINT_REQUIRED -> {
                    busy = false
                    fingerprintPending = state.message
                    error = "首次连接需要确认主机指纹：${state.message}"
                    sshFingerprint = state.message
                }
                DshSshPhase.READY -> {
                        val repository = DshRemoteRepository(
                        network = acquireModule(com.tencent.kuikly.core.module.NetworkModule.MODULE_NAME),
                        webSocket = acquireModule(DshWebSocketModule.MODULE_NAME),
                        connection = DshHostConnection("http://127.0.0.1:${state.localPort}"),
                        pagerId = pagerId,
                    )
                    probeRepository = repository
                        repository.loadSessions({
                            setTimeout(pagerId, 0) {
                                busy = false
                                error = ""
                                (probeRepository as? DshRemoteRepository)?.stop()
                                module.stopSsh()
                                openHome()
                            }
                        }, { message ->
                            setTimeout(pagerId, 0) {
                                busy = false
                                error = "远程 DSH 不可用：$message"
                                (probeRepository as? DshRemoteRepository)?.stop()
                                module.stopSsh()
                            }
                    })
                }
                DshSshPhase.ERROR -> {
                    busy = false
                    error = state.message.ifEmpty { "SSH 连接失败" }
                    module.stopSsh()
                }
                else -> error = state.message.ifEmpty { "正在连接 SSH" }
            }
        }
    }

    private fun trustFingerprint() {
        val fingerprint = fingerprintPending
        if (fingerprint.isBlank()) return
        sshFingerprint = fingerprint
        engineModule?.trustSshFingerprint(fingerprint)
        localStore?.saveRemoteProfile(DshRemoteProfile(
            host = host.trim(),
            sshPort = sshPort.toIntOrNull() ?: 22,
            username = user.trim(),
            remoteDshPort = dshPort.toIntOrNull() ?: 3080,
            keyId = keyId,
            hostFingerprint = fingerprint,
        ))
        fingerprintPending = ""
        error = ""
        probeRemote(DshRemoteProfile(
            host = host.trim(),
            sshPort = sshPort.toIntOrNull() ?: 22,
            username = user.trim(),
            remoteDshPort = dshPort.toIntOrNull() ?: 3080,
            keyId = keyId,
            hostFingerprint = fingerprint,
        ))
    }

    private fun openHome() {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("home", JSONObject().apply {
            put("pageName", "home")
            put("connectionMode", when (connectionMode) {
                DshConnectionMode.LOCAL -> "local"
                DshConnectionMode.RELAY -> "relay"
                DshConnectionMode.SSH -> "ssh"
            })
            put("profileId", when (connectionMode) {
                DshConnectionMode.RELAY -> relayHostId.ifEmpty { DshSessionScope.DEFAULT_REMOTE_PROFILE_ID }
                else -> DshSessionScope.DEFAULT_REMOTE_PROFILE_ID
            })
        })
    }

    private fun prefs(): SharedPreferencesModule = acquireModule(SharedPreferencesModule.MODULE_NAME)

    private fun clearLegacyPreferences() {
        val prefs = prefs()
        listOf(
            LEGACY_MODE_KEY,
            LEGACY_HOST_KEY,
            LEGACY_USER_KEY,
            LEGACY_SSH_PORT_KEY,
            LEGACY_DSH_PORT_KEY,
            LEGACY_KEY_ID_KEY,
            LEGACY_FINGERPRINT_KEY,
        ).forEach { prefs.setItem(it, "") }
    }

    companion object {
        private const val LEGACY_HOST_KEY = "dsh_ssh_host"
        private const val LEGACY_MODE_KEY = "dsh_connection_mode"
        private const val LEGACY_USER_KEY = "dsh_ssh_user"
        private const val LEGACY_SSH_PORT_KEY = "dsh_ssh_port"
        private const val LEGACY_DSH_PORT_KEY = "dsh_ssh_dsh_port"
        private const val LEGACY_KEY_ID_KEY = "dsh_ssh_key_id"
        private const val LEGACY_FINGERPRINT_KEY = "dsh_ssh_fingerprint"
    }
}

private fun ViewContainer<*, *>.DshSetupModeButton(label: String, selected: () -> Boolean, onClick: () -> Unit) {
    View {
        attr { flex(1f); height(40f); flexDirectionRow(); justifyContentCenter(); alignItemsCenter(); borderRadius(7f); backgroundColor(if (selected()) tokens.surfaceElevated else Color.TRANSPARENT) }
        Text { attr { text(label); fontSize(14f); color(if (selected()) tokens.primary else tokens.secondaryText) } }
        event { click { onClick() } }
    }
}

private fun ViewContainer<*, *>.DshSetupInput(
    label: String,
    value: () -> String,
    hint: String,
    flexValue: Float = 1f,
    marginLeft: Float = 0f,
    onChange: (String) -> Unit,
) {
    View {
        attr { flex(flexValue); marginLeft(marginLeft); flexDirectionColumn(); marginTop(12f) }
        Text { attr { text(label); fontSize(12f); color(tokens.secondaryText) } }
        View {
            attr { height(42f); marginTop(5f); paddingLeft(10f); paddingRight(10f); borderRadius(8f); backgroundColor(tokens.surface); border(Border(1f, BorderStyle.SOLID, tokens.divider)) }
            Input {
                ref { it.view?.setText(value()) }
                attr { flex(1f); fontSize(14f); color(tokens.primaryText); placeholder(hint); placeholderColor(tokens.tertiaryText); returnKeyTypeDone() }
                event { textDidChange { onChange(it.text) } }
            }
        }
    }
}
