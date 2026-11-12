package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.base.*
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
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.model.DshJsonNode

/** Client-local disclosure state; it never writes back to the Host. */

data class DshDisclosureState(
    val key: String,
    val open: Boolean = false,
)

data class DshToolCardModel(
    val key: String,
    val title: String,
    val summary: String,
    val input: String?,
    val output: String?,
    val error: String? = null,
    val running: Boolean = false,
)

data class DshContextInjectionModel(
    val key: String,
    val sourceLabel: String,
    val summary: String,
    val body: String,
)

fun String.dshCollapsedLines(maxLines: Int = 16): List<String> {
    val lines = split('\n')
    if (lines.size <= maxLines) return lines
    val head = (maxLines + 1) / 2
    val tail = maxLines - head
    return lines.take(head) + listOf("… 其余 ${lines.size - maxLines} 行") + lines.takeLast(tail)
}

fun dshJsonPreview(value: String): String {
    if (value.length <= 160) return value
    return value.take(148) + "…"
}

fun dshParseJsonTree(raw: String): Any? {
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
fun dshJsonObjectToMap(value: JSONObject): Map<String, Any?> {
    val map = linkedMapOf<String, Any?>()
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = value.opt(key)
    }
    return map
}
fun dshBuildJsonNodes(
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
class DshJsonTreeView : ComposeView<DshJsonTreeAttr, ComposeEvent>() {
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
                    backgroundColor(Color(0xFFF9FAFB))
                    border(Border(1f, BorderStyle.SOLID, Color(0xFFE4E8EC)))
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
                            color(Color(0xFF333B42))
                        }
                    }
                }
            }
        }
    }
}
class DshJsonTreeAttr : ComposeAttr() {
    var content: String by observable("")
    var isExpanded: (String) -> Boolean by observable({ false })
    var onToggle: (String) -> Unit by observable({})
}
class DshJsonNodeRowView : ComposeView<DshJsonNodeRowAttr, ComposeEvent>() {
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
                            }
                        }
                    }
                    Text {
                        attr {
                            text(ctx.attr.node.label)
                            marginLeft(6f)
                            fontSize(12f)
                            fontFamily("monospace")
                            color(Color(0xFF333B42))
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
                            color(Color(0xFF7A838A))
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
class DshJsonNodeRowAttr : ComposeAttr() {
    var node: DshJsonNode by observable(DshJsonNode("$", "$", "null"))
    var expanded: Boolean by observable(false)
    var onToggle: () -> Unit by observable({})
    var isNodeExpanded: (String) -> Boolean by observable({ false })
    var onToggleNode: (String) -> Unit by observable({})
}
fun ViewContainer<*, *>.DshJsonTree(init: DshJsonTreeView.() -> Unit) {
    addChild(DshJsonTreeView(), init)
}
fun ViewContainer<*, *>.DshJsonNodeRow(init: DshJsonNodeRowView.() -> Unit) {
    addChild(DshJsonNodeRowView(), init)
}
