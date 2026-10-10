package com.example.dsh.dsh

import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.InputView

/** "修改电脑端 DSH 的 API Key" modal: draft, validation and the host write. */
internal class DshCredentialController(
    private val ctx: DshHomeContext,
    private val onVisibilityChanged: (visible: Boolean) -> Unit,
    /** Runs after the modal closed on a successful save. */
    private val onSaved: () -> Unit,
) : DshHomeContext by ctx {
    var visible by observable(false)
        private set
    var busy by observable(false)
        private set
    var error by observable("")
        private set
    var title by observable("添加一个 API Key 开始使用")
        private set
    private var draft by observable("")
    private var inputView: InputView? = null

    fun open() {
        title = "修改电脑端 DSH 的 API Key"
        error = ""
        draft = ""
        show(true)
    }

    fun close() = show(false)

    fun bindInput(view: InputView?) {
        inputView = view
        view?.setText(draft)
    }

    fun updateDraft(text: String) {
        draft = text
        error = ""
    }

    fun save() {
        val key = draft.trim()
        when {
            key.isEmpty() -> {
                error = "请输入 API Key 后继续。"
                return
            }
            key.any { it.code !in 0x21..0x7E } -> {
                error = "API Key 格式错误，请检查后重试。"
                return
            }
        }
        val client = hostClient
        if (client == null) {
            error = "远程 DSH 尚未就绪"
            return
        }
        busy = true
        error = ""
        client.saveDeepSeekApiKey(key, {
            setTimeout(pagerId, 0) {
                draft = ""
                inputView?.setText("")
                busy = false
                show(false)
                onSaved()
            }
        }, { message ->
            setTimeout(pagerId, 0) {
                busy = false
                error = "无法修改电脑端 DSH：$message"
            }
        })
    }

    private fun show(next: Boolean) {
        visible = next
        onVisibilityChanged(next)
    }
}
