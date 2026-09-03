package com.example.dsh.theme

import com.tencent.kuikly.core.base.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DshThemeTokensTest {
    private val light = DshThemeTokens.LIGHT
    private val dark = DshThemeTokens.DARK

    @Test
    fun coreTokensDifferBetweenSchemes() {
        listOf<Pair<String, (DshThemeTokens) -> Color>>(
            "background" to { it.background },
            "surface" to { it.surface },
            "surfaceVariant" to { it.surfaceVariant },
            "primaryText" to { it.primaryText },
            "secondaryText" to { it.secondaryText },
            "primary" to { it.primary },
            "icon" to { it.icon },
            "userBubble" to { it.userBubble },
        ).forEach { (name, pick) ->
            assertNotEquals(pick(light).hexColor, pick(dark).hexColor, "token $name must differ between light and dark")
        }
        assertNotEquals(DshCodeColors.LIGHT.codeBlockBackground, DshCodeColors.DARK.codeBlockBackground)
        assertNotEquals(DshCodeColors.LIGHT.link, DshCodeColors.DARK.link)
    }

    @Test
    fun stateColorsArePairedAndDistinct() {
        listOf(light, dark).forEach { tokens ->
            tokens.stateColors().forEach { (name, pair) ->
                assertNotEquals(pair.background.hexColor, pair.foreground.hexColor, "$name fg must differ from bg")
                assertTrue(contrast(pair.foreground, pair.background) >= 4.5, "$name fg/bg contrast too low: ${contrast(pair.foreground, pair.background)}")
            }
        }
    }

    @Test
    fun bodyTextMeetsWcagAA() {
        listOf("light" to light, "dark" to dark).forEach { (scheme, t) ->
            assertAtLeast(4.5, t.primaryText, t.background, "$scheme primaryText/background")
            assertAtLeast(4.5, t.primaryText, t.surface, "$scheme primaryText/surface")
            assertAtLeast(4.5, t.primaryText, t.surfaceVariant, "$scheme primaryText/surfaceVariant")
            assertAtLeast(4.5, t.secondaryText, t.background, "$scheme secondaryText/background")
            assertAtLeast(4.5, t.secondaryText, t.surface, "$scheme secondaryText/surface")
            assertAtLeast(4.5, t.userBubbleText, t.userBubble, "$scheme userBubbleText/userBubble")
            assertAtLeast(3.0, t.tertiaryText, t.surface, "$scheme tertiaryText/surface")
            assertAtLeast(3.0, t.captionText, t.surface, "$scheme captionText/surface")
            assertAtLeast(3.0, t.icon, t.surface, "$scheme icon/surface")
            assertAtLeast(3.0, t.onPrimary, t.primary, "$scheme onPrimary/primary")
            assertAtLeast(3.0, t.primary, t.surface, "$scheme primary/surface")
            assertAtLeast(1.5, t.primaryDisabled, t.surface, "$scheme primaryDisabled/surface")
        }
        assertEquals(DshChromePalette.LIGHT_BACKGROUND, light.background.hexColor)
        assertEquals(DshChromePalette.DARK_BACKGROUND, dark.background.hexColor)
        assertEquals(DshChromePalette.LIGHT_BAR_CONTENT, light.primaryText.hexColor)
        assertEquals(DshChromePalette.DARK_BAR_CONTENT, dark.primaryText.hexColor)
        listOf("light" to DshCodeColors.LIGHT, "dark" to DshCodeColors.DARK).forEach { (scheme, c) ->
            assertAtLeast(4.5, Color(c.codeText), Color(c.codeBlockBackground), "$scheme codeText/codeBlockBackground")
            assertAtLeast(4.5, Color(c.text), Color(c.inlineCodeBackground), "$scheme text/inlineCodeBackground")
            assertAtLeast(4.5, Color(c.quoteText), Color(c.quoteBackground), "$scheme quoteText/quoteBackground")
            assertAtLeast(4.5, Color(c.formulaText), Color(c.formulaBackground), "$scheme formulaText/formulaBackground")
            assertAtLeast(3.0, Color(c.link), Color(c.codeBlockBackground), "$scheme link/codeBlockBackground")
        }
    }

    private fun assertAtLeast(min: Double, fg: Color, bg: Color, label: String) {
        val ratio = contrast(fg, bg)
        assertTrue(ratio >= min, "$label contrast $ratio < $min")
    }

    private fun DshThemeTokens.stateColors() = listOf(
        "success" to success,
        "warning" to warning,
        "error" to error,
        "info" to info,
        "running" to running,
        "disabled" to disabled,
    )

    /** WCAG 2.x 对比度。带 alpha 的前景先与背景按 alpha 合成。 */
    private fun contrast(fg: Color, bg: Color): Double {
        val b = rgb(bg)
        val f = composite(fg, b)
        val l1 = luminance(f)
        val l2 = luminance(b)
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun rgb(c: Color): Triple<Int, Int, Int> {
        val v = c.hexColor
        return Triple(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
    }

    private fun composite(fg: Color, bg: Triple<Int, Int, Int>): Triple<Int, Int, Int> {
        val alpha = ((fg.hexColor shr 24) and 0xFF).toInt() / 255.0
        val (r, g, b) = rgb(fg)
        fun mix(f: Int, back: Int) = (f * alpha + back * (1 - alpha)).toInt()
        return Triple(mix(r, bg.first), mix(g, bg.second), mix(b, bg.third))
    }

    private fun luminance(rgb: Triple<Int, Int, Int>): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(rgb.first) + 0.7152 * channel(rgb.second) + 0.0722 * channel(rgb.third)
    }
}
