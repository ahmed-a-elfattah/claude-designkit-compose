package dev.designimporter.core.reader

import dev.designimporter.core.Names
import dev.designimporter.core.model.ComponentType
import dev.designimporter.core.model.Props
import dev.designimporter.core.model.TokenValue
import dev.designimporter.core.model.TypographyToken

/**
 * Collects CSS from all .css files and <style> blocks, then extracts:
 * custom properties (light + dark), typography (vars and text classes), @font-face and component classes.
 */
internal class CssReader(private val b: DesignSystemBuilder) {
    private val rules = mutableListOf<CssRule>()
    private val lightVars = LinkedHashMap<String, String>()
    private val darkVars = LinkedHashMap<String, String>()

    fun add(css: String) {
        rules += CssParser.parse(css)
    }

    fun finish(hasTokenTypography: Boolean) {
        if (rules.isEmpty()) return
        collectVars()
        classifyVars()
        readFontFaces()
        if (!hasTokenTypography) readTypographyClasses()
        readComponents()
        detectIconFont()
    }

    // region icon font

    private val knownIconClasses = mapOf(
        "material-symbols-outlined" to "Outlined", "material-symbols-rounded" to "Outlined", "material-symbols-sharp" to "Outlined",
        "material-icons" to "Filled", "material-icons-outlined" to "Outlined", "material-icons-round" to "Rounded",
        "material-icons-sharp" to "Sharp", "material-icons-two-tone" to "TwoTone",
    )

    /** Classes rendering Material Symbols / Material Icons ligatures, and the closest Compose icon style. */
    private fun detectIconFont() {
        val classes = knownIconClasses.keys.toMutableSet()
        var hint: String? = null
        for (rule in rules) {
            val family = rule.decls["font-family"] ?: continue
            if (!family.contains("Material Symbols", true) && !family.contains("Material Icons", true)) continue
            val simple = rule.selectors.mapNotNull { Regex("^\\.([\\w-]+)$").matchEntire(it)?.groupValues?.get(1) }
            if (simple.isEmpty()) continue
            classes += simple
            val filled = rule.decls["font-variation-settings"]?.let { Regex("['\"]FILL['\"]\\s*1").containsMatchIn(it) } ?: false
            val symbols = family.contains("Symbols", true)
            hint = when {
                family.contains("Two Tone", true) -> "TwoTone"
                symbols && !filled -> "Outlined"
                family.contains("Round", true) -> "Rounded"
                family.contains("Sharp", true) -> "Sharp"
                family.contains("Outlined", true) -> "Outlined"
                else -> "Filled"
            }
        }
        b.iconFont = IconFont(classes, hint ?: "Outlined")
    }

    // endregion

    // region variables

    private val darkSelector = Regex("""\.dark\b|\[data-(theme|mode|color-scheme|bs-theme)=["']?dark|\.theme-dark|\.dark-theme|\.dark-mode""")
    private val rootSelector = Regex("""^(:root|html|body|:host|\*|\.light|\.theme-light|\[data-(theme|mode)=["']?light["']?])$""")

    private fun isDark(rule: CssRule) =
        rule.media.replace(" ", "").contains("prefers-color-scheme:dark") || rule.selectors.any { darkSelector.containsMatchIn(it) }

    private fun collectVars() {
        for (rule in rules) {
            val vars = rule.decls.filterKeys { it.startsWith("--") }
            if (vars.isEmpty()) continue
            when {
                isDark(rule) -> darkVars.putAll(vars)
                rule.media.isEmpty() && rule.selectors.any { rootSelector.matches(it) } -> lightVars.putAll(vars)
            }
        }
    }

    private fun resolve(value: String, scopes: List<Map<String, String>>, depth: Int = 0): String {
        if (depth > 12 || !value.contains("var(")) return value
        val re = Regex("""var\(\s*(--[\w-]+)\s*(?:,\s*((?:[^()]|\([^()]*\))*))?\)""")
        val replaced = re.replace(value) { m ->
            scopes.firstNotNullOfOrNull { it[m.groupValues[1]] } ?: m.groupValues[2].trim().ifEmpty { "unresolved(${m.groupValues[1]})" }
        }
        return resolve(replaced, scopes, depth + 1)
    }

