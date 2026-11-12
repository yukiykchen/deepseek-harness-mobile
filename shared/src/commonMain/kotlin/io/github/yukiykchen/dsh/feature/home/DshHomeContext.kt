package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import io.github.yukiykchen.dsh.data.DshHostClient

/**
 * What a home-screen feature controller may read from the page that owns it.
 *
 * Controllers own their own observable state and talk to the Host through
 * [hostClient]. Anything that needs other page state (drawer, keyboard,
 * timeline reloads) is passed to the controller as an explicit callback, so
 * the dependency is visible at construction time instead of hidden in a
 * 3000-line class.
 */

interface DshHomeContext : PagerScope {
    /** Client for the connected DSH Host; null while disconnected. */
    val hostClient: DshHostClient?

    /** Session currently shown in the conversation column. */
    val activeSessionId: String
}
