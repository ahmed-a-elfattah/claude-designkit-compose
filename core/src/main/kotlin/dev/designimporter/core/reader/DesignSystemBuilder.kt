package dev.designimporter.core.reader

import dev.designimporter.core.Names
import dev.designimporter.core.model.ColorToken
import dev.designimporter.core.model.ComponentSpec
import dev.designimporter.core.model.ComponentType
import dev.designimporter.core.model.ComponentVariant
import dev.designimporter.core.model.DesignSystem
import dev.designimporter.core.model.DimenToken
import dev.designimporter.core.model.FontAsset
import dev.designimporter.core.model.IconAsset
import dev.designimporter.core.model.Props
import dev.designimporter.core.model.TokenValue
import dev.designimporter.core.model.TypographyToken

internal object Categories {
    private val prefixes = setOf("global", "core", "semantic", "tokens", "token", "primitives", "primitive", "alias", "sys", "ref", "theme", "light", "dark", "md", "ds")
    val COLOR = prefixes + setOf("color", "colors", "colour", "colours", "clr", "palette")
    val RADIUS = prefixes + setOf("radius", "radii", "rounded", "corner", "corners", "border", "shape", "shapes")
    val SPACING = prefixes + setOf("space", "spacing", "spaces", "gap", "spacer")
    val ELEVATION = prefixes + setOf("elevation", "elevations", "elev", "shadow", "shadows", "box")
    val SIZE = prefixes + setOf("size", "sizes", "sizing", "dimension", "dimensions", "dim")
    val TYPOGRAPHY = prefixes + setOf("typography", "type", "text", "font", "fonts", "style", "styles", "typescale")
    val FONT_FAMILY = prefixes + setOf("font", "fonts", "family", "families", "ff")

    fun isRadius(words: List<String>) = words.any { it in setOf("radius", "radii", "rounded", "corner", "corners") }
    fun isSpacing(words: List<String>) = words.any { it in setOf("space", "spacing", "spaces", "gap", "spacer", "padding", "margin", "inset") }
    fun isElevation(words: List<String>) = words.any { it in setOf("elevation", "elev", "shadow", "shadows") }
    fun isSize(words: List<String>) = words.firstOrNull() in setOf("size", "sizes", "sizing")
}

internal data class FontFace(val family: String, val weight: Int, val italic: Boolean)

/** Icon font the export renders icons with (Material Symbols / Material Icons). */
internal data class IconFont(val classes: Set<String>, val styleHint: String)

/** Mutable accumulator; readers write into it, later sources override earlier ones per token. */
internal class DesignSystemBuilder {
    var name: String? = null
    private val colors = LinkedHashMap<String, ColorToken>()
    private val radii = LinkedHashMap<String, Float>()
    private val spacing = LinkedHashMap<String, Float>()
    private val elevation = LinkedHashMap<String, Float>()
    private val sizes = LinkedHashMap<String, Float>()
    private val descriptions = HashMap<String, String>()
    private val typography = LinkedHashMap<String, TypographyToken>()
    val fontFaces = HashMap<String, FontFace>()
    val fontFamilies = LinkedHashMap<String, String>()
    var defaultFontFamily: String? = null
    private val fonts = LinkedHashMap<String, FontAsset>()
    private val icons = LinkedHashMap<String, IconAsset>()
    var iconFont: IconFont? = null
    private val components = LinkedHashMap<ComponentType, LinkedHashMap<String, MutableMap<String, TokenValue>>>()
    val warnings = mutableListOf<String>()

    /** Original token path / CSS var name -> generated color identifier. */
    val colorAliases = HashMap<String, String>()

    fun colorIds(): Set<String> = colors.keys

    fun putColor(id: String, argb: Long, dark: Boolean) {
        val existing = colors[id]
        colors[id] = when {
            existing == null -> ColorToken(id, light = argb, dark = if (dark) argb else null)
            dark -> existing.copy(dark = argb)
            else -> existing.copy(light = argb)
        }
    }

    fun putRadius(id: String, dp: Float) { radii[id] = dp }
    fun putSpacing(id: String, dp: Float) { spacing[id] = dp }
    fun putElevation(id: String, dp: Float) { elevation[id] = dp }
    fun putSize(id: String, dp: Float) { sizes[id] = dp }

