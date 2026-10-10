package com.example.dsh.module

import com.example.dsh.ssh.DshSshConfig
import com.example.dsh.ssh.DshSshKeyStore
import com.example.dsh.ssh.SshTunnelManager
import com.example.dsh.ssh.SshState
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

internal class KRDshEngineModule : KuiklyRenderBaseModule() {
    private var sshListener: ((SshState) -> Unit)? = null

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? = when (method) {
        "startSsh" -> {
            sshListener?.let(SshTunnelManager::removeListener)
            val value = org.json.JSONObject(params ?: "{}")
            val listener: (SshState) -> Unit = { state ->
                activity?.runOnUiThread { callback?.invoke(state.toMap()) }
            }
            sshListener = listener
            val keyId = value.optString("keyId")
            val keyStore = DshSshKeyStore(requireNotNull(context))
            if (!keyStore.exists(keyId)) {
                callback?.invoke(mapOf("phase" to "ERROR", "message" to "SSH 私钥不存在"))
            } else {
                SshTunnelManager.addListener(listener)
                runCatching {
                    SshTunnelManager.connect(requireNotNull(context), DshSshConfig(
                            host = value.optString("host"),
                            port = value.optInt("port", 22),
                            username = value.optString("username"),
                            remoteDshPort = value.optInt("remoteDshPort", 3080),
                        keyId = keyId,
                        hostFingerprint = value.optString("hostFingerprint"),
                        keyPassphrase = value.optString("keyPassphrase"),
                    ), keyStore.readKeyBytes(keyId), value.optString("keyPassphrase"))
                }.onFailure { error ->
                    callback?.invoke(mapOf("phase" to "ERROR", "message" to (error.message ?: "SSH 私钥读取失败")))
                }
            }
            null
        }
        "trustSshFingerprint" -> {
            SshTunnelManager.acceptFingerprint(org.json.JSONObject(params ?: "{}").optString("fingerprint"))
            null
        }
        "stopSsh" -> {
            sshListener?.let(SshTunnelManager::removeListener)
            SshTunnelManager.disconnect()
            null
        }
        "sshEndpoint" -> SshTunnelManager.endpoint()
        else -> null
    }

    private fun SshState.toMap(): Map<String, Any> = mapOf(
        "phase" to phase.name,
        "message" to message,
        "localPort" to localPort,
        "generation" to generation,
    )

    companion object {
        const val MODULE_NAME = "DshEngineModule"
    }
}
