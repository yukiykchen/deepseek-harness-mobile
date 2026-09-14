package com.example.dsh

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.css.ktx.toMap
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.example.dsh.adapter.KRColorParserAdapter
import com.example.dsh.adapter.KRFontAdapter
import com.example.dsh.adapter.KRImageAdapter
import com.example.dsh.adapter.KRLogAdapter
import com.example.dsh.adapter.KRRouterAdapter
import com.example.dsh.adapter.KRThreadAdapter
import com.example.dsh.adapter.KRUncaughtExceptionHandlerAdapter
import com.example.dsh.module.KRBridgeModule
import com.example.dsh.module.KRDshEngineModule
import com.example.dsh.module.KRDshMediaModule
import com.example.dsh.module.KRDshRelayModule
import com.example.dsh.module.KRDshWebSocketModule
import com.example.dsh.module.KRShareModule
import com.tencent.kuiklybase.android.KRWebView
import org.json.JSONObject

class KuiklyRenderActivity : AppCompatActivity(), KuiklyRenderViewBaseDelegatorDelegate {

    private lateinit var hrContainerView: ViewGroup
    private lateinit var loadingView: View
    private lateinit var errorView: View

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)

    private val pageName: String
        get() {
            val pn = intent.getStringExtra(KEY_PAGE_NAME) ?: ""
            return if (pn.isNotEmpty()) {
                return pn
            } else {
                "connection_setup"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_hr)
        setupImmersiveMode()
        hrContainerView = findViewById(R.id.hr_container)
        loadingView = findViewById(R.id.hr_loading)
        errorView = findViewById(R.id.hr_error)
        kuiklyRenderViewDelegator.onAttach(hrContainerView, "", pageName, createPageData())
    }

    override fun softInputMode(): Int? = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING

    override fun onDestroy() {
        super.onDestroy()
        kuiklyRenderViewDelegator.onDetach()
    }

    override fun onPause() {
        super.onPause()
        kuiklyRenderViewDelegator.onPause()
    }

    @Deprecated("Android dispatches legacy activity results to this host for the current app target")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        KRBridgeModule.dispatchActivityResult(requestCode, resultCode, data)
        KRDshRelayModule.dispatchActivityResult(requestCode, resultCode, data)
        KRDshMediaModule.dispatchActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        KRBridgeModule.dispatchRequestPermissionsResult(requestCode, permissions, grantResults)
        KRDshMediaModule.dispatchRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onResume() {
        super.onResume()
        kuiklyRenderViewDelegator.onResume()
    }

    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalModule(kuiklyRenderExport)
        with(kuiklyRenderExport) {
            moduleExport(KRBridgeModule.MODULE_NAME) {
                KRBridgeModule()
            }
            moduleExport(KRShareModule.MODULE_NAME) {
                KRShareModule()
            }
            moduleExport(KRDshEngineModule.MODULE_NAME) {
                KRDshEngineModule()
            }
            moduleExport(KRDshRelayModule.MODULE_NAME) {
                KRDshRelayModule()
            }
            moduleExport(KRDshWebSocketModule.MODULE_NAME) {
                KRDshWebSocketModule()
            }
            moduleExport(KRDshMediaModule.MODULE_NAME) {
                KRDshMediaModule()
            }
        }
    }

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalRenderView(kuiklyRenderExport)
        with(kuiklyRenderExport) {
            renderViewExport(KRWebView.VIEW_NAME, { context -> KRWebView(context) }, null)
        }
    }

    private fun createPageData(): Map<String, Any> {
        val param = argsToMap()
        param["appId"] = 1
        param["embeddedEngine"] = false
        param[KEY_IS_NIGHT_MODE] = isSystemNightMode()
        param[KEY_UTC_OFFSET_MINUTES] = utcOffsetMinutes()
        param["databaseDir"] = java.io.File(KRApplication.application.filesDir.parentFile, "databases").apply {
            if (!exists()) mkdirs()
        }.absolutePath
        return param
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        kuiklyRenderViewDelegator.sendEvent(
            PAGER_EVENT_THEME_DID_CHANGED,
            mapOf(KEY_IS_NIGHT_MODE to isSystemNightMode()),
        )
    }

    private fun isSystemNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * Called from the shared layer whenever the resolved app palette flips. The app theme
     * can differ from the system one, so the night resource qualifier is not enough here.
     */
    fun applyThemeChrome(dark: Boolean) {
        window?.decorView?.let { decor ->
            decor.systemUiVisibility = if (dark) {
                decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
        findViewById<View>(R.id.hr_root)?.setBackgroundColor(
            getColor(if (dark) R.color.app_background_dark else R.color.app_background_light),
        )
    }

    /** Minutes east of UTC, for the solar theme. Kuikly only exposes epoch milliseconds. */
    private fun utcOffsetMinutes(): Int {
        val zone = java.util.TimeZone.getDefault()
        return zone.getOffset(System.currentTimeMillis()) / 60_000
    }

    private fun argsToMap(): MutableMap<String, Any> {
        val jsonStr = intent.getStringExtra(KEY_PAGE_DATA) ?: return mutableMapOf()
        return JSONObject(jsonStr).toMap()
    }

    private fun setupImmersiveMode() {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window?.statusBarColor = Color.TRANSPARENT
            window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

    }

    companion object {

        private const val KEY_PAGE_NAME = "pageName"
        private const val KEY_PAGE_DATA = "pageData"
        private const val KEY_IS_NIGHT_MODE = "isNightMode"
        private const val KEY_UTC_OFFSET_MINUTES = "utcOffsetMinutes"
        private const val PAGER_EVENT_THEME_DID_CHANGED = "themeDidChanged"

        init {
            initKuiklyAdapter()
        }

        fun start(context: Context, pageName: String, pageData: JSONObject) {
            val starter = Intent(context, KuiklyRenderActivity::class.java)
            starter.putExtra(KEY_PAGE_NAME, pageName)
            starter.putExtra(KEY_PAGE_DATA, pageData.toString())
            context.startActivity(starter)
        }

        private fun initKuiklyAdapter() {
            with(KuiklyRenderAdapterManager) {
                krImageAdapter = KRImageAdapter(KRApplication.application)
                krLogAdapter = KRLogAdapter
                krUncaughtExceptionHandlerAdapter = KRUncaughtExceptionHandlerAdapter
                krFontAdapter = KRFontAdapter
                krColorParseAdapter = KRColorParserAdapter(KRApplication.application)
                krRouterAdapter = KRRouterAdapter
                krThreadAdapter = KRThreadAdapter()
            }
        }
    }
}
