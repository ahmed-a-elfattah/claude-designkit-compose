package dev.designimporter.core.gen

import dev.designimporter.core.Names
import dev.designimporter.core.model.ComponentSpec
import dev.designimporter.core.model.ComponentType
import dev.designimporter.core.model.ComponentVariant
import dev.designimporter.core.model.DesignSystem
import dev.designimporter.core.model.Props
import dev.designimporter.core.model.TokenValue

/** Thin Material3 wrappers styled from the exported component specs (or theme defaults when absent). */
internal class ComponentGenerator(private val ds: DesignSystem, private val cfg: GeneratorConfig) {
    private val p = cfg.prefix
    private val t = cfg.themeName
    private val pkg = "${cfg.packageName}.components"
    private val dir = "${cfg.packageDir}/components"
    private val colorIds = ds.colors.map { it.name }.toSet()

    fun generate(): List<GeneratedFile> = listOf(
        GeneratedFile.text("$dir/${p}Button.kt", button()),
        GeneratedFile.text("$dir/${p}TextField.kt", textField()),
        GeneratedFile.text("$dir/${p}Card.kt", card()),
        GeneratedFile.text("$dir/${p}Chip.kt", pill(ComponentType.CHIP, "Chip", listOf("Default", "Selected"), clickable = true)),
        GeneratedFile.text("$dir/${p}Badge.kt", pill(ComponentType.BADGE, "Badge", listOf("Neutral", "Success", "Warning", "Error"), clickable = false)),
    )

    private fun spec(type: ComponentType): ComponentSpec? = ds.components.firstOrNull { it.type == type }

    private fun variants(type: ComponentType, defaults: List<String>): List<ComponentVariant> =
        spec(type)?.variants?.takeIf { it.isNotEmpty() } ?: defaults.map { ComponentVariant(it, emptyMap()) }

    private fun color(v: TokenValue?): String? = when (v) {
        is TokenValue.ColorRef -> if (v.tokenName in colorIds) "$t.colors.${Names.escape(v.tokenName)}" else null
        is TokenValue.ColorLiteral -> colorLiteral(v.argb)
        TokenValue.Transparent -> "Color.Transparent"
        else -> null
    }

    private fun dimen(v: TokenValue?): Float? = (v as? TokenValue.Dimen)?.dp

    private fun shape(props: Map<String, TokenValue>, fallback: String): String = dimen(props[Props.RADIUS])?.let {
        if (it >= 999f) "RoundedCornerShape(percent = 50)" else "RoundedCornerShape(${dp(it)})"
    } ?: fallback

    private fun border(props: Map<String, TokenValue>, fallback: String): String {
        val width = dimen(props[Props.BORDER_WIDTH])
        if (width == 0f) return "null"
        val c = color(props[Props.BORDER_COLOR]) ?: return if (width != null) "BorderStroke(${dp(width)}, MaterialTheme.colorScheme.outline)" else fallback
        return "BorderStroke(${dp(width ?: 1f)}, $c)"
    }

    private fun isQuiet(name: String) = name.lowercase() in setOf("outline", "outlined", "ghost", "text", "link", "tertiary", "plain", "subtle", "flat")
    private fun isDanger(name: String) = name.lowercase() in setOf("destructive", "danger", "error", "negative", "delete")
    private fun isSecondary(name: String) = name.lowercase() in setOf("secondary", "neutral", "muted", "soft", "tonal")

    private fun imports(f: KotlinFile) = f.import(
        "androidx.compose.foundation.BorderStroke",
        "androidx.compose.foundation.layout.Arrangement",
        "androidx.compose.foundation.layout.Column",
        "androidx.compose.foundation.layout.PaddingValues",
        "androidx.compose.foundation.layout.padding",
        "androidx.compose.foundation.shape.RoundedCornerShape",
        "androidx.compose.material3.MaterialTheme",
        "androidx.compose.runtime.Composable",
        "androidx.compose.runtime.Immutable",
        "androidx.compose.ui.Modifier",
        "androidx.compose.ui.graphics.Color",
        "androidx.compose.ui.graphics.Shape",
        "androidx.compose.ui.tooling.preview.Preview",
        "androidx.compose.ui.unit.Dp",
        "androidx.compose.ui.unit.dp",
        "${cfg.packageName}.$t",
    )

    private fun enum(f: KotlinFile, name: String, variants: List<ComponentVariant>) {
        f.line("enum class $name { ${variants.joinToString(", ") { it.name }} }").line()
    }

    // region button

