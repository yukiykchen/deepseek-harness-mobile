package com.example.dsh.module

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.example.dsh.KRApplication
import com.example.dsh.KuiklyRenderActivity
import com.example.dsh.ssh.DshSshForegroundService
import com.example.dsh.ssh.DshSshKeyStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

class KRBridgeModule : KuiklyRenderBaseModule() {
    private var navigationBarColorBeforeDim: Int? = null
    private var navigationBarContrastBeforeDim: Boolean? = null
    private var sshKeyCallback: KuiklyRenderCallback? = null

    /** Keeps the offscreen print WebView alive until the print adapter takes over. */
    private var printWebView: android.webkit.WebView? = null

    init {
        activeInstance = this
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "ssoRequest" -> {
                ssoRequest(params, callback)
            }

            "showAlert" -> {
                showAlert(params, callback)
            }

            "closePage" -> {
                closePage(params)
            }

            "openPage" -> {
                openPage(params)
            }

            "copyToPasteboard" -> {
                copyToPasteboard(params)
            }

            "toast" -> {
                toast(params)
            }

            "log" -> {
                log(params)
            }

            "reportDT" -> {
                reportDT(params)
            }

            "reportRealtime" -> {
                reportRealtime(params)
            }

            "qqLiveSSORequest" -> {
                qqLiveSSORequest(params, callback)
            }

            "localServeTime" -> {
                localServeTime(params, callback)
            }

            "currentTimestamp" -> {
                currentTimestamp(params)
            }

            "dateFormatter" -> {
                dateFormatter(params)
            }

            "closeKeyboard" -> {
                closeKeyboard()
            }

            "setSystemBarsDimmed" -> {
                setSystemBarsDimmed(params)
            }

            "setStatusBarStyle" -> {
                setStatusBarStyle(params)
            }

            "shareText" -> {
                shareText(params)
            }

            "shareHtml" -> shareHtml(params)
            "printHtml" -> printHtml(params)

            "pickSshKey" -> pickSshKey(callback)
            "importSshKey" -> importSshKey(params, callback)
            "validateSshKey" -> validateSshKey(params, callback)
            "deleteSshKey" -> deleteSshKey(params)
            "startSshKeepAlive" -> startSshKeepAlive()
            "stopSshKeepAlive" -> stopSshKeepAlive()
            "mintAuthCookie" -> mintAuthCookie(params, callback)

            else -> callback?.invoke(
                mapOf(
                    "code" to -1,
                    "message" to "方法不存在"
                )
            )
        }
    }

    private fun reportRealtime(params: String?) {
    }

    private fun reportDT(params: String?) {
    }

    private fun log(params: String?) {
        if (params == null) {
            return
        }

        val paramJSON = JSONObject(params)
        Log.i("KuiklyRender", paramJSON.optString("content"))
    }

    private fun toast(params: String?) {
        if (params == null) {
            return
        }
        val paramJSON = JSONObject(params)
        Toast.makeText(
            KRApplication.application,
            paramJSON.optString("content"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun copyToPasteboard(params: String?) {
        if (params == null) {
            return
        }

        val paramJSON = JSONObject(params)
        (context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            it.setPrimaryClip(ClipData.newPlainText(MODULE_NAME, paramJSON.optString("content")))
        }
    }

    private fun openPage(params: String?) {
        if (params == null) {
            return
        }
        val ctx = context ?: return
        val paramJSON = JSONObject(params)
        val url = paramJSON.optString("url")
    }

    private fun closePage(params: String?) {
        activity?.finish()
    }

    private fun showAlert(params: String?, callback: KuiklyRenderCallback?) {
        if (params == null) {
            return
        }
        val paramJSON = JSONObject(params)
        val titleText = paramJSON.optString("title")
        val message = paramJSON.optString("message")
        val buttons = paramJSON.optJSONArray("buttons") ?: JSONArray()
    }

    private fun ssoRequest(params: String?, callback: KuiklyRenderCallback?) {}

    private fun qqLiveSSORequest(params: String?, callback: KuiklyRenderCallback?) {
    }

    private fun localServeTime(params: String?, callback: KuiklyRenderCallback?) {
        val time = (System.currentTimeMillis() / 1000.0)
        callback?.invoke(
            mapOf(
                "time" to time
            )
        )
    }

    private fun currentTimestamp(params: String?): String {
        return (System.currentTimeMillis()).toString()
    }

    private fun dateFormatter(params: String?): String {
        val paramJSONObject = JSONObject(params ?: "{}")
        val data = Date(paramJSONObject.optLong("timeStamp"))
        val format = SimpleDateFormat(paramJSONObject.optString("format"))
        return format.format(data)
    }

    private fun closeKeyboard(): String {
        activity?.runOnUiThread {
            val focusedView = activity?.currentFocus
            focusedView?.clearFocus()
            val inputMethodManager = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputMethodManager?.hideSoftInputFromWindow(focusedView?.windowToken, 0)
        }
        return "true"
    }

    private fun setSystemBarsDimmed(params: String?) {
        val dimmed = JSONObject(params ?: "{}").optBoolean("dimmed")
        activity?.runOnUiThread {
            val window = activity?.window ?: return@runOnUiThread
            if (dimmed) {
                if (navigationBarColorBeforeDim == null) {
                    navigationBarColorBeforeDim = window.navigationBarColor
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        navigationBarContrastBeforeDim = window.isNavigationBarContrastEnforced
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                window.navigationBarColor = Color.rgb(153, 153, 153)
            } else {
                restoreNavigationBar()
            }
        }
    }

    private fun shareText(params: String?) {
        val json = JSONObject(params ?: "{}")
        val text = json.optString("text")
        if (text.isEmpty()) return
        val title = json.optString("title")
        val act = activity ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        runCatching { act.startActivity(Intent.createChooser(send, title.ifEmpty { null })) }
    }

    /** Shares an `.html` file through the same FileProvider the camera capture uses. */
    private fun shareHtml(params: String?) {
        val json = JSONObject(params ?: "{}")
        val html = json.optString("html")
        if (html.isEmpty()) return
        val title = json.optString("title")
        val act = activity ?: return
        val file = runCatching {
            val dir = java.io.File(act.cacheDir, "exports").apply { mkdirs() }
            java.io.File(dir, "${exportFileName(title)}.html").apply { writeText(html) }
        }.getOrNull() ?: return
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", file)
        }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { act.startActivity(Intent.createChooser(send, title.ifEmpty { null })) }
    }

    /**
     * Renders the transcript in an offscreen WebView and hands it to the print framework,
     * whose built-in "Save as PDF" destination is the PDF export. The WebView has to
     * outlive this call, so the adapter keeps the only reference until printing starts.
     */
    private fun printHtml(params: String?) {
        val json = JSONObject(params ?: "{}")
        val html = json.optString("html")
        if (html.isEmpty()) return
        val jobName = json.optString("title").ifEmpty { "DSH conversation" }
        val act = activity ?: return
        act.runOnUiThread {
            val webView = android.webkit.WebView(act)
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                    val printManager = act.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
                    runCatching {
                        printManager?.print(
                            jobName,
                            view.createPrintDocumentAdapter(jobName),
                            android.print.PrintAttributes.Builder().build(),
                        )
                    }
                    printWebView = null
                }
            }
            printWebView = webView
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }

    /** Safe, recognisable file stem for an exported transcript. */
    private fun exportFileName(title: String): String {
        val cleaned = title.trim().map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("").trim('-').take(40)
        return cleaned.ifEmpty { "dsh-conversation" }
    }

    /** Dark app themes need light status-bar glyphs, and vice versa. */
    private fun setStatusBarStyle(params: String?) {
        val dark = JSONObject(params ?: "{}").optInt("dark") == 1
        activity?.runOnUiThread {
            (activity as? KuiklyRenderActivity)?.applyThemeChrome(dark)
        }
    }

    private fun pickSshKey(callback: KuiklyRenderCallback?) {
        sshKeyCallback = callback
        val act = activity ?: run {
            callback?.invoke(mapOf("uri" to ""))
            return
        }
        // SAF 选择器本身不需要调用方持有存储权限，但小米/MIUI 等国产 ROM 的文件选择器
        // 在调用方未获得存储读取权限时不会列出本地文件（列表为空、没有可选中文件），也
        // 不会替调用方弹出授权框。因此在 Android 12L 及以下先申请一次读取权限再打开选择器。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            act.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            act.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_SSH_KEY_PERMISSION,
            )
            return
        }
        launchSshKeyPicker()
    }

    private fun launchSshKeyPicker() {
        val act = activity
        if (act == null) {
            sshKeyCallback?.invoke(mapOf("uri" to ""))
            sshKeyCallback = null
            return
        }
        // 私钥可能是 id_rsa 这类无扩展名文件，也可能是 *.pem / *.key / *.txt。
        // 用 application/octet-stream 之类的具体 MIME 过滤时，小米/MIUI 的 DocumentsUI
        // 会把不匹配的文件置灰或直接过滤掉，表现为“没有任何文件可以选中”。这里放开为
        // */*（私钥是否合法由导入后的解析校验负责），保证任何来源的文件都可选。
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            act.startActivityForResult(picker, REQUEST_SSH_KEY)
        } catch (e: ActivityNotFoundException) {
            // 个别 ROM 缺少 DocumentsUI（ACTION_OPEN_DOCUMENT 无人处理），退回老式选择器。
            Log.w("KuiklyRender", "ACTION_OPEN_DOCUMENT has no handler, falling back to ACTION_GET_CONTENT", e)
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try {
                act.startActivityForResult(fallback, REQUEST_SSH_KEY)
            } catch (e2: ActivityNotFoundException) {
                Log.w("KuiklyRender", "no system file picker available", e2)
                Toast.makeText(KRApplication.application, "无法打开文件选择器，请检查系统文件管理应用", Toast.LENGTH_SHORT).show()
                sshKeyCallback?.invoke(mapOf("uri" to ""))
                sshKeyCallback = null
            }
        }
    }

    private fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_SSH_KEY_PERMISSION) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(
                KRApplication.application,
                "未授予存储读取权限：若文件选择器中看不到文件，请在系统设置中允许“文件/存储”访问后重试",
                Toast.LENGTH_LONG,
            ).show()
        }
        // 授权与否都继续打开系统文件选择器（Android 原生 SAF 不依赖该权限）。
        launchSshKeyPicker()
    }

    private fun importSshKey(params: String?, callback: KuiklyRenderCallback?) {
        val uri = Uri.parse(JSONObject(params ?: "{}").optString("uri"))
        val bytes = runCatching {
            context?.contentResolver?.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            callback?.invoke(mapOf("ok" to false, "message" to "无法读取 SSH 私钥"))
            return
        }
        val keyId = runCatching { DshSshKeyStore(requireNotNull(context)).importBytes("ssh-key", bytes) }.getOrNull()
        bytes.fill(0)
        if (keyId == null) {
            callback?.invoke(mapOf("ok" to false, "message" to "无法导入 SSH 私钥"))
            return
        }
        callback?.invoke(mapOf("ok" to true, "keyId" to keyId))
    }

    private fun deleteSshKey(params: String?) {
        DshSshKeyStore(requireNotNull(context)).delete(JSONObject(params ?: "{}").optString("keyId"))
    }

    private fun validateSshKey(params: String?, callback: KuiklyRenderCallback?) {
        val keyId = JSONObject(params ?: "{}").optString("keyId")
        val valid = runCatching { DshSshKeyStore(requireNotNull(context)).validateKey(keyId) }.getOrDefault(false)
        callback?.invoke(mapOf("valid" to valid))
    }

    /**
     * DSH >= 0.1.2 browser-session bootstrap: `GET {baseUrl}/?token=` answers
     * `303` + `Set-Cookie`. Redirects must stay disabled or the cookie is lost
     * when the client follows to `/` without it.
     */
    private fun mintAuthCookie(params: String?, callback: KuiklyRenderCallback?) {
        val json = JSONObject(params ?: "{}")
        val baseUrl = json.optString("baseUrl").trimEnd('/')
        val token = json.optString("token")
        val bearer = json.optString("bearer")
        if (baseUrl.isEmpty() || token.isEmpty()) {
            callback?.invoke(mapOf("cookie" to "", "status" to 0, "message" to "missing baseUrl or token"))
            return
        }
        Thread {
            val result = runCatching {
                val request = okhttp3.Request.Builder()
                    .url("$baseUrl/?token=${Uri.encode(token)}")
                    .get()
                    .apply { if (bearer.isNotEmpty()) header("Authorization", "Bearer $bearer") }
                    .build()
                AUTH_CLIENT.newCall(request).execute().use { response ->
                    val cookies = response.headers("Set-Cookie")
                        .flatMap { splitSetCookie(it) }
                        .mapNotNull { it.split(";").firstOrNull()?.trim()?.takeIf { pair -> pair.contains('=') } }
                    val cookie = cookies.firstOrNull { it.startsWith("dsh-auth-") } ?: cookies.firstOrNull().orEmpty()
                    mapOf(
                        "cookie" to cookie,
                        "status" to response.code,
                        "message" to if (cookie.isEmpty()) "HTTP ${response.code}: no session cookie returned" else "",
                    )
                }
            }.getOrElse { error ->
                mapOf("cookie" to "", "status" to 0, "message" to (error.message ?: "request failed"))
            }
            activity?.runOnUiThread { callback?.invoke(result) } ?: callback?.invoke(result)
        }.start()
    }

    /** The relay gateway may serialize a multi-value header as a JSON array string. */
    private fun splitSetCookie(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.startsWith("[")) {
            return runCatching {
                val array = JSONArray(trimmed)
                (0 until array.length()).map { array.optString(it) }
            }.getOrDefault(listOf(value))
        }
        return listOf(value)
    }

    private fun startSshKeepAlive() {
        val intent = Intent(context, DshSshForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context?.startForegroundService(intent) else context?.startService(intent)
    }

    private fun stopSshKeepAlive() {
        context?.stopService(Intent(context, DshSshForegroundService::class.java))
    }

    private fun restoreNavigationBar() {
        val window = activity?.window ?: return
        navigationBarColorBeforeDim?.let { window.navigationBarColor = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            navigationBarContrastBeforeDim?.let { window.isNavigationBarContrastEnforced = it }
        }
        navigationBarColorBeforeDim = null
        navigationBarContrastBeforeDim = null
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        restoreNavigationBar()
        super.onDestroy()
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_SSH_KEY) return
        val uri = if (resultCode == android.app.Activity.RESULT_OK) data?.data?.toString().orEmpty() else ""
        sshKeyCallback?.invoke(mapOf("uri" to uri))
        sshKeyCallback = null
    }

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
        const val REQUEST_SSH_KEY = 4091
        const val REQUEST_SSH_KEY_PERMISSION = 4092
        private var activeInstance: KRBridgeModule? = null
        private val AUTH_CLIENT = okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        fun dispatchActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            activeInstance?.onActivityResult(requestCode, resultCode, data)
        }

        fun dispatchRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
            activeInstance?.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
}

private fun JSONObject.toMap(): Map<Any, Any> {
    val map = mutableMapOf<Any, Any>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val v = opt(key)) {
            is JSONObject -> {
                map[key] = v.toMap()
            }

            else -> {
                v?.also {
                    map[key] = it
                }
            }
        }
    }
    return map
}
