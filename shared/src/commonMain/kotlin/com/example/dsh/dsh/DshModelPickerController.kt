package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList

/** Model label in the composer and the model picker sheet. */
internal class DshModelPickerController(private val ctx: DshHomeContext) : PagerScope by ctx {
    var visible by observable(false)
    var busy by observable(false)
        private set
    var error by observable("")
        private set
    var selectedLabel by observable("选择模型")
        private set
    val options by observableList<DshModelOption>()

    private val requests = DshRequestVersion()

    fun reset() {
        requests.invalidate()
        busy = false
        visible = false
        error = ""
        selectedLabel = "选择模型"
        options.clear()
    }

    fun load(sessionId: String) {
        val request = ctx.captureRequest(sessionId) ?: return
        val version = requests.next()
        request.client.loadModels(sessionId, { loaded ->
            if (!request.isCurrent() || !requests.accepts(version)) return@loadModels
            selectedLabel = loaded.current.name
            options.clear()
            options.addAll(loaded.options)
            busy = false
            error = if (loaded.routable) "" else "当前模型不可用，请选择其他模型。"
        }, { message ->
            if (!request.isCurrent() || !requests.accepts(version)) return@loadModels
            busy = false
            error = message
        })
    }

    fun open() {
        visible = true
        busy = true
        error = ""
        load(ctx.activeSessionId)
    }

    fun select(option: DshModelOption) {
        if (busy) return
        val request = ctx.captureRequest() ?: return
        val version = requests.next()
        busy = true
        error = ""
        request.client.selectModel(request.sessionId, option, { selected ->
            if (!request.isCurrent() || !requests.accepts(version)) return@selectModel
            selectedLabel = selected.name
            busy = false
            visible = false
            val current = options.toList()
            options.clear()
            options.addAll(current.map {
                it.copy(selected = it.provider == selected.provider && it.model == selected.model)
            })
        }, { message ->
            if (!request.isCurrent() || !requests.accepts(version)) return@selectModel
            busy = false
            error = message
        })
    }
}