    private fun button(): String {
        val f = KotlinFile(pkg)
        imports(f).import(
            "androidx.compose.foundation.layout.Spacer",
            "androidx.compose.foundation.layout.defaultMinSize",
            "androidx.compose.foundation.layout.size",
            "androidx.compose.foundation.layout.width",
            "androidx.compose.material3.Button",
            "androidx.compose.material3.ButtonDefaults",
            "androidx.compose.material3.Icon",
            "androidx.compose.material3.Text",
            "androidx.compose.ui.graphics.vector.ImageVector",
        )
        val variants = variants(ComponentType.BUTTON, listOf("Primary", "Secondary", "Outline", "Text"))
        val enumName = "${p}ButtonVariant"
        enum(f, enumName, variants)
        f.raw(
            """
            @Composable
            fun ${p}Button(
                text: String,
                onClick: () -> Unit,
                modifier: Modifier = Modifier,
                variant: $enumName = $enumName.${variants.first().name},
                enabled: Boolean = true,
                leadingIcon: ImageVector? = null,
            ) {
                val style = buttonStyle(variant)
                Button(
                    onClick = onClick,
                    modifier = modifier.defaultMinSize(minHeight = style.height),
                    enabled = enabled,
                    shape = style.shape,
                    colors = ButtonDefaults.buttonColors(containerColor = style.container, contentColor = style.content),
                    elevation = null,
                    border = style.border,
                    contentPadding = style.padding,
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(text)
                }
            }

            @Immutable
            private class ButtonStyle(
                val container: Color,
                val content: Color,
                val border: BorderStroke?,
                val shape: Shape,
                val padding: PaddingValues,
                val height: Dp,
            )

            @Composable
            private fun buttonStyle(variant: $enumName): ButtonStyle = when (variant) {

            """.trimIndent() + "\n"
        )
        for (v in variants) {
            val quiet = isQuiet(v.name)
            val containerDefault = when {
                quiet -> "Color.Transparent"
                isDanger(v.name) -> "MaterialTheme.colorScheme.error"
                isSecondary(v.name) -> "MaterialTheme.colorScheme.secondaryContainer"
                else -> "MaterialTheme.colorScheme.primary"
            }
            val container = color(v.props[Props.BACKGROUND]) ?: containerDefault
            val transparent = container == "Color.Transparent"
            val contentDefault = when {
                transparent -> "MaterialTheme.colorScheme.primary"
                isDanger(v.name) -> "MaterialTheme.colorScheme.onError"
                isSecondary(v.name) -> "MaterialTheme.colorScheme.onSecondaryContainer"
                else -> "MaterialTheme.colorScheme.onPrimary"
            }
            val borderDefault = if (v.name.lowercase().startsWith("outline")) "BorderStroke(1.dp, MaterialTheme.colorScheme.outline)" else "null"
            val h = dimen(v.props[Props.PADDING_H])
            val vv = dimen(v.props[Props.PADDING_V])
            val padding = if (h == null && vv == null) "ButtonDefaults.ContentPadding" else "PaddingValues(horizontal = ${dp(h ?: 24f)}, vertical = ${dp(vv ?: 8f)})"
            f.line("    $enumName.${v.name} -> ButtonStyle(")
            f.line("        container = $container,")
            f.line("        content = ${color(v.props[Props.CONTENT]) ?: contentDefault},")
            f.line("        border = ${border(v.props, borderDefault)},")
            f.line("        shape = ${shape(v.props, "MaterialTheme.shapes.small")},")
            f.line("        padding = $padding,")
            f.line("        height = ${dp(dimen(v.props[Props.HEIGHT]) ?: 40f)},")
            f.line("    )")
        }
        f.line("}").line()
        f.raw(
            """
            @Preview(showBackground = true)
            @Composable
            private fun ${p}ButtonPreview() {
                $t {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        $enumName.entries.forEach { ${p}Button(text = it.name, onClick = {}, variant = it) }
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        return f.render()
    }

    // endregion

    // region text field

    private fun textField(): String {
        val f = KotlinFile(pkg)
        imports(f).import(
            "androidx.compose.material3.Icon",
            "androidx.compose.material3.OutlinedTextField",
            "androidx.compose.material3.OutlinedTextFieldDefaults",
            "androidx.compose.material3.Text",
            "androidx.compose.ui.graphics.vector.ImageVector",
        )
        val props = variants(ComponentType.TEXT_FIELD, listOf("Default")).first().props
        val container = color(props[Props.BACKGROUND]) ?: "Color.Transparent"
        val text = color(props[Props.CONTENT]) ?: "MaterialTheme.colorScheme.onSurface"
        val borderColor = color(props[Props.BORDER_COLOR]) ?: "MaterialTheme.colorScheme.outline"
        f.raw(
            """
            @Composable
            fun ${p}TextField(
                value: String,
                onValueChange: (String) -> Unit,
                modifier: Modifier = Modifier,
                label: String? = null,
                placeholder: String? = null,
                enabled: Boolean = true,
                isError: Boolean = false,
                singleLine: Boolean = true,
                leadingIcon: ImageVector? = null,
                trailingIcon: ImageVector? = null,
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = modifier,
                    enabled = enabled,
                    isError = isError,
                    singleLine = singleLine,
                    label = if (label != null) { { Text(label) } } else null,
                    placeholder = if (placeholder != null) { { Text(placeholder) } } else null,
                    leadingIcon = if (leadingIcon != null) { { Icon(leadingIcon, contentDescription = null) } } else null,
                    trailingIcon = if (trailingIcon != null) { { Icon(trailingIcon, contentDescription = null) } } else null,
                    shape = ${shape(props, "MaterialTheme.shapes.small")},
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = $text,
                        unfocusedTextColor = $text,
                        focusedContainerColor = $container,
                        unfocusedContainerColor = $container,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = $borderColor,
                    ),
                )
            }

