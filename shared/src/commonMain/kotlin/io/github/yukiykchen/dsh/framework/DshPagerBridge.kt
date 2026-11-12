package io.github.yukiykchen.dsh.framework

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.manager.PagerManager

/** Resolves modules from the owning page instead of the deprecated global page id. */
val PagerScope.bridgeModule: BridgeModule
    get() = PagerManager.getPager(pagerId).acquireModule(BridgeModule.MODULE_NAME)

fun PagerScope.setTimeout(delay: Int, callback: () -> Unit): String =
    com.tencent.kuikly.core.timer.setTimeout(pagerId, delay, callback)
