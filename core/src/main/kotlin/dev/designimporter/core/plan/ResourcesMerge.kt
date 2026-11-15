package dev.designimporter.core.plan

/**
 * Keeps generated entries inside a marked block of a user-owned resources file (values/strings.xml),
 * so re-imports replace only that block and never the user's own entries.
 */
object ResourcesMerge {
    const val BEGIN = "<!-- Claude Design Importer: begin. Generated; edits between these markers are replaced on re-import -->"
    const val END = "<!-- Claude Design Importer: end -->"

    private val block = Regex("""[ \t]*<!-- Claude Design Importer: begin.*?-->.*?<!-- Claude Design Importer: end -->[ \t]*\r?\n?""", RegexOption.DOT_MATCHES_ALL)

    fun hasBlock(text: String) = block.containsMatchIn(text)

    /** [body] is the generated lines (already indented). A missing file becomes a new resources file. */
    fun merge(existing: String?, body: String): String? {
        val section = "    $BEGIN\n$body    $END\n"
        if (existing == null) return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$section</resources>\n"
        if (hasBlock(existing)) return block.replaceFirst(existing, Regex.escapeReplacement(section))
        val text = existing.replace(Regex("<resources(\\s[^>]*)?/>")) { "<resources${it.groupValues[1]}>\n</resources>" }
        val close = text.lastIndexOf("</resources>")
        if (close < 0) return null
        return text.substring(0, close).trimEnd() + "\n" + section + text.substring(close)
    }

    fun remove(existing: String): String = block.replace(existing, "")

    /** True when nothing but the XML declaration, comments and an empty <resources> element is left. */
    fun isEmpty(text: String): Boolean =
        text.replace(Regex("<\\?xml.*?\\?>|<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\s"), "")
            .let { it == "<resources></resources>" || it == "<resources/>" || it.matches(Regex("<resources[^>]*></resources>")) }
}
