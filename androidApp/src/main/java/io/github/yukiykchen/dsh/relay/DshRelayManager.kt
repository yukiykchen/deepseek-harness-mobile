package io.github.yukiykchen.dsh.relay

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Android service/pairing bridge. The transport lifecycle lives in [DshRelayRuntime]. */
internal object DshRelayManager {
    private const val TAG = "DshRelay"
    @Volatile private var secrets: DshRelaySecrets? = null
    @Volatile private var appContext: Context? = null
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val pairingHttp = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val runtime = DshRelayRuntime(
        store = object : DshRelayCredentialStore {
            override fun load(): DshRelayCredentials? = secrets?.load()
            override fun save(credentials: DshRelayCredentials) = requireNotNull(secrets).save(credentials)
            override fun clear() { secrets?.clear() }
        },
        transport = DshOkHttpRelayTransport(androidUserAgent()),
        log = { Log.w(TAG, it) },
    )

    init { runtime.addListener { reconcilePlatform() } }

    fun addListener(listener: (DshRelayNativeState) -> Unit) = runtime.addListener(listener)
    fun removeListener(listener: (DshRelayNativeState) -> Unit) = runtime.removeListener(listener)
    fun current(): DshRelayNativeState = runtime.current()

    fun attach(context: Context) {
        synchronized(this) {
            appContext = context.applicationContext
            if (secrets == null) secrets = DshRelaySecrets(context.applicationContext)
        }
        runtime.refreshPairing()
    }

    fun beginPairing(context: Context): Long { attach(context); return runtime.beginPairing() }

    fun pairFromQr(context: Context, qr: String, generation: Long = beginPairing(context)): Map<String, Any> {
        if (!runtime.isPairing(generation)) return mapOf("ok" to false, "message" to "配对操作已取消")
        attach(context)
        val link: DshRelayPairLink
        val claim: String
        try {
            link = parseDshRelayPairQr(qr)
            claim = SealedTunnelCrypto.deriveClaimToken(link.masterKey)
        } catch (error: Exception) {
            runtime.failPairing(generation, "二维码无效")
            return mapOf("ok" to false, "message" to (error.message ?: "二维码无效"))
        }
        return try {
            check(runtime.isPairing(generation)) { "配对操作已取消" }
            val body = JSONObject().put("pairId", link.pairId).put("claimToken", claim)
                .put("deviceLabel", "Android").put("platform", "android")
            val json = pairingHttp.newCall(Request.Builder().url("${link.origin}/pair/claim-device")
                .header("User-Agent", androidUserAgent())
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
                .execute().use { response ->
                    readDshRelayJson(response)
                }
            val hostId = json.optString("hostId")
            val token = json.optString("clientToken")
            val name = json.optString("hostName").ifBlank { "电脑" }
            require(hostId.isNotBlank() && token.isNotBlank()) { "pairing response missing credentials" }
            val credentials = DshRelayCredentials(link.masterKey, token, hostId, name, link.origin)
            if (!runtime.completePairing(generation, credentials)) {
                mapOf("ok" to false, "message" to "配对操作已取消")
            } else mapOf("ok" to true, "hostId" to hostId, "hostName" to name, "relayOrigin" to link.origin,
                "pairedAt" to System.currentTimeMillis())
        } catch (error: Exception) {
            val message = redactRelayMessage(error.message ?: "配对失败", link.masterKey, claim)
            runtime.failPairing(generation, message)
            Log.w(TAG, "pairing failed")
            mapOf("ok" to false, "message" to message)
        }
    }

    fun connect(context: Context) {
        val intent = runtime.beginConnect()
        attach(context)
        runtime.connect(intent)
        reconcilePlatform()
    }

    fun disconnect() { runtime.disconnect(); reconcilePlatform() }
    fun forget() { runtime.forget(); reconcilePlatform() }

    /** Only the main thread owns foreground service and connectivity registration. Late posts read current intent. */
    private fun reconcilePlatform() {
        val context = appContext ?: return
        val action = {
            if (runtime.isEnabled()) {
                val intent = Intent(context, DshRelayForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                registerNetwork(context)
            } else {
                unregisterNetwork()
                context.stopService(Intent(context, DshRelayForegroundService::class.java))
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else Handler(Looper.getMainLooper()).post(action)
    }

    private fun registerNetwork(context: Context) {
        if (networkCallback != null) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runtime.networkAvailable() }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { connectivity = manager; networkCallback = callback }
            .onFailure { Log.w(TAG, "network callback registration failed") }
    }

    private fun unregisterNetwork() {
        val callback = networkCallback ?: return
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        networkCallback = null
        connectivity = null
    }

    private fun androidUserAgent(): String = "DSH-Android/${Build.VERSION.RELEASE} (Linux; Android ${Build.VERSION.RELEASE}; Mobile)"
}