    /** [category] is one of color, radius, spacing, elevation, size. */
    fun describe(category: String, id: String, text: String?) {
        if (!text.isNullOrBlank()) descriptions["$category:$id"] = text.trim()
    }

    fun mergeTypography(token: TypographyToken) {
        val e = typography[token.name]
        typography[token.name] = if (e == null) token else TypographyToken(
            name = token.name,
            fontFamily = token.fontFamily ?: e.fontFamily,
            fontSizeSp = token.fontSizeSp ?: e.fontSizeSp,
            fontWeight = token.fontWeight ?: e.fontWeight,
            lineHeightSp = token.lineHeightSp ?: e.lineHeightSp,
            letterSpacingSp = token.letterSpacingSp ?: e.letterSpacingSp,
            italic = token.italic || e.italic,
            description = token.description ?: e.description,
        )
    }

    fun addFont(font: FontAsset) { fonts[font.resourceName] = font }
    fun addIcon(icon: IconAsset) { icons.putIfAbsent(icon.name, icon) }
    fun hasIcon(name: String) = icons.containsKey(name)

    fun mergeComponent(type: ComponentType, variant: String, props: Map<String, TokenValue>) {
        val variants = components.getOrPut(type) { LinkedHashMap() }
        variants.getOrPut(variant) { LinkedHashMap() }.putAll(props)
    }

    private val variantWords = mapOf(
        "sel" to "selected", "on" to "active", "prog" to "progress", "err" to "error", "warn" to "warning",
        "emu" to "emulator", "dev" to "device", "req" to "required", "pri" to "primary", "sec" to "secondary",
    )

    private fun dimens(map: Map<String, Float>, category: String) = map.map { DimenToken(it.key, it.value, descriptions["$category:${it.key}"]) }

    fun build(): DesignSystem {
        val comps = components.map { (type, variants) ->
            val base = variants.remove(BASE_VARIANT)
            val list = if (variants.isEmpty()) {
                listOf(ComponentVariant("Default", base.orEmpty()))
            } else {
                val used = mutableSetOf<String>()
                // A base rule with its own colors (".chip" next to ".chip.sel") is a usable look of its own.
                val baseVariant = base?.takeIf { it.containsKey(Props.BACKGROUND) || it.containsKey(Props.CONTENT) }
                    ?.let { listOf(ComponentVariant(Names.unique("Default", used), it)) }.orEmpty()
                baseVariant + variants.map { (name, props) ->
                    val words = Names.words(name).map { variantWords[it] ?: it }
                    ComponentVariant(Names.unique(Names.pascal(words).ifEmpty { "Default" }, used), base.orEmpty() + props)
                }
            }
            ComponentSpec(type, list)
        }
        val defaultFamily = defaultFontFamily ?: fontFamilies.values.firstOrNull() ?: fonts.values.firstOrNull()?.family
        val typographyList = typography.values.filter { it.fontSizeSp != null }
        missingFontWarnings(typographyList.mapNotNull { it.fontFamily } + listOfNotNull(defaultFamily))
        return DesignSystem(
            name = name,
            colors = colors.values.map { it.copy(description = descriptions["color:${it.name}"]) },
            radii = dimens(radii, "radius"),
            spacing = dimens(spacing, "spacing").sortedBy { it.dp },
            elevation = dimens(elevation, "elevation").sortedBy { it.dp },
            sizes = dimens(sizes, "size"),
            typography = typographyList,
            defaultFontFamily = defaultFamily,
            fonts = fonts.values.toList(),
            icons = icons.values.toList(),
            iconStyleHint = iconFont?.styleHint,
            components = comps,
            warnings = warnings.distinct(),
        )
    }

    private fun missingFontWarnings(families: List<String>) {
        val available = fonts.values.map { norm(it.family) }.toSet()
        val generic = Regex("(?i)^(system-ui|-apple-system|blinkmacsystemfont|sans-serif|serif|monospace|ui-monospace|ui-sans-serif|ui-serif|inherit|roboto)$")
        families.distinct().filter { !generic.matches(it.trim()) && norm(it) !in available && !it.contains("mono", ignoreCase = true) }.forEach {
            warnings += "Font \"$it\" is used by the design but its files aren't in the export. Add its TTF/OTF files to the export (or res/font); until then the theme uses FontFamily.Default."
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    companion object {
        const val BASE_VARIANT = "\u0000base"
    }
}
