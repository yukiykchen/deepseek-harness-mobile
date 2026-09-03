package com.example.dsh.dsh

import com.example.dsh.base.BasePager
import com.example.dsh.theme.tokens
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** Harmony cannot resolve KuiklyWebview; this page keeps the `link_view` route so the host still packs. */
@Page("link_view")
internal class DshWebViewPage : BasePager() {
    override fun body(): ViewBuilder {
        return {
            View {
                attr {
                    flex(1f)
                    allCenter()
                    autoDarkEnable(false)
                    backgroundColor(tokens.background)
                    padding(24f)
                }
                Text {
                    attr {
                        text("此构建未包含链接预览")
                        fontSize(15f)
                        color(tokens.secondaryText)
                    }
                }
            }
        }
    }
}