    private val hslTriplet = Regex("""^\s*-?\d*\.?\d+(deg)?\s+\d*\.?\d+%\s+\d*\.?\d+%\s*(/\s*[\d.]+%?)?\s*$""")

    private fun colorOf(value: String): Long? =
        CssValues.parseColor(value) ?: if (hslTriplet.matches(value)) CssValues.parseColor("hsl($value)") else null

    private class TypeVars(var size: String? = null, var lineHeight: String? = null, var letterSpacing: String? = null, var weight: String? = null, var family: String? = null)

    private val typeVarPatterns = listOf(
        Regex("^(?:font-size|fs|text-size)-(.+)$") to "size",
        Regex("^(?:line-height|lh|leading)-(.+)$") to "lineHeight",
        Regex("^(?:letter-spacing|tracking)-(.+)$") to "letterSpacing",
    )
    private val typeCompound = Regex("^(?:text|type|typography|font|typescale)-(.+?)-(size|font-size|line-height|leading|weight|font-weight|letter-spacing|tracking|family|font-family)$")

    private fun classifyVars() {
        val typeGroups = LinkedHashMap<String, TypeVars>()
        for ((rawName, raw) in lightVars) {
            val name = rawName.removePrefix("--")
            val value = resolve(raw, listOf(lightVars)).trim()
            val words = Names.words(name)

            typeCompound.find(name)?.let { m ->
                val g = typeGroups.getOrPut(m.groupValues[1]) { TypeVars() }
                when (m.groupValues[2]) {
                    "size", "font-size" -> g.size = value
                    "line-height", "leading" -> g.lineHeight = value
                    "letter-spacing", "tracking" -> g.letterSpacing = value
                    "weight", "font-weight" -> g.weight = value
                    else -> g.family = value
                }
                continue
            }
            val simple = typeVarPatterns.firstNotNullOfOrNull { (re, prop) -> re.find(name)?.let { it.groupValues[1] to prop } }
            if (simple != null) {
                val g = typeGroups.getOrPut(simple.first) { TypeVars() }
                when (simple.second) {
                    "size" -> g.size = value
                    "lineHeight" -> g.lineHeight = value
                    else -> g.letterSpacing = value
                }
                continue
            }
            if (name.startsWith("text-") && cssDimen(value) != null) {
                typeGroups.getOrPut(name.removePrefix("text-")) { TypeVars() }.size = value
                continue
            }
            if (Regex("^(font-family|font|ff)(-.+)?$").matches(name) && looksLikeFontFamily(value)) {
                val family = CssValues.firstFontFamily(value)
                b.fontFamilies[rawName] = family
                if (words.any { it in setOf("body", "base", "sans", "primary", "default", "text", "ui") } || b.defaultFontFamily == null) {
                    if (words.none { it in setOf("mono", "code", "heading", "display", "serif") }) b.defaultFontFamily = family
                }
                continue
            }
            if (Regex("^(font-weight|fw|weight)-.+$").matches(name)) continue

            when {
                Categories.isRadius(words) && cssDimen(value) != null -> b.putRadius(Names.tokenIdentifier(name, Categories.RADIUS), cssDimen(value)!!)
                Categories.isElevation(words) -> (CssValues.shadowToElevation(value) ?: cssDimen(value))
                    ?.let { b.putElevation(Names.tokenIdentifier(name, Categories.ELEVATION), it) }
                Categories.isSpacing(words) && cssDimen(value) != null -> b.putSpacing(Names.tokenIdentifier(name, Categories.SPACING), cssDimen(value)!!)
                Categories.isSize(words) && cssDimen(value) != null -> b.putSize(Names.tokenIdentifier(name, Categories.SIZE), cssDimen(value)!!)
                else -> colorOf(value)?.let { argb ->
                    val id = Names.tokenIdentifier(name, Categories.COLOR)
                    b.colorAliases[rawName] = id
                    b.putColor(id, argb, dark = false)
                }
            }
        }

        for ((rawName, raw) in darkVars) {
            val argb = colorOf(resolve(raw, listOf(darkVars, lightVars)).trim()) ?: continue
            val id = b.colorAliases[rawName] ?: Names.tokenIdentifier(rawName.removePrefix("--"), Categories.COLOR).also { b.colorAliases[rawName] = it }
            b.putColor(id, argb, dark = true)
        }

        for ((key, g) in typeGroups) {
            val size = g.size?.let { cssDimen(it) } ?: continue
            b.mergeTypography(
                TypographyToken(
                    name = Names.tokenIdentifier(key, Categories.TYPOGRAPHY),
                    fontFamily = g.family?.let { CssValues.firstFontFamily(it) },
                    fontSizeSp = size,
                    fontWeight = g.weight?.let { CssValues.parseFontWeight(it) },
                    lineHeightSp = g.lineHeight?.let { relativeToFont(it, size, multiplierAllowed = true) },
                    letterSpacingSp = g.letterSpacing?.let { relativeToFont(it, size, multiplierAllowed = false) },
                )
            )
        }
    }

