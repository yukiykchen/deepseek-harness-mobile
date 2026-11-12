package io.github.yukiykchen.dsh.feature.connection

import io.github.yukiykchen.dsh.data.createDshLocalStore
import io.github.yukiykchen.dsh.data.closeSafely
import io.github.yukiykchen.dsh.platform.supportsRelayBridge
import io.github.yukiykchen.dsh.platform.supportsSshBridge
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import io.github.yukiykchen.dsh.ui.DshWordmark
import io.github.yukiykchen.dsh.ui.effectiveKeyboardHeight
import io.github.yukiykchen.dsh.framework.bridgeModule
import io.github.yukiykchen.dsh.platform.DshRelayModule
import io.github.yukiykchen.dsh.platform.DshWebSocketModule
import io.github.yukiykchen.dsh.platform.BasePager
import io.github.yukiykchen.dsh.platform.DshEngineModule
import io.github.yukiykchen.dsh.platform.DshSshConfig
import io.github.yukiykchen.dsh.platform.DshSshState
import io.github.yukiykchen.dsh.platform.DshRelayPairResult
import io.github.yukiykchen.dsh.model.DshHostConnection
import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshSessionScope
import io.github.yukiykchen.dsh.model.DshRelayProfile
import io.github.yukiykchen.dsh.model.DshRemoteProfile
import io.github.yukiykchen.dsh.model.DshLegacyRemoteProfile
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.data.DshLocalStore
import io.github.yukiykchen.dsh.data.DshStoreCommand
import io.github.yukiykchen.dsh.data.DshStoreCommandData

/** First page shown by the app. It only selects a host and never starts an engine. */

@Page("connection_setup")

class DshConnectionSetupPage : BasePager() {
    var connectionMode: DshConnectionMode
        get() = setup.connectionMode
        set(value) { setup.connectionMode = value }
    val sshMode: Boolean
        get() = connectionMode == DshConnectionMode.SSH
    val relayPaired get() = setup.relayPaired
    val relayHostName get() = setup.relayHostName
    val relayHostId get() = setup.relayHostId
    val relayOrigin get() = setup.relayOrigin
    val relayMessage get() = setup.relayMessage
    val supportsSsh: Boolean
        get() = pageData.supportsSshBridge
    val form = DshSshProfileForm(this)
    /** Shared with [form] so key import and relay pairing report through one busy / error line. */
    var busy: Boolean
        get() = form.busy
        set(value) {
            form.busy = value
        }
    var error: String
        get() = form.error
        set(value) {
            form.error = value
        }
    val fingerprintPending get() = setup.fingerprintPending
    var localStore: DshLocalStore? = null
    private var keyboardHeight by observable(0f)
    private val setup = DshConnectionSetupController(
        scope = this,
        form = form,
        store = { localStore },
        supportsRelay = { pageData.supportsRelayBridge },
        supportsSsh = { supportsSsh },
        openHome = ::openHome,
        transport = object : DshConnectionSetupTransport {
            private var engine: DshEngineModule? = null
            override fun scanAndPair(callback: (DshRelayPairResult) -> Unit) =
                acquireModule<DshRelayModule>(DshRelayModule.MODULE_NAME).scanAndPair(callback)
            override fun cancelPairing() = acquireModule<DshRelayModule>(DshRelayModule.MODULE_NAME).disconnect()
            override fun forget(callback: (Boolean) -> Unit) = acquireModule<DshRelayModule>(DshRelayModule.MODULE_NAME).forget(callback)
            override fun validateKey(id: String, passphrase: String, callback: (Boolean) -> Unit) =
                bridgeModule.validateSshKey(id, passphrase, callback)
            override fun startSsh(config: DshSshConfig, callback: (DshSshState) -> Unit) {
                val module = acquireModule<DshEngineModule>(DshEngineModule.MODULE_NAME)
                engine = module
                module.startSsh(config, callback)
            }
            override fun trustFingerprint(fingerprint: String) { engine?.trustSshFingerprint(fingerprint) }
            override fun stopSsh() { engine?.stopSsh() }
            override fun createProbe(port: Int) = DshHostClient(
                network = acquireModule(com.tencent.kuikly.core.module.NetworkModule.MODULE_NAME),
                webSocket = acquireModule(DshWebSocketModule.MODULE_NAME),
                connection = DshHostConnection("http://127.0.0.1:$port"),
                pagerId = pagerId,
            ).also { it.start() }
        },
    )

