package dev.designimporter.core.gen

import dev.designimporter.core.Names
import dev.designimporter.core.model.DesignSystem
import dev.designimporter.core.model.DimenToken
import dev.designimporter.core.model.FontAsset
import dev.designimporter.core.model.TypographyToken

internal class ThemeGenerator(private val ds: DesignSystem, private val cfg: GeneratorConfig) {
    private val p = cfg.prefix
    private val pkg = cfg.packageName

    fun generate(): List<GeneratedFile> = listOf(
        GeneratedFile.text("${cfg.packageDir}/Color.kt", colors()),
        GeneratedFile.text("${cfg.packageDir}/Type.kt", typography()),
        GeneratedFile.text("${cfg.packageDir}/Shape.kt", shapes()),
        GeneratedFile.text("${cfg.packageDir}/Spacing.kt", dimens("Spacing", ds.spacing)),
        GeneratedFile.text("${cfg.packageDir}/Elevation.kt", dimens("Elevation", ds.elevation)),
        GeneratedFile.text("${cfg.packageDir}/Sizes.kt", dimens("Sizes", ds.sizes)),
        GeneratedFile.text("${cfg.packageDir}/Theme.kt", theme()),
    ) + ds.fonts.distinctBy { it.resourceName }.map { GeneratedFile.binary("${cfg.resDir}/font/${it.resourceName}.${it.extension}", it.bytes) }

    // region colors

    private val roleAliases: List<Pair<String, List<String>>> = listOf(
        "primary" to listOf("primary", "brand", "brandPrimary", "accent", "colorPrimary"),
        "onPrimary" to listOf("onPrimary", "primaryForeground", "primaryContent", "onBrand", "brandForeground", "onAccent", "accentForeground"),
        "primaryContainer" to listOf("primaryContainer", "primarySubtle", "primaryLight", "primarySoft", "brandSubtle"),
        "onPrimaryContainer" to listOf("onPrimaryContainer"),
        "inversePrimary" to listOf("inversePrimary"),
        "secondary" to listOf("secondary", "brandSecondary"),
        "onSecondary" to listOf("onSecondary", "secondaryForeground", "secondaryContent"),
        "secondaryContainer" to listOf("secondaryContainer", "secondarySubtle", "secondaryLight"),
        "onSecondaryContainer" to listOf("onSecondaryContainer"),
        "tertiary" to listOf("tertiary"),
        "onTertiary" to listOf("onTertiary", "tertiaryForeground"),
        "tertiaryContainer" to listOf("tertiaryContainer"),
        "onTertiaryContainer" to listOf("onTertiaryContainer"),
        "background" to listOf("background", "bg", "canvas", "page", "backgroundDefault", "bgDefault", "bgPage", "bgCanvas", "surfaceBackground"),
        "onBackground" to listOf("onBackground", "foreground", "fg", "text", "textPrimary", "textDefault", "ink", "fgDefault"),
        "surface" to listOf("surface", "card", "bgSurface", "backgroundSurface", "surfaceDefault", "panel", "bgCard", "bgElevated", "popover"),
        "onSurface" to listOf("onSurface", "cardForeground", "surfaceForeground", "onCard", "text", "textPrimary", "foreground", "fg"),
        "surfaceVariant" to listOf("surfaceVariant", "muted", "bgMuted", "backgroundMuted", "surfaceMuted", "surfaceAlt", "surface2", "bgSubtle", "surfaceSubtle"),
        "onSurfaceVariant" to listOf("onSurfaceVariant", "mutedForeground", "textSecondary", "textMuted", "textSubtle", "fgMuted", "fgSecondary"),
        "surfaceTint" to listOf("surfaceTint"),
        "inverseSurface" to listOf("inverseSurface"),
        "inverseOnSurface" to listOf("inverseOnSurface"),
        "error" to listOf("error", "danger", "destructive", "negative", "statusError", "red"),
        "onError" to listOf("onError", "destructiveForeground", "dangerForeground", "errorForeground", "onDanger"),
        "errorContainer" to listOf("errorContainer", "dangerSubtle", "errorSubtle"),
        "onErrorContainer" to listOf("onErrorContainer"),
        "outline" to listOf("outline", "borderStrong", "border", "borderDefault", "stroke"),
        "outlineVariant" to listOf("outlineVariant", "borderSubtle", "borderMuted", "divider", "input", "borderLight", "border"),
        "scrim" to listOf("scrim", "overlay"),
        "surfaceBright" to listOf("surfaceBright"),
        "surfaceDim" to listOf("surfaceDim"),
        "surfaceContainer" to listOf("surfaceContainer"),
        "surfaceContainerHigh" to listOf("surfaceContainerHigh"),
        "surfaceContainerHighest" to listOf("surfaceContainerHighest"),
        "surfaceContainerLow" to listOf("surfaceContainerLow"),
        "surfaceContainerLowest" to listOf("surfaceContainerLowest"),
    )

