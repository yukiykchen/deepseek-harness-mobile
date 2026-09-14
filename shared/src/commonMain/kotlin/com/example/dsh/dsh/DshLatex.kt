package com.example.dsh.dsh

/**
 * LaTeX support for assistant messages, as a display-time text conversion.
 *
 * KuiklyMarkdown paints a paragraph as one opaque unit, so an inline formula cannot be
 * given its own view: [substituteInline] rewrites closed `$...$` spans into a Unicode
 * approximation before the markdown parser sees them. A `$$...$$` block is left alone
 * and routed to a DSH-owned view by [blockFormula] / [renderLines].
 *
 * Everything here is display-only. The message model keeps the LaTeX source, so copy and
 * export still produce `$...$` (Task 2), and the formula-source copy bonus is free.
 *
 * Only *closed* spans convert. A formula still arriving across stream chunks stays plain
 * text until its closing delimiter lands, which is what keeps a split formula from
 * corrupting the layout, and what makes an unclosed formula fall back to its source.
 */
internal object DshLatex {

    /** A closed math span found in a raw markdown string. */
    data class Span(
        val start: Int,
        val endExclusive: Int,
        val source: String,
        val display: Boolean,
    )

    /** Rows of a rendered block formula, plus whether it is the raw source fallback. */
    data class Block(
        val lines: List<String>,
        val fallback: Boolean,
    )

    // region public API

    /**
     * Closed math spans in [text], skipping fenced code blocks and inline code spans.
     * Display (`$$`) and inline (`$`) spans are both reported, in source order.
     */
    fun scan(text: String): List<Span> {
        if (!text.contains('$')) return emptyList()
        val spans = mutableListOf<Span>()
        val masked = maskCode(text)
        var i = 0
        while (i < masked.length) {
            if (masked[i] != '$') {
                i++
                continue
            }
            val display = i + 1 < masked.length && masked[i + 1] == '$'
            val open = if (display) i + 2 else i + 1
            val close = findClose(masked, open, display)
            if (close < 0) {
                i += if (display) 2 else 1
                continue
            }
            val body = text.substring(open, close)
            if (display || isInlineMath(body)) {
                val end = close + if (display) 2 else 1
                spans.add(Span(i, end, body, display))
                i = end
            } else {
                i += 1
            }
        }
        return spans
    }

