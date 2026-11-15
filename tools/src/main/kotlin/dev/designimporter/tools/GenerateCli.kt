package dev.designimporter.tools

import dev.designimporter.core.gen.DesignSystemGenerator
import dev.designimporter.core.gen.GeneratorConfig
import dev.designimporter.core.gen.IconFormat
import dev.designimporter.core.icons.MatchMode
import dev.designimporter.core.icons.MaterialIconIndex
import dev.designimporter.core.icons.MaterialIconMatcher
import dev.designimporter.core.plan.DependencyUpdater
import dev.designimporter.core.plan.FileAction
import dev.designimporter.core.plan.ImportPlanner
import dev.designimporter.core.plan.ResourceNames
import dev.designimporter.core.reader.DesignSystemReader
import dev.designimporter.core.reader.StringKeys
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/** Usage: <export zip|folder> <module dir> <package> [namespace] [IMAGE_VECTOR|DRAWABLE] */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("Usage: generate <export> <moduleDir> <package> [namespace] [IMAGE_VECTOR|DRAWABLE]")
        exitProcess(2)
    }
    val export = Path.of(args[0])
    val module = Path.of(args[1])
    val pkg = args[2]
    val namespace = args.getOrNull(3)?.ifEmpty { null } ?: pkg.substringBeforeLast('.')
    val format = IconFormat.valueOf(args.getOrNull(4) ?: "IMAGE_VECTOR")

    val ds = DesignSystemReader.read(export)
    val matcher = MaterialIconMatcher(MaterialIconIndex.load(), MatchMode.GEOMETRY_AND_NAME)
    val candidates = DesignSystemGenerator.analyzeIcons(ds, matcher)
    val icons = DesignSystemGenerator.iconPlans(candidates)
    val sourceDir = if (!module.resolve("src/main/kotlin").exists() && module.resolve("src/main/java").exists()) "src/main/java" else "src/main/kotlin"
    val cfg = GeneratorConfig(packageName = pkg, rPackage = namespace, iconFormat = format, kotlinSourceDir = sourceDir)
    val strings = StringKeys.avoid(ds.strings, ResourceNames.existingStrings(module))
    val files = DesignSystemGenerator.generate(ds, cfg, icons, strings)
    val plan = ImportPlanner.plan(module, files)
    ImportPlanner.apply(module, plan)

    println("Colors ${ds.colors.size}, typography ${ds.typography.size}, radii ${ds.radii.size}, spacing ${ds.spacing.size}, elevation ${ds.elevation.size}, sizes ${ds.sizes.size}, fonts ${ds.fonts.size}")
    println("Strings ${strings.size} (translations: ${strings.flatMap { it.translations.keys }.groupingBy { it }.eachCount()})")
    candidates.forEach { c ->
        val m = c.match?.let { "-> ${it.icon.expression} (${it.kind.name.lowercase()}${if (it.icon.extended) ", extended" else ""})" } ?: "-> generated"
        println("icon ${c.asset.name} $m${c.error?.let { "  [$it]" } ?: ""}")
    }
    plan.groupBy { it.action }.forEach { (action, list) -> println("$action: ${list.size}") }
    plan.filter { it.action == FileAction.CONFLICT }.forEach { println("conflict (skipped): ${it.path}") }
    val deps = DesignSystemGenerator.materialDependencies(icons)
    val buildFile = listOf("build.gradle.kts", "build.gradle").map { module.resolve(it) }.firstOrNull { it.exists() }
    if (buildFile != null) {
        val catalog = generateSequence(module.toAbsolutePath()) { it.parent }.take(4)
            .map { it.resolve("gradle/libs.versions.toml") }.firstOrNull { it.exists() }
        DependencyUpdater.plan(buildFile, buildFile.readText(), catalog, catalog?.readText(), deps).forEach { edit ->
            edit.path.writeText(edit.newText)
            println("updated ${edit.path.fileName} for Material icons")
        }
    }
    (ds.warnings + candidates.flatMap { it.vector?.warnings.orEmpty() }).distinct().forEach { println("warning: $it") }
}
