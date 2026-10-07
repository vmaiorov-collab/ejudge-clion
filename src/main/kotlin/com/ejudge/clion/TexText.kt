package com.ejudge.clion

/**
 * Converts TeX math (the kind used in ejudge statements) into plain text with unicode symbols
 * and <sup>/<sub> for the Swing HTML renderer. Unknown commands degrade to their bare name.
 */
object TexText {
    private val symbols = mapOf(
        "le" to "≤", "leq" to "≤", "leqslant" to "≤", "ge" to "≥", "geq" to "≥", "geqslant" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "sim" to "∼", "equiv" to "≡", "ll" to "≪", "gg" to "≫",
        "lt" to "&lt;", "gt" to "&gt;", "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮", "to" to "→", "rightarrow" to "→",
        "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "leftrightarrow" to "↔",
        "infty" to "∞", "sum" to "∑", "prod" to "∏", "in" to "∈", "notin" to "∉", "oplus" to "⊕", "otimes" to "⊗",
        "lvert" to "|", "rvert" to "|", "vert" to "|", "mid" to "|", "lVert" to "‖", "rVert" to "‖", "Vert" to "‖",
        "langle" to "⟨", "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉", "lfloor" to "⌊", "rfloor" to "⌋",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "varepsilon" to "ε", "epsilon" to "ε",
        "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "sigma" to "σ", "phi" to "φ", "varphi" to "φ",
        "omega" to "ω", "Delta" to "Δ", "Sigma" to "Σ", "Omega" to "Ω", "cup" to "∪", "cap" to "∩",
        "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "emptyset" to "∅", "forall" to "∀", "exists" to "∃",
        "land" to "∧", "lor" to "∨", "neg" to "¬", "ell" to "ℓ", "circ" to "∘", "partial" to "∂", "nabla" to "∇",
        "bmod" to " mod ", "mod" to " mod ", "quad" to " ", "qquad" to "  ",
    )
    private val names = setOf("max", "min", "gcd", "lcm", "log", "ln", "lg", "sin", "cos", "tan", "exp", "sup", "inf", "det", "deg")
    private val ignored = setOf("limits", "nolimits", "displaystyle", "textstyle", "scriptstyle", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr")
    private val mono = setOf("texttt", "mathtt")
    private val plainWrappers = setOf(
        "mathrm", "text", "mathit", "mathbf", "textbf", "textit", "mathbin", "mathrel", "mathop", "mathcal", "mathsf",
        "operatorname", "boldsymbol", "mbox", "textrm", "mathbb", "overline", "underline", "bf", "it", "rm",
    )

    fun toHtml(src: String): String = Parser(src).sequence(null).replace(Regex("\\s+"), " ").trim()

    private class Parser(val s: String) {
        var i = 0

        fun sequence(end: Char?): String {
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (end != null && c == end) { i++; return sb.toString() }
                when (c) {
                    '{' -> { i++; sb.append(sequence('}')) }
                    '}' -> i++
                    '^' -> { i++; sb.append("<sup>").append(arg()).append("</sup>") }
                    '_' -> { i++; sb.append("<sub>").append(arg()).append("</sub>") }
                    '\\' -> { i++; sb.append(command()) }
                    '~' -> { i++; sb.append(" ") }
                    '$' -> i++
                    '<' -> { i++; sb.append("&lt;") }
                    '>' -> { i++; sb.append("&gt;") }
                    '&' -> { i++; sb.append("&amp;") }
                    else -> { i++; sb.append(c) }
                }
            }
            return sb.toString()
        }

        /** One TeX argument: a {group}, a command, or a single character. */
        fun arg(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            return when (val c = s[i]) {
                '{' -> { i++; sequence('}') }
                '\\' -> { i++; command() }
                '<' -> { i++; "&lt;" }
                '>' -> { i++; "&gt;" }
                '&' -> { i++; "&amp;" }
                else -> { i++; c.toString() }
            }
        }

        fun command(): String {
            if (i >= s.length) return ""
            val c = s[i]
            if (!c.isLetter()) {
                i++
                return when (c) {
                    '{', '}', '%', '#', '_', '$', '&', '|' -> if (c == '&') "&amp;" else c.toString()
                    ',', ';', ':', ' ', '\\' -> " "
                    else -> ""
                }
            }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            return when {
                name == "frac" || name == "dfrac" || name == "tfrac" -> {
                    val a = arg(); val b = arg()
                    fun wrap(x: String) = if (Regex("[\\s+\\-·×=]").containsMatchIn(x.replace(Regex("<[^>]*>"), ""))) "($x)" else x
                    "${wrap(a)}/${wrap(b)}"
                }
                name == "sqrt" -> {
                    if (i < s.length && s[i] == '[') { while (i < s.length && s[i] != ']') i++; i++ }
                    "√(${arg()})"
                }
                name == "left" || name == "right" -> if (i < s.length && s[i] == '.') { i++; "" } else ""
                name in ignored -> ""
                name in mono -> "<tt>${arg()}</tt>"
                name in plainWrappers -> arg()
                name in names -> name
                else -> symbols[name] ?: name
            }
        }
    }
}
