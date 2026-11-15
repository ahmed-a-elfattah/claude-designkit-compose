package dev.designimporter.core.gen

import dev.designimporter.core.Names
import dev.designimporter.core.icons.IconMatch
import dev.designimporter.core.icons.MaterialIconMatcher
import dev.designimporter.core.model.DesignSystem
import dev.designimporter.core.model.IconAsset
import dev.designimporter.core.model.LocalizedString
import dev.designimporter.core.svg.SvgParser
import dev.designimporter.core.svg.VectorDoc

/** Result of analysing one exported icon, before the user decides Material vs generated. */
class IconCandidate(
    val asset: IconAsset,
    val propertyName: String,
    val vector: VectorDoc?,
    val match: IconMatch?,
    val error: String?,
)

data class MaterialDependencies(val core: Boolean, val extended: Boolean)

object DesignSystemGenerator {

    fun analyzeIcons(ds: DesignSystem, matcher: MaterialIconMatcher?): List<IconCandidate> {
        val used = mutableSetOf<String>()
        return ds.icons.map { asset ->
            val words = Names.words(asset.name).ifEmpty { listOf("icon") }
            val base = Names.pascal(words).let { if (it.first().isDigit()) "Ic$it" else it }
            val prop = Names.unique(base, used)
            if (asset.svg == null) {
                val match = matcher?.matchSymbol(asset.name, asset.styleHint)
                IconCandidate(
                    asset = asset,
                    propertyName = prop,
                    vector = null,
                    match = match,
                    error = if (match == null) "Material Symbol \"${asset.name}\" has no Compose Material icon; add it to the export as an SVG to include it" else null,
                )
            } else {
                val parsed = runCatching { SvgParser.parse(asset.svg) }
                val vector = parsed.getOrNull()
                IconCandidate(
                    asset = asset,
                    propertyName = prop,
                    vector = vector,
                    match = matcher?.match(asset.name, vector),
                    error = parsed.exceptionOrNull()?.let { "${asset.sourcePath}: ${it.message ?: "invalid SVG"}" },
                )
            }
        }
    }

    /** [useMaterial]: per icon name, whether to reference the matched Material icon. Defaults to yes when matched. */
    fun iconPlans(candidates: List<IconCandidate>, useMaterial: Map<String, Boolean> = emptyMap()): List<IconPlan> =
        candidates.mapNotNull { c ->
            val material = c.match?.icon?.takeIf { useMaterial[c.asset.name] ?: true }
            if (material == null && c.vector == null) null else IconPlan(c.asset.name, c.propertyName, material, if (material == null) c.vector else null)
        }

    fun materialDependencies(plans: List<IconPlan>) = MaterialDependencies(
        core = plans.any { it.material != null },
        extended = plans.any { it.material?.extended == true },
    )

    /**
     * [strings] are the texts to write (already filtered and renamed against the module's existing resources,
     * see [dev.designimporter.core.reader.StringKeys.avoid]); defaults to all of [DesignSystem.strings].
     */
    fun generate(ds: DesignSystem, cfg: GeneratorConfig, icons: List<IconPlan>, strings: List<LocalizedString> = ds.strings): List<GeneratedFile> =
        ThemeGenerator(ds, cfg).generate() +
            IconGenerator(icons, cfg).generate() +
            ComponentGenerator(ds, cfg).generate() +
            StringsGenerator(strings, cfg).generate()
}