    /** M3 color role → token identifier. */
    fun materialRoles(): Map<String, String> {
        val byLower = ds.colors.associateBy { it.name.lowercase() }
        val out = LinkedHashMap<String, String>()
        for ((role, aliases) in roleAliases) {
            val taken = if (role == "outlineVariant") setOfNotNull(out["outline"]) else emptySet()
            aliases.firstNotNullOfOrNull { a -> byLower[a.lowercase()]?.takeIf { it.name !in taken } }?.let { out[role] = it.name }
        }
        return out
    }

    private fun colors(): String {
        val f = KotlinFile(pkg).import(
            "androidx.compose.material3.darkColorScheme",
            "androidx.compose.material3.lightColorScheme",
            "androidx.compose.runtime.Immutable",
            "androidx.compose.runtime.staticCompositionLocalOf",
            "androidx.compose.ui.graphics.Color",
        )
        val colors = ds.colors
        f.line("@Immutable")
        if (colors.isEmpty()) {
            f.line("class ${p}Colors").line()
            f.line("val Light${p}Colors = ${p}Colors()").line()
            f.line("val Dark${p}Colors = ${p}Colors()").line()
        } else {
            f.line("data class ${p}Colors(")
            colors.forEach { f.doc(it.description).line("    val ${Names.escape(it.name)}: Color,") }
            f.line(")").line()
            f.line("val Light${p}Colors = ${p}Colors(")
            colors.forEach { f.line("    ${Names.escape(it.name)} = ${colorLiteral(it.light)},") }
            f.line(")").line()
            f.line("val Dark${p}Colors = ${p}Colors(")
            colors.forEach { f.line("    ${Names.escape(it.name)} = ${colorLiteral(it.dark ?: it.light)},") }
            f.line(")").line()
        }
        f.line("val Local${p}Colors = staticCompositionLocalOf { Light${p}Colors }").line()
        val roles = materialRoles()
        for ((scheme, fn, source) in listOf(Triple("LightColorScheme", "lightColorScheme", "Light${p}Colors"), Triple("DarkColorScheme", "darkColorScheme", "Dark${p}Colors"))) {
            f.line("internal val $scheme = $fn(")
            roles.forEach { (role, token) -> f.line("    $role = $source.${Names.escape(token)},") }
            f.line(")").line()
        }
        return f.render()
    }

    // endregion

    // region typography

    private val families: Map<String, List<FontAsset>> = ds.fonts.groupBy { it.family }
    private fun familyIdentifier(family: String) = Names.pascal(Names.words(family)) + "FontFamily"
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun familyExpression(requested: String?): String {
        val name = requested ?: ds.defaultFontFamily ?: return "FontFamily.Default"
        families.keys.firstOrNull { norm(it) == norm(name) }?.let { return familyIdentifier(it) }
        val n = name.lowercase()
        return when {
            n.contains("mono") || n.contains("code") || n.contains("courier") -> "FontFamily.Monospace"
            (n == "serif" || n.endsWith(" serif") || n.contains("georgia") || n.contains("times")) && !n.contains("sans") -> "FontFamily.Serif"
            requested == null && families.size == 1 -> familyIdentifier(families.keys.first())
            else -> "FontFamily.Default"
        }
    }

    private fun weightExpression(w: Int): String = when (w) {
        100 -> "FontWeight.Thin"; 200 -> "FontWeight.ExtraLight"; 300 -> "FontWeight.Light"; 400 -> "FontWeight.Normal"
        500 -> "FontWeight.Medium"; 600 -> "FontWeight.SemiBold"; 700 -> "FontWeight.Bold"; 800 -> "FontWeight.ExtraBold"
        900 -> "FontWeight.Black"; else -> "FontWeight($w)"
    }

    private fun textStyle(t: TypographyToken): String {
        val args = mutableListOf("fontFamily = ${familyExpression(t.fontFamily)}")
        t.fontWeight?.let { args += "fontWeight = ${weightExpression(it)}" }
        if (t.italic) args += "fontStyle = FontStyle.Italic"
        t.fontSizeSp?.let { args += "fontSize = ${sp(it)}" }
        t.lineHeightSp?.let { args += "lineHeight = ${sp(it)}" }
        t.letterSpacingSp?.let { args += "letterSpacing = ${sp(it)}" }
        return "TextStyle(\n        ${args.joinToString(",\n        ")},\n    )"
    }

    private val slots = listOf("display", "headline", "title", "body", "label").flatMap { c -> listOf("Large", "Medium", "Small").map { "$c$it" } }