    /**
     * Replaces closed `$...$` spans with their Unicode approximation. A `$$...$$` span
     * that stands alone on its own lines is left verbatim, because [blockFormula] routes
     * it to its own view; one embedded in a paragraph is converted in place instead of
     * being painted as raw source. A span the converter cannot render faithfully keeps
     * its source text, which criterion 4 explicitly allows.
     */
    fun substituteInline(text: String): String {
        val spans = scan(text).filter { !it.display || !isStandalone(text, it) }
        if (spans.isEmpty()) return text
        val out = StringBuilder(text.length)
        var cursor = 0
        spans.forEach { span ->
            out.append(text, cursor, span.start)
            val rendered = toUnicode(span.source)
            out.append(rendered ?: text.substring(span.start, span.endExclusive))
            cursor = span.endExclusive
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /**
     * LaTeX sources in document order, for "copy formula N". The view renders Unicode,
     * so this is the only place the original source is handed back to the user.
     */
    fun sources(text: String): List<String> = scan(text).map { it.source.trim() }

    /** True when nothing but whitespace shares the span's lines, so it is its own block. */
    private fun isStandalone(text: String, span: Span): Boolean {
        val lineStart = text.lastIndexOf('\n', span.start - 1) + 1
        if (text.substring(lineStart, span.start).isNotBlank()) return false
        val lineEnd = text.indexOf('\n', span.endExclusive).let { if (it < 0) text.length else it }
        return text.substring(span.endExclusive, lineEnd).isBlank()
    }

    /** The LaTeX source of [blockContent] when the whole block is one `$$...$$` formula. */
    fun blockFormula(blockContent: String): String? {
        val trimmed = blockContent.trim()
        if (trimmed.length < 5) return null
        if (!trimmed.startsWith("$$") || !trimmed.endsWith("$$")) return null
        val body = trimmed.substring(2, trimmed.length - 2)
        if (body.contains("$$")) return null
        if (body.isBlank()) return null
        return body
    }

    /** Rows to paint for a block formula. Never empty; falls back to the source text. */
    fun renderLines(latex: String): Block {
        matrixLines(latex)?.let { return Block(it, fallback = false) }
        val rendered = toUnicode(latex)
        return if (rendered == null || rendered.isBlank()) {
            Block(listOf(latex.trim()), fallback = true)
        } else {
            Block(listOf(rendered), fallback = false)
        }
    }

    /**
     * A Unicode approximation of [latex], or null when it uses a construct this renderer
     * would misrepresent. Callers show the source text when null.
     */
    fun toUnicode(latex: String): String? {
        if (latex.contains("\\begin")) return null
        val expanded = expandCommands(latex) ?: return null
        val scripted = applyScripts(expanded) ?: return null
        val cleaned = cleanup(scripted)
        if (cleaned.contains('\\')) return null
        return cleaned.ifBlank { null }
    }

    // endregion

    // region scanning

    /**
     * Blanks out every `$` inside a fenced block or an inline code span, so code is never
     * read as math. Masking preserves length, so the indices stay valid against the input.
     */
    private fun maskCode(text: String): String {
        val out = text.toCharArray()
        var fence: String? = null
        var offset = 0
        text.split("\n").forEach { line ->
            val marker = fenceAt(line)
            when {
                fence == null && marker != null -> fence = marker
                fence != null -> {
                    mask(out, offset, offset + line.length)
                    if (marker == fence) fence = null
                }
                else -> maskInlineCode(out, line, offset)
            }
            offset += line.length + 1
        }
        return out.concatToString()
    }

    private fun fenceAt(line: String): String? {
        val body = line.trimStart()
        return when {
            body.startsWith("```") -> "```"
            body.startsWith("~~~") -> "~~~"
            else -> null
        }
    }

    private fun mask(out: CharArray, from: Int, toExclusive: Int) {
        for (i in from until minOf(toExclusive, out.size)) {
            if (out[i] == '$') out[i] = ' '
        }
    }

    private fun maskInlineCode(out: CharArray, line: String, offset: Int) {
        if (!line.contains('`')) return
        var i = 0
        while (i < line.length) {
            if (line[i] != '`') {
                i++
                continue
            }
            val ticks = runLength(line, i, '`')
            val close = findTickRun(line, i + ticks, ticks)
            if (close < 0) return
            mask(out, offset + i, offset + close + ticks)
            i = close + ticks
        }
    }

    private fun runLength(line: String, index: Int, c: Char): Int {
        var n = 0
        while (index + n < line.length && line[index + n] == c) n++
        return n
    }

    private fun findTickRun(line: String, from: Int, ticks: Int): Int {
        var i = from
        while (i < line.length) {
            if (line[i] == '`' && runLength(line, i, '`') == ticks) return i
            i++
        }
        return -1
    }

    private fun findClose(text: String, from: Int, display: Boolean): Int {
        var i = from
        var blankRun = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '\n') {
                blankRun++
                // Neither an inline nor a display span may cross a blank line: that is a
                // new markdown block, so the delimiter was never closed.
                if (blankRun > 1 || !display) return -1
                i++
                continue
            }
            if (!c.isWhitespace()) blankRun = 0
            if (c == '$') {
                val isDouble = i + 1 < text.length && text[i + 1] == '$'
                if (display && isDouble) return i
                if (!display && !isDouble) return i
                if (!display && isDouble) return -1
            }
            i++
        }
        return -1
    }

    /**
     * Guards prose against being read as math. `$5 and $10` must stay text, so an inline
     * span may not open or close on whitespace and may not be empty.
     */
    private fun isInlineMath(body: String): Boolean {
        if (body.isEmpty()) return false
        if (body.first().isWhitespace() || body.last().isWhitespace()) return false
        return true
    }

    // endregion

    // region conversion

