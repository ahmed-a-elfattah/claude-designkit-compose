package dev.designimporter.core.gen

import dev.designimporter.core.model.LocalizedString

/**
 * String entries for res/values/strings.xml (default language) and res/values-<locale>/strings.xml,
 * as blocks merged into those files rather than files of their own.
 */
internal class StringsGenerator(private val strings: List<LocalizedString>, private val cfg: GeneratorConfig) {

    companion object {
        const val FILE_NAME = "strings.xml"
        /** Separate file earlier versions wrote; its keys must not count as the user's own. */
        const val LEGACY_FILE_NAME = "design_strings.xml"
    }

    fun generate(): List<GeneratedFile> {
        if (strings.isEmpty()) return emptyList()
        val locales = strings.flatMap { it.translations.keys }.distinct().sorted()
        return listOf(GeneratedFile.block("${cfg.resDir}/values/$FILE_NAME", entries(strings.associate { it.key to it.text }))) +
            locales.map { loc ->
                val values = strings.mapNotNull { s -> s.translations[loc]?.let { s.key to it } }.toMap()
                GeneratedFile.block("${cfg.resDir}/values-$loc/$FILE_NAME", entries(values))
            }
    }

    private fun entries(values: Map<String, String>): String = buildString {
        val byKey = strings.associateBy { it.key }
        for ((key, value) in values) {
            val args = byKey[key]?.args ?: 0
            val formatted = if (args == 0 && value.contains('%')) " formatted=\"false\"" else ""
            append("    <string name=\"$key\"$formatted>${escape(value)}</string>\n")
        }
    }

    /** aapt string escaping: XML entities, quotes, backslashes, newlines, leading @/? and edge whitespace. */
    private fun escape(raw: String): String {
        var s = raw
            .replace("\\", "\\\\")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        if (s.startsWith("@") || s.startsWith("?")) s = "\\" + s
        if (s != s.trim() || s.contains("  ")) s = "\"$s\""
        return s
    }
}