    /** M3 typography slot → token name, by explicit names first, then category + size ranking. */
    fun materialTypography(): Map<String, String> {
        val tokens = ds.typography
        val out = LinkedHashMap<String, String>()
        val byLower = tokens.associateBy { it.name.lowercase() }
        slots.forEach { slot -> byLower[slot.lowercase()]?.let { out[slot] = it.name } }

        val headingMap = mapOf("h1" to "headlineLarge", "h2" to "headlineMedium", "h3" to "headlineSmall", "h4" to "titleLarge", "h5" to "titleMedium", "h6" to "titleSmall")
        tokens.forEach { t -> headingMap[t.name.lowercase()]?.let { slot -> out.putIfAbsent(slot, t.name) } }

        fun category(words: List<String>): String? = when {
            words.any { it == "display" || it == "hero" || it == "jumbo" } -> "display"
            words.any { it in setOf("headline", "heading", "h", "header") } -> "headline"
            words.any { it in setOf("title", "subtitle", "subheading", "subhead") } -> "title"
            words.any { it in setOf("body", "paragraph", "p", "text", "base", "copy") } -> "body"
            words.any { it in setOf("label", "caption", "button", "overline", "small", "tiny", "micro", "meta") } -> "label"
            else -> null
        }
        fun rank(words: List<String>): String? = when {
            words.any { it in setOf("large", "lg", "xl", "2xl", "3xl", "l", "big") } -> "Large"
            words.any { it in setOf("medium", "md", "m", "base", "default", "regular") } -> "Medium"
            words.any { it in setOf("small", "sm", "xs", "s", "caption", "tiny") } -> "Small"
            else -> null
        }
        val grouped = tokens.filter { it.name !in out.values }.groupBy { category(Names.words(it.name)) }
        for ((cat, list) in grouped) {
            cat ?: continue
            val unranked = mutableListOf<TypographyToken>()
            list.forEach { t ->
                val r = rank(Names.words(t.name))
                if (r != null) out.putIfAbsent("$cat$r", t.name) else unranked += t
            }
            val free = listOf("Large", "Medium", "Small").filter { "$cat$it" !in out }
            unranked.sortedByDescending { it.fontSizeSp ?: 0f }.zip(free).forEach { (t, r) -> out["$cat$r"] = t.name }
        }
        return slots.mapNotNull { s -> out[s]?.let { s to it } }.toMap()
    }

    private fun typography(): String {
        val f = KotlinFile(pkg).import(
            "androidx.compose.material3.Typography",
            "androidx.compose.runtime.Immutable",
            "androidx.compose.runtime.staticCompositionLocalOf",
            "androidx.compose.ui.text.TextStyle",
            "androidx.compose.ui.text.font.FontFamily",
            "androidx.compose.ui.text.font.FontWeight",
            "androidx.compose.ui.unit.sp",
        )
        if (ds.typography.any { it.italic } || ds.fonts.any { it.italic }) f.import("androidx.compose.ui.text.font.FontStyle")
        if (families.isNotEmpty()) f.import("androidx.compose.ui.text.font.Font", "${cfg.rPackage}.R")

        for ((family, fonts) in families) {
            f.line("val ${familyIdentifier(family)} = FontFamily(")
            fonts.distinctBy { it.weight to it.italic }.forEach { font ->
                val style = if (font.italic) ", FontStyle.Italic" else ""
                f.line("    Font(R.font.${font.resourceName}, ${weightExpression(font.weight)}$style),")
            }
            f.line(")").line()
        }

        val tokens = ds.typography
        f.line("@Immutable")
        if (tokens.isEmpty()) {
            f.line("class ${p}Typography").line()
            f.line("val Default${p}Typography = ${p}Typography()").line()
        } else {
            f.line("data class ${p}Typography(")
            tokens.forEach { f.doc(it.description).line("    val ${Names.escape(it.name)}: TextStyle,") }
            f.line(")").line()
            f.line("val Default${p}Typography = ${p}Typography(")
            tokens.forEach { f.line("    ${Names.escape(it.name)} = ${textStyle(it)},") }
            f.line(")").line()
        }
        f.line("val Local${p}Typography = staticCompositionLocalOf { Default${p}Typography }").line()
        f.line("internal val ${p}MaterialTypography = Typography(")
        materialTypography().forEach { (slot, token) -> f.line("    $slot = Default${p}Typography.${Names.escape(token)},") }
        f.line(")")
        return f.render()
    }

    // endregion

    // region shapes and dimens

    private fun shapeExpression(dp: Float) = if (dp >= 999f) "RoundedCornerShape(percent = 50)" else "RoundedCornerShape(${dp(dp)})"

