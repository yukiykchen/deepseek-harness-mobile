package io.github.yukiykchen.dsh.feature.connection

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.data.DshLocalStore
import io.github.yukiykchen.dsh.data.DshStoreCommand
import io.github.yukiykchen.dsh.data.DshStoreCommandData
import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshRelayProfile
import io.github.yukiykchen.dsh.model.DshRemoteProfile
import io.github.yukiykchen.dsh.model.DshSessionScope
import io.github.yukiykchen.dsh.platform.DshRelayPairResult
import io.github.yukiykchen.dsh.platform.DshSshConfig
import io.github.yukiykchen.dsh.platform.DshSshPhase
import io.github.yukiykchen.dsh.platform.DshSshState

internal interface DshConnectionSetupTransport {
    fun scanAndPair(callback: (DshRelayPairResult) -> Unit)
    fun cancelPairing()
    fun forget(callback: (Boolean) -> Unit)
    fun validateKey(id: String, passphrase: String, callback: (Boolean) -> Unit)
    fun startSsh(config: DshSshConfig, callback: (DshSshState) -> Unit)
    fun trustFingerprint(fingerprint: String)
    fun stopSsh()
    fun createProbe(port: Int): DshHostClient
}

/** Setup commands own their validation, native attempt and Host probe until completion or invalidation. */
internal class DshConnectionSetupController(
    scope: PagerScope,
    val form: DshSshProfileForm,
    private val store: () -> DshLocalStore?,
    private val transport: DshConnectionSetupTransport,
    private val supportsRelay: () -> Boolean,
    private val supportsSsh: () -> Boolean,
    private val openHome: (DshConnectionMode, String, String) -> Unit,
    private val post: (() -> Unit) -> Unit = { setTimeout(scope.pagerId, 0, it) },
) : PagerScope by scope {
    private class Request(val mode: DshConnectionMode, val revision: Long) {
        var completed = false
        var nativeStarted = false
        var validationCompleted = false
        var nativeGeneration = -1L
        var probe: DshHostClient? = null
        var probePort = 0
        var confirmedFingerprint = ""
        var config: DshSshConfig? = null
        var profile: DshRemoteProfile? = null
    }

    private var modeValue by observable(DshConnectionMode.RELAY)
    var connectionMode: DshConnectionMode
        get() = modeValue
        set(value) {
            if (!closed && !navigated && modeValue != value) {
                cancel()
                form.cancelSelection()
                modeValue = value
                form.error = ""
            }
        }
    var relayPaired by observable(false)
        private set
    var relayHostName by observable("")
        private set
    var relayHostId by observable("")
        private set
    var relayOrigin by observable("")
        private set
    var relayMessage by observable("")
        private set
    var fingerprintPending by observable("")
        private set
    private var active: Request? = null
    private var closed = false
    private var navigated = false

    init { form.onEdited { cancel() } }

    fun loadRelay(profile: DshRelayProfile?) {
        if (closed || navigated) return
        relayPaired = profile != null
        relayHostId = profile?.hostId.orEmpty()
        relayHostName = profile?.hostName.orEmpty()
        relayOrigin = profile?.relayOrigin.orEmpty()
        relayMessage = ""
    }

    fun scanRelayQr() {
        if (!canStart()) return
        if (!supportsRelay()) { form.error = "扫码连接目前仅支持 Android、iOS 和 HarmonyOS"; return }
        val request = begin(DshConnectionMode.RELAY) ?: return
        request.nativeStarted = true
        try {
            transport.scanAndPair { result -> post {
                if (!accepts(request)) return@post
                if (!result.ok) { fail(request, result.message.ifEmpty { "扫码配对失败" }); return@post }
                val profile = DshRelayProfile(result.hostId, result.hostName.ifEmpty { "电脑" }, result.relayOrigin, result.pairedAt)
                submit(request, DshStoreCommand.SAVE_RELAY_PROFILE, DshStoreCommandData(relayProfile = profile), "无法保存配对信息") {
                    loadRelay(profile)
                    relayMessage = "配对成功"
                    finish(request)
                }
            } }
        } catch (_: Exception) { fail(request, "扫码配对失败") }
    }

    fun forgetRelay() {
        if (!canStart()) return
        val request = begin(DshConnectionMode.RELAY) ?: return
        try {
            transport.forget { ok -> post {
                if (!accepts(request)) return@post
                if (!ok) { fail(request, "无法移除配对，请重试"); return@post }
                submit(request, DshStoreCommand.CLEAR_RELAY_PROFILE, DshStoreCommandData(), "无法清除配对信息") {
                    loadRelay(null)
                    finish(request)
                }
            } }
        } catch (_: Exception) { fail(request, "无法移除配对，请重试") }
    }

    fun continueToHost() {
        if (!canStart()) return
        if (connectionMode == DshConnectionMode.RELAY) {
            if (!relayPaired) { form.error = "请先扫描电脑二维码"; return }
            val request = begin(DshConnectionMode.RELAY) ?: return
            submit(request, DshStoreCommand.SAVE_RELAY_MODE, DshStoreCommandData(), "无法保存连接配置") {
                finish(request)
                navigate(DshConnectionMode.RELAY, relayHostId.ifEmpty { DshSessionScope.DEFAULT_REMOTE_PROFILE_ID }, "")
            }
            return
        }
        if (!supportsSsh()) { form.error = "远程 SSH 模式目前仅支持 Android 和 iOS"; return }
        val profile = form.validate() ?: return
        val request = begin(DshConnectionMode.SSH) ?: return
        val config = form.toSshConfig(profile)
        request.profile = profile
        request.config = config
        try {
            transport.validateKey(profile.keyId, config.keyPassphrase) { valid -> post {
                if (!accepts(request) || request.validationCompleted) return@post
                request.validationCompleted = true
                if (!valid) { fail(request, "SSH 私钥不存在、格式无法识别或口令错误"); return@post }
                request.nativeStarted = true
                form.error = "正在连接 SSH 并检查远程 DSH"
                try { transport.startSsh(config) { state -> post { handleSshState(request, state) } } }
                catch (_: Exception) { fail(request, "SSH 连接失败") }
            } }
        } catch (_: Exception) { fail(request, "SSH 私钥校验失败") }
    }

    fun trustFingerprint() {
        val request = active ?: return
        val offered = fingerprintPending
        if (!accepts(request) || offered.isBlank() || form.busy) return
        request.confirmedFingerprint = offered
        form.fingerprint = offered
        form.busy = true
        fingerprintPending = ""
        form.error = "正在使用已确认的主机指纹连接"
        try { transport.trustFingerprint(offered) }
        catch (_: Exception) { fail(request, "无法确认 SSH 主机指纹") }
    }

    private fun handleSshState(request: Request, state: DshSshState) {
        if (!accepts(request) || (state.generation > 0 && state.generation < request.nativeGeneration)) return
        if (state.generation > 0) {
            if (state.generation != request.nativeGeneration) releaseProbe(request)
            request.nativeGeneration = state.generation
        }
        when (state.phase) {
            DshSshPhase.FINGERPRINT_REQUIRED -> {
                releaseProbe(request)
                if (state.message.isBlank()) { fail(request, "无法读取 SSH 主机指纹"); return }
                form.busy = false
                fingerprintPending = state.message
                form.error = "需要确认主机指纹：${state.message}"
            }
            DshSshPhase.READY -> {
                if (state.localPort !in 1..65535) { fail(request, "SSH 返回的转发端口无效"); return }
                if (request.probe != null && request.probePort == state.localPort) return
                releaseProbe(request)
                val probe = try { transport.createProbe(state.localPort) }
                catch (_: Exception) { fail(request, "无法检查远程 DSH"); return }
                if (!accepts(request)) { probe.stop(); return }
                request.probe = probe
                request.probePort = state.localPort
                form.busy = true
                probe.loadSessions({ post {
                    if (!accepts(request) || request.probe !== probe) return@post
                    val profile = requireNotNull(request.profile).let {
                        if (request.confirmedFingerprint.isEmpty()) it else it.copy(hostFingerprint = request.confirmedFingerprint)
                    }
                    submit(request, DshStoreCommand.SAVE_SSH_PROFILE, DshStoreCommandData(remoteProfile = profile), "无法保存 SSH 配置") {
                        form.markKeySaved(profile.keyId)
                        val passphrase = requireNotNull(request.config).keyPassphrase
                        finish(request)
                        navigate(DshConnectionMode.SSH, DshSessionScope.DEFAULT_REMOTE_PROFILE_ID, passphrase)
                    }
                } }, { message -> post {
                    if (accepts(request) && request.probe === probe) fail(request, "远程 DSH 不可用：$message")
                } })
            }
            DshSshPhase.ERROR, DshSshPhase.STOPPED -> fail(request, state.message.ifEmpty { "SSH 连接失败" })
            DshSshPhase.RECONNECTING -> {
                releaseProbe(request)
                form.busy = true
                fingerprintPending = ""
                form.error = state.message.ifEmpty { "SSH 重连中" }
            }
            else -> if (fingerprintPending.isEmpty()) form.error = state.message.ifEmpty { "正在连接 SSH" }
        }
    }

    private fun canStart() = !closed && !navigated && !form.busy && active == null

    private fun submit(
        request: Request,
        command: DshStoreCommand,
        data: DshStoreCommandData,
        failureMessage: String,
        onCommitted: () -> Unit,
    ) {
        val local = store() ?: run { fail(request, failureMessage); return }
        runCatching {
            local.submitAsync(command, data, { callback -> post(callback) }) { error ->
                if (!accepts(request)) return@submitAsync
                if (error != null) {
                    fail(request, failureMessage)
                    return@submitAsync
                }
                onCommitted()
            }
        }.onFailure { fail(request, failureMessage) }
    }

    private fun begin(mode: DshConnectionMode): Request? {
        if (connectionMode != mode) return null
        cancel()
        val request = Request(mode, form.revision)
        active = request
        form.busy = true
        form.error = ""
        return request
    }

    private fun accepts(request: Request) = !closed && !navigated && !request.completed && active === request &&
        connectionMode == request.mode && form.revision == request.revision

    private fun releaseProbe(request: Request) {
        val probe = request.probe
        request.probe = null
        request.probePort = 0
        probe?.stop()
    }

    private fun finish(request: Request) {
        if (active !== request) return
        active = null
        request.completed = true
        request.config = null
        request.profile = null
        releaseProbe(request)
        if (request.nativeStarted && request.mode == DshConnectionMode.SSH) transport.stopSsh()
        form.busy = false
        form.error = ""
        fingerprintPending = ""
    }

    private fun fail(request: Request, message: String) {
        if (!accepts(request)) return
        finish(request)
        form.error = message
    }

    private fun cancel() {
        val request = active
        active = null
        if (request != null) {
            request.completed = true
            request.config = null
            request.profile = null
            releaseProbe(request)
            if (request.nativeStarted) {
                if (request.mode == DshConnectionMode.SSH) transport.stopSsh() else transport.cancelPairing()
            }
        }
        form.busy = false
        fingerprintPending = ""
    }

    private fun navigate(mode: DshConnectionMode, id: String, passphrase: String) {
        cancel()
        navigated = true
        form.cancelSelection()
        form.passphrase = ""
        openHome(mode, id, passphrase)
    }

    fun resume() {
        if (closed || !navigated) return
        navigated = false
        form.load(runCatching { store()?.loadRemoteProfile() }.getOrNull())
        loadRelay(runCatching { store()?.loadRelayProfile() }.getOrNull())
    }

    fun dispose() {
        if (closed) return
        closed = true
        cancel()
        form.dispose()
    }
}