    override fun created() {
        super.created()
        // 连接页没有覆盖层，系统返回键直接结束当前页面。
        getBackPressHandler().addCallback(object : BackPressCallback() {
            override fun handleOnBackPressed() {
                acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            }
        })
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
            ?: DshConnectionMode.RELAY
        if (!supportsSsh && connectionMode == DshConnectionMode.SSH) connectionMode = DshConnectionMode.RELAY
        val relay = runCatching { store?.loadRelayProfile() }.getOrNull()
        setup.loadRelay(relay)
        var profile = runCatching { store?.loadRemoteProfile() }.getOrNull()
        if (profile == null && legacyProfile != null) {
            runCatching { store?.migrateLegacyRemoteProfile(legacyProfile) }
            profile = runCatching { store?.loadRemoteProfile() }.getOrNull()
        }
        if (profile != null && legacyMode == "ssh") {
            connectionMode = DshConnectionMode.SSH
            store?.submitAsync(DshStoreCommand.SAVE_SSH_MODE, DshStoreCommandData(), { it() }) {}
        }
        if (profile != null) clearLegacyPreferences()
        form.load(profile)
        form.passphrase = pageData.params.optString("keyPassphrase")
        pageData.params.put("keyPassphrase", "")
    }

    override fun pageWillDestroy() {
        setup.dispose()
        localStore.closeSafely()
        super.pageWillDestroy()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        setup.resume()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    paddingTop(pagerData.statusBarHeight)
                    backgroundColor(DshConnectionPalette.canvas)
                }
                View {
                    attr {
                        height(58f)
                        flexDirectionRow()
                        alignItemsCenter()
                        paddingLeft(20f)
                        paddingRight(20f)
                        backgroundColor(DshConnectionPalette.surface)
                        borderBottom(Border(1f, BorderStyle.SOLID, DshConnectionPalette.divider))
                    }
                    DshWordmark(height = 24f)
                }
                Scroller {
                    attr {
                        flex(1f)
                        width(pagerData.pageViewWidth)
                        marginBottom(ctx.keyboardHeight)
                        paddingLeft(20f)
                        paddingRight(20f)
                        paddingTop(40f)
                        flexDirectionColumn()
                    }
                    Text { attr { text("连接 DSH"); fontSize(28f); fontWeightBold(); color(DshConnectionPalette.primary) } }
                    Text { attr { text("选择电脑上的 Agent"); marginTop(10f); fontSize(15f); color(DshConnectionPalette.secondary) } }
                    View {
                        attr { height(48f); marginTop(24f); flexDirectionRow(); padding(4f); borderRadius(8f); backgroundColor(DshConnectionPalette.segmentedTrack) }
                        DshSetupModeButton("扫码连接", { ctx.connectionMode == DshConnectionMode.RELAY }, { ctx.connectionMode = DshConnectionMode.RELAY; ctx.error = "" })
                        vif({ ctx.supportsSsh }) {
                            DshSetupModeButton("SSH", { ctx.connectionMode == DshConnectionMode.SSH }, { ctx.connectionMode = DshConnectionMode.SSH; ctx.error = "" })
                        }
                    }
                    vif({ ctx.connectionMode == DshConnectionMode.RELAY }) {
                        vif({ !ctx.relayPaired }) {
                            Text { attr { text("扫描电脑 Settings > Remote Access 中的二维码。首版只保存一台电脑。"); marginTop(16f); fontSize(14f); lineHeight(21f); color(DshConnectionPalette.secondary) } }
                        }
                        vif({ ctx.relayPaired }) {
                            Text { attr { text(ctx.relayHostName.ifEmpty { "已配对电脑" }); marginTop(16f); fontSize(16f); fontWeightBold(); color(DshConnectionPalette.primary) } }
                            Text { attr { text(ctx.relayOrigin); marginTop(6f); fontSize(13f); color(DshConnectionPalette.secondary) } }
                            Text { attr { text(ctx.relayMessage.ifEmpty { "已保存配对，连接后进入聊天" }); marginTop(8f); fontSize(13f); color(DshConnectionPalette.detail) } }
                        }
                        View {
                            attr { height(46f); marginTop(16f); flexDirectionRow(); alignItemsCenter(); justifyContentCenter(); borderRadius(8f); backgroundColor(DshConnectionPalette.action) }
                            Text { attr { text(if (ctx.busy) "处理中..." else if (ctx.relayPaired) "重新扫码" else "扫描电脑二维码"); fontSize(15f); color(DshConnectionPalette.surface) } }
                            event { click { if (!ctx.busy) ctx.scanRelayQr() } }
                        }
                        vif({ ctx.relayPaired }) {
                            Text {
                                attr { text("移除这台电脑"); marginTop(12f); fontSize(14f); color(DshConnectionPalette.error) }
                                event { click { if (!ctx.busy) ctx.forgetRelay() } }
                            }
                        }
                    }
                    vif({ ctx.connectionMode == DshConnectionMode.SSH }) {
                        val form = ctx.form
                        DshSetupInput("SSH 主机", { form.host }, "例如 Tailscale IP 或域名", onKeyboardHeightChange = ctx::updateKeyboard) { form.host = it; form.error = "" }
                        DshSetupInput("SSH 用户名", { form.user }, "例如 alex", onKeyboardHeightChange = ctx::updateKeyboard) { form.user = it; form.error = "" }
                        View {
                            attr { flexDirectionRow(); marginTop(4f) }
                            DshSetupInput("SSH 端口", { form.sshPort }, "22", 0.5f, onKeyboardHeightChange = ctx::updateKeyboard) { form.sshPort = it; form.error = "" }
                            DshSetupInput("远程 DSH 端口", { form.dshPort }, "3080", 0.5f, 12f, onKeyboardHeightChange = ctx::updateKeyboard) { form.dshPort = it; form.error = "" }
                        }
                        View {
                            attr { height(46f); marginTop(12f); flexDirectionRow(); alignItemsCenter(); paddingLeft(12f); paddingRight(12f); borderRadius(8f); backgroundColor(DshConnectionPalette.surface); border(Border(1f, BorderStyle.SOLID, DshConnectionPalette.fieldBorder)) }
                            Text { attr { text(ctx.form.keyLabel); flex(1f); fontSize(14f); color(DshConnectionPalette.detail) } }
                            Text { attr { text(if (ctx.busy) "导入中..." else "导入私钥"); fontSize(14f); color(DshConnectionPalette.action) }; event { click { if (!ctx.busy) ctx.form.pickKey() } } }
                        }
                        DshSetupInput("私钥口令（如有）", { form.passphrase }, "仅本次连接使用", password = true, onKeyboardHeightChange = ctx::updateKeyboard) { form.passphrase = it; form.error = "" }
                    }
                    vif({ ctx.error.isNotEmpty() }) {
                        Text { attr { text(ctx.error); marginTop(12f); fontSize(13f); lineHeight(19f); color(DshConnectionPalette.error) } }
                    }
                    vif({ ctx.fingerprintPending.isNotEmpty() }) {
                        Text {
                            attr { text("确认并继续使用此 SSH 主机指纹"); marginTop(10f); fontSize(13f); color(DshConnectionPalette.action) }
                            event { click { if (!ctx.busy) ctx.trustFingerprint() } }
                        }
                    }
                    Button {
                        attr {
                            height(48f)
                            marginTop(24f)
                            marginBottom(24f)
                            borderRadius(8f)
                            backgroundColor(if (ctx.busy || (!ctx.sshMode && !ctx.relayPaired) || ctx.fingerprintPending.isNotEmpty()) DshConnectionPalette.disabledAction else DshConnectionPalette.action)
                            titleAttr { text(when (ctx.connectionMode) {
                                DshConnectionMode.SSH -> "保存并连接电脑"
                                DshConnectionMode.RELAY -> if (ctx.relayPaired) "连接已配对电脑" else "请先扫码"
                            }); fontSize(15f); color(DshConnectionPalette.surface) }
                        }
                        event { click { if (!ctx.busy && (ctx.sshMode || ctx.relayPaired) && ctx.fingerprintPending.isEmpty()) ctx.continueToHost() } }
                    }
                }
            }
        }
    }


    fun scanRelayQr() = setup.scanRelayQr()
    fun forgetRelay() = setup.forgetRelay()
    fun continueToHost() = setup.continueToHost()
    fun trustFingerprint() = setup.trustFingerprint()
    private fun updateKeyboard(params: KeyboardParams) { keyboardHeight = effectiveKeyboardHeight(params.height, pageData) }

    private fun openHome(mode: DshConnectionMode, profileId: String, passphrase: String) {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("home", JSONObject().apply {
            put("pageName", "home")
            put("connectionMode", when (mode) {
                DshConnectionMode.RELAY -> "relay"
                DshConnectionMode.SSH -> "ssh"
            })
            put("profileId", profileId)
            put("keyPassphrase", passphrase)
        })
    }

    fun prefs(): SharedPreferencesModule = acquireModule(SharedPreferencesModule.MODULE_NAME)

    fun clearLegacyPreferences() {
        val prefs = prefs()
        val keys = listOf(
            LEGACY_MODE_KEY,
            LEGACY_HOST_KEY,
            LEGACY_USER_KEY,
            LEGACY_SSH_PORT_KEY,
            LEGACY_DSH_PORT_KEY,
            LEGACY_KEY_ID_KEY,
            LEGACY_FINGERPRINT_KEY,
        )
        for (key in keys) prefs.setItem(key, "")
    }

    companion object {
        const val LEGACY_HOST_KEY = "dsh_ssh_host"
        const val LEGACY_MODE_KEY = "dsh_connection_mode"
        const val LEGACY_USER_KEY = "dsh_ssh_user"
        const val LEGACY_SSH_PORT_KEY = "dsh_ssh_port"
        const val LEGACY_DSH_PORT_KEY = "dsh_ssh_dsh_port"
        const val LEGACY_KEY_ID_KEY = "dsh_ssh_key_id"
        const val LEGACY_FINGERPRINT_KEY = "dsh_ssh_fingerprint"
    }
}

