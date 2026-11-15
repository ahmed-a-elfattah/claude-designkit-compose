package dev.designimporter.core.reader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.designimporter.core.Names
import dev.designimporter.core.model.ComponentType
import dev.designimporter.core.model.FontAsset
import dev.designimporter.core.model.IconAsset
import dev.designimporter.core.model.Props
import dev.designimporter.core.model.TokenValue

/**
 * Component specs as JSON: { "button": { "base": {…}, "variants": { "primary": {…} } } }
 * or { "button": { "primary": {…} } }. Values may be "{color.primary}" token references or literals.
 */
internal class ComponentJsonReader(private val b: DesignSystemBuilder) {
    private val typeKeys = mapOf(
        ComponentType.BUTTON to setOf("button", "buttons", "btn"),
        ComponentType.TEXT_FIELD to setOf("textfield", "text-field", "textField", "input", "inputs", "field"),
        ComponentType.CARD to setOf("card", "cards"),
        ComponentType.CHIP to setOf("chip", "chips", "badge", "badges", "tag", "tags", "pill"),
    )
    private val propKeys = mapOf(
        Props.BACKGROUND to setOf("background", "backgroundcolor", "bg", "container", "containercolor", "fill"),
        Props.CONTENT to setOf("color", "content", "contentcolor", "text", "textcolor", "foreground", "fg", "label"),
        Props.BORDER_COLOR to setOf("border", "bordercolor", "outline", "stroke"),
        Props.BORDER_WIDTH to setOf("borderwidth", "strokewidth", "outlinewidth"),
        Props.RADIUS to setOf("radius", "borderradius", "cornerradius", "corner", "rounded"),
        Props.PADDING_H to setOf("paddingx", "paddinghorizontal", "paddinginline", "px"),
        Props.PADDING_V to setOf("paddingy", "paddingvertical", "paddingblock", "py"),
        Props.HEIGHT to setOf("height", "minheight", "size"),
        Props.ELEVATION to setOf("elevation", "shadow", "boxshadow"),
    )

    companion object {
        private val allTypeKeys = setOf("button", "buttons", "btn", "textfield", "text-field", "input", "inputs", "card", "cards", "chip", "chips", "badge", "tag")
        fun looksLikeComponents(file: ExportFile, text: String): Boolean {
            if (file.name.contains("component", ignoreCase = true)) return true
            return try {
                val o = JsonParser.parseString(text).asJsonObject
                o.keySet().map { it.lowercase() }.count { it in allTypeKeys } >= 1 && !DtcgJsonReader.looksLikeTokens(text)
            } catch (_: Exception) {
                false
            }
        }
    }

    fun read(file: ExportFile, tokenPaths: Map<String, String>) {
        val root = try {
            JsonParser.parseString(file.text).asJsonObject
        } catch (e: Exception) {
            b.warnings += "${file.path}: invalid JSON (${e.message})"
            return
        }
        val obj = root["components"]?.takeIf { it.isJsonObject }?.asJsonObject ?: root
        for ((key, value) in obj.entrySet()) {
            if (!value.isJsonObject) continue
            val type = typeKeys.entries.firstOrNull { (_, keys) -> keys.any { it.equals(key, true) } }?.key ?: continue
            val c = value.asJsonObject
            (c["base"] ?: c["default"])?.takeIf { it.isJsonObject }?.let {
                b.mergeComponent(type, DesignSystemBuilder.BASE_VARIANT, props(it.asJsonObject, tokenPaths))
            }
            val variants = c["variants"]?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { o -> c.entrySet().filter { it.key !in setOf("base", "default") && it.value.isJsonObject }.forEach { o.add(it.key, it.value) } }
            for ((name, v) in variants.entrySet()) {
                if (v.isJsonObject) b.mergeComponent(type, name, props(v.asJsonObject, tokenPaths))
            }
        }
    }

    private fun props(o: JsonObject, tokenPaths: Map<String, String>): Map<String, TokenValue> {
        val out = LinkedHashMap<String, TokenValue>()
        for ((key, v) in o.entrySet()) {
            val prop = propKeys.entries.firstOrNull { (_, keys) -> key.lowercase().replace("_", "").replace("-", "") in keys }?.key ?: continue
            if (!v.isJsonPrimitive) continue
            val s = v.asString.trim()
            val isColorProp = prop in setOf(Props.BACKGROUND, Props.CONTENT, Props.BORDER_COLOR)
            val ref = Regex("^\\{([^}]+)}$").find(s)?.groupValues?.get(1)
            val value: TokenValue? = when {
                ref != null && isColorProp -> (tokenPaths[ref] ?: tokenPaths.entries.firstOrNull { it.key.endsWith(".$ref") }?.value)
                    ?.let { TokenValue.ColorRef(it) }
                isColorProp && CssValues.isTransparent(s) -> TokenValue.Transparent
                isColorProp -> CssValues.parseColor(s)?.let { TokenValue.ColorLiteral(it) }
                prop == Props.ELEVATION -> (CssValues.shadowToElevation(s) ?: CssValues.parseDimen(s))?.let { TokenValue.Dimen(it) }
                else -> (if (v.asJsonPrimitive.isNumber) v.asFloat else CssValues.parseDimen(s))?.let { TokenValue.Dimen(it) }
            }
            if (value != null) out[prop] = value
        }
        return out
    }
}

