package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.pager.PageData

/** Kuikly's Android IME height already excludes the navigation bar. */
fun effectiveKeyboardHeight(rawHeight: Float, pageData: PageData): Float {
    if (!rawHeight.isFinite() || rawHeight <= 0f) return 0f
    return if (pageData.isAndroid) rawHeight else (rawHeight - pageData.safeAreaInsets.bottom).coerceAtLeast(0f)
}
