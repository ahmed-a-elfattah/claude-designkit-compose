package dev.designimporter.core.reader

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.designimporter.core.Names
import dev.designimporter.core.model.TypographyToken

/**
 * Claude Design's design-system token file (ds/<name>/tokens.json):
 * color.themes + color.tokens[{name, value: {light, dark} | "#hex" | "{alias}", usage}],
 * type.families + type.groups[].styles[], and spacing/radius/shadow/size.tokens[{name, value, usage}].
 */
internal class ClaudeDesignTokensReader(private val b: DesignSystemBuilder) {

    companion object {
        private val dimenSections = listOf("spacing", "radius", "shadow", "size")

        fun accepts(text: String): Boolean = try {
            val o = JsonParser.parseString(text).asJsonObject
            o.obj("color")?.get("tokens")?.isJsonArray == true || dimenSections.any { o.obj(it)?.get("tokens")?.isJsonArray == true }
        } catch (_: Exception) {
            false
        }

        private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private lateinit var lightTheme: String
    private var darkTheme: String? = null

    fun read(file: ExportFile) {
        val root = JsonParser.parseString(file.text).asJsonObject
        root["name"]?.takeIf { it.isJsonPrimitive }?.let { b.name = it.asString }

        val themes = root.obj("color")?.get("themes")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { t -> t.isJsonObject }?.asJsonObject?.get("id")?.asString }.orEmpty()
        lightTheme = themes.firstOrNull { it.contains("light", true) } ?: themes.firstOrNull() ?: "light"
        darkTheme = themes.firstOrNull { it.contains("dark", true) } ?: "dark".takeIf { themes.isEmpty() }

        readColors(root.obj("color"))
        readTypography(root.obj("type"))
        readDimens(root.obj("spacing"), "spacing", Categories.SPACING) { id, dp -> b.putSpacing(id, dp) }
        readDimens(root.obj("radius"), "radius", Categories.RADIUS) { id, dp -> b.putRadius(id, dp) }
        readDimens(root.obj("size"), "size", Categories.SIZE) { id, dp -> b.putSize(id, dp) }
        readShadows(root.obj("shadow"))
    }

    private fun tokens(section: JsonObject?): List<JsonObject> =
        section?.get("tokens")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { t -> t.isJsonObject }?.asJsonObject }.orEmpty()

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /** The value for a theme: plain values apply to every theme; per-theme objects fall back to light. */
    private fun themed(value: JsonElement?, theme: String): String? = when {
        value == null || value.isJsonNull -> null
        value.isJsonPrimitive -> value.asString
        value.isJsonObject -> (value.asJsonObject[theme] ?: value.asJsonObject[lightTheme])?.takeIf { it.isJsonPrimitive }?.asString
        else -> null
    }

    private fun readColors(section: JsonObject?) {
        val list = tokens(section)
        val byName = list.associateBy { it.str("name") ?: "" }
        fun resolve(name: String, theme: String, depth: Int = 0): String? {
            if (depth > 10) return null
            val raw = themed(byName[name]?.get("value"), theme) ?: return null
            val ref = Regex("^\\{([^}]+)}$").find(raw.trim())?.groupValues?.get(1) ?: return raw
            return resolve(ref.removePrefix("color."), theme, depth + 1)
        }
        for (t in list) {
            val name = t.str("name") ?: continue
            val light = resolve(name, lightTheme)?.let { CssValues.parseColor(it) } ?: continue
            val id = Names.tokenIdentifier(name, Categories.COLOR)
            b.putColor(id, light, dark = false)
            darkTheme?.let { dt -> resolve(name, dt)?.let { CssValues.parseColor(it) }?.let { b.putColor(id, it, dark = true) } }
            b.describe("color", id, t.str("usage") ?: t.str("description"))
            b.colorAliases[name] = id
            b.colorAliases["--$name"] = id
        }
    }

    private fun readTypography(section: JsonObject?) {
        section ?: return
        val families = section.obj("families")?.entrySet()?.associate { (k, v) ->
            k to CssValues.firstFontFamily(if (v.isJsonArray) v.asJsonArray.firstOrNull()?.asString.orEmpty() else v.asString)
        }.orEmpty()
        families.forEach { (k, v) -> b.fontFamilies[k] = v }
        b.defaultFontFamily = listOf("sans", "body", "base", "default", "text", "ui").firstNotNullOfOrNull { families[it] } ?: families.values.firstOrNull()

        val groups = section["groups"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { g -> g.isJsonObject }?.asJsonObject }.orEmpty()
        val styles = groups.flatMap { g ->
            val groupFamily = g.str("family")
            g["styles"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { s -> s.isJsonObject }?.asJsonObject }.orEmpty().map { it to groupFamily }
        } + section["styles"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { s -> s.isJsonObject }?.asJsonObject }.orEmpty().map { it to null }

        for ((s, groupFamily) in styles) {
            val name = s.str("name") ?: continue
            val size = s.str("fontSize")?.let { CssValues.parseDimen(it) } ?: continue
            val familyKey = s.str("family") ?: s.str("fontFamily") ?: groupFamily
            val family = familyKey?.let { families[it] ?: CssValues.firstFontFamily(it) }
            b.mergeTypography(
                TypographyToken(
                    name = Names.tokenIdentifier(name, Categories.TYPOGRAPHY),
                    fontFamily = family,
                    fontSizeSp = size,
                    fontWeight = s.str("fontWeight")?.let { CssValues.parseFontWeight(it) },
                    lineHeightSp = s.str("lineHeight")?.let { relativeToFont(it, size, multiplierAllowed = true) },
                    letterSpacingSp = s.str("letterSpacing")?.let { relativeToFont(it, size, multiplierAllowed = false) },
                    italic = s.str("fontStyle")?.contains("italic", true) == true,
                    description = s.str("usage") ?: s.str("description"),
                )
            )
        }
    }

    private fun readDimens(section: JsonObject?, category: String, words: Set<String>, put: (String, Float) -> Unit) {
        for (t in tokens(section)) {
            val name = t.str("name") ?: continue
            val dp = themed(t["value"], lightTheme)?.let { CssValues.parseDimen(it) } ?: continue
            val id = Names.tokenIdentifier(name, words)
            put(id, dp)
            b.describe(category, id, t.str("usage") ?: t.str("description"))
        }
    }

    private fun readShadows(section: JsonObject?) {
        for (t in tokens(section)) {
            val name = t.str("name") ?: continue
            val raw = themed(t["value"], lightTheme) ?: continue
            val dp = if (raw.trim() == "none") 0f else CssValues.shadowToElevation(raw) ?: CssValues.parseDimen(raw) ?: continue
            val id = Names.tokenIdentifier(name, Categories.ELEVATION)
            b.putElevation(id, dp)
            b.describe("elevation", id, t.str("usage") ?: t.str("description"))
        }
    }
}