    private fun expandCommands(latex: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < latex.length) {
            val c = latex[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            val nameEnd = commandEnd(latex, i + 1)
            if (nameEnd == i + 1) {
                // An escaped delimiter such as `\{` or `\%`.
                if (i + 1 < latex.length) {
                    out.append(latex[i + 1])
                    i += 2
                    continue
                }
                return null
            }
            val name = latex.substring(i + 1, nameEnd)
            i = nameEnd
            when (name) {
                "frac", "tfrac", "dfrac" -> {
                    val num = readGroup(latex, i) ?: return null
                    val den = readGroup(latex, num.second) ?: return null
                    val top = expandCommands(num.first) ?: return null
                    val bottom = expandCommands(den.first) ?: return null
                    out.append(fraction(applyScripts(top) ?: return null, applyScripts(bottom) ?: return null))
                    i = den.second
                }
                "sqrt" -> {
                    val arg = readGroup(latex, i) ?: return null
                    val inner = expandCommands(arg.first) ?: return null
                    val body = applyScripts(inner) ?: return null
                    out.append(if (isAtomic(body)) "√$body" else "√($body)")
                    i = arg.second
                }
                "text", "mathrm", "mathbf", "operatorname" -> {
                    val arg = readGroup(latex, i) ?: return null
                    out.append(arg.first)
                    i = arg.second
                }
                "left", "right", "big", "Big", "bigg", "Bigg", "displaystyle", "limits" -> Unit
                "quad" -> out.append("  ")
                "qquad" -> out.append("    ")
                else -> {
                    val symbol = SYMBOLS[name] ?: return null
                    out.append(symbol)
                }
            }
        }
        return out.toString()
    }

    private fun commandEnd(latex: String, from: Int): Int {
        var i = from
        while (i < latex.length && latex[i].isLetter()) i++
        return i
    }

    /** Reads `{...}` (or a single token) starting at [from]; returns the body and the index after it. */
    private fun readGroup(latex: String, from: Int): Pair<String, Int>? {
        var i = from
        while (i < latex.length && latex[i] == ' ') i++
        if (i >= latex.length) return null
        if (latex[i] != '{') {
            if (latex[i] == '\\') {
                val end = commandEnd(latex, i + 1)
                if (end > i + 1) return latex.substring(i, end) to end
            }
            return latex[i].toString() to (i + 1)
        }
        var depth = 0
        var j = i
        while (j < latex.length) {
            when (latex[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return latex.substring(i + 1, j) to (j + 1)
                }
                '\\' -> j++
            }
            j++
        }
        return null
    }

