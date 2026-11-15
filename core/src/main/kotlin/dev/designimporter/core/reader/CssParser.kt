package dev.designimporter.core.reader

internal data class CssRule(val selectors: List<String>, val decls: Map<String, String>, val media: String)

/** Minimal CSS parser: rules, @media/@supports/@layer nesting and @font-face. Enough for token extraction. */
internal object CssParser {
    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val groupingAtRules = listOf("@supports", "@layer", "@container", "@scope")

    fun parse(css: String): List<CssRule> {
        val out = mutableListOf<CssRule>()
        parseBlock(css.replace(comments, ""), "", out)
        return out
    }

    private fun parseBlock(s: String, media: String, out: MutableList<CssRule>) {
        var i = 0
        while (i < s.length) {
            val brace = indexOutsideStrings(s, '{', i)
            if (brace < 0) break
            val semi = indexOutsideStrings(s, ';', i)
            if (semi in i until brace) {
                i = semi + 1
                continue
            }
            val prelude = s.substring(i, brace).trim()
            val end = matchingBrace(s, brace)
            val body = s.substring(brace + 1, end)
            when {
                prelude.startsWith("@media") -> parseBlock(body, "$media $prelude".trim(), out)
                // @layer/@supports/@container don't make rules conditional on theme or viewport
                groupingAtRules.any { prelude.startsWith(it) } -> parseBlock(body, media, out)
                prelude.startsWith("@font-face") -> out += CssRule(listOf("@font-face"), declarations(body), media)
                prelude.startsWith("@") -> Unit
                prelude.isNotEmpty() -> {
                    val selectors = prelude.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    out += CssRule(selectors, declarations(body), media)
                    if (body.contains('{')) parseNested(selectors, body, media, out)
                }
            }
            i = end + 1
        }
    }

    /** CSS nesting: `.btn { &.primary { … } }` → `.btn.primary`. */
    private fun parseNested(parents: List<String>, body: String, media: String, out: MutableList<CssRule>) {
        val nested = mutableListOf<CssRule>()
        parseBlock(body.replace(Regex("(^|[;}])\\s*[\\w-]+\\s*:[^;{}]*(?=;)"), "$1"), media, nested)
        for (rule in nested) {
            val selectors = rule.selectors.flatMap { child ->
                parents.map { p -> if (child.contains('&')) child.replace("&", p) else "$p $child" }
            }
            out += rule.copy(selectors = selectors)
        }
    }

    fun declarations(body: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val segment = StringBuilder()
        var depthParen = 0
        var i = 0
        fun flush() {
            val text = segment.toString()
            segment.clear()
            val colon = text.indexOf(':')
            if (colon <= 0) return
            val prop = text.substring(0, colon).trim().lowercase()
            val value = text.substring(colon + 1).replace("!important", "").trim()
            if (prop.isNotEmpty() && value.isNotEmpty() && !prop.contains(' ')) result[prop] = value
        }
        while (i < body.length) {
            val c = body[i]
            when {
                c == '(' -> { depthParen++; segment.append(c) }
                c == ')' -> { depthParen--; segment.append(c) }
                c == '{' && depthParen == 0 -> {
                    segment.clear()
                    i = matchingBrace(body, i)
                }
                c == ';' && depthParen == 0 -> flush()
                else -> segment.append(c)
            }
            i++
        }
        flush()
        return result
    }

    private fun indexOutsideStrings(s: String, target: Char, from: Int): Int {
        var quote: Char? = null
        for (i in from until s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == quote && s[i - 1] != '\\') quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == target) {
                return i
            }
        }
        return -1
    }

    private fun matchingBrace(s: String, open: Int): Int {
        var depth = 0
        var quote: Char? = null
        for (i in open until s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == quote && s[i - 1] != '\\') quote = null
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return s.length - 1
    }
}
