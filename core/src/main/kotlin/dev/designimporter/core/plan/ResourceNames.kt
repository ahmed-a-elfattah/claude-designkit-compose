package dev.designimporter.core.plan

import dev.designimporter.core.gen.StringsGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

object ResourceNames {
    private val declared = Regex("""<(?:string|plurals|string-array)\s+[^>]*\bname\s*=\s*"([^"]+)"""")

    /** String resource names the module already declares, ignoring the importer's own files and blocks. */
    fun existingStrings(moduleDir: Path, resDir: String = "src/main/res"): Set<String> {
        val res = moduleDir.resolve(resDir)
        if (!res.isDirectory()) return emptySet()
        val out = mutableSetOf<String>()
        Files.list(res).use { dirs ->
            dirs.filter { it.isDirectory() && it.name.startsWith("values") }.forEach { values ->
                Files.list(values).use { files ->
                    files.filter { it.name.endsWith(".xml") && it.name != StringsGenerator.LEGACY_FILE_NAME }
                        .forEach { f ->
                            val own = ResourcesMerge.remove(runCatching { f.readText() }.getOrDefault(""))
                            declared.findAll(own).forEach { out += it.groupValues[1] }
                        }
                }
            }
        }
        return out
    }
}
