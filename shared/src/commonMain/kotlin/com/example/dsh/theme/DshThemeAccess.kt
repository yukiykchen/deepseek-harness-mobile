package com.example.dsh.theme

import com.example.dsh.base.BasePager
import com.tencent.kuikly.core.base.PagerScope

/**
 * 任意 View / Attr 内直接读取当前页面的主题快照。
 * 读取的是 [BasePager.theme] 这个 observable，因此在 `attr {}` 里使用时会自动订阅主题变化。
 */
internal val PagerScope.theme: DshThemeSnapshot
    get() = (getPager() as BasePager).theme

internal val PagerScope.tokens: DshThemeTokens
    get() = theme.tokens
