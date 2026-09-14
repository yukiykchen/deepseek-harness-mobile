package com.example.dsh.dsh

import com.tencent.kuikly.core.base.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Task 1 criteria 3 and 4 are about legibility, not about which colours were chosen, so
 * they are testable: every text token must clear the WCAG 2.1 contrast ratio against the
 * surfaces it is actually painted on, in all four modes and in both code themes. A
 * palette edit that makes dark mode unreadable fails here instead of in a demo.
 */
class DshThemeContrastTest {

    private val palettes: List<Pair<String, DshPalette>> = listOf(
        "light" to DshTheme.palette(DshThemeMode.LIGHT, false, false, DshCodeTheme.AUTO),
        "dark" to DshTheme.palette(DshThemeMode.DARK, false, false, DshCodeTheme.AUTO),
        "high-contrast" to DshTheme.palette(DshThemeMode.HIGH_CONTRAST, false, false, DshCodeTheme.AUTO),
        "system-dark" to DshTheme.palette(DshThemeMode.SYSTEM, true, false, DshCodeTheme.AUTO),
        "solar-night" to DshTheme.palette(DshThemeMode.SOLAR, false, true, DshCodeTheme.AUTO),
        "light-code-dark" to DshTheme.palette(DshThemeMode.LIGHT, false, false, DshCodeTheme.DARK),
        "dark-code-light" to DshTheme.palette(DshThemeMode.DARK, false, false, DshCodeTheme.LIGHT),
    )

    @Test
    fun bodyTextClearsWcagAa() {
        palettes.forEach { (name, p) ->
            atLeast(4.5, p.textPrimary, p.background, "$name textPrimary on background")
            atLeast(4.5, p.textPrimary, p.surface, "$name textPrimary on surface")
            atLeast(4.5, p.textPrimary, p.surfaceRaised, "$name textPrimary on surfaceRaised")
            atLeast(4.5, p.textSecondary, p.background, "$name textSecondary on background")
            atLeast(4.5, p.textSecondary, p.surface, "$name textSecondary on surface")
            atLeast(4.5, p.userBubbleText, p.userBubble, "$name userBubbleText on userBubble")
            atLeast(4.5, p.textOnAccent, p.accentFill, "$name textOnAccent on accentFill")
        }
    }

    /** Secondary text, icons and accents only need the 3.0 large-text / non-text ratio. */
    @Test
    fun supportingTextClearsTheLargeTextRatio() {
        palettes.forEach { (name, p) ->
            atLeast(3.0, p.textMuted, p.surface, "$name textMuted on surface")
            atLeast(3.0, p.placeholder, p.inputBackground, "$name placeholder on inputBackground")
            atLeast(3.0, p.accent, p.surface, "$name accent on surface")
        }
    }

    /** Criterion 3 names code blocks, formulas, tool states and error colours. */
    @Test
    fun codeAndStateColoursStayReadable() {
        palettes.forEach { (name, p) ->
            atLeast(4.5, p.codeText, p.codeBackground, "$name codeText on codeBackground")
            atLeast(3.0, p.codeTextMuted, p.codeBackground, "$name codeTextMuted on codeBackground")
            atLeast(3.0, p.codeAccent, p.codeBackground, "$name codeAccent on codeBackground")
            atLeast(4.5, p.textPrimary, p.toolCardBackground, "$name tool card body")
            atLeast(4.5, p.textPrimary, p.toolCardRunningBackground, "$name running tool card body")
            atLeast(4.5, p.dangerText, p.toolCardErrorBackground, "$name dangerText on failed tool card")
            atLeast(4.5, p.dangerText, p.errorBubble, "$name dangerText on errorBubble")
            atLeast(4.5, p.warningText, p.warningSoft, "$name warningText on warningSoft")
        }
    }

    /** A dark mode that forgot to override a token would otherwise be invisible here. */
    @Test
    fun lightAndDarkActuallyDiffer() {
        val light = DshTheme.palette(DshThemeMode.LIGHT, false, false, DshCodeTheme.AUTO)
        val dark = DshTheme.palette(DshThemeMode.DARK, false, false, DshCodeTheme.AUTO)
        listOf<Pair<String, (DshPalette) -> Color>>(
            "background" to { it.background },
            "surface" to { it.surface },
            "surfaceRaised" to { it.surfaceRaised },
            "surfaceSunken" to { it.surfaceSunken },
            "textPrimary" to { it.textPrimary },
            "textSecondary" to { it.textSecondary },
            "border" to { it.border },
            "codeBackground" to { it.codeBackground },
            "codeText" to { it.codeText },
            "toolCardBackground" to { it.toolCardBackground },
            "userBubble" to { it.userBubble },
        ).forEach { (token, pick) ->
            assertNotEquals(pick(light).hexColor, pick(dark).hexColor, "$token must differ between light and dark")
        }
    }

    /** High contrast has to actually beat plain dark, or the accessibility claim is empty. */
    @Test
    fun highContrastBeatsPlainDark() {
        val dark = DshTheme.palette(DshThemeMode.DARK, false, false, DshCodeTheme.AUTO)
        val hc = DshTheme.palette(DshThemeMode.HIGH_CONTRAST, false, false, DshCodeTheme.AUTO)
        assertTrue(
            contrast(hc.textPrimary, hc.background) > contrast(dark.textPrimary, dark.background),
            "high-contrast body text must out-contrast dark",
        )
        atLeast(7.0, hc.textPrimary, hc.background, "high-contrast textPrimary on background (AAA)")
    }

    private fun atLeast(minimum: Double, foreground: Color, background: Color, label: String) {
        val ratio = contrast(foreground, background)
        assertTrue(ratio >= minimum, "$label: contrast $ratio is below $minimum")
    }

    /** WCAG 2.1 contrast. A translucent foreground is composited over its background first. */
    private fun contrast(foreground: Color, background: Color): Double {
        val bg = rgb(background)
        val fg = composite(rgb(foreground), alpha(foreground), bg)
        val lighter = maxOf(luminance(fg), luminance(bg))
        val darker = minOf(luminance(fg), luminance(bg))
        return ((lighter + 0.05) / (darker + 0.05) * 1000).toInt() / 1000.0
    }

    private fun rgb(color: Color): Triple<Int, Int, Int> {
        val hex = color.hexColor
        return Triple(
            ((hex shr 16) and 0xFF).toInt(),
            ((hex shr 8) and 0xFF).toInt(),
            (hex and 0xFF).toInt(),
        )
    }

    private fun alpha(color: Color): Double = ((color.hexColor shr 24) and 0xFF).toDouble() / 255.0

    private fun composite(
        foreground: Triple<Int, Int, Int>,
        alpha: Double,
        background: Triple<Int, Int, Int>,
    ): Triple<Int, Int, Int> {
        if (alpha >= 1.0) return foreground
        fun blend(f: Int, b: Int) = (f * alpha + b * (1 - alpha)).toInt()
        return Triple(
            blend(foreground.first, background.first),
            blend(foreground.second, background.second),
            blend(foreground.third, background.third),
        )
    }

    private fun luminance(rgb: Triple<Int, Int, Int>): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(rgb.first) + 0.7152 * channel(rgb.second) + 0.0722 * channel(rgb.third)
    }
}
