package dev.designimporter.core.plan

import dev.designimporter.core.gen.MaterialDependencies

/** A full-text replacement for one build file. */
data class TextEdit(val path: java.nio.file.Path, val newText: String)

/**
 * Adds the Material icons artifact the generated AppIcons needs, through the version catalog when the project has one.
 * Versions come from the Compose BOM when the module uses it.
 */
object DependencyUpdater {
    private const val GROUP = "androidx.compose.material"
    private const val FALLBACK_VERSION = "1.7.8"

    fun plan(buildFile: java.nio.file.Path, buildText: String, catalogFile: java.nio.file.Path?, catalogText: String?, deps: MaterialDependencies): List<TextEdit> {
        val artifact = when {
            deps.extended -> "material-icons-extended"
            deps.core -> "material-icons-core"
            else -> return emptyList()
        }
        if (satisfied(buildText, deps.extended)) return emptyList()
        // Dependencies belong in the module that applies the Android plugin, never the root build file.
        if (GradleModules.androidPluginType(buildText) == null) return emptyList()

        val usesBom = buildText.contains("compose-bom") || buildText.contains("compose.bom") || buildText.contains("composeBom")
        val groovy = buildFile.fileName.toString().endsWith(".gradle")
        val edits = mutableListOf<TextEdit>()

        val dependencyLine = if (catalogFile != null && catalogText != null && catalogText.contains("[libraries]")) {
            val existingAlias = catalogText.lines().firstOrNull { it.contains("\"$artifact\"") || it.contains("$GROUP:$artifact") }
                ?.substringBefore('=')?.trim()
            val alias = existingAlias ?: "androidx-compose-$artifact"
            if (existingAlias == null) {
                val version = if (usesBom) "" else ", version = \"$FALLBACK_VERSION\""
                val entry = "$alias = { group = \"$GROUP\", name = \"$artifact\"$version }"
                edits += TextEdit(catalogFile, catalogText.replaceFirst("[libraries]", "[libraries]\n$entry"))
            }
            val accessor = "libs." + alias.replace('-', '.').replace('_', '.')
            if (groovy) "implementation $accessor" else "implementation($accessor)"
        } else {
            val coordinates = "$GROUP:$artifact" + if (usesBom) "" else ":$FALLBACK_VERSION"
            if (groovy) "implementation '$coordinates'" else "implementation(\"$coordinates\")"
        }

        val block = Regex("""(?m)^dependencies\s*\{""").find(buildText) ?: Regex("""dependencies\s*\{""").find(buildText)
        val newBuild = if (block != null) {
            buildText.substring(0, block.range.last + 1) + "\n    $dependencyLine" + buildText.substring(block.range.last + 1)
        } else {
            buildText.trimEnd() + "\n\ndependencies {\n    $dependencyLine\n}\n"
        }
        edits += TextEdit(buildFile, newBuild)
        return edits
    }

    private fun satisfied(buildText: String, needExtended: Boolean): Boolean {
        val extendedPresent = buildText.contains("material-icons-extended") || buildText.contains("icons.extended") || buildText.contains("iconsExtended")
        if (extendedPresent) return true
        if (needExtended) return false
        return buildText.contains("material-icons-core") || buildText.contains("icons.core") || buildText.contains("iconsCore")
    }
}
