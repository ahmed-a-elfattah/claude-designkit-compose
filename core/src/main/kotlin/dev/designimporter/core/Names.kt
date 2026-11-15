package dev.designimporter.core

object Names {
    private val hardKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    /** Splits "brand.primary-500", "textPrimary", "ic_arrow_back" into lowercase words. */
    fun words(raw: String): List<String> =
        raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }

    /** "space","0","5" → "space0_5": digit groups stay separated so 0.5 and 05 don't collide. */
    fun camel(words: List<String>): String = buildString {
        words.forEachIndexed { i, w ->
            if (i > 0 && last().isDigit() && w.first().isDigit()) append('_')
            append(if (i == 0) w else w.replaceFirstChar { it.uppercase() })
        }
    }

    fun pascal(words: List<String>): String = words.joinToString("") { w -> w.replaceFirstChar { it.uppercase() } }

    fun snake(words: List<String>): String = words.joinToString("_")

    /**
     * Turns a token path into a Kotlin identifier, dropping leading category words
     * ("color.brand.primary" -> "brandPrimary"). Digit-leading results keep the category ("space4").
     */
    fun tokenIdentifier(raw: String, categoryWords: Set<String>): String {
        val all = words(raw)
        var w = all
        while (w.size > 1 && w.first() in categoryWords) w = w.drop(1)
        if (w.isEmpty()) w = all.ifEmpty { listOf("token") }
        if (w.first().first().isDigit()) {
            val prefix = all.firstOrNull { it in categoryWords && !it.first().isDigit() } ?: "x"
            w = listOf(prefix) + w
        }
        return camel(w)
    }

    fun escape(identifier: String): String = if (identifier in hardKeywords) "`$identifier`" else identifier

    /** R fields are Java identifiers, so aapt rejects these as resource names ("continue", "new", …). */
    private val javaKeywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "false", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "null", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "true", "try", "void", "volatile", "while", "var", "record", "yield",
    )

    /** Android resource names: lowercase letters, digits, underscore, starting with a letter, not a Java keyword. */
    fun resourceName(raw: String): String {
        val w = words(raw).ifEmpty { listOf("res") }
        val s = snake(w)
        val name = if (s.first().isLetter()) s else "r_$s"
        return if (name in javaKeywords) "${name}_text" else name
    }

    fun unique(base: String, used: MutableSet<String>): String {
        var candidate = base
        var i = 2
        while (!used.add(candidate)) candidate = "$base${i++}"
        return candidate
    }
}
