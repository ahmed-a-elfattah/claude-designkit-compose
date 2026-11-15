package dev.designimporter.plugin

import dev.designimporter.core.plan.GradleModules
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

data class AndroidModule(
    val dir: Path,
    val gradlePath: String,
    val buildFile: Path,
    val namespace: String?,
    val isApplication: Boolean,
) {
    val kotlinSourceDir: String
        get() = if (!dir.resolve("src/main/kotlin").exists() && dir.resolve("src/main/java").exists()) "src/main/java" else "src/main/kotlin"

    override fun toString() = if (namespace != null) "$gradlePath  ($namespace)" else gradlePath
}

/** Finds Android Gradle modules under the project root by reading their build files. */
object AndroidModules {
    private val skipDirs = setOf("build", ".gradle", ".idea", "node_modules", ".git", ".kotlin")
    private val buildFiles = setOf("build.gradle.kts", "build.gradle")
    private val manifestPackage = Regex("""<manifest[^>]*\bpackage\s*=\s*["']([\w.]+)["']""")

    fun find(projectDir: Path): List<AndroidModule> {
        if (!projectDir.exists()) return emptyList()
        val result = mutableListOf<AndroidModule>()
        Files.walk(projectDir, 5).use { stream ->
            stream.filter { it.name in buildFiles && projectDir.relativize(it).none { seg -> seg.name in skipDirs } }
                .forEach { buildFile ->
                    val text = runCatching { buildFile.readText() }.getOrNull() ?: return@forEach
                    val pluginType = GradleModules.androidPluginType(text) ?: return@forEach
                    val dir = buildFile.parent
                    val namespace = GradleModules.namespace(text)
                        ?: dir.resolve("src/main/AndroidManifest.xml").takeIf { it.exists() }
                            ?.let { manifestPackage.find(it.readText())?.groupValues?.get(1) }
                    val rel = projectDir.relativize(dir).joinToString(":") { it.name }
                    result += AndroidModule(dir, if (rel.isEmpty()) ":" else ":$rel", buildFile, namespace, pluginType == "application")
                }
        }
        return result.sortedWith(compareByDescending<AndroidModule> { it.isApplication }.thenBy { it.gradlePath })
    }
}