    private fun looksLikeFontFamily(v: String): Boolean {
        if (cssDimen(v) != null || CssValues.parseFontWeight(v) != null || colorOf(v) != null) return false
        return v.contains(',') || v.contains('"') || v.contains('\'') ||
            Regex("^[A-Za-z][A-Za-z0-9 -]*$").matches(v)
    }

    // endregion

    /** Dimension, also taking the preferred size out of clamp()/min()/max(). */
    private fun cssDimen(v: String): Float? {
        val s = v.trim()
        Regex("^clamp\\((.*)\\)$").find(s)?.let { m ->
            return m.groupValues[1].split(',').map { it.trim() }.let { parts -> parts.lastOrNull()?.let { CssValues.parseDimen(it) } ?: parts.firstNotNullOfOrNull { CssValues.parseDimen(it) } }
        }
        Regex("^(min|max)\\((.*)\\)$").find(s)?.let { m -> return m.groupValues[2].split(',').firstNotNullOfOrNull { CssValues.parseDimen(it.trim()) } }
        return CssValues.parseDimen(s)
    }

    private fun scopesFor(rule: CssRule, vararg extra: Map<String, String>): List<Map<String, String>> =
        listOf(rule.decls.filterKeys { it.startsWith("--") }) + extra.toList() + listOf(lightVars)

    private fun readFontFaces() {
        for (rule in rules.filter { it.selectors == listOf("@font-face") }) {
            val family = rule.decls["font-family"]?.let { CssValues.firstFontFamily(it) } ?: continue
            val weight = rule.decls["font-weight"]?.split(Regex("\\s+"))?.first()?.let { CssValues.parseFontWeight(it) } ?: 400
            val italic = rule.decls["font-style"]?.contains("italic", true) == true
            val src = rule.decls["src"] ?: continue
            Regex("""url\(\s*["']?([^"')]+)["']?\s*\)""").findAll(src).forEach { m ->
                val base = m.groupValues[1].substringAfterLast('/').substringBeforeLast('.').lowercase()
                b.fontFaces[base] = FontFace(family, weight, italic)
            }
        }
    }

    // region typography classes

    private val typographySelector = Regex("^(\\.[A-Za-z][\\w-]*|h[1-6]|body|p|small|caption|label)$")
    private val componentNames = Regex("^\\.(btn|button|card|input|chip|badge|tag|pill|field|form|nav|modal|dialog|container|wrapper|grid|row|col)\\b")

