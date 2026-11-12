package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.model.DshModelOption

/** Model label in the composer and the model picker sheet. */

class DshModelPickerController(private val ctx: DshHomeContext) : PagerScope by ctx {
    var visible by observable(false)
        private set
    var busy by observable(false)
        private set
    var error by observable("")
        private set
    var selectedLabel by observable("选择模型")
        private set
    val options by observableList<DshModelOption>()
    private val requests = DshControllerRequestScope(ctx)
    private var selection: DshControllerRequestScope.Request? = null
    private var reloadAfterSelection = false
    private var displayedClient: DshHostClient? = null
    private var displayedSessionId = ""

    fun load(sessionId: String) = load(sessionId, "")

    private fun load(sessionId: String, selectionError: String) {
        if (requests.closed) return
        if (selection?.let(requests::accepts) == true) {
            reloadAfterSelection = true
            return
        }
        val request = requests.begin(sessionId) ?: run {
            busy = false
            options.clear()
            selectedLabel = "选择模型"
            displayedClient = null
            error = "远程 DSH 尚未就绪"
            return
        }
        selection = null
        reloadAfterSelection = false
        if (displayedClient !== request.client || displayedSessionId != sessionId) {
            options.clear()
            selectedLabel = "选择模型"
            displayedClient = request.client
            displayedSessionId = sessionId
        }
        busy = true
        error = selectionError
        request.client.loadModels(sessionId, { loaded ->
            if (!requests.owns(request)) return@loadModels
            busy = false
            if (!requests.accepts(request)) return@loadModels
            selectedLabel = loaded.current.name
            options.clear()
            options.addAll(loaded.options)
            error = if (loaded.routable) selectionError else "当前模型不可用，请选择其他模型。"
        }, { message ->
            if (!requests.owns(request)) return@loadModels
            busy = false
            if (!requests.accepts(request)) return@loadModels
            error = message
        })
    }
    fun open() {
        if (requests.closed) return
        visible = true
        busy = true
        error = ""
        load(ctx.activeSessionId)
    }

    fun close() {
        requests.invalidate()
        selection = null
        reloadAfterSelection = false
        busy = false
        visible = false
    }

    fun select(option: DshModelOption) {
        if (busy || requests.closed) return
        if (displayedClient !== ctx.hostClient || displayedSessionId != ctx.activeSessionId) {
            load(ctx.activeSessionId)
            return
        }
        if (options.none { it.provider == option.provider && it.model == option.model }) return
        val request = requests.begin(ctx.activeSessionId) ?: run {
            error = "远程 DSH 尚未就绪"
            return
        }
        selection = request
        busy = true
        error = ""
        request.client.selectModel(ctx.activeSessionId, option, { selected ->
            if (!requests.owns(request)) return@selectModel
            busy = false
            selection = null
            if (!requests.accepts(request)) return@selectModel
            selectedLabel = selected.name
            visible = false
            val current = options.toList()
            options.clear()
            options.addAll(current.map {
                it.copy(selected = it.provider == selected.provider && it.model == selected.model)
            })
            if (reloadAfterSelection) load(ctx.activeSessionId)
        }, { message ->
            if (!requests.owns(request)) return@selectModel
            busy = false
            selection = null
            if (!requests.accepts(request)) return@selectModel
            error = message
            if (reloadAfterSelection) load(ctx.activeSessionId, message)
        })
    }

    fun dispose() {
        requests.close()
        selection = null
        reloadAfterSelection = false
        busy = false
        visible = false
        options.clear()
        displayedClient = null
    }
}
