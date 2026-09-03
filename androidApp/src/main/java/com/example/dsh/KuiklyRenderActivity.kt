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
import com.example.dsh.module.KRDshRelayModule
import com.example.dsh.module.KRDshWebSocketModule
import com.example.dsh.module.KRDshSseModule
import com.example.dsh.module.KRDshThemeModule
import com.example.dsh.module.KRShareModule
import com.tencent.kuiklybase.android.KRWebView
import org.json.JSONObject

class KuiklyRenderActivity : AppCompatActivity(), KuiklyRenderViewBaseDelegatorDelegate {

    private lateinit var hrContainerView: ViewGroup
    private lateinit var loadingView: View
    private lateinit var errorView: View

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)
    private var currentIsDark = false
    private var lastSystemDark = false
    private var chromePaintedWithContainers = false

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

        // 在 inflate 布局之前就把窗口刷成解析后的主题色，避免深色模式下首帧白闪。
        lastSystemDark = DshThemeChrome.systemIsDark(this)
        currentIsDark = DshThemeChrome.resolveIsDark(this)
        DshThemeChrome.apply(this, currentIsDark)

        setContentView(R.layout.activity_hr)
        setupImmersiveMode()
        hrContainerView = findViewById(R.id.hr_container)
        loadingView = findViewById(R.id.hr_loading)
        errorView = findViewById(R.id.hr_error)
        applyThemeChrome(currentIsDark, forceContainers = true)
        kuiklyRenderViewDelegator.onAttach(hrContainerView, "", pageName, createPageData())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val systemDark = DshThemeChrome.systemIsDark(this)
        if (systemDark == lastSystemDark) return
        lastSystemDark = systemDark
        kuiklyRenderViewDelegator.sendEvent(
            DshThemeChrome.THEME_DID_CHANGED,
            mapOf(DshThemeChrome.IS_NIGHT_MODE_KEY to systemDark),
        )
        applyThemeChrome(DshThemeChrome.resolveIsDark(this))
    }

    fun reapplyThemeChrome() = applyThemeChrome(currentIsDark, forceContainers = true)

    /** 由 [com.example.dsh.module.KRDshThemeModule] 在 Kuikly 侧切换主题后调用。 */
    fun applyThemeChrome(isDark: Boolean, forceContainers: Boolean = false) {
        val containersReady = ::hrContainerView.isInitialized
        if (!forceContainers && currentIsDark == isDark && (!containersReady || chromePaintedWithContainers)) {
            return
        }
        currentIsDark = isDark
        // hr_loading / hr_error 叠在容器之上，必须保持透明，只给窗口与容器上色。
        val containers = if (containersReady) {
            arrayOf(window.decorView.findViewById(android.R.id.content), hrContainerView)
        } else {
            emptyArray()
        }
        DshThemeChrome.apply(this, isDark, *containers)
        chromePaintedWithContainers = containersReady
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
            moduleExport(KRDshSseModule.MODULE_NAME) {
                KRDshSseModule()
            }
            moduleExport(KRDshThemeModule.MODULE_NAME) {
                KRDshThemeModule()
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
        val systemDark = DshThemeChrome.systemIsDark(this)
        lastSystemDark = systemDark
        param[DshThemeChrome.IS_NIGHT_MODE_KEY] = systemDark
        param["databaseDir"] = java.io.File(KRApplication.application.filesDir.parentFile, "databases").apply {
            if (!exists()) mkdirs()
        }.absolutePath
        return param
    }

    private fun argsToMap(): MutableMap<String, Any> {
        val jsonStr = intent.getStringExtra(KEY_PAGE_DATA) ?: return mutableMapOf()
        return JSONObject(jsonStr).toMap()
    }

    private fun setupImmersiveMode() {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.TRANSPARENT
            // 状态栏图标深浅由 DshThemeChrome.apply 按主题决定，这里只保留布局相关 flag。
            decorView.systemUiVisibility = decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    companion object {

        private const val KEY_PAGE_NAME = "pageName"
        private const val KEY_PAGE_DATA = "pageData"

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