internal class FontReader(private val b: DesignSystemBuilder) {
    private val weightWords = linkedMapOf(
        "extralight" to 200, "ultralight" to 200, "semibold" to 600, "demibold" to 600, "extrabold" to 800, "ultrabold" to 800,
        "thin" to 100, "hairline" to 100, "light" to 300, "regular" to 400, "normal" to 400, "book" to 400, "medium" to 500,
        "bold" to 700, "black" to 900, "heavy" to 900,
    )

    fun read(file: ExportFile) {
        when (file.extension) {
            "woff", "woff2", "eot" -> {
                b.warnings += "${file.path}: Android can't load .${file.extension} fonts; export TTF/OTF to include it"
                return
            }
            "ttf", "otf" -> Unit
            else -> return
        }
        val face = b.fontFaces[file.baseName.lowercase()]
        val stem = file.baseName.replace(Regex("\\[.*]"), "")
        val compact = stem.lowercase().replace(Regex("[^a-z0-9]"), "")
        val italic = face?.italic ?: compact.contains("italic")
        val weight = face?.weight
            ?: weightWords.entries.firstOrNull { compact.removeSuffix("italic").endsWith(it.key) }?.value
            ?: Regex("(\\d00)").find(compact)?.value?.toInt()
            ?: 400
        val family = face?.family ?: run {
            val parts = stem.split(Regex("[-_ ]+"))
            val first = parts.first()
            first.replace(Regex("([a-z])([A-Z])"), "$1 $2").takeIf { parts.size > 1 } ?: first
        }
        val resName = Names.resourceName(file.baseName.replace(Regex("\\[.*]"), "_variable"))
        b.addFont(FontAsset(family, weight, italic, resName, file.extension, file.bytes))
    }
}

/**
 * Material Symbols / Material Icons ligatures used in HTML (`<span class="ms">payments</span>`), plus icon names
 * held in the prototype's data (`icon: 'schedule'`, `backIcon = rtl ? 'arrow_forward' : 'arrow_back'`).
 * Names from data are only kept when they are real Material icon names, so status strings aren't mistaken for icons.
 */
internal class MaterialSymbolReader(private val b: DesignSystemBuilder, private val isMaterialName: (String) -> Boolean) {
    private val element = Regex("""<(\w+)\b[^>]*\bclass(?:Name)?\s*=\s*["']([^"']*)["'][^>]*>\s*([a-z][a-z0-9_]{0,40})\s*</\1>""")
    private val iconKey = Regex("""\b(\w*(?:[iI]con|[cC]hev)\w*)\s*[:=]\s*""")
    private val literal = Regex("""['"]([a-z][a-z0-9_]{1,40})['"]""")

    fun scan(html: String, path: String) {
        val font = b.iconFont ?: return
        var found = false
        for (m in element.findAll(html)) {
            val classes = m.groupValues[2].split(Regex("\\s+")).toSet()
            if (classes.none { it in font.classes }) continue
            found = true
            val filled = classes.any { it == "fill" || it == "filled" }
            add(m.groupValues[3], path, if (filled) filledStyle(font.styleHint) else font.styleHint)
        }
        if (!found && !html.contains("Material+Symbols") && !html.contains("Material+Icons")) return
        for (m in iconKey.findAll(html)) {
            val start = m.range.last + 1
            val seg = html.substring(start, minOf(html.length, start + 240))
            val masked = seg.replace(Regex("'[^']*'|\"[^\"]*\"")) { "x".repeat(it.value.length) }
            val end = Regex("[,;}\\n)]").find(masked)?.range?.first ?: seg.length
            literal.findAll(seg.substring(0, end)).map { it.groupValues[1] }.filter(isMaterialName).forEach { add(it, path, font.styleHint) }
        }
    }

    private fun filledStyle(hint: String) = if (hint == "Outlined") "Rounded" else hint

    private fun add(name: String, path: String, style: String) {
        if (!b.hasIcon(name)) b.addIcon(IconAsset(name, svg = null, sourcePath = "$path (Material Symbol)", isSymbol = true, styleHint = style))
    }
}

internal class SvgIconReader(private val b: DesignSystemBuilder) {
    fun read(file: ExportFile) {
        val text = file.text
        if (text.contains("<symbol", ignoreCase = true)) {
            Regex("""<symbol\b([^>]*)>(.*?)</symbol>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(text).forEach { m ->
                val attrs = m.groupValues[1]
                val id = Regex("""\bid\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val viewBox = Regex("""\bviewBox\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1) ?: "0 0 24 24"
                val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$viewBox">${m.groupValues[2]}</svg>"""
                b.addIcon(IconAsset(iconName(id), svg, "${file.path}#$id"))
            }
            return
        }
        b.addIcon(IconAsset(iconName(file.baseName), text, file.path))
    }

    private fun iconName(raw: String): String =
        raw.replace(Regex("^(ic|icon|icons)[-_]", RegexOption.IGNORE_CASE), "")
}
