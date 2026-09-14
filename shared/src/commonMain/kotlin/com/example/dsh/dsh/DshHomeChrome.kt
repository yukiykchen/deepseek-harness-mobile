package com.example.dsh.dsh

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

internal fun ViewContainer<*, *>.DshConnectionSettingsModal(
    sshMode: () -> Boolean,
    host: () -> String,
    user: () -> String,
    port: () -> String,
    dshPort: () -> String,
    keyLabel: () -> String,
    keyPassphrase: () -> String,
    authToken: () -> String,
    busy: () -> Boolean,
    error: () -> String,
    onModeChange: (Boolean) -> Unit,
    onHostChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onDshPortChange: (String) -> Unit,
    onPickKey: () -> Unit,
    onPassphraseChange: (String) -> Unit,
    onAuthTokenChange: (String) -> Unit,
    onApplyAuthToken: () -> Unit,
    onTrustFingerprint: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onOpenApiKey: () -> Unit,
) {
    Modal(inWindow = true) {
        attr { absolutePositionAllZero(); allCenter(); backgroundColor(theme.mask); padding(20f) }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(440f)
                flexDirectionColumn()
                padding(22f)
                borderRadius(16f)
                backgroundColor(theme.surface)
            }
            View {
                attr { height(32f); flexDirectionRow(); alignItemsCenter() }
                Text { attr { text("连接设置"); flex(1f); fontSize(20f); fontWeightBold(); color(theme.textPrimary) } }
                View { attr { size(32f, 32f); allCenter() }; Image { attr { src(ImageUri.commonAssets("x.svg")); size(20f, 20f); tintColor(theme.textSecondary) } }; DshHitButton { if (!busy()) onClose() } }
            }
            Text { attr { text("选择 Agent 运行位置"); marginTop(16f); fontSize(13f); color(theme.textSecondary) } }
            View {
                attr { height(42f); marginTop(8f); flexDirectionRow(); borderRadius(8f); backgroundColor(theme.surfaceSunken); padding(4f) }
                View {
                    attr { flex(1f); height(34f); flexDirectionRow(); alignItemsCenter(); justifyContentCenter(); backgroundColor(if (!sshMode()) theme.surface else Color(0x00FFFFFF)); borderRadius(6f) }
                    Text { attr { text("扫码连接"); fontSize(13f); color(if (!sshMode()) theme.accent else theme.textSecondary) } }
                    event { click { onModeChange(false) } }
                }
                View {
                    attr { flex(1f); height(34f); flexDirectionRow(); alignItemsCenter(); justifyContentCenter(); backgroundColor(if (sshMode()) theme.surface else Color(0x00FFFFFF)); borderRadius(6f) }
                    Text { attr { text("SSH 连接电脑"); fontSize(13f); color(if (sshMode()) theme.accent else theme.textSecondary) } }
                    event { click { onModeChange(true) } }
                }
            }
            vif({ !sshMode() }) {
                Text { attr { text("扫码模式连接电脑上的 DSH。返回连接页可重新扫码或更换电脑。"); marginTop(16f); fontSize(14f); lineHeight(21f); color(theme.textSecondary) } }
                Text { attr { text("DSH 登录 token 由电脑端 dsh-scan-remote 插件自动提供。若提示登录失效，可在此粘贴 dsh web 打印的 token 重新登录。"); marginTop(10f); fontSize(12f); lineHeight(18f); color(theme.textMuted) } }
                DshConnectionInput("dsh web 登录 token（可选）", authToken, "粘贴 token 或 dsh web 打印的完整 URL", onAuthTokenChange, password = true)
                vif({ error().isNotEmpty() }) {
                    Text { attr { text(error()); marginTop(8f); fontSize(12f); lineHeight(18f); color(theme.danger) } }
                }
                View {
                    attr { height(40f); marginTop(16f); flexDirectionRow(); justifyContentFlexEnd(); alignItemsCenter() }
                    Text { attr { text("重新登录"); marginRight(14f); fontSize(14f); color(theme.accent) }; event { click { if (!busy()) onApplyAuthToken() } } }
                    Button { attr { width(132f); height(40f); borderRadius(8f); backgroundColor(theme.accentFill); titleAttr { text("返回连接页"); fontSize(14f); color(theme.textOnAccent) } }; event { click { if (!busy()) onSave() } } }
                }
            }
            velse {
                DshConnectionInput("SSH 主机", host, "例如 100.86.12.34 或 computer.example.com", onHostChange)
                DshConnectionInput("SSH 用户名", user, "例如 alex", onUserChange)
                View { attr { flexDirectionRow(); marginTop(12f) }; DshConnectionInput("SSH 端口", port, "22", onPortChange, 0.5f); DshConnectionInput("远程 DSH 端口", dshPort, "3080", onDshPortChange, 0.5f, 10f) }
                View {
                    attr { height(44f); marginTop(12f); flexDirectionRow(); alignItemsCenter(); paddingLeft(12f); paddingRight(10f); borderRadius(8f); backgroundColor(theme.surfaceSunken) }
                    Text { attr { text(keyLabel()); flex(1f); fontSize(13f); color(theme.textSecondary) } }
                    Text { attr { text(if (busy()) "导入中..." else "选择私钥"); fontSize(13f); color(theme.accent) }; event { click { if (!busy()) onPickKey() } } }
                }
                DshConnectionInput("私钥口令（如有）", keyPassphrase, "仅本次连接使用", onPassphraseChange, password = true)
                DshConnectionInput("dsh web 登录 token", authToken, "粘贴 dsh web 打印的 token 或完整 URL", onAuthTokenChange, password = true)
                Text { attr { text("DSH 0.1.2+ 要求浏览器登录。token 在电脑端 `dsh web` 启动时打印，每次重启 DSH 都会变化。"); marginTop(6f); fontSize(12f); lineHeight(18f); color(theme.textMuted) } }
                View {
                    attr { height(28f); marginTop(4f); flexDirectionRow(); justifyContentFlexEnd() }
                    Text { attr { text("使用此 token 重新登录"); fontSize(13f); color(theme.accent) }; event { click { if (!busy()) onApplyAuthToken() } } }
                }
                vif({ error().startsWith("首次连接需要确认主机指纹：") }) {
                    View {
                        attr { marginTop(10f); padding(10f); borderRadius(8f); backgroundColor(theme.warningSoft) }
                        Text { attr { text("请确认这是你电脑的 SSH 主机指纹。确认后会保存，指纹变化时连接将被拒绝。"); fontSize(12f); lineHeight(18f); color(theme.warningText) } }
                        Text { attr { text("信任此指纹并连接"); marginTop(8f); fontSize(13f); color(theme.accent) }; event { click { if (!busy()) onTrustFingerprint() } } }
                    }
                }
                vif({ error().isNotEmpty() && !error().startsWith("首次连接需要确认主机指纹：") }) {
                    Text { attr { text(error()); marginTop(8f); fontSize(12f); lineHeight(18f); color(theme.danger) } }
                }
                View { attr { marginTop(18f); height(40f); flexDirectionRow(); justifyContentFlexEnd() }; Button { attr { width(132f); height(40f); borderRadius(8f); backgroundColor(if (busy()) theme.accentFillDisabled else theme.accentFill); titleAttr { text(if (busy()) "连接中..." else "保存并连接"); fontSize(14f); color(theme.textOnAccent) } }; event { click { if (!busy()) onSave() } } } }
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshConnectionInput(
    title: String,
    value: () -> String,
    hint: String,
    onChange: (String) -> Unit,
    flexValue: Float = 1f,
    marginLeft: Float = 0f,
    password: Boolean = false,
) {
    View {
        attr { flex(flexValue); marginLeft(marginLeft); flexDirectionColumn() }
        Text { attr { text(title); marginTop(10f); fontSize(12f); color(theme.textSecondary) } }
        View {
            attr { height(40f); marginTop(5f); borderRadius(8f); border(Border(1f, BorderStyle.SOLID, theme.inputBorder)); backgroundColor(theme.inputBackground); paddingLeft(10f); paddingRight(10f) }
            Input {
                ref { it.view?.setText(value()) }
                attr { flex(1f); fontSize(14f); color(theme.textPrimary); placeholder(hint); placeholderColor(theme.placeholder); returnKeyTypeDone(); if (password) keyboardTypePassword() }
                event { textDidChange { onChange(it.text) } }
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshCredentialSetupModal(
    title: () -> String,
    busy: () -> Boolean,
    error: () -> String,
    inputRef: (ViewRef<InputView>) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            allCenter()
            paddingLeft(20f)
            paddingRight(20f)
            backgroundColor(theme.mask)
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(420f)
                flexDirectionColumn()
                padding(24f)
                borderRadius(18f)
                backgroundColor(theme.surface)
            }
            View {
                attr {
                    height(32f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text(title())
                        flex(1f)
                        fontSize(20f)
                        fontWeightBold()
                        color(theme.textPrimary)
                    }
                }
                View {
                    attr {
                        size(32f, 32f)
                        allCenter()
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("x.svg"))
                            size(20f, 20f)
                            tintColor(theme.textSecondary)
                        }
                    }
                    DshHitButton { if (!busy()) onClose() }
                }
            }
            Text {
                attr {
                    text(if (title().contains("电脑端")) "确认后将修改电脑端 DSH 的凭据。" else "配置 DeepSeek 官方模型，即可开始使用。")
                    marginTop(8f)
                    fontSize(14f)
                    lineHeight(21f)
                    color(theme.textSecondary)
                }
            }
            Text {
                attr {
                    text("API Key")
                    marginTop(22f)
                    fontSize(13f)
                    fontWeightMedium()
                    color(theme.textSecondary)
                }
            }
            View {
                attr {
                    height(46f)
                    marginTop(8f)
                    borderRadius(8f)
                    border(Border(1f, BorderStyle.SOLID,
                        if (error().isEmpty()) theme.inputBorder else theme.danger,
                    ))
                    backgroundColor(theme.inputBackground)
                    paddingLeft(12f)
                    paddingRight(12f)
                }
                Input {
                    ref { inputRef(it) }
                    attr {
                        flex(1f)
                        fontSize(15f)
                        color(theme.textPrimary)
                        placeholder("输入 DeepSeek API Key")
                        placeholderColor(theme.placeholder)
                        keyboardTypePassword()
                        returnKeyTypeDone()
                        autofocus(true)
                        editable(!busy())
                    }
                    event {
                        textDidChange { onApiKeyChange(it.text) }
                        inputReturn { if (!busy()) onSave() }
                    }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text {
                    attr {
                        text(error())
                        marginTop(8f)
                        fontSize(12f)
                        lineHeight(18f)
                        color(theme.danger)
                    }
                }
            }
            View {
                attr {
                    marginTop(24f)
                    height(40f)
                    flexDirectionRow()
                    justifyContentFlexEnd()
                }
                Button {
                    attr {
                        width(132f)
                        height(40f)
                        borderRadius(8f)
                        backgroundColor(if (busy()) theme.accentFillDisabled else theme.accentFill)
                        titleAttr {
                            text(if (busy()) "保存中..." else "保存并继续")
                            fontSize(14f)
                            color(theme.textOnAccent)
                        }
                    }
                    event { click { if (!busy()) onSave() } }
                }
            }
        }
    }
}

/** Task 2 long-press menu for one conversation row. New UI copy is English. */
internal fun ViewContainer<*, *>.DshMessageActionSheet(
    codeBlockCount: () -> Int,
    formulaCount: () -> Int,
    onSelectText: () -> Unit,
    onSelectMessages: () -> Unit,
    onCopyMessage: () -> Unit,
    onCopyCodeBlock: (Int) -> Unit,
    onCopyFormula: (Int) -> Unit,
    onExportConversation: () -> Unit,
    onExportHtml: () -> Unit,
    onPrintConversation: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionColumn()
            justifyContentFlexEnd()
            backgroundColor(theme.maskLight)
        }
        View {
            attr { flex(1f) }
            event { click { onClose() } }
        }
        View {
            attr {
                flexDirectionColumn()
                padding(12f, 14f, 22f, 14f)
                borderRadius(20f)
                backgroundColor(theme.surface)
            }
            DshActionSheetRow("Select text", "Long-press handles to adjust the range") { onSelectText() }
            DshActionSheetRow("Copy message", "Readable body text, cards and attachments") { onCopyMessage() }
            DshActionSheetRow("Select messages", "Pick several, then copy or export together") { onSelectMessages() }
            vfor({
                val blocks = ObservableList<Int>()
                blocks.addAll((0 until codeBlockCount()).toList())
                blocks
            }) { index ->
                DshActionSheetRow("Copy code block ${index + 1}", "Code only, without the fence") {
                    onCopyCodeBlock(index)
                }
            }
            vfor({
                val formulas = ObservableList<Int>()
                formulas.addAll((0 until formulaCount()).toList())
                formulas
            }) { index ->
                DshActionSheetRow("Copy formula ${index + 1}", "LaTeX source, not the rendered form") {
                    onCopyFormula(index)
                }
            }
            DshActionSheetRow("Export conversation", "Share the whole transcript as Markdown") {
                onExportConversation()
            }
            DshActionSheetRow("Export as HTML", "Share a formatted .html file") {
                onExportHtml()
            }
            DshActionSheetRow("Print / save as PDF", "Opens the system print sheet") {
                onPrintConversation()
            }
            View {
                attr {
                    height(46f); marginTop(8f); allCenter()
                    borderRadius(12f); backgroundColor(theme.surfaceSunken)
                }
                Text { attr { text("Cancel"); fontSize(15f); color(theme.textSecondary) } }
                event { click { onClose() } }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshActionSheetRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    View {
        attr {
            minHeight(54f)
            marginBottom(6f)
            flexDirectionColumn()
            justifyContentCenter()
            paddingLeft(14f)
            paddingRight(14f)
            borderRadius(12f)
            backgroundColor(theme.surfaceRaised)
        }
        Text { attr { text(title); fontSize(15f); fontWeightMedium(); color(theme.textPrimary) } }
        Text { attr { text(subtitle); marginTop(2f); fontSize(11f); color(theme.textMuted) } }
        event { click { onClick() } }
    }
}

/**
 * Per-conversation management menu (Task 4). Only the operations the Host actually
 * exposes are offered: `session/rename` and `workspace/archiveSession`. When the Host
 * is not reachable both are disabled with the reason, rather than shown as buttons
 * that cannot work.
 */
internal fun ViewContainer<*, *>.DshSessionActionSheet(
    title: () -> String,
    manageable: () -> Boolean,
    unavailableReason: () -> String,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionColumn()
            justifyContentFlexEnd()
            backgroundColor(theme.maskLight)
        }
        View {
            attr { flex(1f) }
            event { click { onClose() } }
        }
        View {
            attr {
                flexDirectionColumn()
                padding(12f, 14f, 22f, 14f)
                borderRadius(20f)
                backgroundColor(theme.surface)
            }
            Text {
                attr {
                    text(title())
                    marginBottom(10f)
                    marginLeft(4f)
                    lines(1)
                    fontSize(13f)
                    fontWeightMedium()
                    color(theme.textMuted)
                }
            }
            vif({ manageable() }) {
                DshActionSheetRow("Rename", "Change the title on the Host") { onRename() }
                DshActionSheetRow("Archive", "Hides it from the main list; nothing is deleted") { onArchive() }
            }
            velse {
                DshDisabledSheetRow("Rename", unavailableReason())
                DshDisabledSheetRow("Archive", unavailableReason())
            }
            View {
                attr {
                    height(46f); marginTop(8f); allCenter()
                    borderRadius(12f); backgroundColor(theme.surfaceSunken)
                }
                Text { attr { text("Cancel"); fontSize(15f); color(theme.textSecondary) } }
                event { click { onClose() } }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshDisabledSheetRow(title: String, reason: String) {
    View {
        attr {
            minHeight(54f)
            marginBottom(6f)
            flexDirectionColumn()
            justifyContentCenter()
            paddingLeft(14f)
            paddingRight(14f)
            borderRadius(12f)
            backgroundColor(theme.surfaceSunken)
        }
        Text { attr { text(title); fontSize(15f); fontWeightMedium(); color(theme.textDisabled) } }
        Text { attr { text(reason); marginTop(2f); fontSize(11f); lines(2); color(theme.textMuted) } }
    }
}

/** `session/rename`. A title the Host refuses comes back as `session/title-invalid`. */
internal fun ViewContainer<*, *>.DshRenameSessionModal(
    draft: () -> String,
    busy: () -> Boolean,
    error: () -> String,
    inputRef: (ViewRef<InputView>) -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr { absolutePositionAllZero(); allCenter(); backgroundColor(theme.mask); padding(20f) }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(420f)
                flexDirectionColumn()
                padding(22f)
                borderRadius(16f)
                backgroundColor(theme.surface)
            }
            Text { attr { text("Rename conversation"); fontSize(19f); fontWeightBold(); color(theme.textPrimary) } }
            View {
                attr {
                    height(46f)
                    marginTop(16f)
                    paddingLeft(12f)
                    paddingRight(12f)
                    justifyContentCenter()
                    borderRadius(10f)
                    backgroundColor(theme.inputBackground)
                    border(Border(1f, BorderStyle.SOLID, if (error().isEmpty()) theme.inputBorder else theme.danger))
                }
                Input {
                    ref { inputRef(it) }
                    attr {
                        height(44f)
                        fontSize(15f)
                        color(theme.textPrimary)
                        placeholder("Conversation title")
                        placeholderColor(theme.placeholder)
                        returnKeyTypeSend()
                        editable(!busy())
                    }
                    event {
                        textDidChange { onDraftChange(it.text) }
                        inputReturn { onSave() }
                    }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text {
                    attr {
                        text(error())
                        marginTop(8f)
                        fontSize(12f)
                        lines(3)
                        color(theme.dangerText)
                    }
                }
            }
            View {
                attr { height(44f); marginTop(18f); flexDirectionRow() }
                View {
                    attr {
                        flex(1f); marginRight(8f); allCenter()
                        borderRadius(11f); backgroundColor(theme.surfaceSunken)
                    }
                    Text { attr { text("Cancel"); fontSize(15f); color(theme.textSecondary) } }
                    DshHitButton { if (!busy()) onClose() }
                }
                View {
                    attr {
                        flex(1f); marginLeft(8f); allCenter()
                        borderRadius(11f)
                        // Not disabled on an empty draft: the Host is the authority on
                        // titles and answers `session/title-invalid`, which is the error
                        // the user has to see.
                        backgroundColor(if (busy()) theme.accentFillDisabled else theme.accentFill)
                    }
                    Text {
                        attr {
                            text(if (busy()) "Saving…" else "Save")
                            fontSize(15f)
                            fontWeightMedium()
                            color(theme.textOnAccent)
                        }
                    }
                    DshHitButton { if (!busy()) onSave() }
                }
            }
        }
    }
}

/**
 * Archiving is confirmed, and the copy has to say what it does: the Host only adds the
 * id to its archive set, so logs and the workspace ledger stay intact.
 */
/**
 * A plain confirmation. The body text comes from whoever is asking — for plugin
 * lifecycle it is the Host's own `confirm-required` message, so the wording names the
 * module the Host actually resolved rather than one the phone guessed at.
 */
internal fun ViewContainer<*, *>.DshConfirmModal(
    title: String,
    body: () -> String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Modal(inWindow = true) {
        attr { absolutePositionAllZero(); allCenter(); backgroundColor(theme.mask); padding(20f) }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(420f)
                flexDirectionColumn()
                padding(22f)
                borderRadius(16f)
                backgroundColor(theme.surface)
            }
            Text { attr { text(title); fontSize(19f); fontWeightBold(); color(theme.textPrimary) } }
            Text {
                attr {
                    text(body())
                    marginTop(10f)
                    lines(4)
                    fontSize(14f)
                    lineHeight(20f)
                    color(theme.textSecondary)
                }
            }
            View {
                attr { height(44f); marginTop(18f); flexDirectionRow() }
                View {
                    attr {
                        flex(1f); marginRight(8f); allCenter()
                        borderRadius(11f); backgroundColor(theme.surfaceSunken)
                    }
                    Text { attr { text("Cancel"); fontSize(15f); color(theme.textSecondary) } }
                    DshHitButton { onCancel() }
                }
                View {
                    attr {
                        flex(1f); marginLeft(8f); allCenter()
                        borderRadius(11f); backgroundColor(theme.accentFill)
                    }
                    Text { attr { text(confirmLabel); fontSize(15f); fontWeightMedium(); color(theme.textOnAccent) } }
                    DshHitButton { onConfirm() }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshArchiveConfirmModal(
    title: () -> String,
    busy: () -> Boolean,
    error: () -> String,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr { absolutePositionAllZero(); allCenter(); backgroundColor(theme.mask); padding(20f) }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(420f)
                flexDirectionColumn()
                padding(22f)
                borderRadius(16f)
                backgroundColor(theme.surface)
            }
            Text { attr { text("Archive this conversation?"); fontSize(19f); fontWeightBold(); color(theme.textPrimary) } }
            Text {
                attr {
                    text(title())
                    marginTop(10f)
                    lines(2)
                    fontSize(14f)
                    fontWeightMedium()
                    color(theme.textSecondary)
                }
            }
            Text {
                attr {
                    text(
                        "Archiving hides it from the main list. It is not deleted: the history, " +
                            "the logs and the workspace entry stay on the Host, and you can open it " +
                            "again from Archived.",
                    )
                    marginTop(10f)
                    fontSize(12f)
                    lineHeight(18f)
                    color(theme.textMuted)
                }
            }
            vif({ error().isNotEmpty() }) {
                Text {
                    attr { text(error()); marginTop(10f); fontSize(12f); lines(3); color(theme.dangerText) }
                }
            }
            View {
                attr { height(44f); marginTop(18f); flexDirectionRow() }
                View {
                    attr {
                        flex(1f); marginRight(8f); allCenter()
                        borderRadius(11f); backgroundColor(theme.surfaceSunken)
                    }
                    Text { attr { text("Cancel"); fontSize(15f); color(theme.textSecondary) } }
                    DshHitButton { if (!busy()) onClose() }
                }
                View {
                    attr {
                        flex(1f); marginLeft(8f); allCenter()
                        borderRadius(11f)
                        backgroundColor(if (busy()) theme.accentFillDisabled else theme.dangerFill)
                    }
                    Text {
                        attr {
                            text(if (busy()) "Archiving…" else "Archive")
                            fontSize(15f)
                            fontWeightMedium()
                            color(theme.textOnAccent)
                        }
                    }
                    DshHitButton { if (!busy()) onConfirm() }
                }
            }
        }
    }
}

/**
 * The Host's archive set as its own list. Opening a row loads the same durable history
 * the main list does; the App keeps no separate copy of the archive.
 */
internal fun ViewContainer<*, *>.DshArchiveListModal(
    sessions: () -> ObservableList<DshSession>,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionColumn()
            justifyContentFlexEnd()
            backgroundColor(theme.maskLight)
        }
        View {
            attr { flex(1f) }
            event { click { onClose() } }
        }
        View {
            attr {
                height((pagerData.pageViewHeight * 0.6f).coerceAtMost(520f))
                flexDirectionColumn()
                padding(18f)
                borderRadius(20f)
                backgroundColor(theme.surface)
            }
            View {
                attr { height(32f); flexDirectionRow(); alignItemsCenter() }
                Text { attr { text("Archived"); flex(1f); fontSize(19f); fontWeightBold(); color(theme.textPrimary) } }
                View {
                    attr { size(32f, 32f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(20f, 20f); tintColor(theme.textSecondary) } }
                    DshHitButton { onClose() }
                }
            }
            Text {
                attr {
                    text("Hidden from the main list. Opening one shows its complete history.")
                    marginTop(4f)
                    marginBottom(10f)
                    fontSize(12f)
                    color(theme.textMuted)
                }
            }
            vif({ sessions().isEmpty() }) {
                View {
                    attr { flex(1f); allCenter() }
                    Text { attr { text("No archived conversations"); fontSize(14f); color(theme.textMuted) } }
                }
            }
            velse {
                Scroller {
                    attr { flex(1f) }
                    vfor({ sessions() }) { session ->
                        DshSessionDrawerRow(
                            title = session.title,
                            subtitle = session.workspace,
                            active = false,
                            running = session.running,
                            onSelect = { onOpen(session.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Floating bar shown while a row is in text-selection mode. */
internal fun ViewContainer<*, *>.DshSelectionBar(
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
) {
    View {
        attr {
            absolutePosition(left = 0f, right = 0f, bottom = 0f)
            height(58f)
            flexDirectionRow()
            alignItemsCenter()
            justifyContentCenter()
            zIndex(6)
            backgroundColor(theme.surface)
            borderTop(Border(1f, BorderStyle.SOLID, theme.divider))
        }
        DshSelectionBarButton("Copy", primary = true) { onCopy() }
        DshSelectionBarButton("Select all", primary = false) { onSelectAll() }
        DshSelectionBarButton("Done", primary = false) { onDone() }
    }
}

/** Batch bar for Task 2's multi-message export. Mirrors [DshSelectionBar]'s placement. */
internal fun ViewContainer<*, *>.DshBatchSelectionBar(
    count: () -> Int,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    View {
        attr {
            absolutePosition(left = 0f, right = 0f, bottom = 0f)
            height(58f)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(14f)
            paddingRight(8f)
            zIndex(6)
            backgroundColor(theme.surface)
            borderTop(Border(1f, BorderStyle.SOLID, theme.divider))
        }
        Text {
            attr {
                text(count().let { if (it == 1) "1 message" else "$it messages" })
                flex(1f)
                fontSize(14f)
                color(theme.textSecondary)
            }
        }
        DshSelectionBarButton("Copy", primary = false) { onCopy() }
        DshSelectionBarButton("Export", primary = true) { onExport() }
        DshSelectionBarButton("Cancel", primary = false) { onCancel() }
    }
}

private fun ViewContainer<*, *>.DshSelectionBarButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    View {
        attr {
            height(38f)
            marginLeft(6f)
            marginRight(6f)
            paddingLeft(18f)
            paddingRight(18f)
            allCenter()
            borderRadius(10f)
            backgroundColor(if (primary) theme.accentFill else theme.surfaceSunken)
        }
        Text {
            attr {
                text(label)
                fontSize(14f)
                fontWeightMedium()
                color(if (primary) theme.textOnAccent else theme.textSecondary)
            }
        }
        event { click { onClick() } }
    }
}

/** Task 1 appearance picker. New UI copy is English. */
internal fun ViewContainer<*, *>.DshAppearanceModal(
    themeMode: () -> DshThemeMode,
    codeTheme: () -> DshCodeTheme,
    onSelectTheme: (DshThemeMode) -> Unit,
    onSelectCodeTheme: (DshCodeTheme) -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr { absolutePositionAllZero(); allCenter(); backgroundColor(theme.mask); padding(20f) }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(440f)
                flexDirectionColumn()
                padding(22f)
                borderRadius(16f)
                backgroundColor(theme.surface)
            }
            View {
                attr { height(32f); flexDirectionRow(); alignItemsCenter() }
                Text { attr { text("Appearance"); flex(1f); fontSize(20f); fontWeightBold(); color(theme.textPrimary) } }
                View {
                    attr { size(32f, 32f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(20f, 20f); tintColor(theme.textSecondary) } }
                    DshHitButton { onClose() }
                }
            }
            Text { attr { text("Theme"); marginTop(16f); fontSize(12f); fontWeightMedium(); color(theme.textSecondary) } }
            DshAppearanceOption(
                "Light", "Always use the light palette",
                { themeMode() == DshThemeMode.LIGHT },
            ) { onSelectTheme(DshThemeMode.LIGHT) }
            DshAppearanceOption(
                "Dark", "Always use the dark palette",
                { themeMode() == DshThemeMode.DARK },
            ) { onSelectTheme(DshThemeMode.DARK) }
            DshAppearanceOption(
                "System", "Follow the device light / dark setting",
                { themeMode() == DshThemeMode.SYSTEM },
            ) { onSelectTheme(DshThemeMode.SYSTEM) }
            DshAppearanceOption(
                "Sunrise to sunset", "Dark after dusk, estimated from your time zone",
                { themeMode() == DshThemeMode.SOLAR },
            ) { onSelectTheme(DshThemeMode.SOLAR) }
            DshAppearanceOption(
                "High contrast", "Accessible dark palette with stronger contrast",
                { themeMode() == DshThemeMode.HIGH_CONTRAST },
            ) { onSelectTheme(DshThemeMode.HIGH_CONTRAST) }
            Text {
                attr {
                    text("Daylight is estimated from the device clock and time zone. The app never asks for your location, so the switch can be off by up to an hour.")
                    marginTop(10f); fontSize(12f); lineHeight(18f); color(theme.textMuted)
                }
            }
            Text { attr { text("Tool output"); marginTop(18f); fontSize(12f); fontWeightMedium(); color(theme.textSecondary) } }
            View {
                attr {
                    height(42f); marginTop(8f); flexDirectionRow()
                    borderRadius(8f); backgroundColor(theme.surfaceSunken); padding(4f)
                }
                DshAppearanceSegment("Auto", { codeTheme() == DshCodeTheme.AUTO }) { onSelectCodeTheme(DshCodeTheme.AUTO) }
                DshAppearanceSegment("Light", { codeTheme() == DshCodeTheme.LIGHT }) { onSelectCodeTheme(DshCodeTheme.LIGHT) }
                DshAppearanceSegment("Dark", { codeTheme() == DshCodeTheme.DARK }) { onSelectCodeTheme(DshCodeTheme.DARK) }
            }
            Text {
                attr {
                    text(
                        "Terminal output, diffs and JSON listings can keep their own theme. " +
                            "Markdown code blocks follow the app theme.",
                    )
                    marginTop(10f); fontSize(12f); lineHeight(18f); color(theme.textMuted)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshAppearanceOption(
    title: String,
    subtitle: String,
    selected: () -> Boolean,
    onSelect: () -> Unit,
) {
    View {
        attr {
            marginTop(8f)
            flexDirectionRow()
            alignItemsCenter()
            padding(10f, 12f, 10f, 12f)
            borderRadius(12f)
            backgroundColor(if (selected()) theme.accentSoft else theme.surfaceRaised)
            border(Border(1f, BorderStyle.SOLID, if (selected()) theme.accentBorder else theme.border))
        }
        View {
            attr {
                size(18f, 18f)
                borderRadius(9f)
                border(Border(1.5f, BorderStyle.SOLID, if (selected()) theme.accent else theme.textDisabled))
                backgroundColor(if (selected()) theme.accent else Color(0x00FFFFFF))
                allCenter()
            }
            View {
                attr {
                    size(if (selected()) 6f else 0f, if (selected()) 6f else 0f)
                    borderRadius(3f)
                    backgroundColor(theme.textOnAccent)
                }
            }
        }
        View {
            attr { flex(1f); marginLeft(10f); flexDirectionColumn() }
            Text { attr { text(title); fontSize(14f); fontWeightMedium(); color(theme.textPrimary) } }
            Text { attr { text(subtitle); marginTop(2f); fontSize(11f); lineHeight(15f); color(theme.textMuted) } }
        }
        event { click { onSelect() } }
    }
}

private fun ViewContainer<*, *>.DshAppearanceSegment(
    label: String,
    selected: () -> Boolean,
    onSelect: () -> Unit,
) {
    View {
        attr {
            flex(1f); height(34f); allCenter(); borderRadius(6f)
            backgroundColor(if (selected()) theme.surface else Color(0x00FFFFFF))
        }
        Text { attr { text(label); fontSize(13f); color(if (selected()) theme.accent else theme.textSecondary) } }
        event { click { onSelect() } }
    }
}

private const val DSH_WORDMARK_RATIO = 143f / 23f

internal fun ViewContainer<*, *>.DshWordmark(height: Float = 22f) {
    Image {
        attr {
            src(ImageUri.commonAssets("wordmark.svg"))
            width(height * DSH_WORDMARK_RATIO)
            height(height)
            resizeContain()
            tintColor(theme.textPrimary)
        }
    }
}

internal fun ViewContainer<*, *>.DshSessionDrawer(
    sessions: () -> ObservableList<DshSession>,
    workspaceGroups: () -> ObservableList<DshWorkspaceGroup>,
    isWebTimeline: () -> Boolean,
    activeId: () -> String,
    animated: () -> Boolean,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenLogs: () -> Unit,
    archivedCount: () -> Int,
    onOpenArchive: () -> Unit,
    onSessionMenu: (String) -> Unit,
    onNewSession: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionRow()
            backgroundColor(Color(0x00000000))
        }
        View {
            attr {
                width((pagerData.pageViewWidth - 44f).coerceAtMost(340f))
                height(pagerData.pageViewHeight)
                flexDirectionColumn()
                paddingTop(pagerData.statusBarHeight + 10f)
                paddingLeft(14f)
                paddingRight(14f)
                paddingBottom(18f)
                backgroundColor(theme.surfaceRaised)
                transform(Translate(if (animated()) 0f else -1f, 0f))
                animation(Animation.easeOut(0.24f), animated())
            }
            View {
                attr {
                    height(48f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                DshWordmark()
                View { attr { flex(1f) } }
                View {
                    attr { size(38f, 38f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(22f, 22f); tintColor(theme.textSecondary) } }
                    event { click { onClose() } }
                }
            }
            View {
                attr {
                    height(42f)
                    marginTop(8f)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(12f)
                    paddingRight(12f)
                    borderRadius(9f)
                    backgroundColor(theme.surfaceSunken)
                }
                Image { attr { src(ImageUri.commonAssets("plus.svg")); size(20f, 20f); tintColor(theme.textPrimary) } }
                Text {
                    attr {
                        text("新会话")
                        marginLeft(10f)
                        fontSize(14f)
                        fontWeightMedium()
                        color(theme.textPrimary)
                    }
                }
                event { click { onNewSession() } }
            }
            View {
                attr {
                    height(42f)
                    marginTop(8f)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(12f)
                    paddingRight(12f)
                    borderRadius(9f)
                    backgroundColor(Color(0x00000000))
                }
                Image { attr { src(ImageUri.commonAssets("sliders.svg")); size(20f, 20f); tintColor(theme.textSecondary) } }
                Text {
                    attr {
                        text("设置")
                        marginLeft(10f)
                        fontSize(14f)
                        fontWeightMedium()
                        color(theme.textSecondary)
                    }
                }
                event { click { onOpenSettings() } }
            }
            View {
                attr {
                    height(42f)
                    marginTop(4f)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(12f)
                    paddingRight(12f)
                    borderRadius(9f)
                    backgroundColor(Color(0x00000000))
                }
                Image { attr { src(ImageUri.commonAssets("appearance.svg")); size(20f, 20f); tintColor(theme.textSecondary) } }
                Text {
                    attr {
                        text("Appearance")
                        marginLeft(10f)
                        fontSize(14f)
                        fontWeightMedium()
                        color(theme.textSecondary)
                    }
                }
                event { click { onOpenAppearance() } }
            }
            vif({ isWebTimeline() }) {
                View {
                    attr {
                        height(42f)
                        marginTop(4f)
                        flexDirectionRow()
                        alignItemsCenter()
                        paddingLeft(12f)
                        paddingRight(12f)
                        borderRadius(9f)
                        backgroundColor(Color(0x00000000))
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("plugin.svg"))
                            size(20f, 20f)
                            tintColor(theme.textSecondary)
                        }
                    }
                    Text {
                        attr {
                            text("Plugins")
                            flex(1f)
                            marginLeft(10f)
                            fontSize(14f)
                            fontWeightMedium()
                            color(theme.textSecondary)
                        }
                    }
                    event { click { onOpenPlugins() } }
                }
            }
            View {
                attr {
                    height(42f)
                    marginTop(4f)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(12f)
                    paddingRight(12f)
                    borderRadius(9f)
                    backgroundColor(Color(0x00000000))
                }
                Image {
                    attr {
                        src(ImageUri.commonAssets("logs.svg"))
                        size(20f, 20f)
                        tintColor(theme.textSecondary)
                    }
                }
                Text {
                    attr {
                        text("Logs")
                        flex(1f)
                        marginLeft(10f)
                        fontSize(14f)
                        fontWeightMedium()
                        color(theme.textSecondary)
                    }
                }
                event { click { onOpenLogs() } }
            }
            vif({ isWebTimeline() }) {
                View {
                    attr {
                        height(42f)
                        marginTop(4f)
                        flexDirectionRow()
                        alignItemsCenter()
                        paddingLeft(12f)
                        paddingRight(12f)
                        borderRadius(9f)
                        backgroundColor(Color(0x00000000))
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("archive.svg"))
                            size(20f, 20f)
                            tintColor(theme.textSecondary)
                        }
                    }
                    Text {
                        attr {
                            text("Archived")
                            flex(1f)
                            marginLeft(10f)
                            fontSize(14f)
                            fontWeightMedium()
                            color(theme.textSecondary)
                        }
                    }
                    Text {
                        attr {
                            text(archivedCount().toString())
                            fontSize(12f)
                            color(theme.textMuted)
                        }
                    }
                    event { click { onOpenArchive() } }
                }
            }
            Text {
                attr {
                    text("会话")
                    marginTop(20f)
                    marginBottom(8f)
                    fontSize(12f)
                    color(theme.textMuted)
                }
            }
            Scroller {
                attr { flex(1f) }
                vif({ !isWebTimeline() }) {
                    vfor({ sessions() }) { session ->
                        DshSessionDrawerRow(
                            title = session.title,
                            subtitle = session.workspace,
                            active = activeId() == session.id,
                            running = session.running,
                            onSelect = { onSelect(session.id) },
                        )
                    }
                }
                vif({ isWebTimeline() }) {
                    vfor({ workspaceGroups() }) { group ->
                        View {
                            attr {
                                marginTop(10f)
                                marginBottom(6f)
                                flexDirectionColumn()
                            }
                            Text {
                                attr {
                                    text(group.title + if (group.path.isEmpty()) "" else " · ${group.path}")
                                    lines(1)
                                    fontSize(12f)
                                    fontWeightMedium()
                                    color(theme.textMuted)
                                }
                            }
                            group.sessions.forEach { session ->
                                DshSessionDrawerRow(
                                    title = session.title,
                                    subtitle = if (session.cwd.isEmpty()) group.title else session.cwd,
                                    active = activeId() == session.id,
                                    running = session.running,
                                    onSelect = { onSelect(session.id) },
                                    onMenu = { onSessionMenu(session.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
        View {
            attr {
                flex(1f)
                height(pagerData.pageViewHeight)
            }
            event { click { onClose() } }
        }
    }
}

internal fun ViewContainer<*, *>.DshSessionDrawerRow(
    title: String,
    subtitle: String,
    active: Boolean,
    running: Boolean,
    onSelect: () -> Unit,
    onMenu: (() -> Unit)? = null,
) {
    View {
        attr {
            height(48f)
            marginBottom(4f)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(12f)
            paddingRight(if (onMenu == null) 10f else 2f)
            borderRadius(9f)
            backgroundColor(if (active) theme.rowSelected else Color(0x00FFFFFF))
        }
        View {
            attr {
                size(7f, 7f)
                borderRadius(4f)
                backgroundColor(if (running) theme.accent else theme.textDisabled)
            }
        }
        View {
            attr {
                flex(1f)
                marginLeft(10f)
                flexDirectionColumn()
                justifyContentCenter()
            }
            Text {
                attr {
                    text(title)
                    lines(1)
                    fontSize(14f)
                    color(theme.textPrimary)
                }
            }
            Text {
                attr {
                    text(subtitle)
                    lines(1)
                    marginTop(2f)
                    fontSize(10f)
                    color(theme.textMuted)
                }
            }
        }
        event { click { onSelect() } }
        // A child with its own click wins the hit test for its own area, so the row's
        // select handler is not reached through the menu button.
        if (onMenu != null) {
            View {
                attr { size(40f, 44f); allCenter() }
                Text {
                    attr {
                        text("···")
                        fontSize(17f)
                        fontWeightBold()
                        color(theme.textMuted)
                    }
                }
                event { click { onMenu() } }
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshModelPicker(
    options: () -> ObservableList<DshModelOption>,
    busy: () -> Boolean,
    error: () -> String,
    onClose: () -> Unit,
    onSelect: (DshModelOption) -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionColumn()
            justifyContentFlexEnd()
            backgroundColor(theme.maskLight)
        }
        View {
            attr { flex(1f) }
            event { click { onClose() } }
        }
        View {
            attr {
                height((pagerData.pageViewHeight * 0.62f).coerceAtMost(540f))
                flexDirectionColumn()
                padding(18f)
                borderRadius(20f)
                backgroundColor(theme.surface)
            }
            View {
                attr { height(40f); flexDirectionRow(); alignItemsCenter() }
                Text {
                    attr {
                        text("选择模型")
                        fontSize(18f)
                        fontWeightBold()
                        color(theme.textPrimary)
                    }
                }
                View { attr { flex(1f) } }
                View {
                    attr { size(36f, 36f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(21f, 21f); tintColor(theme.textSecondary) } }
                    event { click { onClose() } }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text {
                    attr {
                        text(error())
                        marginTop(6f)
                        marginBottom(6f)
                        fontSize(12f)
                        color(theme.danger)
                    }
                }
            }
            vif({ busy() && options().isEmpty() }) {
                Text {
                    attr {
                        text("正在加载模型...")
                        marginTop(24f)
                        fontSize(14f)
                        color(theme.textMuted)
                    }
                }
            }
            Scroller {
                attr { flex(1f); marginTop(8f) }
                vfor({ options() }) { option ->
                    View {
                        attr {
                            minHeight(58f)
                            marginBottom(6f)
                            flexDirectionRow()
                            alignItemsCenter()
                            padding(10f, 12f, 10f, 12f)
                            borderRadius(10f)
                            backgroundColor(if (option.selected) theme.accentSoft else theme.surfaceRaised)
                        }
                        View {
                            attr { flex(1f); flexDirectionColumn() }
                            Text {
                                attr {
                                    text(option.name)
                                    fontSize(14f)
                                    fontWeightMedium()
                                    color(theme.textPrimary)
                                }
                            }
                            Text {
                                attr {
                                    text(option.providerName + if (option.description.isEmpty()) "" else " · ${option.description}")
                                    marginTop(3f)
                                    lines(1)
                                    fontSize(11f)
                                    color(theme.textMuted)
                                }
                            }
                        }
                        if (option.selected) {
                            Text { attr { text("✓"); fontSize(17f); color(theme.accent) } }
                        }
                        event { click { if (!busy()) onSelect(option) } }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshTopBar(
    title: () -> String,
    connection: () -> String,
) {
    View {
        attr {
            height(58f)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(12f)
            paddingRight(14f)
            backgroundColor(theme.surface)
            borderBottom(Border(1f, BorderStyle.SOLID, theme.divider))
        }
        View {
            attr { size(38f, 38f); allCenter() }
            Image {
                attr {
                    src(ImageUri.commonAssets("menu.svg"))
                    size(26f, 26f)
                    tintColor(theme.textPrimary)
                }
            }
        }
        Text {
            attr {
                text(title())
                marginLeft(10f)
                flex(1f)
                fontSize(17f)
                fontWeightMedium()
                color(theme.textPrimary)
                lines(1)
            }
        }
        View {
            attr {
                val ready = isConnectionReadyLabel(connection())
                height(22f)
                marginLeft(8f)
                paddingLeft(8f)
                paddingRight(8f)
                borderRadius(11f)
                backgroundColor(if (ready) theme.successSoft else theme.surfaceSunken)
                justifyContentCenter()
                alignItemsCenter()
            }
            Text {
                attr {
                    val ready = isConnectionReadyLabel(connection())
                    text(if (ready) "已连接" else topBarConnectingText(connection()))
                    fontSize(11f)
                    lines(1)
                    color(if (ready) theme.success else theme.textMuted)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshSessionRail(
    sessions: () -> ObservableList<DshSession>,
    activeId: () -> String,
    compact: Boolean,
    onSelect: (String) -> Unit,
) {
    View {
        attr {
            if (compact) {
                height(92f)
                flexDirectionRow()
            } else {
                width(236f)
                flexDirectionColumn()
            }
            backgroundColor(theme.surfaceRaised)
            padding(14f)
        }
        Text {
            attr {
                text("会话")
                fontSize(13f)
                color(theme.textMuted)
                marginBottom(9f)
            }
        }
        if (compact) {
            Scroller {
                attr {
                    flex(1f)
                    flexDirectionRow()
                }
                vfor({ sessions() }) { session ->
                    DshSessionButton(session, activeId() == session.id, onSelect)
                }
            }
        } else {
            Scroller {
                attr { flex(1f) }
                vfor({ sessions() }) { session ->
                    DshSessionButton(session, activeId() == session.id, onSelect)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshSessionButton(
    session: DshSession,
    active: Boolean,
    onSelect: (String) -> Unit,
) {
    Button {
        attr {
            height(48f)
            width(if (active) 220f else 220f)
            marginBottom(4f)
            borderRadius(7f)
            backgroundColor(if (active) theme.accentSoft else Color(0x00000000))
            titleAttr {
                text(session.title)
                color(if (active) theme.accent else theme.textSecondary)
                fontSize(13f)
            }
        }
        event { click { onSelect(session.id) } }
    }
}

internal fun ViewContainer<*, *>.DshSessionDetailsPanel(
    title: () -> String,
    cwd: () -> String,
    modelLabel: () -> String,
    agentPreset: () -> String,
    running: () -> Boolean,
    queueCount: () -> Int,
    jobCount: () -> Int,
) {
    View {
        attr {
            width(280f)
            height(pagerData.pageViewHeight)
            flexDirectionColumn()
            padding(16f)
            backgroundColor(theme.background)
            border(Border(1f, BorderStyle.SOLID, theme.border))
        }
        Text {
            attr {
                text("Session")
                fontSize(12f)
                color(theme.textMuted)
            }
        }
        Text {
            attr {
                text(title())
                marginTop(6f)
                fontSize(17f)
                fontWeightSemiBold()
                color(theme.textPrimary)
                lines(2)
            }
        }
        View {
            attr {
                height(1f)
                marginTop(14f)
                backgroundColor(theme.divider)
            }
        }
        DshDetailRow("状态", if (running()) "运行中" else "空闲")
        DshDetailRow("模型", modelLabel())
        vif({ agentPreset().isNotEmpty() }) {
            DshDetailRow("Agent Preset", agentPreset())
        }
        DshDetailRow("队列", "${queueCount()} 条")
        DshDetailRow("后台任务", "${jobCount()} 个")
        vif({ cwd().isNotEmpty() }) {
            DshDetailRow("目录", cwd())
        }
    }
}

internal fun ViewContainer<*, *>.DshDetailRow(
    label: String,
    value: String,
) {
    View {
        attr {
            minHeight(44f)
            marginTop(10f)
            flexDirectionColumn()
            justifyContentCenter()
        }
        Text {
            attr {
                text(label)
                fontSize(11f)
                color(theme.textMuted)
            }
        }
        Text {
            attr {
                text(value)
                marginTop(2f)
                fontSize(13f)
                color(theme.textSecondary)
                lines(2)
            }
        }
    }
}

internal fun ViewContainer<*, *>.DshWorkspaceBrowserModal(
    path: () -> String,
    home: () -> String,
    entries: () -> ObservableList<DshDirectoryEntry>,
    busy: () -> Boolean,
    error: () -> String,
    newName: () -> String,
    onDirectorySelect: (String) -> Unit,
    onNewNameChange: (String) -> Unit,
    onCreateDirectory: () -> Unit,
    onAdopt: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            allCenter()
            paddingLeft(20f)
            paddingRight(20f)
            backgroundColor(theme.mask)
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(560f)
                maxHeight(pagerData.pageViewHeight - 80f)
                flexDirectionColumn()
                padding(18f)
                borderRadius(16f)
                backgroundColor(theme.surface)
            }
            View {
                attr { height(36f); flexDirectionRow(); alignItemsCenter() }
                Text {
                    attr {
                        text(if (path().isEmpty()) home() else path())
                        flex(1f)
                        lines(1)
                        fontSize(17f)
                        fontWeightBold()
                        color(theme.textPrimary)
                    }
                }
                View { attr { size(32f, 32f); allCenter() }; Image { attr { src(ImageUri.commonAssets("x.svg")); size(20f, 20f); tintColor(theme.textSecondary) } }; DshHitButton { onClose() } }
            }
            Scroller {
                attr {
                    flex(1f)
                    marginTop(12f)
                    borderRadius(8f)
                    backgroundColor(theme.surfaceRaised)
                }
                vfor({ entries() }) { entry ->
                    View {
                        attr {
                            height(42f)
                            flexDirectionRow()
                            alignItemsCenter()
                            paddingLeft(10f)
                            paddingRight(10f)
                        }
                        Text {
                            attr {
                                text(entry.name)
                                flex(1f)
                                lines(1)
                                fontSize(14f)
                                color(theme.textSecondary)
                            }
                        }
                        event { click { if (!busy()) onDirectorySelect(entry.path) } }
                    }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text { attr { text(error()); marginTop(8f); fontSize(12f); color(theme.danger) } }
            }
            Input {
                attr {
                    height(38f)
                    marginTop(10f)
                    fontSize(14f)
                    placeholder("新目录名称")
                    placeholderColor(theme.placeholder)
                }
                event { textDidChange { onNewNameChange(it.text) } }
            }
            View {
                attr { height(42f); marginTop(12f); flexDirectionRow(); justifyContentFlexEnd() }
                Text {
                    attr {
                        text(if (busy()) "处理中..." else "新建目录")
                        width(88f)
                        height(38f)
                        textAlignCenter()
                        fontSize(13f)
                        color(theme.textMuted)
                    }
                    event { click { if (!busy()) onCreateDirectory() } }
                }
                Text {
                    attr {
                        text(if (busy()) "处理中..." else "使用此目录")
                        width(112f)
                        height(38f)
                        marginLeft(8f)
                        textAlignCenter()
                        fontSize(13f)
                        color(theme.accent)
                    }
                    event { click { if (!busy()) onAdopt() } }
                }
            }
        }
    }
}
