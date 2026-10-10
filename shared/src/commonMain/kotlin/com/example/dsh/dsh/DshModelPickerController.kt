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

    fun load(sessionId: String) {
        val client = ctx.hostClient ?: return
        client.loadModels(sessionId, { loaded ->
            if (ctx.activeSessionId != sessionId) return@loadModels
            selectedLabel = loaded.current.name
            options.clear()
            options.addAll(loaded.options)
            busy = false
            error = if (loaded.routable) "" else "当前模型不可用，请选择其他模型。"
        }, { message ->
            if (ctx.activeSessionId != sessionId) return@loadModels
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
        val client = ctx.hostClient ?: return
        busy = true
        error = ""
        client.selectModel(ctx.activeSessionId, option, { selected ->
            selectedLabel = selected.name
            busy = false
            visible = false
            val current = options.toList()
            options.clear()
            options.addAll(current.map {
                it.copy(selected = it.provider == selected.provider && it.model == selected.model)
            })
        }, { message ->
            busy = false
            error = message
        })
    }
}