    private fun readTypographyClasses() {
        for (rule in rules) {
            if (isDark(rule) || rule.media.isNotEmpty()) continue
            if (!rule.decls.containsKey("font-size") && !rule.decls.containsKey("font")) continue
            for (selector in rule.selectors) {
                if (!typographySelector.matches(selector) || componentNames.containsMatchIn(selector)) continue
                val scopes = scopesFor(rule)
                fun d(key: String) = rule.decls[key]?.let { resolve(it, scopes).trim() }
                var size = d("font-size")?.let { cssDimen(it) }
                var family = d("font-family")?.let { CssValues.firstFontFamily(it) }
                var weight = d("font-weight")?.let { CssValues.parseFontWeight(it) }
                var lineHeightRaw = d("line-height")
                d("font")?.let { shorthand ->
                    parseFontShorthand(shorthand)?.let { f ->
                        size = size ?: f.size
                        weight = weight ?: f.weight
                        family = family ?: f.family
                        lineHeightRaw = lineHeightRaw ?: f.lineHeight
                    }
                }
                val px = size ?: continue
                b.mergeTypography(
                    TypographyToken(
                        name = Names.tokenIdentifier(selector.removePrefix("."), Categories.TYPOGRAPHY),
                        fontFamily = family,
                        fontSizeSp = px,
                        fontWeight = weight,
                        lineHeightSp = lineHeightRaw?.let { relativeToFont(it, px, multiplierAllowed = true) },
                        letterSpacingSp = d("letter-spacing")?.let { relativeToFont(it, px, multiplierAllowed = false) },
                        italic = d("font-style")?.contains("italic") == true,
                    )
                )
            }
        }
    }

    private class FontShorthand(val size: Float?, val lineHeight: String?, val weight: Int?, val family: String?)

    private fun parseFontShorthand(v: String): FontShorthand? {
        val m = Regex("""(?:^|\s)(\d*\.?\d+(?:px|rem|em|pt))(?:\s*/\s*(\S+))?\s+(.+)$""").find(v) ?: return null
        val before = v.substring(0, m.range.first).trim()
        val weight = before.split(Regex("\\s+")).firstNotNullOfOrNull { CssValues.parseFontWeight(it) }
        return FontShorthand(cssDimen(m.groupValues[1]), m.groupValues[2].ifEmpty { null }, weight, CssValues.firstFontFamily(m.groupValues[3]))
    }

    // endregion

    // region components

    // Optional short design-system prefix, e.g. ".pf-btn", ".ds-card", ".ui-chip".
    private val prefix = """(?:[a-z][a-z0-9]{0,3}-)?"""
    private val variantSuffix = """(?:(?:--|-|_|\.)([\w-]+))?"""
    private val componentPatterns = listOf(
        ComponentType.BUTTON to Regex("""^\.$prefix(btn|button)$variantSuffix$"""),
        ComponentType.TEXT_FIELD to Regex("""^(?:\.$prefix(input|text-field|textfield|text-input|textinput|field|form-control|form-input)|(input)|(textarea))$variantSuffix$"""),
        ComponentType.CARD to Regex("""^\.$prefix(card)$variantSuffix$"""),
        ComponentType.CHIP to Regex("""^\.$prefix(chip)$variantSuffix$"""),
        ComponentType.BADGE to Regex("""^\.$prefix(badge|pill|status)$variantSuffix$"""),
    )
    /** Standalone modifier classes like ".b-pending" that style a component through its initial letter. */
    private val letterModifier = Regex("""^\.([a-z])-([\w-]+)$""")

    private val notVariants = setOf(
        "header", "body", "title", "subtitle", "content", "footer", "image", "img", "icon", "label", "text", "group",
        "wrapper", "container", "list", "item", "sm", "md", "lg", "xl", "xs", "small", "large", "medium", "block", "full",
        "wide", "icon-only", "description", "actions", "media", "row", "grid", "lg-icon", "sm-icon", "close", "dot", "avatar",
        "n", "t", "s", "ic", "ms", "main", "end", "amt", "meta", "disc", "badge", "pill",
    )

