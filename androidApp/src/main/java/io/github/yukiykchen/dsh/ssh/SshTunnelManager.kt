package io.github.yukiykchen.dsh.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import org.apache.sshd.common.util.io.PathUtils
import java.nio.file.Paths

/** Android lifecycle integration. SSH state and native resources belong to [DshSshRuntime]. */
internal object SshTunnelManager {
    private val runtime = DshSshRuntime(::DshMinaSshConnection, DshSshWorkers::work, DshSshWorkers::schedule) {
        Log.w("DshSshTunnel", it)
    }
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun addListener(listener: (SshState) -> Unit, replay: Boolean = true) = runtime.addListener(replay, listener)
    fun removeListener(listener: (SshState) -> Unit) = runtime.removeListener(listener)
    fun endpoint(): String? = runtime.endpoint()
    fun acceptFingerprint(fingerprint: String) = runtime.acceptFingerprint(fingerprint)

    @Synchronized
    fun connect(context: Context, next: DshSshConfig, keyBytes: ByteArray, passphrase: String = "") {
        val app = context.applicationContext
        // SSHD otherwise resolves ~/.ssh through Android's unusable JVM user.home.
        PathUtils.setUserHomeFolderResolver { Paths.get(app.filesDir.absolutePath).toAbsolutePath().normalize() }
        unregisterNetworkCallback()
        runtime.connect(next, keyBytes, passphrase)
        registerNetworkCallback(app)
    }

    @Synchronized
    fun disconnect() {
        unregisterNetworkCallback()
        runtime.disconnect()
    }

    private fun registerNetworkCallback(context: Context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = synchronized(this@SshTunnelManager) {
                if (networkCallback === this) runtime.networkLost()
            }
            override fun onAvailable(network: Network) = synchronized(this@SshTunnelManager) {
                if (networkCallback === this) runtime.networkAvailable()
            }
        }
        connectivity = manager
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onFailure {
            connectivity = null
            networkCallback = null
            Log.w("DshSshTunnel", "Network monitoring unavailable")
        }
    }

    private fun unregisterNetworkCallback() {
        val manager = connectivity
        val callback = networkCallback
        networkCallback = null
        connectivity = null
        if (manager != null && callback != null) runCatching { manager.unregisterNetworkCallback(callback) }
    }
}
