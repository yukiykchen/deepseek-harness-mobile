package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout

/** "修改电脑端 DSH 的 API Key" modal: draft, validation and the host write. */

class DshCredentialController(
    private val ctx: DshHomeContext,
    private val onVisibilityChanged: (visible: Boolean) -> Unit,
    /** Runs after the modal closed on a successful save. */
    private val onSaved: () -> Unit,
    private val postToPage: (() -> Unit) -> Unit = { setTimeout(ctx.pagerId, 0, it) },
    private val scheduleTimeout: (Int, () -> Unit) -> Unit = { delay, action ->
        com.tencent.kuikly.core.timer.setTimeout(ctx.pagerId, delay, action)
    },
) : DshHomeContext by ctx {
    var visible by observable(false)
        private set
    var busy by observable(false)
        private set
    private var errorValue by observable("")
    fun error(): String = errorValue
    var title by observable("添加一个 API Key 开始使用")
        private set
    var draft by observable("")
    private var credentialRef: String? = null
    private var credentialsWritable = false
    private var setupFailed = false
    private var setupGeneration = 0L
    private val requests = DshControllerRequestScope(ctx)

    fun open() {
        if (requests.closed) return
        requests.invalidate()
        busy = false
        title = "检查电脑端 DSH 的凭据配置"
        errorValue = ""
        draft = ""
        credentialRef = null
        credentialsWritable = false
        setupFailed = false
        val generation = ++setupGeneration
        show(true)
        val setupRequest = requests.begin() ?: run {
            errorValue = "远程 DSH 尚未就绪"
            return
        }
        busy = true
        scheduleSetupTimeout(generation)
        setupRequest.client.loadCredentialSetup({ setup ->
            postToPage {
                if (generation != setupGeneration || !requests.owns(setupRequest)) return@postToPage
                busy = false
                if (!requests.accepts(setupRequest)) {
                    errorValue = "连接已更新，请重新打开凭据设置"
                    return@postToPage
                }
                setupFailed = false
                title = "修改电脑端 DSH 的 API Key"
                credentialRef = setup.credentialRef
                credentialsWritable = setup.writable
                if (!setup.providerAvailable) errorValue = "DeepSeek Provider 未启用"
                else if (!setup.writable) errorValue = "电脑端 DSH 的 API Key 当前不可写"
            }
        }, { message ->
            postToPage {
                if (generation != setupGeneration || !requests.owns(setupRequest)) return@postToPage
                busy = false
                if (!requests.accepts(setupRequest)) return@postToPage
                setupFailed = true
                errorValue = "无法读取电脑端 DSH 的凭据配置：$message"
            }
        })
    }
    fun close() {
        requests.invalidate()
        setupGeneration++
        busy = false
        draft = ""
        errorValue = ""
        credentialRef = null
        credentialsWritable = false
        setupFailed = false
        show(false)
    }
    fun updateDraft(text: String) {
        if (!visible || requests.closed) return
        draft = text
        if (credentialsWritable) errorValue = ""
    }
    fun save() {
        if (busy || !visible || requests.closed) return
        if (setupFailed) return
        val ref = credentialRef ?: run {
            errorValue = "凭据配置尚未加载完成"
            return
        }
        if (!credentialsWritable) {
            errorValue = "电脑端 DSH 的 API Key 当前不可写"
            return
        }
        val key = draft.trim()
        when {
            key.isEmpty() -> {
                errorValue = "请输入 API Key 后继续。"
                return
            }
            key.any { it.code !in 0x21..0x7E } -> {
                errorValue = "API Key 格式错误，请检查后重试。"
                return
            }
        }
        val request = requests.begin()
        if (request == null) {
            errorValue = "远程 DSH 尚未就绪"
            return
        }
        busy = true
        errorValue = ""
        request.client.saveApiKey(ref, key, {
            postToPage {
                if (!requests.owns(request)) return@postToPage
                busy = false
                if (!requests.accepts(request)) return@postToPage
                if (draft.trim() != key) {
                    errorValue = "密钥已更新，当前编辑尚未保存"
                    return@postToPage
                }
                draft = ""
                show(false)
                onSaved()
            }
        }, { message ->
            postToPage {
                if (!requests.owns(request)) return@postToPage
                busy = false
                if (!requests.accepts(request)) return@postToPage
                errorValue = "无法修改电脑端 DSH：$message"
            }
        })
    }

    private fun show(next: Boolean) {
        visible = next
        onVisibilityChanged(next)
    }

    fun dispose() {
        requests.close()
        setupGeneration++
        busy = false
        visible = false
        draft = ""
        credentialRef = null
        credentialsWritable = false
        setupFailed = false
    }

    private fun scheduleSetupTimeout(generation: Long) {
        scheduleTimeout(30_000) {
            if (generation != setupGeneration || !busy) return@scheduleTimeout
            busy = false
            setupFailed = true
            credentialRef = null
            credentialsWritable = false
                errorValue = "凭据配置加载超时，请重新打开"
        }
    }
}
