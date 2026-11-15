package dev.designimporter.core.model

/** Colors are stored as ARGB longs (0xAARRGGBB). */
data class ColorToken(val name: String, val light: Long, val dark: Long? = null, val description: String? = null)

data class DimenToken(val name: String, val dp: Float, val description: String? = null)

data class TypographyToken(
    val name: String,
    val fontFamily: String? = null,
    val fontSizeSp: Float? = null,
    val fontWeight: Int? = null,
    val lineHeightSp: Float? = null,
    val letterSpacingSp: Float? = null,
    val italic: Boolean = false,
    val description: String? = null,
)

class FontAsset(
    val family: String,
    val weight: Int,
    val italic: Boolean,
    val resourceName: String,
    val extension: String,
    val bytes: ByteArray,
)

/**
 * An icon from the export: an SVG ([svg] set) or a Material Symbols ligature name used by the design
 * ([isSymbol], no geometry; it can only be satisfied by the Material icon of the same name).
 */
data class IconAsset(
    val name: String,
    val svg: String?,
    val sourcePath: String,
    val isSymbol: Boolean = false,
    val styleHint: String? = null,
)

enum class ComponentType { BUTTON, TEXT_FIELD, CARD, CHIP, BADGE }

/** Normalized prop keys used by component specs. */
object Props {
    const val BACKGROUND = "background"
    const val CONTENT = "content"
    const val BORDER_COLOR = "borderColor"
    const val BORDER_WIDTH = "borderWidth"
    const val RADIUS = "radius"
    const val PADDING_H = "paddingH"
    const val PADDING_V = "paddingV"
    const val HEIGHT = "height"
    const val ELEVATION = "elevation"
}

sealed interface TokenValue {
    data class ColorRef(val tokenName: String) : TokenValue
    data class ColorLiteral(val argb: Long) : TokenValue
    data class Dimen(val dp: Float) : TokenValue
    data object Transparent : TokenValue
}

/**
 * UI text in the default language plus translations by locale ("ar" → Arabic).
 * [text] and translations are raw text; [args] is how many `%n$s` placeholders were added for values
 * the design concatenates onto the text (e.g. "Refund · " + amount → "Refund · %1$s").
 */
data class LocalizedString(
    val key: String,
    val text: String,
    val translations: Map<String, String>,
    val args: Int = 0,
)

data class ComponentVariant(val name: String, val props: Map<String, TokenValue>)

data class ComponentSpec(val type: ComponentType, val variants: List<ComponentVariant>)

data class DesignSystem(
    val name: String? = null,
    val colors: List<ColorToken> = emptyList(),
    val radii: List<DimenToken> = emptyList(),
    val spacing: List<DimenToken> = emptyList(),
    val elevation: List<DimenToken> = emptyList(),
    val sizes: List<DimenToken> = emptyList(),
    val typography: List<TypographyToken> = emptyList(),
    val defaultFontFamily: String? = null,
    val fonts: List<FontAsset> = emptyList(),
    val icons: List<IconAsset> = emptyList(),
    /** Material Icons style closest to the icon font the design uses (e.g. "Outlined" for unfilled Material Symbols). */
    val iconStyleHint: String? = null,
    val components: List<ComponentSpec> = emptyList(),
    val strings: List<LocalizedString> = emptyList(),
    val warnings: List<String> = emptyList(),
)
