package com.example.dsh.theme

/**
 * Markdown 与代码相关色值。KuiklyMarkdown 的 `MarkdownConfig` 直接接收 Long，因此这里保留 Long 形式。
 * 色值对齐 deepseek-harness `ui-theme` 的 markdown alias 与 `shiki.css`。
 */
internal data class DshCodeColors(
    val text: Long,
    val codeBlockBackground: Long,
    val inlineCodeBackground: Long,
    val codeText: Long,
    val divider: Long,
    val tableBackground: Long,
    val quoteBar: Long,
    val quoteBackground: Long,
    val quoteText: Long,
    val link: Long,
    val formulaText: Long,
    val formulaBackground: Long,
) {
    companion object {
        val LIGHT = DshCodeColors(
            text = 0xFF0F1115,
            codeBlockBackground = 0xFFF9FAFB,
            inlineCodeBackground = 0xFFEBEEF2,
            codeText = 0xFF1F1F23,
            divider = 0xFFE5E5E5,
            tableBackground = 0xFFFAFAFA,
            quoteBar = 0xFFA2A4A8,
            quoteBackground = 0xFFF5F6F7,
            quoteText = 0xFF61666D,
            link = 0xFF4176E6,
            formulaText = 0xFF0F1115,
            formulaBackground = 0xFFF9FAFB,
        )

        val DARK = DshCodeColors(
            text = 0xFFF9FAFB,
            codeBlockBackground = 0xFF1B1B1C,
            inlineCodeBackground = 0xFF2C2C2E,
            codeText = 0xFFF5F6F7,
            divider = 0xFF45474B,
            tableBackground = 0xFF202124,
            quoteBar = 0xFF858990,
            quoteBackground = 0xFF242528,
            quoteText = 0xFFB7BBC2,
            link = 0xFF78A4F8,
            formulaText = 0xFFF9FAFB,
            formulaBackground = 0xFF1B1B1C,
        )

        fun of(isDark: Boolean): DshCodeColors = if (isDark) DARK else LIGHT
    }
}
