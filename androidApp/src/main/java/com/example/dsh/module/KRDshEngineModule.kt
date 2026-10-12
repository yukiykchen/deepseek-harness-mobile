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
        "prepareSsh" -> {
            val token = java.util.UUID.randomUUID().toString()
            synchronized(preparedLock) {
                preparedToken = token
                preparedAt = System.currentTimeMillis()
                preparedConfig = org.json.JSONObject(params ?: "{}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                synchronized(preparedLock) {
                    if (preparedToken == token) { preparedToken = ""; preparedConfig = null }
                }
            }, 60_000L)
            callback?.invoke(mapOf("preparedId" to token))
            null
        }
        "startSsh" -> {
            sshListener?.let(SshTunnelManager::removeListener)
            val request = org.json.JSONObject(params ?: "{}")
            val preparedId = request.optString("preparedId")
            val value = if (preparedId.isEmpty()) request else synchronized(preparedLock) {
                if (preparedId != preparedToken || System.currentTimeMillis() - preparedAt > 60_000L) null
                else preparedConfig.also { preparedToken = ""; preparedConfig = null }
            }
            if (value == null) {
                callback?.invoke(mapOf("phase" to "ERROR", "message" to "本次 SSH 配置已失效，请重新填写私钥口令"))
            } else {
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
        private val preparedLock = Any()
        private var preparedToken = ""
        private var preparedAt = 0L
        private var preparedConfig: org.json.JSONObject? = null
    }
}