    private fun readComponents() {
        val baseWords = LinkedHashMap<ComponentType, String>()
        val withVariants = mutableSetOf<ComponentType>()
        /** Types with a compound variant like ".pf-badge.b-refund": proof that ".b-*" classes belong to them. */
        val letterOwners = HashMap<String, MutableSet<ComponentType>>()
        val letterRules = mutableListOf<Pair<CssRule, MatchResult>>()
        for (rule in rules) {
            if (isDark(rule) || rule.media.isNotEmpty()) continue
            for (selector in rule.selectors) {
                val hit = componentPatterns.firstNotNullOfOrNull { (t, re) -> re.matchEntire(selector)?.let { t to it } }
                if (hit == null) {
                    letterModifier.matchEntire(selector)?.let { letterRules += rule to it }
                    continue
                }
                val (type, match) = hit
                val groups = match.groupValues.drop(1)
                val word = groups.dropLast(1).firstOrNull { it.isNotEmpty() } ?: continue
                var variant = groups.last().ifEmpty { null }
                if (variant != null && Regex("^${word.first()}-.+").matches(variant)) {
                    letterOwners.getOrPut(word.first().toString()) { mutableSetOf() } += type
                    variant = variant.substring(2)
                }
                if (variant != null && !isVariantName(type, variant)) continue
                val props = componentProps(rule)
                if (props.isEmpty()) continue
                if (variant == null) baseWords.putIfAbsent(type, word) else withVariants += type
                b.mergeComponent(type, variant ?: DesignSystemBuilder.BASE_VARIANT, props)
            }
        }
        // ".b-pending" → badge variant, when exactly one component's class word starts with "b" (preferring ones without own variants).
        for ((rule, m) in letterRules) {
            val letter = m.groupValues[1]
            val variant = m.groupValues[2]
            val candidates = baseWords.filter { it.value.startsWith(letter) }.keys
            val type = letterOwners[letter]?.singleOrNull()
                ?: candidates.singleOrNull()
                ?: candidates.filter { it !in withVariants }.singleOrNull()
                ?: continue
            val props = componentProps(rule)
            if (props.isNotEmpty() && isVariantName(type, variant)) b.mergeComponent(type, variant, props)
        }
    }

    private fun isVariantName(type: ComponentType, variant: String): Boolean {
        val v = variant.lowercase()
        if (type == ComponentType.BUTTON && v in setOf("text", "link", "icon")) return true
        return v !in notVariants
    }

