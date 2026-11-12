package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image

private const val DSH_WORDMARK_RATIO = 143f / 23f

fun ViewContainer<*, *>.DshWordmark(height: Float = 22f) {
    Image {
        attr {
            src(ImageUri.commonAssets("wordmark.svg"))
            width(height * DSH_WORDMARK_RATIO)
            height(height)
            resizeContain()
        }
    }
}
