package io.github.yukiykchen.dsh.module

import android.app.Activity
import android.content.Intent
import io.github.yukiykchen.dsh.relay.DshQrScanActivity
import io.github.yukiykchen.dsh.relay.DshRelayManager
import io.github.yukiykchen.dsh.relay.DshRelayNativeState
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import kotlin.concurrent.thread
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

internal class KRDshRelayModule : KuiklyRenderBaseModule() {
    private var stateListener: ((DshRelayNativeState) -> Unit)? = null
    private var pairCallback: KuiklyRenderCallback? = null
    private var pendingPairResponse: KuiklyRenderCallback? = null
    private val subscriptionVersion = AtomicLong(0)
    private var scannerRequest = 0
    @Volatile private var disposed = false

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        if (disposed) return null
        return when (method) {
        "scanAndPair" -> {
            if (pairCallback != null || pendingPairResponse != null || scannerOwner?.get()?.pairCallback != null) {
                callback?.invoke(mapOf("ok" to false, "message" to "扫码配对正在进行中"))
                return null
            }
            val host = activity
            if (host == null) {
                callback?.invoke(mapOf("ok" to false, "message" to "扫码页面不可用"))
                return null
            }
            pairCallback = callback
            scannerRequest = REQUEST_QR + (nextScannerRequest.incrementAndGet() % 10000).toInt()
            scannerOwner = WeakReference(this)
            val intent = Intent(activity, DshQrScanActivity::class.java)
            host.startActivityForResult(intent, scannerRequest)
            null
        }
        "connect" -> {
            DshRelayManager.connect(requireNotNull(context))
            listen(callback)
            null
        }
        "disconnect" -> {
            cancelPairingCallbacks()
            DshRelayManager.disconnect()
            null
        }
        "forget" -> {
            cancelPairingCallbacks()
            DshRelayManager.forget()
            callback?.invoke(mapOf("ok" to true))
            null
        }
        "status" -> {
            context?.let(DshRelayManager::attach)
            DshRelayManager.current().toMap()
        }
        "subscribe" -> {
            listen(callback)
            null
        }
        else -> null
        }
    }

    private fun listen(callback: KuiklyRenderCallback?) {
        val version = subscriptionVersion.incrementAndGet()
        stateListener?.let(DshRelayManager::removeListener)
        val listener: (DshRelayNativeState) -> Unit = { state ->
            activity?.runOnUiThread {
                if (!disposed && version == subscriptionVersion.get() && DshRelayManager.current() === state) {
                    callback?.invoke(state.toMap())
                }
            }
        }
        stateListener = listener
        DshRelayManager.addListener(listener)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (disposed || requestCode != scannerRequest || pairCallback == null) return
        if (scannerOwner?.get() === this) scannerOwner = null
        val qr = data?.getStringExtra(DshQrScanActivity.EXTRA_QR)
            ?: data?.getStringExtra("qr").orEmpty()
        if (resultCode != Activity.RESULT_OK || qr.isBlank()) {
            pairCallback?.invoke(mapOf("ok" to false, "message" to "已取消扫码"))
            pairCallback = null
            return
        }
        val callback = pairCallback
        pairCallback = null
        pendingPairResponse = callback
        val appContext = requireNotNull(context).applicationContext
        // Capture native intent before dispatch. A disconnect cannot be undone by a delayed worker starting.
        val intent = DshRelayManager.beginPairing(appContext)
        val owner = WeakReference(this)
        thread(name = "dsh-relay-pair") {
            if (owner.get()?.disposed != false) return@thread
            val result = DshRelayManager.pairFromQr(appContext, qr, intent)
            owner.get()?.activity?.runOnUiThread {
                val module = owner.get() ?: return@runOnUiThread
                if (!module.disposed && module.pendingPairResponse === callback) {
                    val response = module.pendingPairResponse
                    module.pendingPairResponse = null
                    response?.invoke(result)
                }
            }
        }
    }

    override fun onDestroy() {
        disposed = true
        if (pairCallback != null || pendingPairResponse != null) DshRelayManager.disconnect()
        subscriptionVersion.incrementAndGet()
        stateListener?.let(DshRelayManager::removeListener)
        stateListener = null
        pairCallback = null
        pendingPairResponse = null
        if (scannerOwner?.get() === this) scannerOwner = null
        super.onDestroy()
    }

    private fun cancelPairingCallbacks() {
        pairCallback = null
        pendingPairResponse = null
        if (scannerOwner?.get() === this) scannerOwner = null
    }

    private fun DshRelayNativeState.toMap(): Map<String, Any> = mapOf(
        "phase" to phase,
        "message" to message,
        "localPort" to localPort,
        "localToken" to localToken,
        "hostId" to hostId,
        "hostName" to hostName,
        "relayOrigin" to relayOrigin,
        "paired" to paired,
        "generation" to generation,
    )

    companion object {
        const val MODULE_NAME = "DshRelayModule"
        const val REQUEST_QR = 4102
        private var scannerOwner: WeakReference<KRDshRelayModule>? = null
        private val nextScannerRequest = AtomicLong(0)

        fun dispatchActivityResult(requestCode: Int, resultCode: Int, data: Intent?, source: Activity) {
            val owner = scannerOwner?.get() ?: return
            if (owner.activity === source) owner.onActivityResult(requestCode, resultCode, data)
        }
    }

}