    private fun applyScripts(text: String): String? {
        if (!text.contains('^') && !text.contains('_')) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '^' && c != '_') {
                out.append(c)
                i++
                continue
            }
            val group = readGroup(text, i + 1) ?: return null
            val inner = applyScripts(group.first) ?: return null
            val table = if (c == '^') SUPERSCRIPTS else SUBSCRIPTS
            val mapped = mapAll(inner, table)
            // Unicode has no superscript `q` and only about fifteen subscript letters, so
            // a group that cannot map keeps caret/underscore notation rather than being
            // silently wrong.
            out.append(mapped ?: "$c(${inner})")
            i = group.second
        }
        return out.toString()
    }

    private fun mapAll(text: String, table: Map<Char, Char>): String? {
        val out = StringBuilder(text.length)
        text.forEach { c ->
            if (c == ' ') return@forEach
            out.append(table[c] ?: return null)
        }
        return out.toString().ifEmpty { null }
    }

    private fun fraction(numerator: String, denominator: String): String {
        val top = if (isAtomic(numerator)) numerator else "($numerator)"
        val bottom = if (isAtomic(denominator)) denominator else "($denominator)"
        return "$top⁄$bottom"
    }

    private fun isAtomic(text: String): Boolean =
        text.length == 1 || text.none { it == ' ' || it == '+' || it == '-' || it == '⁄' }

    private fun cleanup(text: String): String {
        val out = StringBuilder(text.length)
        var lastSpace = false
        text.forEach { c ->
            if (c == '{' || c == '}') return@forEach
            if (c == ' ') {
                if (!lastSpace) out.append(' ')
                lastSpace = true
            } else {
                out.append(c)
                lastSpace = false
            }
        }
        return out.toString().trim()
    }

    // endregion

    // region matrices

    private val MATRIX_FENCES = mapOf(
        "pmatrix" to Triple("⎛⎞", "⎜⎟", "⎝⎠"),
        "bmatrix" to Triple("⎡⎤", "⎢⎥", "⎣⎦"),
        "vmatrix" to Triple("││", "││", "││"),
        "matrix" to Triple("  ", "  ", "  "),
    )

    private fun matrixLines(latex: String): List<String>? {
        val trimmed = latex.trim()
        val kind = MATRIX_FENCES.keys.firstOrNull { trimmed.startsWith("\\begin{$it}") } ?: return null
        val open = "\\begin{$kind}"
        val close = "\\end{$kind}"
        if (!trimmed.endsWith(close)) return null
        val body = trimmed.substring(open.length, trimmed.length - close.length)
        val rows = body.split("\\\\").map { row ->
            row.split("&").map { cell ->
                val source = cell.trim()
                if (source.isEmpty()) "" else toUnicode(source) ?: return null
            }
        }.filter { row -> row.any { it.isNotEmpty() } }
        if (rows.isEmpty()) return null
        val columns = rows.maxOf { it.size }
        val widths = IntArray(columns) { column ->
            rows.maxOf { row -> row.getOrNull(column)?.length ?: 0 }
        }
        val (top, middle, bottom) = MATRIX_FENCES.getValue(kind)
        return rows.mapIndexed { index, row ->
            val fence = when {
                rows.size == 1 -> if (kind == "vmatrix") middle else top
                index == 0 -> top
                index == rows.lastIndex -> bottom
                else -> middle
            }
            val cells = (0 until columns).joinToString("  ") { column ->
                (row.getOrNull(column) ?: "").padEnd(widths[column])
            }
            "${fence[0]} $cells ${fence[1]}"
        }
    }

    // endregion

    // region tables

    private val SYMBOLS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
        "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ",
        "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "sum" to "Σ", "prod" to "∏", "int" to "∫", "iint" to "∬", "oint" to "∮",
        "partial" to "∂", "nabla" to "∇", "infty" to "∞", "emptyset" to "∅",
        "cdot" to "·", "cdots" to "⋯", "ldots" to "…", "dots" to "…", "vdots" to "⋮",
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆",
        "supset" to "⊃", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
        "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨",
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
        "mapsto" to "↦", "implies" to "⇒",
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "therefore" to "∴",
        "prime" to "′", "degree" to "°", "circ" to "∘",
        "log" to "log", "ln" to "ln", "exp" to "exp", "sin" to "sin", "cos" to "cos",
        "tan" to "tan", "cot" to "cot", "sec" to "sec", "csc" to "csc",
        "min" to "min", "max" to "max", "lim" to "lim", "det" to "det",
    )

    private val SUPERSCRIPTS: Map<Char, Char> = buildScriptTable(
        "0123456789+-=()n i",
        "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿ ⁱ",
    ) + buildScriptTable(
        "abcdefghijklmoprstuvwxyz",
        "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐᵒᵖʳˢᵗᵘᵛʷˣʸᶻ",
    ) + buildScriptTable("βγδφχ", "ᵝᵞᵟᵠᵡ")

    private val SUBSCRIPTS: Map<Char, Char> = buildScriptTable(
        "0123456789+-=()",
        "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎",
    ) + buildScriptTable(
        "aehijklmnoprstuvx",
        "ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ",
    ) + buildScriptTable("βγρφχ", "ᵦᵧᵨᵩᵪ")

    private fun buildScriptTable(plain: String, scripted: String): Map<Char, Char> =
        plain.indices.mapNotNull { index ->
            val from = plain[index]
            val to = scripted.getOrNull(index) ?: return@mapNotNull null
            if (from == ' ' || to == ' ') null else from to to
        }.toMap()

    // endregion
}
