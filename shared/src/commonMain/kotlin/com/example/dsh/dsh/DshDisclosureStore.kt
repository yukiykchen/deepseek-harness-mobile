package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable

/**
 * Expanded/collapsed state of tool cards, their bodies and JSON tree nodes.
 *
 * Plain maps keyed by message id plus one reactive [revision]: readers touch
 * the revision so every disclosure re-renders after any toggle, without
 * allocating an observable per card. Collapsing a card also forgets the state
 * of everything inside it.
 */
internal class DshDisclosureStore(
    scope: PagerScope,
    private val onChanged: () -> Unit,
) : PagerScope by scope {
    private val cards = mutableMapOf<String, Boolean>()
    private val bodies = mutableMapOf<String, Boolean>()
    private val jsonNodes = mutableMapOf<String, Boolean>()
    private var revision by observable(0)

    fun isExpanded(id: String): Boolean {
        revision
        return cards[id] == true
    }

    fun toggle(id: String) {
        val next = cards[id] != true
        cards[id] = next
        if (!next) {
            bodies.remove(id)
            jsonNodes.keys.filter { it.startsWith("$id:") }.forEach(jsonNodes::remove)
        }
        changed()
    }

    fun isBodyExpanded(id: String): Boolean {
        revision
        return bodies[id] == true
    }

    fun toggleBody(id: String) {
        bodies[id] = bodies[id] != true
        changed()
    }

    fun isJsonNodeExpanded(messageId: String, nodeId: String): Boolean {
        revision
        return jsonNodes["$messageId:$nodeId"] == true
    }

    fun toggleJsonNode(messageId: String, nodeId: String) {
        val key = "$messageId:$nodeId"
        jsonNodes[key] = jsonNodes[key] != true
        changed()
    }

    private fun changed() {
        revision += 1
        onChanged()
    }
}
