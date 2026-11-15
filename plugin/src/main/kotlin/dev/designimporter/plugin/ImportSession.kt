package dev.designimporter.plugin

import dev.designimporter.core.gen.DesignSystemGenerator
import dev.designimporter.core.gen.GeneratorConfig
import dev.designimporter.core.gen.IconCandidate
import dev.designimporter.core.gen.IconPlan
import dev.designimporter.core.gen.MaterialDependencies
import dev.designimporter.core.icons.MatchMode
import dev.designimporter.core.icons.MaterialIconIndex
import dev.designimporter.core.icons.MaterialIconMatcher
import dev.designimporter.core.model.DesignSystem
import dev.designimporter.core.plan.DependencyUpdater
import dev.designimporter.core.plan.ImportPlanner
import dev.designimporter.core.plan.PlannedChange
import dev.designimporter.core.plan.ResourceNames
import dev.designimporter.core.reader.StringKeys
import dev.designimporter.core.plan.TextEdit
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

object MaterialIndexHolder {
    val index: MaterialIconIndex get() = MaterialIconIndex.shared
}

class ImportResult(
    val module: AndroidModule,
    val config: GeneratorConfig,
    val changes: List<PlannedChange>,
    val icons: List<IconPlan>,
    val dependencies: MaterialDependencies,
    val dependencyEdits: List<TextEdit>,
)

/** Holds one export while the preview dialog lets the user tweak options. */
class ImportSession(val ds: DesignSystem, private val projectDir: Path) {
    var candidates: List<IconCandidate> = emptyList()
        private set
    /** Per icon name: false = generate even though a Material icon matched. */
    val useMaterial = HashMap<String, Boolean>()
    private var analyzedWith: Pair<MatchMode, String>? = null

    fun analyze(mode: MatchMode, defaultStyle: String) {
        if (analyzedWith == mode to defaultStyle) return
        val override = defaultStyle.takeIf { ds.iconStyleHint != null && it != ds.iconStyleHint }
        val matcher = if (mode == MatchMode.OFF) null else MaterialIconMatcher(MaterialIndexHolder.index, mode, defaultStyle, override)
        candidates = DesignSystemGenerator.analyzeIcons(ds, matcher)
        analyzedWith = mode to defaultStyle
    }

    /** Per string (reader key): false = leave it out of design_strings.xml. */
    val useString = HashMap<String, Boolean>()
    var includeStrings = true

    /** Reader key → key written to the module (renamed when the module already declares it). */
    var stringKeys: Map<String, String> = emptyMap()
        private set

    fun build(module: AndroidModule, config: GeneratorConfig): ImportResult {
        val icons = DesignSystemGenerator.iconPlans(candidates, useMaterial)
        val selected = if (includeStrings) ds.strings.filter { useString[it.key] != false } else emptyList()
        val renamed = StringKeys.avoid(selected, ResourceNames.existingStrings(module.dir))
        stringKeys = selected.zip(renamed).associate { (a, b) -> a.key to b.key }
        val files = DesignSystemGenerator.generate(ds, config, icons, renamed)
        val changes = ImportPlanner.plan(module.dir, files)
        val deps = DesignSystemGenerator.materialDependencies(icons)
        val catalog = projectDir.resolve("gradle/libs.versions.toml").takeIf { it.exists() }
        val edits = DependencyUpdater.plan(module.buildFile, module.buildFile.readText(), catalog, catalog?.readText(), deps)
        return ImportResult(module, config, changes, icons, deps, edits)
    }

    fun warnings(): List<String> =
        (ds.warnings + candidates.mapNotNull { it.error } + candidates.flatMap { c -> c.vector?.warnings.orEmpty().map { "${c.asset.sourcePath}: $it" } }).distinct()
}