    private fun componentProps(rule: CssRule): Map<String, TokenValue> {
        val scopes = scopesFor(rule)
        val out = LinkedHashMap<String, TokenValue>()
        fun raw(vararg keys: String) = keys.firstNotNullOfOrNull { rule.decls[it] }

        raw("background-color", "background")?.let { v -> colorValue(firstColorToken(v), scopes)?.let { out[Props.BACKGROUND] = it } }
        raw("color")?.let { v -> colorValue(v, scopes)?.let { out[Props.CONTENT] = it } }

        raw("border")?.let { v ->
            val resolved = resolve(v, scopes).trim()
            if (resolved == "none" || resolved == "0") {
                out[Props.BORDER_WIDTH] = TokenValue.Dimen(0f)
            } else {
                splitOutsideParens(v).forEach { part ->
                    val dim = cssDimen(resolve(part, scopes))
                    if (dim != null && Props.BORDER_WIDTH !in out) out[Props.BORDER_WIDTH] = TokenValue.Dimen(dim)
                    else colorValue(part, scopes)?.let { out[Props.BORDER_COLOR] = it }
                }
            }
        }
        rule.decls["border-color"]?.let { v -> colorValue(v, scopes)?.let { out[Props.BORDER_COLOR] = it } }
        rule.decls["border-width"]?.let { v -> cssDimen(resolve(v, scopes))?.let { out[Props.BORDER_WIDTH] = TokenValue.Dimen(it) } }
        rule.decls["border-radius"]?.let { v ->
            splitOutsideParens(resolve(v, scopes)).firstNotNullOfOrNull { cssDimen(it) }?.let { out[Props.RADIUS] = TokenValue.Dimen(it) }
        }

        rule.decls["padding"]?.let { v ->
            val parts = splitOutsideParens(resolve(v, scopes)).mapNotNull { cssDimen(it) }
            when (parts.size) {
                1 -> { out[Props.PADDING_V] = TokenValue.Dimen(parts[0]); out[Props.PADDING_H] = TokenValue.Dimen(parts[0]) }
                2, 3 -> { out[Props.PADDING_V] = TokenValue.Dimen(parts[0]); out[Props.PADDING_H] = TokenValue.Dimen(parts[1]) }
                4 -> { out[Props.PADDING_V] = TokenValue.Dimen(parts[0]); out[Props.PADDING_H] = TokenValue.Dimen(parts[1]) }
            }
        }
        raw("padding-inline", "padding-left", "padding-right")?.let { v ->
            splitOutsideParens(resolve(v, scopes)).firstNotNullOfOrNull { cssDimen(it) }?.let { out[Props.PADDING_H] = TokenValue.Dimen(it) }
        }
        raw("padding-block", "padding-top", "padding-bottom")?.let { v ->
            splitOutsideParens(resolve(v, scopes)).firstNotNullOfOrNull { cssDimen(it) }?.let { out[Props.PADDING_V] = TokenValue.Dimen(it) }
        }
        raw("height", "min-height")?.let { v -> cssDimen(resolve(v, scopes))?.let { out[Props.HEIGHT] = TokenValue.Dimen(it) } }
        rule.decls["box-shadow"]?.let { v ->
            val resolved = resolve(v, scopes).trim()
            val inset = v.split(Regex(",(?![^(]*\\))")).map { it.trim() }.firstOrNull { it.startsWith("inset") }
            when {
                resolved == "none" -> {
                    out[Props.ELEVATION] = TokenValue.Dimen(0f)
                    out[Props.BORDER_WIDTH] = TokenValue.Dimen(0f)
                }
                // "inset 0 0 0 1px var(--border)" is how many designs draw a 1px border
                inset != null -> {
                    val parts = splitOutsideParens(inset.removePrefix("inset").trim())
                    val dims = parts.mapNotNull { cssDimen(resolve(it, scopes)) }
                    val width = dims.getOrNull(3) ?: dims.getOrNull(2)
                    if (width != null && width > 0f) out[Props.BORDER_WIDTH] = TokenValue.Dimen(width)
                    parts.firstOrNull { cssDimen(resolve(it, scopes)) == null }?.let { c -> colorValue(c, scopes)?.let { out[Props.BORDER_COLOR] = it } }
                }
                else -> out[Props.ELEVATION] = TokenValue.Dimen(CssValues.shadowToElevation(resolved) ?: 0f)
            }
        }
        return out
    }

    private fun firstColorToken(v: String): String =
        if (v.trim().startsWith("var(") || colorOf(v.trim()) != null) v.trim() else splitOutsideParens(v).firstOrNull { it.startsWith("var(") || colorOf(it) != null } ?: v

    /** Keeps a reference to a color token when the value is (a chain of) var() pointing at one. */
    private fun colorValue(v: String, scopes: List<Map<String, String>>, depth: Int = 0): TokenValue? {
        val s = v.trim()
        if (depth > 10) return null
        Regex("""^(?:hsla?|rgba?|oklch)\(\s*var\(\s*(--[\w-]+)\s*\)\s*\)$""").matchEntire(s)?.let { m ->
            b.colorAliases[m.groupValues[1]]?.let { return TokenValue.ColorRef(it) }
        }
        Regex("""^var\(\s*(--[\w-]+)\s*(?:,.*)?\)$""").matchEntire(s)?.let { m ->
            val name = m.groupValues[1]
            b.colorAliases[name]?.let { return TokenValue.ColorRef(it) }
            val next = scopes.firstNotNullOfOrNull { it[name] }
            if (next == null) {
                // var() pointing at a token defined in JSON rather than CSS
                Names.tokenIdentifier(name.removePrefix("--"), Categories.COLOR).takeIf { it in b.colorIds() }
                    ?.let { return TokenValue.ColorRef(it) }
            }
            if (next != null) return colorValue(next, scopes, depth + 1)
        }
        val resolved = resolve(s, scopes).trim()
        if (CssValues.isTransparent(resolved)) return TokenValue.Transparent
        return colorOf(resolved)?.let { TokenValue.ColorLiteral(it) }
    }

    private fun splitOutsideParens(v: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in v) {
            when {
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c.isWhitespace() && depth == 0 -> { if (sb.isNotEmpty()) out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    // endregion
}
