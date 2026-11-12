package io.github.yukiykchen.dsh.module

import io.github.yukiykchen.dsh.ssh.DshSshConfig
import io.github.yukiykchen.dsh.ssh.DshSshKeyStore
import io.github.yukiykchen.dsh.ssh.SshTunnelManager
import io.github.yukiykchen.dsh.ssh.SshState
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject

internal class KRDshEngineModule : KuiklyRenderBaseModule() {
    private var sshListener: ((SshState) -> Unit)? = null
    private var destroyed = false

    override fun onDestroy() {
        destroyed = true
        removeSubscription()
        super.onDestroy()
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        if (destroyed) return null
        return try {
            when (method) {
                "startSsh" -> { startSsh(JSONObject(params ?: "{}"), callback); null }
                "trustSshFingerprint" -> {
                    SshTunnelManager.acceptFingerprint(JSONObject(params ?: "{}").optString("fingerprint"))
                    null
                }
                "stopSsh" -> { removeSubscription(); SshTunnelManager.disconnect(); null }
                "sshEndpoint" -> SshTunnelManager.endpoint()
                else -> null
            }
        } catch (_: Exception) {
            callback?.invoke(mapOf("phase" to "ERROR", "message" to "SSH 配置或私钥读取失败"))
            null
        }
    }

    private fun startSsh(value: JSONObject, callback: KuiklyRenderCallback?) {
        removeSubscription()
        val app = requireNotNull(context)
        val keyId = value.optString("keyId")
        val keyStore = DshSshKeyStore(app)
        if (!keyStore.exists(keyId)) {
            callback?.invoke(mapOf("phase" to "ERROR", "message" to "SSH 私钥不存在"))
            return
        }
        val bytes = keyStore.readKeyBytes(keyId)
        try {
            lateinit var listener: (SshState) -> Unit
            listener = { state ->
                activity?.runOnUiThread {
                    if (!destroyed && sshListener === listener) callback?.invoke(state.toMap())
                }
            }
            sshListener = listener
            // Starting a new profile must not replay the previous profile's READY endpoint.
            SshTunnelManager.addListener(listener, replay = false)
            SshTunnelManager.connect(app, DshSshConfig(
                host = value.optString("host"),
                port = value.optInt("port", 22),
                username = value.optString("username"),
                remoteDshPort = value.optInt("remoteDshPort", 3080),
                keyId = keyId,
                hostFingerprint = value.optString("hostFingerprint"),
            ), bytes, value.optString("keyPassphrase"))
        } catch (error: Exception) {
            removeSubscription()
            throw error
        } finally { bytes.fill(0) }
    }

    private fun removeSubscription() {
        sshListener?.let(SshTunnelManager::removeListener)
        sshListener = null
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