            @Preview(showBackground = true)
            @Composable
            private fun ${p}TextFieldPreview() {
                $t {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ${p}TextField(value = "", onValueChange = {}, label = "Label", placeholder = "Placeholder")
                        ${p}TextField(value = "Error", onValueChange = {}, label = "Label", isError = true)
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        return f.render()
    }

    // endregion

    // region card

    private fun card(): String {
        val f = KotlinFile(pkg)
        imports(f).import(
            "androidx.compose.foundation.layout.ColumnScope",
            "androidx.compose.material3.Card",
            "androidx.compose.material3.CardDefaults",
            "androidx.compose.material3.Text",
        )
        val variants = variants(ComponentType.CARD, listOf("Filled", "Elevated", "Outlined"))
        val enumName = "${p}CardVariant"
        enum(f, enumName, variants)
        f.raw(
            """
            @Composable
            fun ${p}Card(
                modifier: Modifier = Modifier,
                variant: $enumName = $enumName.${variants.first().name},
                onClick: (() -> Unit)? = null,
                content: @Composable ColumnScope.() -> Unit,
            ) {
                val style = cardStyle(variant)
                val colors = CardDefaults.cardColors(containerColor = style.container, contentColor = style.content)
                val elevation = CardDefaults.cardElevation(defaultElevation = style.elevation)
                if (onClick != null) {
                    Card(onClick = onClick, modifier = modifier, shape = style.shape, colors = colors, elevation = elevation, border = style.border) {
                        Column(Modifier.padding(style.padding), content = content)
                    }
                } else {
                    Card(modifier = modifier, shape = style.shape, colors = colors, elevation = elevation, border = style.border) {
                        Column(Modifier.padding(style.padding), content = content)
                    }
                }
            }

            @Immutable
            private class CardStyle(
                val container: Color,
                val content: Color,
                val border: BorderStroke?,
                val shape: Shape,
                val padding: PaddingValues,
                val elevation: Dp,
            )

            @Composable
            private fun cardStyle(variant: $enumName): CardStyle = when (variant) {

            """.trimIndent() + "\n"
        )
        for (v in variants) {
            val outlined = v.name.lowercase().startsWith("outline")
            val elevated = v.name.lowercase().startsWith("elevat") || v.name.lowercase() == "raised"
            val h = dimen(v.props[Props.PADDING_H])
            val vv = dimen(v.props[Props.PADDING_V])
            f.line("    $enumName.${v.name} -> CardStyle(")
            f.line("        container = ${color(v.props[Props.BACKGROUND]) ?: "MaterialTheme.colorScheme.surface"},")
            f.line("        content = ${color(v.props[Props.CONTENT]) ?: "MaterialTheme.colorScheme.onSurface"},")
            f.line("        border = ${border(v.props, if (outlined) "BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)" else "null")},")
            f.line("        shape = ${shape(v.props, "MaterialTheme.shapes.medium")},")
            f.line("        padding = PaddingValues(horizontal = ${dp(h ?: 16f)}, vertical = ${dp(vv ?: h ?: 16f)}),")
            f.line("        elevation = ${dp(dimen(v.props[Props.ELEVATION]) ?: if (elevated) 2f else 0f)},")
            f.line("    )")
        }
        f.line("}").line()
        f.raw(
            """
            @Preview(showBackground = true)
            @Composable
            private fun ${p}CardPreview() {
                $t {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        $enumName.entries.forEach { variant ->
                            ${p}Card(variant = variant) {
                                Text(variant.name, style = MaterialTheme.typography.titleMedium)
                                Text("Card content", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        return f.render()
    }

    // endregion

    // region chip / badge

    private fun pill(type: ComponentType, kind: String, defaults: List<String>, clickable: Boolean): String {
        val f = KotlinFile(pkg)
        val styleClass = "${kind}Style"
        val styleFun = "${kind.lowercase()}Style"
        // Indented like the raw-string template below, which is trimIndent()-ed after interpolation.
        val clickParam = if (clickable) "\n                onClick: (() -> Unit)? = null," else ""
        val surfaceModifier = if (clickable) "modifier.then(if (onClick != null) Modifier.clip(style.shape).clickable(onClick = onClick) else Modifier)" else "modifier"
        val textStyle = if (type == ComponentType.BADGE) "labelSmall" else "labelMedium"
        imports(f).import(
            "androidx.compose.foundation.clickable",
            "androidx.compose.foundation.layout.Row",
            "androidx.compose.foundation.layout.heightIn",
            "androidx.compose.foundation.layout.size",
            "androidx.compose.material3.Icon",
            "androidx.compose.material3.Surface",
            "androidx.compose.material3.Text",
            "androidx.compose.ui.Alignment",
            "androidx.compose.ui.draw.clip",
            "androidx.compose.ui.graphics.vector.ImageVector",
        )
        val variants = variants(type, defaults)
        val enumName = "$p${kind}Variant"
        enum(f, enumName, variants)
        f.raw(
            """
            @Composable
            fun $p$kind(
                text: String,
                modifier: Modifier = Modifier,
                variant: $enumName = $enumName.${variants.first().name},
                leadingIcon: ImageVector? = null,$clickParam
            ) {
                val style = $styleFun(variant)
                Surface(
                    modifier = $surfaceModifier,
                    shape = style.shape,
                    color = style.container,
                    contentColor = style.content,
                    border = style.border,
                ) {
                    Row(
                        modifier = Modifier.heightIn(min = style.height).padding(style.padding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(text, style = MaterialTheme.typography.$textStyle)
                    }
                }
            }

            @Immutable
            private class $styleClass(
                val container: Color,
                val content: Color,
                val border: BorderStroke?,
                val shape: Shape,
                val padding: PaddingValues,
                val height: Dp,
            )

            @Composable
            private fun $styleFun(variant: $enumName): $styleClass = when (variant) {

            """.trimIndent() + "\n"
        )
        for (v in variants) {
            val quiet = isQuiet(v.name)
            val name = v.name.lowercase()
            val primary = name in setOf("primary", "brand", "accent", "selected", "active", "info")
            val success = name in setOf("success", "succeeded", "positive", "done", "paid", "completed")
            val warning = name in setOf("warning", "pending", "caution")
            val container = color(v.props[Props.BACKGROUND]) ?: when {
                quiet -> "Color.Transparent"
                primary -> "MaterialTheme.colorScheme.primaryContainer"
                success -> "MaterialTheme.colorScheme.tertiaryContainer"
                warning -> "MaterialTheme.colorScheme.secondaryContainer"
                isDanger(v.name) || name == "failed" -> "MaterialTheme.colorScheme.errorContainer"
                else -> "MaterialTheme.colorScheme.surfaceVariant"
            }
            val content = color(v.props[Props.CONTENT]) ?: when {
                primary -> "MaterialTheme.colorScheme.onPrimaryContainer"
                success -> "MaterialTheme.colorScheme.onTertiaryContainer"
                warning -> "MaterialTheme.colorScheme.onSecondaryContainer"
                isDanger(v.name) || name == "failed" -> "MaterialTheme.colorScheme.onErrorContainer"
                else -> "MaterialTheme.colorScheme.onSurfaceVariant"
            }
            val h = dimen(v.props[Props.PADDING_H])
            val vv = dimen(v.props[Props.PADDING_V])
            val defaultV = if (type == ComponentType.BADGE) 2f else 6f
            f.line("    $enumName.${v.name} -> $styleClass(")
            f.line("        container = $container,")
            f.line("        content = $content,")
            f.line("        border = ${border(v.props, if (name.startsWith("outline")) "BorderStroke(1.dp, MaterialTheme.colorScheme.outline)" else "null")},")
            f.line("        shape = ${shape(v.props, "RoundedCornerShape(percent = 50)")},")
            f.line("        padding = PaddingValues(horizontal = ${dp(h ?: if (type == ComponentType.BADGE) 8f else 12f)}, vertical = ${dp(vv ?: defaultV)}),")
            f.line("        height = ${dp(dimen(v.props[Props.HEIGHT]) ?: if (type == ComponentType.BADGE) 24f else 32f)},")
            f.line("    )")
        }
        f.line("}").line()
        f.raw(
            """
            @Preview(showBackground = true)
            @Composable
            private fun $p${kind}Preview() {
                $t {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        $enumName.entries.forEach { $p$kind(text = it.name, variant = it) }
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        return f.render()
    }

    // endregion
}
