package dev.designimporter.core.reader

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.designimporter.core.Names
import dev.designimporter.core.model.TypographyToken

/**
 * Reads W3C design-token JSON ($value/$type) and the Tokens Studio flavour (value/type).
 * Modes come from top-level "light"/"dark" groups or a file name containing "dark".
 */
internal class DtcgJsonReader(private val b: DesignSystemBuilder) {

    private class RawToken(val path: List<String>, val type: String?, val value: JsonElement)

    companion object {
        fun looksLikeTokens(text: String) = text.contains("\"\$value\"") || Regex("\"value\"\\s*:").containsMatchIn(text)
    }

    fun read(file: ExportFile) {
        val root = try {
            JsonParser.parseString(file.text).takeIf { it.isJsonObject }?.asJsonObject ?: return
        } catch (e: Exception) {
            b.warnings += "${file.path}: invalid JSON (${e.message})"
            return
        }
        val fileDark = file.name.contains("dark", ignoreCase = true)
        val modeKeys = root.keySet().filter { it.lowercase() in setOf("light", "dark") && root[it].isJsonObject }
        val sets = mutableListOf<Pair<JsonObject, Boolean>>()
        if (modeKeys.isNotEmpty()) {
            val rest = JsonObject()
            root.entrySet().filter { it.key !in modeKeys }.forEach { rest.add(it.key, it.value) }
            sets += rest to fileDark
            modeKeys.sortedBy { it.lowercase() == "dark" }.forEach { sets += root[it].asJsonObject to (it.lowercase() == "dark") }
        } else {
            sets += root to fileDark
        }

        val lightTokens = LinkedHashMap<String, RawToken>()
        val darkTokens = LinkedHashMap<String, RawToken>()
        sets.forEach { (obj, dark) -> flatten(obj, emptyList(), null, if (dark) darkTokens else lightTokens) }

        lightTokens.values.forEach { classify(it, dark = false, scope = lightTokens, fallback = lightTokens) }
        darkTokens.values.forEach { classify(it, dark = true, scope = darkTokens, fallback = lightTokens) }
    }

    private fun flatten(obj: JsonObject, path: List<String>, inheritedType: String?, out: MutableMap<String, RawToken>) {
        val groupType = (obj["\$type"] ?: obj["type"]?.takeIf { it.isJsonPrimitive && !obj.has("value") })
            ?.takeIf { it.isJsonPrimitive }?.asString ?: inheritedType
        for ((key, child) in obj.entrySet()) {
            if (key.startsWith("$") || !child.isJsonObject) continue
            val c = child.asJsonObject
            val value = c["\$value"] ?: c["value"]?.takeIf { !it.isJsonObject || isCompositeValue(it.asJsonObject) }
            val p = path + key
            if (value != null) {
                val type = (c["\$type"] ?: c["type"])?.takeIf { it.isJsonPrimitive }?.asString ?: groupType
                out[p.joinToString(".")] = RawToken(p, type?.lowercase(), value)
            } else {
                flatten(c, p, groupType, out)
            }
        }
    }

    private fun isCompositeValue(o: JsonObject) =
        o.has("fontSize") || o.has("fontFamily") || o.has("offsetX") || o.has("blur") || o.has("colorSpace") || o.has("unit")

    private fun resolve(e: JsonElement, scope: Map<String, RawToken>, fallback: Map<String, RawToken>, depth: Int = 0): JsonElement {
        if (depth > 10 || !e.isJsonPrimitive || !e.asJsonPrimitive.isString) return e
        val s = e.asString.trim()
        val ref = Regex("^\\{([^}]+)}$").find(s)?.groupValues?.get(1) ?: return e
        val target = scope[ref] ?: fallback[ref]
            ?: scope.entries.firstOrNull { it.key.endsWith(".$ref") }?.value
            ?: fallback.entries.firstOrNull { it.key.endsWith(".$ref") }?.value
            ?: return e
        return resolve(target.value, scope, fallback, depth + 1)
    }

    private fun classify(t: RawToken, dark: Boolean, scope: Map<String, RawToken>, fallback: Map<String, RawToken>) {
        val value = resolve(t.value, scope, fallback)
        val raw = t.path.joinToString(".")
        val words = t.path.flatMap { Names.words(it) }
        val type = t.type
        val str = value.takeIf { it.isJsonPrimitive }?.asString

        when {
            type == "color" || (type == null && str != null && CssValues.parseColor(str) != null) -> {
                val argb = colorOf(value) ?: return
                val id = Names.tokenIdentifier(raw, Categories.COLOR)
                b.colorAliases[raw] = id
                b.putColor(id, argb, dark)
            }
            dark -> Unit
            type == "typography" || (value.isJsonObject && value.asJsonObject.has("fontSize")) -> {
                if (value.isJsonObject) readTypography(raw, value.asJsonObject, scope, fallback)
            }
            type in setOf("shadow", "boxshadow") -> {
                elevationOf(value, scope, fallback)?.let { b.putElevation(Names.tokenIdentifier(raw, Categories.ELEVATION), it) }
            }
            type in setOf("fontfamily", "fontfamilies") -> {
                val family = when {
                    value.isJsonArray && value.asJsonArray.size() > 0 -> value.asJsonArray[0].asString
                    str != null -> CssValues.firstFontFamily(str)
                    else -> return
                }
                b.fontFamilies[raw] = family
                if (words.any { it in setOf("body", "base", "sans", "primary", "default", "text") }) b.defaultFontFamily = family
            }
            else -> {
                val dp = dimenOf(value) ?: return
                when {
                    type == "borderradius" || Categories.isRadius(words) -> b.putRadius(Names.tokenIdentifier(raw, Categories.RADIUS), dp)
                    type == "spacing" || Categories.isSpacing(words) -> b.putSpacing(Names.tokenIdentifier(raw, Categories.SPACING), dp)
                    Categories.isElevation(words) -> b.putElevation(Names.tokenIdentifier(raw, Categories.ELEVATION), dp)
                }
            }
        }
    }

