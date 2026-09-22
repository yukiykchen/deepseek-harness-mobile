package com.example.dsh.dsh

import com.example.dsh.base.BasePager
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuiklybase.WebView

/** Full-screen link viewer for Markdown, file and external web links. */
@Page("link_view")
internal class DshWebViewPage : BasePager() {
    private var url by observable("")
    private var status by observable("正在连接")
    private var progress by observable(0)
    private var webViewRef: ViewRef<com.tencent.kuiklybase.KuiklyWebView>? = null

    override fun created() {
        super.created()
        // 链接页没有覆盖层，系统返回键直接结束当前页面。
        getBackPressHandler().addCallback(object : BackPressCallback() {
            override fun handleOnBackPressed() {
                acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            }
        })
        url = pageData.params.optString("url").trim()
        if (url.isEmpty()) status = "链接为空"
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(Color(0xFFF7F9FA))
                    paddingTop(pagerData.statusBarHeight)
                }
                DshLinkHeader(
                    status = { ctx.status },
                    progress = { ctx.progress },
                    onBack = { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
                    onReload = { ctx.webViewRef?.view?.reload() },
                )
                if (ctx.url.isEmpty()) {
                    View {
                        attr {
                            flex(1f)
                            allCenter()
                            padding(24f)
                        }
                        Text {
                            attr {
                                text("链接为空")
                                fontSize(15f)
                                color(Color(0xFF68737D))
                            }
                        }
                    }
                } else {
                    WebView {
                        ref { ctx.webViewRef = it }
                        attr {
                            flex(1f)
                            src(ctx.url)
                            javaScriptEnabled(true)
                            domStorageEnabled(true)
                            allowsInlineMediaPlayback(true)
                        }
                        event {
                            onPageStarted { _ -> ctx.status = "加载中" }
                            onPageFinished { _ ->
                                ctx.status = "已连接"
                                ctx.progress = 100
                            }
                            onProgressChanged { value -> ctx.progress = value }
                            onError { _, description -> ctx.status = description.ifEmpty { "加载失败" } }
                        }
                    }
                }
            }
        }
    }
}

private class DshLinkHeader : ComposeView<DshLinkHeaderAttr, ComposeEvent>() {
    override fun createAttr(): DshLinkHeaderAttr = DshLinkHeaderAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    height(48f)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(12f)
                    paddingRight(12f)
                    backgroundColor(Color.WHITE)
                    borderBottom(Border(1f, BorderStyle.SOLID, Color(0xFFE5E8EB)))
                }
                Text {
                    attr {
                        text("返回")
                        fontSize(14f)
                        color(Color(0xFF4176E6))
                    }
                    event { click { ctx.attr.onBack() } }
                }
                Text {
                    attr {
                        text("链接")
                        marginLeft(18f)
                        fontSize(15f)
                        color(Color(0xFF26313A))
                        fontWeightBold()
                    }
                }
                View { attr { flex(1f) } }
                Text {
                    attr {
                        text(ctx.attr.status)
                        fontSize(12f)
                        color(Color(0xFF7A8790))
                    }
                }
                Text {
                    attr {
                        text("刷新")
                        marginLeft(14f)
                        fontSize(14f)
                        color(Color(0xFF4176E6))
                    }
                    event { click { ctx.attr.onReload() } }
                }
            }
            if (ctx.attr.progress in 1 until 100) {
                View {
                    attr {
                        height(2f)
                        width(ctx.attr.progress.toFloat() / 100f * pagerData.pageViewWidth)
                        backgroundColor(Color(0xFF4176E6))
                    }
                }
            }
        }
    }
}

private class DshLinkHeaderAttr : ComposeAttr() {
    var status: String by observable("")
    var progress: Int by observable(0)
    var onBack: () -> Unit = {}
    var onReload: () -> Unit = {}
}

private fun ViewContainer<*, *>.DshLinkHeader(
    status: () -> String,
    progress: () -> Int,
    onBack: () -> Unit,
    onReload: () -> Unit,
) {
    addChild(DshLinkHeader()) {
        attr {
            this.status = status()
            this.progress = progress()
            this.onBack = onBack
            this.onReload = onReload
        }
    }
}
