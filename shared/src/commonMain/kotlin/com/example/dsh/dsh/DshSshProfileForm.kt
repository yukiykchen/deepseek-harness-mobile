package com.example.dsh.dsh

import com.example.dsh.base.Utils
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout

/**
 * Editable SSH profile shared by the connection setup page and the in-chat
 * connection settings modal, so both validate and persist the same way.
 */
internal class DshSshProfileForm(scope: PagerScope) : PagerScope by scope {
    var host by observable("")
    var user by observable("")
    var sshPort by observable(DEFAULT_SSH_PORT.toString())
    var dshPort by observable(DEFAULT_DSH_PORT.toString())
    var keyId by observable("")
        private set
    var fingerprint by observable("")
    var keyLabel by observable(NO_KEY_LABEL)
        private set
    var passphrase by observable("")
    var busy by observable(false)
    var error by observable("")

    fun load(profile: DshRemoteProfile?) {
        host = profile?.host.orEmpty()
        user = profile?.username.orEmpty()
        sshPort = profile?.sshPort?.toString() ?: DEFAULT_SSH_PORT.toString()
        dshPort = profile?.remoteDshPort?.toString() ?: DEFAULT_DSH_PORT.toString()
        keyId = profile?.keyId.orEmpty()
        fingerprint = profile?.hostFingerprint.orEmpty()
        keyLabel = if (keyId.isEmpty()) NO_KEY_LABEL else KEY_IMPORTED_LABEL
    }

    /** True when enough is filled in to try a connection without asking the user. */
    val isConfigured: Boolean
        get() = host.isNotBlank() && user.isNotBlank() && keyId.isNotBlank()

    /** Returns the profile, or sets [error] and returns null when a field is invalid. */
    fun validate(): DshRemoteProfile? {
        val port = sshPort.toIntOrNull()
        val remotePort = dshPort.toIntOrNull()
        error = when {
            host.isBlank() -> "请输入 SSH 主机地址"
            user.isBlank() -> "请输入 SSH 用户名"
            port == null || port !in 1..65535 -> "SSH 端口无效"
            remotePort == null || remotePort !in 1..65535 -> "远程 DSH 端口无效"
            keyId.isBlank() -> "请先导入 SSH 私钥"
            else -> return toProfile()
        }
        return null
    }

    /** Current fields as a profile; unparsable ports fall back to the defaults. */
    fun toProfile(hostFingerprint: String = fingerprint): DshRemoteProfile = DshRemoteProfile(
        host = host.trim(),
        sshPort = sshPort.toIntOrNull() ?: DEFAULT_SSH_PORT,
        username = user.trim(),
        remoteDshPort = dshPort.toIntOrNull() ?: DEFAULT_DSH_PORT,
        keyId = keyId,
        hostFingerprint = hostFingerprint,
    )

    fun toSshConfig(profile: DshRemoteProfile = toProfile()): DshSshConfig = DshSshConfig(
        host = profile.host,
        port = profile.sshPort,
        username = profile.username,
        remoteDshPort = profile.remoteDshPort,
        keyId = profile.keyId,
        hostFingerprint = profile.hostFingerprint,
        keyPassphrase = passphrase,
    )

    /** Let the user pick a private key file and import it into the native key store. */
    fun pickKey() {
        val bridge = Utils.bridgeModule(pagerId)
        busy = true
        bridge.pickSshKey { uri ->
            if (uri.isEmpty()) {
                setTimeout(pagerId, 0) { busy = false }
                return@pickSshKey
            }
            bridge.importSshKey(uri) { imported ->
                setTimeout(pagerId, 0) {
                    busy = false
                    if (imported.isEmpty()) {
                        error = "无法导入 SSH 私钥"
                    } else {
                        keyId = imported
                        keyLabel = KEY_IMPORTED_LABEL
                        error = ""
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_DSH_PORT = 3080
        private const val NO_KEY_LABEL = "未导入 SSH 私钥"
        private const val KEY_IMPORTED_LABEL = "已导入 SSH 私钥"
    }
}