    fun materialShapes(): Map<String, Float> {
        val named = mapOf(
            "extraSmall" to setOf("xs", "extrasmall", "xsmall"),
            "small" to setOf("sm", "small", "s"),
            "medium" to setOf("md", "medium", "m", "base", "default", "radius"),
            "large" to setOf("lg", "large", "l"),
            "extraLarge" to setOf("xl", "extralarge", "xlarge", "2xl"),
        )
        val out = LinkedHashMap<String, Float>()
        val usable = ds.radii.filter { it.dp in 0.01f..998f }
        for ((slot, names) in named) {
            usable.firstOrNull { it.name.lowercase() in names }?.let { out[slot] = it.dp }
        }
        if (out.isEmpty() && usable.isNotEmpty()) {
            val sorted = usable.map { it.dp }.distinct().sorted()
            val order = listOf("small", "medium", "large", "extraLarge", "extraSmall")
            sorted.take(5).forEachIndexed { i, v -> out[order[i]] = v }
        }
        return out
    }

    private fun shapes(): String {
        val f = KotlinFile(pkg).import(
            "androidx.compose.foundation.shape.RoundedCornerShape",
            "androidx.compose.material3.Shapes",
            "androidx.compose.runtime.Immutable",
            "androidx.compose.runtime.staticCompositionLocalOf",
            "androidx.compose.ui.unit.dp",
        )
        f.line("@Immutable")
        if (ds.radii.isEmpty()) {
            f.line("class ${p}Shapes").line()
            f.line("val Default${p}Shapes = ${p}Shapes()").line()
        } else {
            f.import("androidx.compose.ui.graphics.Shape")
            f.line("data class ${p}Shapes(")
            ds.radii.forEach { f.doc(it.description).line("    val ${Names.escape(it.name)}: Shape,") }
            f.line(")").line()
            f.line("val Default${p}Shapes = ${p}Shapes(")
            ds.radii.forEach { f.line("    ${Names.escape(it.name)} = ${shapeExpression(it.dp)},") }
            f.line(")").line()
        }
        f.line("val Local${p}Shapes = staticCompositionLocalOf { Default${p}Shapes }").line()
        f.line("internal val ${p}MaterialShapes = Shapes(")
        materialShapes().forEach { (slot, v) -> f.line("    $slot = ${shapeExpression(v)},") }
        f.line(")")
        return f.render()
    }

    private fun dimens(kind: String, tokens: List<DimenToken>): String {
        val f = KotlinFile(pkg).import(
            "androidx.compose.runtime.Immutable",
            "androidx.compose.runtime.staticCompositionLocalOf",
        )
        f.line("@Immutable")
        if (tokens.isEmpty()) {
            f.line("class $p$kind")
        } else {
            f.import("androidx.compose.ui.unit.Dp", "androidx.compose.ui.unit.dp")
            f.line("data class $p$kind(")
            tokens.forEach { f.doc(it.description).line("    val ${Names.escape(it.name)}: Dp = ${dp(it.dp)},") }
            f.line(")")
        }
        f.line().line("val Local$p$kind = staticCompositionLocalOf { $p$kind() }")
        return f.render()
    }

    // endregion

    private fun theme(): String {
        val t = cfg.themeName
        val f = KotlinFile(pkg).import(
            "androidx.compose.foundation.isSystemInDarkTheme",
            "androidx.compose.material3.MaterialTheme",
            "androidx.compose.runtime.Composable",
            "androidx.compose.runtime.CompositionLocalProvider",
            "androidx.compose.runtime.ReadOnlyComposable",
        )
        val accessors = listOf("colors" to "Colors", "typography" to "Typography", "shapes" to "Shapes", "spacing" to "Spacing", "elevation" to "Elevation", "sizes" to "Sizes")
        f.line("object $t {")
        accessors.forEachIndexed { i, (prop, type) ->
            if (i > 0) f.line()
            f.line("    val $prop: $p$type")
            f.line("        @Composable @ReadOnlyComposable get() = Local$p$type.current")
        }
        f.line("}").line()
        f.line("@Composable")
        f.line("fun $t(")
        f.line("    darkTheme: Boolean = isSystemInDarkTheme(),")
        f.line("    content: @Composable () -> Unit,")
        f.line(") {")
        f.line("    CompositionLocalProvider(")
        f.line("        Local${p}Colors provides if (darkTheme) Dark${p}Colors else Light${p}Colors,")
        f.line("        Local${p}Typography provides Default${p}Typography,")
        f.line("        Local${p}Shapes provides Default${p}Shapes,")
        f.line("        Local${p}Spacing provides ${p}Spacing(),")
        f.line("        Local${p}Elevation provides ${p}Elevation(),")
        f.line("        Local${p}Sizes provides ${p}Sizes(),")
        f.line("    ) {")
        f.line("        MaterialTheme(")
        f.line("            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,")
        f.line("            typography = ${p}MaterialTypography,")
        f.line("            shapes = ${p}MaterialShapes,")
        f.line("            content = content,")
        f.line("        )")
        f.line("    }")
        f.line("}")
        return f.render()
    }
}