    private fun colorOf(v: JsonElement): Long? = when {
        v.isJsonPrimitive -> CssValues.parseColor(v.asString)
        v.isJsonObject -> {
            val o = v.asJsonObject
            o["hex"]?.asString?.let { hex ->
                val base = CssValues.parseColor(hex) ?: return null
                val alpha = o["alpha"]?.asFloat ?: return base
                (Math.round(alpha * 255).toLong() shl 24) or (base and 0xFFFFFF)
            } ?: o["components"]?.takeIf { it.isJsonArray }?.asJsonArray?.let { comps ->
                if (comps.size() < 3) return null
                val c = comps.map { (it.asFloat.coerceIn(0f, 1f) * 255).toInt() }
                val a = Math.round((o["alpha"]?.asFloat ?: 1f) * 255).toLong()
                (a shl 24) or (c[0].toLong() shl 16) or (c[1].toLong() shl 8) or c[2].toLong()
            }
        }
        else -> null
    }

    private fun dimenOf(v: JsonElement): Float? = when {
        v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asFloat
        v.isJsonPrimitive -> CssValues.parseDimen(v.asString)
        v.isJsonObject && v.asJsonObject.has("value") -> {
            val o = v.asJsonObject
            CssValues.parseDimen(o["value"].asString + (o["unit"]?.asString ?: "px"))
        }
        else -> null
    }

    private fun elevationOf(v: JsonElement, scope: Map<String, RawToken>, fallback: Map<String, RawToken>): Float? {
        val first = if (v.isJsonArray) (v as JsonArray).firstOrNull() ?: return null else v
        if (first.isJsonPrimitive) return CssValues.shadowToElevation(first.asString)
        if (!first.isJsonObject) return null
        val o = first.asJsonObject
        fun dim(vararg keys: String) = keys.firstNotNullOfOrNull { k -> o[k]?.let { dimenOf(resolve(it, scope, fallback)) } }
        val y = dim("offsetY", "y") ?: 0f
        val blur = dim("blur") ?: 0f
        return maxOf(kotlin.math.abs(y), blur / 2f)
    }

    private fun readTypography(raw: String, o: JsonObject, scope: Map<String, RawToken>, fallback: Map<String, RawToken>) {
        fun prop(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { o[it] }?.let { resolve(it, scope, fallback) }
        val size = prop("fontSize")?.let { dimenOf(it) }
        val family = prop("fontFamily")?.let {
            when {
                it.isJsonArray && it.asJsonArray.size() > 0 -> it.asJsonArray[0].asString
                it.isJsonPrimitive -> CssValues.firstFontFamily(it.asString)
                else -> null
            }
        }
        val weight = prop("fontWeight")?.let { if (it.isJsonPrimitive) CssValues.parseFontWeight(it.asString) else null }
        val italic = prop("fontStyle")?.takeIf { it.isJsonPrimitive }?.asString?.contains("italic", true) == true ||
            prop("fontWeight")?.takeIf { it.isJsonPrimitive }?.asString?.contains("italic", true) == true
        b.mergeTypography(
            TypographyToken(
                name = Names.tokenIdentifier(raw, Categories.TYPOGRAPHY),
                fontFamily = family,
                fontSizeSp = size,
                fontWeight = weight,
                lineHeightSp = prop("lineHeight")?.let { relativeDimen(it, size, multiplierAllowed = true) },
                letterSpacingSp = prop("letterSpacing")?.let { relativeDimen(it, size, multiplierAllowed = false) },
                italic = italic,
            )
        )
    }

    private fun relativeDimen(v: JsonElement, fontSize: Float?, multiplierAllowed: Boolean): Float? {
        if (v.isJsonObject) return dimenOf(v)
        if (!v.isJsonPrimitive) return null
        return relativeToFont(v.asString, fontSize, multiplierAllowed)
    }
}

/** line-height / letter-spacing values: "150%", "1.5", "0.02em", "24px". */
internal fun relativeToFont(raw: String, fontSize: Float?, multiplierAllowed: Boolean): Float? {
    val s = raw.trim().lowercase()
    if (s == "normal") return null
    return when {
        s.endsWith("%") -> s.removeSuffix("%").toFloatOrNull()?.let { p -> fontSize?.let { it * p / 100f } }
        s.endsWith("em") && !s.endsWith("rem") -> s.removeSuffix("em").toFloatOrNull()?.let { m -> fontSize?.let { it * m } }
        multiplierAllowed && s.toFloatOrNull() != null && s.toFloat() <= 4f -> fontSize?.let { it * s.toFloat() }
        else -> CssValues.parseDimen(s)
    }
}
