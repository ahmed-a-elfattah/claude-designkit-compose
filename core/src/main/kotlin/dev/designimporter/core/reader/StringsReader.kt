package dev.designimporter.core.reader

import dev.designimporter.core.Names
import dev.designimporter.core.model.LocalizedString

/**
 * Bilingual UI text in prototype code:
 *  - helper calls with the default text and a translation: `L('Back', 'رجوع')`
 *  - objects: `{ en: 'Visa •• 4242', ar: 'فيزا •• 4242' }`
 * A call joined with `+` to a value becomes a format string ("Refund · " + money(x) → "Refund · %1$s").
 */
internal class StringsReader {
    private val lit = """'((?:[^'\\\n]|\\.)*)'|"((?:[^"\\\n]|\\.)*)"|`([^`$\\]*)`"""
    private val call = Regex("""\b[A-Za-z_$]\w{0,5}\(\s*(?:$lit)\s*,\s*(?:$lit)\s*\)""")
    private val obj = Regex("""\ben\s*:\s*(?:$lit)\s*,\s*([a-z]{2})\s*:\s*(?:$lit)""")
    private val arabic = Regex("[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]")
    private val latinLetter = Regex("[A-Za-z]")

    private class Entry(val text: String, val translations: MutableMap<String, String>, val args: Int)

    private val entries = LinkedHashMap<String, Entry>()

    fun scan(source: String) {
        val locale = Regex("""locale\s*===?\s*['"](ar|fa|ur)['"]""").find(source)?.groupValues?.get(1) ?: "ar"
        for (m in call.findAll(source)) {
            val g = m.groupValues
            val first = unescape(g[1].ifEmpty { g[2] }.ifEmpty { g[3] })
            val second = unescape(g[4].ifEmpty { g[5] }.ifEmpty { g[6] })
            if (!latinLetter.containsMatchIn(first) || !arabic.containsMatchIn(second) || arabic.containsMatchIn(first)) continue
            add(first, locale, second, joinedBefore(source, m.range.first), joinedAfter(source, m.range.last + 1))
        }
        for (m in obj.findAll(source)) {
            val g = m.groupValues
            val en = unescape(g[1].ifEmpty { g[2] }.ifEmpty { g[3] })
            val loc = g[4]
            val tr = unescape(g[5].ifEmpty { g[6] }.ifEmpty { g[7] })
            if (!latinLetter.containsMatchIn(en) || tr.isBlank()) continue
            add(en, loc, tr, prefix = false, suffix = false)
        }
    }

    private fun joinedAfter(s: String, from: Int): Boolean {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i < s.length && s[i] == '+'
    }

    private fun joinedBefore(s: String, to: Int): Boolean {
        var i = to - 1
        while (i >= 0 && s[i].isWhitespace()) i--
        return i >= 0 && s[i] == '+' && (i == 0 || s[i - 1] != '+')
    }

    private fun add(text: String, locale: String, translation: String, prefix: Boolean, suffix: Boolean) {
        if (text.isBlank()) return
        val args = (if (prefix) 1 else 0) + (if (suffix) 1 else 0)
        fun format(t: String): String {
            if (args == 0) return t
            var out = t.replace("%", "%%")
            if (prefix) out = "%1\$s" + out
            if (suffix) out += "%${if (prefix) 2 else 1}\$s"
            return out
        }
        val id = "${format(text)}|$args"
        val e = entries.getOrPut(id) { Entry(format(text), LinkedHashMap(), args) }
        e.translations.putIfAbsent(locale, format(translation))
    }

    fun result(): List<LocalizedString> {
        val used = mutableSetOf<String>()
        return entries.values.map { e -> LocalizedString(StringKeys.unique(StringKeys.fromText(e.text), used), e.text, e.translations, e.args) }
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            val n = s[i + 1]
            when (n) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(s.length, i + 6))
                    if (hex.length == 4 && hex.all { it.isLetterOrDigit() }) { sb.append(hex.toInt(16).toChar()); i += 6 } else { sb.append(n); i += 2 }
                }
                'x' -> {
                    val hex = s.substring(i + 2, minOf(s.length, i + 4))
                    if (hex.length == 2) { sb.append(hex.toInt(16).toChar()); i += 4 } else { sb.append(n); i += 2 }
                }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }
}

object StringKeys {
    /** "Capture 50% · %1$s" → "capture_50". At most five words / 40 characters. */
    fun fromText(text: String): String {
        val words = Names.words(text.replace(Regex("%\\d\\\$s|%%"), " ").replace(Regex("['’]"), "")).take(5)
        var key = Names.resourceName(words.joinToString("_").ifEmpty { "text" })
        if (key.length > 40) key = key.substring(0, 40).substringBeforeLast('_')
        return key
    }

    fun unique(base: String, used: MutableSet<String>): String {
        var k = base
        var i = 2
        while (!used.add(k)) k = "${base}_${i++}"
        return k
    }

    /** Renames keys that already exist in the module's own string resources (e.g. "app_name"). */
    fun avoid(strings: List<LocalizedString>, reserved: Set<String>): List<LocalizedString> {
        if (reserved.isEmpty()) return strings
        val used = reserved.toMutableSet()
        return strings.map { s -> if (s.key in reserved) s.copy(key = unique("${s.key}_text", used)) else { used += s.key; s } }
    }
}
