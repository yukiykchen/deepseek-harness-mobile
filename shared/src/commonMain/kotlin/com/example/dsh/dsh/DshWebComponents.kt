package com.example.dsh.dsh

import com.example.dsh.theme.theme
import com.example.dsh.theme.tokens
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** The shared compact disclosure chrome used by context and tool rows. */
internal class DshDisclosureRowView : ComposeView<DshDisclosureRowAttr, ComposeEvent>() {
    override fun createAttr(): DshDisclosureRowAttr = DshDisclosureRowAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    if (ctx.attr.chrome) {
                        padding(8f, 10f, 8f, 10f)
                        borderRadius(8f)
                        backgroundColor(
                            when {
                                ctx.attr.errorSummary -> tokens.error.background
                                ctx.attr.running -> tokens.running.background
                                else -> tokens.surfaceVariant
                            },
                        )
                        border(Border(1f, BorderStyle.SOLID, tokens.divider))
                    }
                }
                View {
                    attr {
                        height(36f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    vif({ ctx.attr.iconAsset.isNotEmpty() }) {
                        Image {
                            attr {
                                src(ImageUri.commonAssets(ctx.attr.iconAsset))
                                size(14f, 14f)
                                tintColor(
                                    when {
                                        ctx.attr.errorSummary -> tokens.error.foreground
                                        ctx.attr.running -> tokens.running.foreground
                                        else -> tokens.icon
                                    },
                                )
                            }
                        }
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("chevron-down.svg"))
                            size(14f, 14f)
                            marginLeft(if (ctx.attr.iconAsset.isNotEmpty()) 4f else 0f)
                            transform(Rotate(if (ctx.attr.open) 0f else -90f))
                            tintColor(tokens.icon)
                        }
                    }
                    Text {
                        attr {
                            text(ctx.attr.title)
                            marginLeft(7f)
                            fontSize(13f)
                            fontWeightMedium()
                            color(tokens.primaryText)
                        }
                    }
                    vif({ ctx.attr.summary.isNotEmpty() }) {
                        Text {
                            attr {
                                text("·")
                                marginLeft(7f)
                                marginRight(7f)
                                fontSize(13f)
                                color(tokens.tertiaryText)
                            }
                        }
                        Text {
                            attr {
                            text(ctx.attr.summary)
                                flex(1f)
                                lines(1)
                                fontSize(13f)
                                color(if (ctx.attr.errorSummary) tokens.error.foreground else tokens.tertiaryText)
                            }
                        }
                    }
                    vif({ ctx.attr.summary.isEmpty() }) {
                        View { attr { flex(1f) } }
                    }
                    DshTapTarget {
                        if (ctx.attr.expandable) {
                            ctx.attr.open = !ctx.attr.open
                            ctx.attr.onToggle()
                        }
                    }
                }
                vif({ ctx.attr.open }) {
                    View {
                        attr {
                            marginTop(6f)
                            marginBottom(8f)
                            flexDirectionColumn()
                        }
                        vif({ ctx.attr.jsonContent.isNotEmpty() }) {
                            DshJsonTree {
                                attr {
                                    content = ctx.attr.jsonContent
                                    this.isExpanded = ctx.attr.isJsonNodeExpanded
                                    this.onToggle = ctx.attr.onToggleJsonNode
                                }
                            }
                        }
                        vif({ ctx.attr.jsonContent.isEmpty() && ctx.attr.body.isNotEmpty() }) {
                            DshLongText {
                                attr {
                                    content = ctx.attr.body
                                    expanded = ctx.attr.bodyExpanded
                                    maxLines = ctx.attr.maxBodyLines
                                    error = ctx.attr.errorSummary
                                    this.onToggle = {
                                        ctx.attr.bodyExpanded = !ctx.attr.bodyExpanded
                                        ctx.attr.onToggleBody()
                                    }
                                }
                            }
                        }
                        vif({ ctx.attr.jsonContent.isEmpty() && ctx.attr.body.isEmpty() }) {
                            Text {
                                attr {
                                    text("暂无输出")
                                    fontSize(12f)
                                    color(tokens.tertiaryText)
                                    margin(10f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal class DshDisclosureRowAttr : ComposeAttr() {
    var title: String by observable("")
    var summary: String by observable("")
    var body: String by observable("")
    var iconAsset: String by observable("")
    var errorSummary: Boolean by observable(false)
    var open: Boolean by observable(false)
    var expandable: Boolean by observable(false)
    var onToggle: () -> Unit by observable({})
    var bodyExpanded: Boolean by observable(false)
    var onToggleBody: () -> Unit by observable({})
    var maxBodyLines: Int by observable(8)
    var jsonContent: String by observable("")
    var isJsonNodeExpanded: (String) -> Boolean by observable({ false })
    var onToggleJsonNode: (String) -> Unit by observable({})
    var chrome: Boolean by observable(false)
    var running: Boolean by observable(false)
}

/** Second-level disclosure for long terminal/read/diff bodies. */
internal class DshLongTextView : ComposeView<DshLongTextAttr, ComposeEvent>() {
    override fun createAttr(): DshLongTextAttr = DshLongTextAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val expanded = ctx.attr.expanded
        val hidden = ctx.attr.content.lineSequence().count() - ctx.attr.maxLines
        val capped = hidden > 0 && !expanded
        val joined = if (expanded) {
            ctx.attr.content
        } else {
            ctx.attr.content.dshCollapsedLines(ctx.attr.maxLines).joinToString("\n")
        }
        return {
            View {
                attr {
                    flexDirectionColumn()
                    borderRadius(8f)
                    backgroundColor(Color(theme.codeColors.codeBlockBackground))
                    border(Border(1f, BorderStyle.SOLID, tokens.divider))
                    padding(8f)
                }
                Scroller {
                    attr {
                        height(
                            when {
                                ctx.attr.maxHeight > 0f -> ctx.attr.maxHeight.coerceAtMost(280f)
                                expanded -> 280f
                                else -> (ctx.attr.maxLines * 18f).coerceAtMost(280f)
                            },
                        )
                    }
                    Text {
                        attr {
                            text(joined)
                            fontSize(12f)
                            lineHeight(18f)
                            fontFamily("monospace")
                            color(if (ctx.attr.error) tokens.error.foreground else tokens.primaryText)
                        }
                    }
                }
                vif({ capped }) {
                    View {
                        attr {
                            height(20f)
                            marginTop(6f)
                            justifyContentCenter()
                        }
                        Text {
                            attr {
                                text("… 其余 $hidden 行")
                                fontSize(12f)
                                color(tokens.primary)
                            }
                        }
                        DshTapTarget {
                            ctx.attr.expanded = true
                            ctx.attr.onToggle()
                        }
                    }
                }
                vif({ expanded && hidden > 0 }) {
                    View {
                        attr {
                            height(20f)
                            marginTop(6f)
                            justifyContentCenter()
                        }
                        Text {
                            attr {
                                text("收起")
                                fontSize(12f)
                                color(tokens.primary)
                            }
                        }
                        DshTapTarget {
                            ctx.attr.expanded = false
                            ctx.attr.onToggle()
                        }
                    }
                }
            }
        }
    }
}

internal class DshLongTextAttr : ComposeAttr() {
    var content: String by observable("")
    var expanded: Boolean by observable(false)
    var maxLines: Int by observable(16)
    var maxHeight: Float by observable(0f)
    var error: Boolean by observable(false)
    var onToggle: () -> Unit by observable({})
}

internal fun ViewContainer<*, *>.DshLongText(init: DshLongTextView.() -> Unit) {
    addChild(DshLongTextView(), init)
}

internal fun ViewContainer<*, *>.DshDisclosureRow(init: DshDisclosureRowView.() -> Unit) {
    addChild(DshDisclosureRowView(), init)
}

/** Client-local disclosure state; it never writes back to the Host. */
internal data class DshDisclosureState(
    val key: String,
    val open: Boolean = false,
)

internal data class DshToolCardModel(
    val key: String,
    val title: String,
    val summary: String,
    val input: String?,
    val output: String?,
    val error: String? = null,
    val running: Boolean = false,
)

internal data class DshContextInjectionModel(
    val key: String,
    val sourceLabel: String,
    val summary: String,
    val body: String,
)

internal fun String.dshCollapsedLines(maxLines: Int = 16): List<String> {
    val lines = split('\n')
    if (lines.size <= maxLines) return lines
    val head = (maxLines + 1) / 2
    val tail = maxLines - head
    return lines.take(head) + listOf("… 其余 ${lines.size - maxLines} 行") + lines.takeLast(tail)
}

internal fun dshJsonPreview(value: String): String {
    if (value.length <= 160) return value
    return value.take(148) + "…"
}

internal fun dshParseJsonTree(raw: String): Any? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> null
        }
    }.getOrNull()
}

internal fun dshJsonObjectToMap(value: JSONObject): Map<String, Any?> {
    val map = linkedMapOf<String, Any?>()
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = value.opt(key)
    }
    return map
}

internal fun dshBuildJsonNodes(
    value: Any?,
    key: String = "$",
    depth: Int = 0,
): List<DshJsonNode> = when (value) {
    is JSONObject -> dshBuildJsonNodes(dshJsonObjectToMap(value), key, depth)
    is JSONArray -> dshBuildJsonNodes((0 until value.length()).map { value.opt(it) }, key, depth)
    is Map<*, *> -> {
        if (value.isEmpty()) {
            listOf(DshJsonNode(key, "{}", "{}", emptyList(), depth))
        } else {
            value.entries.flatMap { (childKey, childValue) ->
                val label = childKey?.toString() ?: "null"
                val childNodes = dshBuildJsonNodes(childValue, "$key.$label", depth + 1)
                val preview = dshJsonPreview(childValue?.toString().orEmpty())
                listOf(DshJsonNode("$key.$label", label, preview, childNodes, depth)) + childNodes
            }
        }
    }
    is List<*> -> {
        if (value.isEmpty()) {
            listOf(DshJsonNode(key, "[]", "[]", emptyList(), depth))
        } else {
            value.flatMapIndexed { index, childValue ->
                val childNodes = dshBuildJsonNodes(childValue, "$key[$index]", depth + 1)
                val preview = dshJsonPreview(childValue?.toString().orEmpty())
                listOf(DshJsonNode("$key[$index]", "[$index]", preview, childNodes, depth)) + childNodes
            }
        }
    }
    else -> listOf(DshJsonNode(key, key, value?.toString() ?: "null", emptyList(), depth))
}

internal class DshJsonTreeView : ComposeView<DshJsonTreeAttr, ComposeEvent>() {
    override fun createAttr(): DshJsonTreeAttr = DshJsonTreeAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    marginTop(6f)
                    flexDirectionColumn()
                    padding(8f)
                    borderRadius(8f)
                    backgroundColor(Color(theme.codeColors.codeBlockBackground))
                    border(Border(1f, BorderStyle.SOLID, tokens.divider))
                }
                val parsed = dshParseJsonTree(ctx.attr.content)
                vif({ parsed != null }) {
                    val nodes = com.tencent.kuikly.core.reactive.collection.ObservableList<DshJsonNode>()
                    nodes.addAll(dshBuildJsonNodes(parsed ?: Any()).filter { it.depth == 0 })
                    vfor({ nodes }) { node ->
                        DshJsonNodeRow {
                            attr {
                                this.node = node
                                expanded = ctx.attr.isExpanded(node.key)
                                isNodeExpanded = ctx.attr.isExpanded
                                onToggle = { ctx.attr.onToggle(node.key) }
                                onToggleNode = ctx.attr.onToggle
                            }
                        }
                    }
                }
                vif({ parsed == null }) {
                    Text {
                        attr {
                            text(ctx.attr.content)
                            fontSize(12f)
                            fontFamily("monospace")
                            color(tokens.primaryText)
                        }
                    }
                }
            }
        }
    }
}