fun ViewContainer<*, *>.DshSetupModeButton(label: String, selected: () -> Boolean, onClick: () -> Unit) {
    View {
        attr { flex(1f); height(40f); flexDirectionRow(); justifyContentCenter(); alignItemsCenter(); borderRadius(7f); backgroundColor(if (selected()) DshConnectionPalette.surface else DshConnectionPalette.transparent) }
        Text { attr { text(label); fontSize(14f); color(if (selected()) DshConnectionPalette.action else DshConnectionPalette.secondary) } }
        event { click { onClick() } }
    }
}

fun ViewContainer<*, *>.DshSetupInput(
    label: String,
    value: () -> String,
    hint: String,
    flexValue: Float = 1f,
    marginLeft: Float = 0f,
    password: Boolean = false,
    onKeyboardHeightChange: (KeyboardParams) -> Unit = {},
    onChange: (String) -> Unit,
) {
    View {
        attr { flex(flexValue); marginLeft(marginLeft); flexDirectionColumn(); marginTop(12f) }
        Text { attr { text(label); fontSize(12f); color(DshConnectionPalette.secondary) } }
        View {
            attr { height(42f); marginTop(5f); paddingLeft(10f); paddingRight(10f); borderRadius(8f); backgroundColor(DshConnectionPalette.surface); border(Border(1f, BorderStyle.SOLID, DshConnectionPalette.fieldBorder)) }
            Input {
                attr { flex(1f); fontSize(14f); text(value()); color(DshConnectionPalette.fieldText); placeholder(hint); placeholderColor(DshConnectionPalette.placeholder); returnKeyTypeDone(); if (password) keyboardTypePassword() }
                event {
                    textDidChange { onChange(it.text) }
                    keyboardHeightChange(onKeyboardHeightChange)
                    inputBlur { onKeyboardHeightChange(KeyboardParams(0f, 0f)) }
                }
            }
        }
    }
}
