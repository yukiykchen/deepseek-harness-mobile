package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshLatexTest {

    // region scanning

    @Test
    fun findsInlineAndDisplaySpans() {
        val spans = DshLatex.scan("Mass is \$E = mc^2\$ and\n\n\$\$x^2 + y^2 = z^2\$\$\n")
        assertEquals(2, spans.size)
        assertEquals("E = mc^2", spans[0].source)
        assertEquals(false, spans[0].display)
        assertEquals("x^2 + y^2 = z^2", spans[1].source)
        assertEquals(true, spans[1].display)
    }

    @Test
    fun leavesCurrencyAlone() {
        assertEquals(emptyList(), DshLatex.scan("it costs \$5 and \$10 today"))
        assertEquals("it costs \$5 and \$10 today", DshLatex.substituteInline("it costs \$5 and \$10 today"))
    }

    @Test
    fun ignoresDollarsInsideInlineCode() {
        val text = "inline `code with \$dollar\$ signs` must not convert"
        assertEquals(emptyList(), DshLatex.scan(text))
        assertEquals(text, DshLatex.substituteInline(text))
    }

    @Test
    fun ignoresDollarsInsideFencedCode() {
        val text = "before\n\n```sh\necho \$x^2\$\n```\n\nafter \$a_1\$"
        val spans = DshLatex.scan(text)
        assertEquals(1, spans.size)
        assertEquals("a_1", spans[0].source)
    }

    @Test
    fun anUnclosedSpanIsNotMath() {
        assertEquals(emptyList(), DshLatex.scan("An unclosed formula stays readable: \$a + b = c"))
        assertEquals(emptyList(), DshLatex.scan("\$\$x^2 + y^2"))
    }

    @Test
    fun anInlineSpanDoesNotCrossALineBreak() {
        assertEquals(emptyList(), DshLatex.scan("open \$a + b\nc\$ close"))
    }

    /** A formula arriving across chunks must stay text until its closing delimiter lands. */
    @Test
    fun aSplitFormulaConvertsOnlyOnceClosed() {
        val prefix = "Euler: \$e^{i"
        assertEquals(prefix, DshLatex.substituteInline(prefix))
        val whole = "Euler: \$e^{i\\pi} + 1 = 0\$"
        assertTrue(DshLatex.substituteInline(whole).contains("π"))
    }

    // endregion

    // region conversion

    @Test
    fun convertsSuperscriptsAndSubscripts() {
        assertEquals("E = mc²", DshLatex.toUnicode("E = mc^2"))
        assertEquals("x₁ + x₂", DshLatex.toUnicode("x_1 + x_2"))
    }

    @Test
    fun convertsGreekAndOperators() {
        assertEquals("α · β ≤ ∞", DshLatex.toUnicode("\\alpha \\cdot \\beta \\leq \\infty"))
        assertEquals("Σᵢ₌₁ⁿ xᵢ", DshLatex.toUnicode("\\sum_{i=1}^{n} x_i"))
    }

    @Test
    fun convertsFractionsAndRoots() {
        assertEquals("a⁄b", DshLatex.toUnicode("\\frac{a}{b}"))
        assertEquals("√δ", DshLatex.toUnicode("\\sqrt{\\delta}"))
        assertEquals("(a + b)⁄√c", DshLatex.toUnicode("\\frac{a + b}{\\sqrt{c}}"))
    }

    /**
     * Unicode has no superscript `pi`, so the group keeps caret notation instead of
     * dropping the character or failing the whole formula.
     */
    @Test
    fun keepsCaretNotationForUnmappableScripts() {
        assertEquals("e^(iπ) + 1 = 0", DshLatex.toUnicode("e^{i\\pi} + 1 = 0"))
        assertEquals("x^(q)", DshLatex.toUnicode("x^{q}"))
    }

    @Test
    fun refusesUnknownCommands() {
        assertNull(DshLatex.toUnicode("\\mathbb{R}^n"))
        assertNull(DshLatex.toUnicode("\\begin{align} a &= b \\end{align}"))
    }

    // endregion

    // region blocks

    @Test
    fun recognisesABlockFormula() {
        assertEquals("x^2 + y^2 = z^2", DshLatex.blockFormula("\$\$x^2 + y^2 = z^2\$\$"))
        assertEquals("a", DshLatex.blockFormula("  \$\$a\$\$  \n"))
        assertNull(DshLatex.blockFormula("text \$\$a\$\$ trailing"))
        assertNull(DshLatex.blockFormula("\$\$ \$\$"))
        assertNull(DshLatex.blockFormula("plain paragraph"))
    }

    @Test
    fun rendersABlockFormulaAsOneRow() {
        val block = DshLatex.renderLines("x^2 + y^2 = z^2")
        assertEquals(listOf("x² + y² = z²"), block.lines)
        assertEquals(false, block.fallback)
    }

    @Test
    fun fallsBackToSourceForAnUnrenderableBlock() {
        val block = DshLatex.renderLines("\\mathbb{R}^n")
        assertEquals(listOf("\\mathbb{R}^n"), block.lines)
        assertEquals(true, block.fallback)
    }

    @Test
    fun rendersAMatrixAsAlignedRows() {
        val block = DshLatex.renderLines("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}")
        assertEquals(listOf("⎛ a  b ⎞", "⎝ c  d ⎠"), block.lines)
        assertEquals(false, block.fallback)
    }

    @Test
    fun padsMatrixColumnsToTheWidestCell() {
        val block = DshLatex.renderLines("\\begin{bmatrix} 1 & 200 \\\\ 30 & 4 \\end{bmatrix}")
        assertEquals(listOf("⎡ 1   200 ⎤", "⎣ 30  4   ⎦"), block.lines)
    }

    // endregion
}