internal class DshJsonTreeAttr : ComposeAttr() {
    var content: String by observable("")
    var isExpanded: (String) -> Boolean by observable({ false })
    var onToggle: (String) -> Unit by observable({})
}

internal class DshJsonNodeRowView : ComposeView<DshJsonNodeRowAttr, ComposeEvent>() {
    override fun createAttr(): DshJsonNodeRowAttr = DshJsonNodeRowAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flexDirectionColumn()
                    marginLeft(ctx.attr.node.depth * 10f)
                }
                View {
                    attr { height(28f); flexDirectionRow(); alignItemsCenter() }
                    vif({ ctx.attr.node.children.isNotEmpty() }) {
                        Image {
                            attr {
                                src(ImageUri.commonAssets("chevron-down.svg"))
                                size(12f, 12f)
                                transform(Rotate(if (ctx.attr.expanded) 0f else -90f))
                                tintColor(tokens.icon)
                            }
                        }
                    }
                    Text {
                        attr {
                            text(ctx.attr.node.label)
                            marginLeft(6f)
                            fontSize(12f)
                            fontFamily("monospace")
                            color(tokens.primaryText)
                        }
                    }
                    Text {
                        attr {
                            text(ctx.attr.node.preview)
                            marginLeft(8f)
                            flex(1f)
                            lines(1)
                            fontSize(11f)
                            fontFamily("monospace")
                            color(tokens.secondaryText)
                        }
                    }
                    vif({ ctx.attr.node.children.isNotEmpty() }) {
                        DshTapTarget {
                            ctx.attr.expanded = !ctx.attr.expanded
                            ctx.attr.onToggle()
                        }
                    }
                }
                vif({ ctx.attr.expanded && ctx.attr.node.children.isNotEmpty() }) {
                    val childNodes = com.tencent.kuikly.core.reactive.collection.ObservableList<DshJsonNode>()
                    childNodes.addAll(ctx.attr.node.children.filter { it.depth == ctx.attr.node.depth + 1 })
                    vfor({ childNodes }) { child ->
                        DshJsonNodeRow {
                            attr {
                                this.node = child
                                expanded = ctx.attr.isNodeExpanded(child.key)
                                isNodeExpanded = ctx.attr.isNodeExpanded
                                onToggle = { ctx.attr.onToggleNode(child.key) }
                                onToggleNode = ctx.attr.onToggleNode
                            }
                        }
                    }
                }
            }
        }
    }
}

internal class DshJsonNodeRowAttr : ComposeAttr() {
    var node: DshJsonNode by observable(DshJsonNode("$", "$", "null"))
    var expanded: Boolean by observable(false)
    var onToggle: () -> Unit by observable({})
    var isNodeExpanded: (String) -> Boolean by observable({ false })
    var onToggleNode: (String) -> Unit by observable({})
}

internal fun ViewContainer<*, *>.DshJsonTree(init: DshJsonTreeView.() -> Unit) {
    addChild(DshJsonTreeView(), init)
}

internal fun ViewContainer<*, *>.DshJsonNodeRow(init: DshJsonNodeRowView.() -> Unit) {
    addChild(DshJsonNodeRowView(), init)
}

internal class DshQueueDockView : ComposeView<DshQueueDockAttr, ComposeEvent>() {
    override fun createAttr(): DshQueueDockAttr = DshQueueDockAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val items = ctx.attr.items
        val expanded = ctx.attr.expanded || items.size <= 1 || ctx.attr.editingId.isNotEmpty() || ctx.attr.actionBusy
        return {
            vif({ items.isNotEmpty() }) {
                View {
                    attr {
                        marginBottom(8f)
                        flexDirectionColumn()
                        padding(8f)
                        borderRadius(10f)
                        backgroundColor(tokens.surfaceVariant)
                        border(Border(1f, BorderStyle.SOLID, tokens.divider))
                    }
                    vif({ items.size > 1 }) {
                        View {
                            attr {
                                height(30f)
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            event { click { if (!ctx.attr.actionBusy && ctx.attr.editingId.isEmpty()) ctx.attr.onToggle() } }
                            Text {
                                attr {
                                    text("队列 · ${items.size}")
                                    flex(1f)
                                    fontSize(13f)
                                    color(tokens.primaryText)
                                }
                            }
                            Image {
                                attr {
                                    src(ImageUri.commonAssets("chevron-down.svg"))
                                    size(14f, 14f)
                                    transform(Rotate(if (expanded) 0f else -90f))
                                    tintColor(tokens.icon)
                                }
                            }
                        }
                    }
                    vif({ expanded }) {
                        vfor({ ctx.attr.items }) { item ->
                            View {
                                attr {
                                    minHeight(40f)
                                    flexDirectionRow()
                                    alignItemsCenter()
                                }
                                vif({ ctx.attr.editingId != item.id }) {
                                    Text {
                                        attr {
                                            text(item.preview)
                                            flex(1f)
                                            lines(1)
                                            fontSize(13f)
                                            color(tokens.secondaryText)
                                        }
                                    }
                                    Text {
                                        attr {
                                            text("编辑")
                                            marginLeft(8f)
                                            fontSize(12f)
                                            color(tokens.primary)
                                        }
                                        event { click { if (!ctx.attr.actionBusy) ctx.attr.onEdit(item.id) } }
                                    }
                                    Text {
                                        attr {
                                            text("删除")
                                            marginLeft(10f)
                                            fontSize(12f)
                                            color(tokens.error.foreground)
                                        }
                                        event { click { if (!ctx.attr.actionBusy) ctx.attr.onRemove(item.id) } }
                                    }
                                    Text {
                                        attr {
                                            text("转向")
                                            marginLeft(10f)
                                            fontSize(12f)
                                            color(if (ctx.attr.running) tokens.primary else tokens.tertiaryText)
                                        }
                                        event { click { if (ctx.attr.running && !ctx.attr.actionBusy) ctx.attr.onSteer(item.id) } }
                                    }
                                }
                                vif({ ctx.attr.editingId == item.id }) {
                                    Input {
                                        ref { it.view?.setText(ctx.attr.editingText) }
                                        attr {
                                            flex(1f)
                                            height(32f)
                                            fontSize(13f)
                                            placeholder("编辑队列消息")
                                            placeholderColor(tokens.tertiaryText)
                                        }
                                        event { textDidChange { ctx.attr.onEditingTextChange(it.text) } }
                                    }
                                    Text {
                                        attr {
                                            text("保存")
                                            marginLeft(8f)
                                            fontSize(12f)
                                            color(tokens.success.foreground)
                                        }
                                        event { click { if (!ctx.attr.actionBusy) ctx.attr.onSaveEdit(item.id) } }
                                    }
                                    Text {
                                        attr {
                                            text("取消")
                                            marginLeft(10f)
                                            fontSize(12f)
                                            color(tokens.secondaryText)
                                        }
                                        event { click { if (!ctx.attr.actionBusy) ctx.attr.onCancelEdit() } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal class DshQueueDockAttr : ComposeAttr() {
    var items: com.tencent.kuikly.core.reactive.collection.ObservableList<DshQueueItem> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var expanded: Boolean by observable(false)
    var running: Boolean by observable(false)
    var editingId: String by observable("")
    var editingText: String by observable("")
    var actionBusy: Boolean by observable(false)
    var onToggle: () -> Unit by observable({})
    var onEdit: (String) -> Unit by observable({})
    var onEditingTextChange: (String) -> Unit by observable({})
    var onSaveEdit: (String) -> Unit by observable({})
    var onCancelEdit: () -> Unit by observable({})
    var onRemove: (String) -> Unit by observable({})
    var onSteer: (String) -> Unit by observable({})
}

internal fun ViewContainer<*, *>.DshQueueDock(init: DshQueueDockView.() -> Unit) {
    addChild(DshQueueDockView(), init)
}

internal class DshJobsPanelView : ComposeView<DshJobsPanelAttr, ComposeEvent>() {
    override fun createAttr(): DshJobsPanelAttr = DshJobsPanelAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val jobs = ObservableList<DshJobItem>().also { it.addAll(dshOrderedJobs(ctx.attr.jobs.toList())) }
        val liveCount = jobs.count { it.status == "running" || it.status == "stopping" }
        return {
            vif({ ctx.attr.jobs.isNotEmpty() }) {
                View {
                    attr {
                        marginBottom(8f)
                        flexDirectionColumn()
                        padding(8f)
                        borderRadius(10f)
                        backgroundColor(tokens.surfaceVariant)
                        border(Border(1f, BorderStyle.SOLID, tokens.divider))
                    }
                    View {
                        attr { height(30f); flexDirectionRow(); alignItemsCenter() }
                        event { click { ctx.attr.onToggle() } }
                        Text {
                            attr {
                                text(if (liveCount > 0) "后台任务 · $liveCount 运行中" else "后台任务 · ${jobs.size}")
                                flex(1f)
                                fontSize(13f)
                                color(tokens.primaryText)
                            }
                        }
                        Image {
                            attr {
                                src(ImageUri.commonAssets("chevron-down.svg"))
                                size(14f, 14f)
                                transform(Rotate(if (ctx.attr.expanded) 0f else -90f))
                                tintColor(tokens.icon)
                            }
                        }
                    }
                    vif({ ctx.attr.expanded }) {
                    vfor({ jobs }) { job ->
                        View {
                            attr {
                                minHeight(46f)
                                marginTop(6f)
                                flexDirectionColumn()
                                padding(6f)
                                borderRadius(8f)
                                backgroundColor(tokens.surfaceVariant)
                            }
                            View {
                                attr { flexDirectionRow(); alignItemsCenter() }
                                View {
                                    attr {
                                        size(7f, 7f)
                                        borderRadius(4f)
                                        backgroundColor(
                                            when (job.status) {
                                                "running" -> tokens.running.foreground
                                                "stopping" -> tokens.warning.foreground
                                                "failed" -> tokens.error.foreground
                                                else -> tokens.tertiaryText
                                            },
                                        )
                                    }
                                }
                                Text {
                                    attr {
                                        text(job.kind)
                                        marginLeft(7f)
                                        fontSize(12f)
                                        fontWeightMedium()
                                        color(tokens.primaryText)
                                    }
                                }
                                Text {
                                    attr {
                                        text(job.detail.ifEmpty { dshJobStatusLabel(job.status) })
                                        marginLeft(8f)
                                        fontSize(11f)
                                        color(tokens.secondaryText)
                                    }
                                }
                                Text {
                                    attr {
                                        text(dshJobDuration(job, ctx.attr.now))
                                        marginLeft(8f)
                                        fontSize(11f)
                                        color(tokens.secondaryText)
                                    }
                                }
                            }
                            Text {
                                attr {
                                    text(job.label)
                                    marginTop(3f)
                                    lines(1)
                                    fontSize(12f)
                                    color(tokens.primaryText)
                                }
                            }
                            vif({ job.detail.isNotEmpty() }) {
                                Text {
                                    attr {
                                        text(job.detail)
                                        marginTop(2f)
                                        lines(1)
                                        fontSize(11f)
                                        color(tokens.secondaryText)
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

internal fun dshOrderedJobs(jobs: List<DshJobItem>): List<DshJobItem> {
    return jobs.sortedWith(
        compareByDescending<DshJobItem> { it.status == "running" || it.status == "stopping" }
            .thenBy { if (it.status == "running" || it.status == "stopping") it.startedAt else Long.MAX_VALUE }
            .thenByDescending { it.finishedAt ?: it.startedAt },
    )
}

private fun dshJobStatusLabel(status: String): String = when (status) {
    "running" -> "运行中"
    "stopping" -> "正在停止"
    "completed" -> "已完成"
    "killed" -> "已取消"
    "failed" -> "已失败"
    else -> status
}

internal fun dshJobDuration(job: DshJobItem, now: Long): String {
    val end = job.finishedAt ?: now.takeIf { it > 0L } ?: return "进行中"
    val seconds = ((end - job.startedAt).coerceAtLeast(0L) / 1000L).toInt()
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${rest}秒"
        else -> "${rest}秒"
    }
}

internal class DshJobsPanelAttr : ComposeAttr() {
    var jobs: com.tencent.kuikly.core.reactive.collection.ObservableList<DshJobItem> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var expanded: Boolean by observable(false)
    var now: Long by observable(0L)
    var onToggle: () -> Unit by observable({})
}

internal fun ViewContainer<*, *>.DshJobsPanel(init: DshJobsPanelView.() -> Unit) {
    addChild(DshJobsPanelView(), init)
}

internal class DshGoalBarView : ComposeView<DshGoalBarAttr, ComposeEvent>() {
    private var editing by observable(false)
    private var draft by observable("")

    override fun createAttr(): DshGoalBarAttr = DshGoalBarAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            vif({ ctx.attr.snapshot != null }) {
                val goal = ctx.attr.snapshot ?: return@vif
                View {
                    attr {
                        marginBottom(8f)
                        minHeight(38f)
                        flexDirectionRow()
                        alignItemsCenter()
                        padding(8f, 10f, 8f, 10f)
                        borderRadius(8f)
                        backgroundColor(tokens.surfaceVariant)
                        border(Border(1f, BorderStyle.SOLID, tokens.divider))
                    }
                    Image {
                        attr { src(ImageUri.commonAssets("goal.svg")); size(14f, 14f); tintColor(tokens.icon) }
                    }
                    Text {
                        attr {
                            text(when (goal.phase) {
                                "active" -> "目标进行中"
                                "paused" -> "目标已暂停"
                                "blocked" -> "目标受阻"
                                else -> goal.phase
                            })
                            marginLeft(6f)
                            fontSize(12f)
                            fontWeightMedium()
                            color(if (goal.phase == "blocked") tokens.error.foreground else tokens.secondaryText)
                        }
                    }
                    vif({ !ctx.editing }) {
                        Text {
                            attr {
                                text(goal.objective)
                                flex(1f)
                                marginLeft(8f)
                                lines(2)
                                fontSize(12f)
                                color(tokens.primaryText)
                            }
                        }
                    }
                    vif({ ctx.editing }) {
                        Input {
                            ref { it.view?.setText(ctx.draft) }
                            attr {
                                flex(1f)
                                height(30f)
                                marginLeft(8f)
                                fontSize(12f)
                                text(ctx.draft)
                            }
                            event { textDidChange { ctx.draft = it.text } }
                        }
                        Text {
                            attr {
                                text(if (ctx.attr.busy) "处理中" else "保存")
                                marginLeft(8f)
                                fontSize(12f)
                                color(tokens.success.foreground)
                            }
                            event {
                                click {
                                    if (!ctx.attr.busy && ctx.draft.trim().isNotEmpty()) {
                                        ctx.attr.onEdit(ctx.draft.trim()) { success -> if (success) ctx.editing = false }
                                    }
                                }
                            }
                        }
                        Text {
                            attr {
                                text("取消")
                                marginLeft(8f)
                                fontSize(12f)
                                color(tokens.secondaryText)
                            }
                            event { click { if (!ctx.attr.busy) ctx.editing = false } }
                        }
                    }
                    vif({ goal.blockedReason.isNotEmpty() }) {
                        Text {
                            attr {
                                text(goal.blockedReason)
                                marginLeft(6f)
                                lines(1)
                                fontSize(11f)
                                color(tokens.error.foreground)
                            }
                        }
                    }
                    vif({ ctx.attr.error.isNotEmpty() }) {
                        Text {
                            attr {
                                text(ctx.attr.error)
                                marginLeft(6f)
                                fontSize(11f)
                                color(tokens.error.foreground)
                            }
                        }
                    }
                    vif({ !ctx.editing && goal.phase == "active" }) {
                        Text {
                            attr {
                                text(if (ctx.attr.busy) "处理中" else "暂停")
                                marginLeft(8f)
                                fontSize(12f)
                                color(tokens.secondaryText)
                            }
                            event { click { if (!ctx.attr.busy) ctx.attr.onPause() } }
                        }
                    }
                    vif({ !ctx.editing && goal.phase == "paused" }) {
                        Text {
                            attr {
                                text(if (ctx.attr.busy) "处理中" else "恢复")
                                marginLeft(8f)
                                fontSize(12f)
                                color(tokens.success.foreground)
                            }
                            event { click { if (!ctx.attr.busy) ctx.attr.onResume() } }
                        }
                    }
                    vif({ !ctx.editing }) {
                    Text {
                        attr {
                            text("清除")
                            marginLeft(8f)
                            fontSize(12f)
                            color(tokens.error.foreground)
                        }
                        event { click { if (!ctx.attr.busy) ctx.attr.onClear() } }
                    }
                    }
                    vif({ !ctx.editing }) {
                    Text {
                        attr {
                            text("编辑")
                            marginLeft(8f)
                            fontSize(12f)
                            color(tokens.primary)
                        }
                        event { click { if (!ctx.attr.busy) { ctx.draft = goal.objective; ctx.editing = true } } }
                    }
                    }
                }
            }
        }
    }
}

internal class DshGoalBarAttr : ComposeAttr() {
    var snapshot: DshGoalSnapshot? by observable(null)
    var busy: Boolean by observable(false)
    var error: String by observable("")
    var onEdit: (String, (Boolean) -> Unit) -> Unit by observable({ _, _ -> })
    var onPause: () -> Unit by observable({})
    var onResume: () -> Unit by observable({})
    var onClear: () -> Unit by observable({})
}

internal fun ViewContainer<*, *>.DshGoalBar(init: DshGoalBarView.() -> Unit) {
    addChild(DshGoalBarView(), init)
}

internal class DshApprovalPanelView : ComposeView<DshApprovalPanelAttr, ComposeEvent>() {
    override fun createAttr(): DshApprovalPanelAttr = DshApprovalPanelAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            vif({ ctx.attr.approval != null }) {
                val approval = ctx.attr.approval ?: return@vif
                View {
                    attr {
                        marginLeft(4f)
                        marginRight(4f)
                        marginBottom(10f)
                        flexDirectionColumn()
                        padding(14f, 14f, 14f, 14f)
                        borderRadius(16f)
                        backgroundColor(tokens.surfaceElevated)
                        border(Border(1f, BorderStyle.SOLID, tokens.divider))
                    }
                    View {
                        attr {
                            alignSelfFlexStart()
                            padding(3f, 8f, 3f, 8f)
                            borderRadius(6f)
                            backgroundColor(tokens.warning.background)
                        }
                        Text {
                            attr {
                                text("等待审批")
                                fontSize(11f)
                                fontWeightMedium()
                                color(tokens.warning.foreground)
                            }
                        }
                    }
                    Text {
                        attr {
                            text(approval.reason ?: "需要使用 ${approval.toolName}")
                            marginTop(10f)
                            fontSize(16f)
                            fontWeightMedium()
                            lineHeight(23f)
                            color(tokens.primaryText)
                        }
                    }
                    vif({ approval.command != null }) {
                        View {
                            attr {
                                marginTop(8f)
                                padding(10f, 12f, 10f, 12f)
                                borderRadius(10f)
                                backgroundColor(Color(theme.codeColors.codeBlockBackground))
                            }
                            Text {
                                attr {
                                    text(approval.command ?: "")
                                    fontSize(12f)
                                    lineHeight(18f)
                                    fontFamily("monospace")
                                    color(tokens.secondaryText)
                                }
                            }
                        }
                    }
                    View {
                        attr {
                            height(40f)
                            marginTop(12f)
                            flexDirectionRow()
                            justifyContentFlexEnd()
                            alignItemsCenter()
                        }
                        View {
                            attr {
                                height(32f)
                                paddingLeft(14f)
                                paddingRight(14f)
                                borderRadius(8f)
                                justifyContentCenter()
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    text(if (ctx.attr.busy) "处理中" else "拒绝")
                                    fontSize(13f)
                                    color(tokens.error.foreground)
                                }
                            }
                            DshTapTarget { if (!ctx.attr.busy) ctx.attr.onAnswer("rejected") }
                        }
                        View {
                            attr {
                                height(32f)
                                marginLeft(8f)
                                paddingLeft(14f)
                                paddingRight(14f)
                                borderRadius(8f)
                                backgroundColor(if (ctx.attr.busy) tokens.primaryDisabled else tokens.primary)
                                justifyContentCenter()
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    text(if (ctx.attr.busy) "处理中" else "允许一次")
                                    fontSize(13f)
                                    fontWeightMedium()
                                    color(tokens.onPrimary)
                                }
                            }
                            DshTapTarget { if (!ctx.attr.busy) ctx.attr.onAnswer("allowed-once") }
                        }
                    }
                }
            }
        }
    }
}

internal class DshApprovalPanelAttr : ComposeAttr() {
    var approval: DshPendingApproval? by observable(null)
    var busy: Boolean by observable(false)
    var onAnswer: (String) -> Unit by observable({})
}

internal class DshQuestionFlowView : ComposeView<DshQuestionFlowAttr, ComposeEvent>() {
    override fun createAttr(): DshQuestionFlowAttr = DshQuestionFlowAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val item = ctx.attr.question?.questions?.getOrNull(ctx.attr.index)
        return {
            vif({ item != null }) {
                val current = item ?: return@vif
                View {
                    attr {
                        marginLeft(4f)
                        marginRight(4f)
                        marginBottom(10f)
                        flexDirectionColumn()
                        padding(14f, 14f, 14f, 14f)
                        borderRadius(16f)
                        backgroundColor(tokens.surfaceElevated)
                        border(Border(1f, BorderStyle.SOLID, tokens.divider))
                    }
                    View {
                        attr {
                            alignSelfFlexStart()
                            padding(3f, 8f, 3f, 8f)
                            borderRadius(6f)
                            backgroundColor(tokens.info.background)
                        }
                        Text {
                            attr {
                                text(current.header.ifEmpty { "需要你选择" })
                                fontSize(11f)
                                fontWeightMedium()
                                color(tokens.info.foreground)
                            }
                        }
                    }
                    Text {
                        attr {
                            text(current.question)
                            marginTop(10f)
                            fontSize(16f)
                            fontWeightMedium()
                            lineHeight(23f)
                            color(tokens.primaryText)
                        }
                    }
                    vif({ current.detail.isNotEmpty() }) {
                        Text {
                            attr {
                                text(current.detail)
                                marginTop(6f)
                                fontSize(13f)
                                lineHeight(19f)
                                color(tokens.secondaryText)
                            }
                        }
                    }
                    vfor({ ctx.attr.options }) { option ->
                        val selected = ctx.attr.selected.contains(option.label)
                        View {
                            attr {
                                marginTop(8f)
                                flexDirectionRow()
                                alignItemsFlexStart()
                                padding(10f, 12f, 10f, 12f)
                                borderRadius(12f)
                                backgroundColor(if (selected) tokens.selectedSurface else tokens.surfaceVariant)
                                border(Border(
                                    1f,
                                    BorderStyle.SOLID,
                                    if (selected) tokens.primary else tokens.divider,
                                ))
                            }
                                View {
                                    attr {
                                        size(18f, 18f)
                                        marginTop(2f)
                                        borderRadius(9f)
                                        border(Border(
                                            1.5f,
                                            BorderStyle.SOLID,
                                            if (selected) tokens.primary else tokens.dividerStrong,
                                        ))
                                        backgroundColor(if (selected) tokens.primary else Color.TRANSPARENT)
                                        justifyContentCenter()
                                        alignItemsCenter()
                                    }
                                    View {
                                        attr {
                                            size(if (selected) 6f else 0f, if (selected) 6f else 0f)
                                            borderRadius(3f)
                                            backgroundColor(tokens.onPrimary)
                                        }
                                    }
                                }
                            View {
                                attr {
                                    flex(1f)
                                    marginLeft(10f)
                                    flexDirectionColumn()
                                }
                                Text {
                                    attr {
                                        text(option.label)
                                        fontSize(14f)
                                        fontWeightMedium()
                                        color(tokens.primaryText)
                                    }
                                }
                                vif({ option.description.isNotEmpty() }) {
                                    Text {
                                        attr {
                                            text(option.description)
                                            marginTop(3f)
                                            fontSize(12f)
                                            lineHeight(17f)
                                            color(tokens.secondaryText)
                                        }
                                    }
                                }
                            }
                            DshTapTarget { ctx.attr.onToggleOption(option.label) }
                        }
                    }
                    View {
                        attr {
                            height(40f)
                            marginTop(10f)
                            paddingLeft(12f)
                            paddingRight(12f)
                            borderRadius(10f)
                            backgroundColor(tokens.surfaceVariant)
                            border(Border(1f, BorderStyle.SOLID, tokens.divider))
                            justifyContentCenter()
                        }
                        Input {
                            ref { it.view?.setText(ctx.attr.custom) }
                            attr {
                                height(36f)
                                placeholder("也可以自己写答案")
                                placeholderColor(tokens.tertiaryText)
                                fontSize(13f)
                                color(tokens.primaryText)
                                text(ctx.attr.custom)
                            }
                            event { textDidChange { ctx.attr.onCustomChange(it.text) } }
                        }
                    }
                    vif({ ctx.attr.error.isNotEmpty() }) {
                        Text {
                            attr {
                                text(ctx.attr.error)
                                marginTop(8f)
                                fontSize(12f)
                                color(tokens.error.foreground)
                            }
                        }
                    }
                    View {
                        attr {
                            height(40f)
                            marginTop(12f)
                            flexDirectionRow()
                            alignItemsCenter()
                            zIndex(2)
                        }
                        Text {
                            attr {
                                text("${ctx.attr.index + 1} / ${ctx.attr.question?.questions?.size ?: 1}")
                                flex(1f)
                                fontSize(12f)
                                color(tokens.tertiaryText)
                            }
                        }
                        vif({ ctx.attr.index > 0 }) {
                            Text {
                                attr {
                                    text("上一题")
                                    marginRight(12f)
                                    fontSize(13f)
                                    color(tokens.primary)
                                }
                                event { click { ctx.attr.onNavigate(-1) } }
                            }
                        }
                        vif({ ctx.attr.index < (ctx.attr.question?.questions?.size ?: 1) - 1 }) {
                            Text {
                                attr {
                                    text("下一题")
                                    marginRight(12f)
                                    fontSize(13f)
                                    color(tokens.primary)
                                }
                                event { click { ctx.attr.onNavigate(1) } }
                            }
                        }
                        View {
                            attr {
                                height(32f)
                                paddingLeft(12f)
                                paddingRight(12f)
                                marginRight(8f)
                                borderRadius(8f)
                                justifyContentCenter()
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    text("跳过")
                                    fontSize(13f)
                                    color(tokens.secondaryText)
                                }
                            }
                            DshTapTarget { if (!ctx.attr.busy) ctx.attr.onSkip() }
                        }
                        View {
                            attr {
                                height(32f)
                                paddingLeft(16f)
                                paddingRight(16f)
                                borderRadius(8f)
                                backgroundColor(if (ctx.attr.busy) tokens.primaryDisabled else tokens.primary)
                                justifyContentCenter()
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    text(if (ctx.attr.busy) "提交中" else "提交")
                                    fontSize(13f)
                                    fontWeightMedium()
                                    color(tokens.onPrimary)
                                }
                            }
                            DshTapTarget { if (!ctx.attr.busy) ctx.attr.onSubmit() }
                        }
                    }
                }
            }
        }
    }
}

internal class DshQuestionFlowAttr : ComposeAttr() {
    var question: DshPendingQuestion? by observable(null)
    var index: Int by observable(0)
    var options: com.tencent.kuikly.core.reactive.collection.ObservableList<DshPendingQuestionOption> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var selected: com.tencent.kuikly.core.reactive.collection.ObservableList<String> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var custom: String by observable("")
    var error: String by observable("")
    var busy: Boolean by observable(false)
    var onToggleOption: (String) -> Unit by observable({})
    var onCustomChange: (String) -> Unit by observable({})
    var onNavigate: (Int) -> Unit by observable({})
    var onSkip: () -> Unit by observable({})
    var onSubmit: () -> Unit by observable({})
}

internal fun ViewContainer<*, *>.DshQuestionFlow(init: DshQuestionFlowView.() -> Unit) {
    addChild(DshQuestionFlowView(), init)
}

internal fun ViewContainer<*, *>.DshApprovalPanel(init: DshApprovalPanelView.() -> Unit) {
    addChild(DshApprovalPanelView(), init)
}

internal class DshQuestionPanelView : ComposeView<DshQuestionPanelAttr, ComposeEvent>() {
    override fun createAttr(): DshQuestionPanelAttr = DshQuestionPanelAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            vif({ ctx.attr.question != null }) {
                val question = ctx.attr.question ?: return@vif
                val item = question.questions.firstOrNull()
                vif({ item != null }) {
                    val current = item ?: return@vif
                    View {
                        attr {
                            marginBottom(8f)
                            flexDirectionColumn()
                            padding(10f)
                            borderRadius(12f)
                            backgroundColor(tokens.info.background)
                            border(Border(1f, BorderStyle.SOLID, tokens.divider))
                        }
                        Text {
                            attr {
                                text(current.header.ifEmpty { "需要你的回答" })
                                fontSize(12f)
                                color(tokens.info.foreground)
                            }
                        }
                        Text {
                            attr {
                                text(current.question)
                                marginTop(4f)
                                fontSize(15f)
                                fontWeightMedium()
                                color(tokens.primaryText)
                            }
                        }
                        vif({ current.detail.isNotEmpty() }) {
                            Text {
                                attr {
                                    text(current.detail)
                                    marginTop(4f)
                                    fontSize(12f)
                                    lineHeight(18f)
                                    color(tokens.secondaryText)
                                }
                            }
                        }
                        vfor({ ctx.attr.options }) { option ->
                            View {
                                attr {
                                    height(34f)
                                    marginTop(6f)
                                    paddingLeft(8f)
                                    paddingRight(8f)
                                    borderRadius(8f)
                                    backgroundColor(tokens.surfaceVariant)
                                    justifyContentCenter()
                                }
                                Text {
                                    attr {
                                        text(option.label)
                                        fontSize(13f)
                                        color(tokens.secondaryText)
                                    }
                                }
                                event { click { ctx.attr.onToggleOption(option.label) } }
                            }
                        }
                        Text {
                            attr {
                                text(if (ctx.attr.busy) "提交中" else "提交")
                                height(34f)
                                marginTop(8f)
                                textAlignCenter()
                                fontSize(14f)
                                color(tokens.success.foreground)
                            }
                            event { click { if (!ctx.attr.busy) ctx.attr.onSubmit() } }
                        }
                    }
                }
            }
        }
    }
}

internal class DshQuestionPanelAttr : ComposeAttr() {
    var question: DshPendingQuestion? by observable(null)
    var options: com.tencent.kuikly.core.reactive.collection.ObservableList<DshPendingQuestionOption> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var selected: com.tencent.kuikly.core.reactive.collection.ObservableList<String> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var custom: String by observable("")
    var busy: Boolean by observable(false)
    var onToggleOption: (String) -> Unit by observable({})
    var onSubmit: () -> Unit by observable({})
}

internal fun ViewContainer<*, *>.DshQuestionPanel(init: DshQuestionPanelView.() -> Unit) {
    addChild(DshQuestionPanelView(), init)
}

private fun ViewContainer<*, *>.DshTapTarget(onClick: () -> Unit) {
    View {
        attr {
            absolutePositionAllZero()
            zIndex(2)
            backgroundColor(Color.TRANSPARENT)
        }
        event { click { onClick() } }
    }
}
