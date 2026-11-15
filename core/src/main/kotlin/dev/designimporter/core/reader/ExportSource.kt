package dev.designimporter.core.reader

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

class ExportFile(val path: String, private val loader: () -> ByteArray) {
    val bytes: ByteArray by lazy(loader)
    val text: String get() = bytes.toString(Charsets.UTF_8)
    val name: String get() = path.substringAfterLast('/')
    val baseName: String get() = name.substringBeforeLast('.')
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Lists the files of an exported design system, from a folder or a zip. */
object ExportSource {
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    private val ignored = Regex("(^|/)(__MACOSX|\\.DS_Store|node_modules|\\.git)(/|$)")

    fun open(path: Path): List<ExportFile> {
        val files = when {
            path.isDirectory() -> fromFolder(path)
            path.isRegularFile() && path.toString().lowercase().endsWith(".zip") -> fromZip(path)
            else -> throw IllegalArgumentException("Expected a folder or a .zip export: $path")
        }
        return stripCommonRoot(files.filterNot { ignored.containsMatchIn(it.path) })
    }

    private fun fromFolder(root: Path): List<ExportFile> = Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile() && Files.size(it) <= MAX_FILE_BYTES }
            .map { p -> ExportFile(p.relativeTo(root).toString().replace('\\', '/')) { Files.readAllBytes(p) } }
            .toList()
    }

    private fun fromZip(zipPath: Path): List<ExportFile> = ZipFile(zipPath.toFile()).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.size <= MAX_FILE_BYTES }
            .map { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                ExportFile(entry.name.replace('\\', '/').trimStart('/')) { bytes }
            }
            .toList()
    }

    private fun stripCommonRoot(files: List<ExportFile>): List<ExportFile> {
        if (files.isEmpty()) return files
        val firstSegments = files.map { it.path.substringBefore('/', "") }
        val root = firstSegments.first()
        if (root.isEmpty() || firstSegments.any { it != root } || files.any { !it.path.contains('/') }) return files
        return files.map { f -> ExportFile(f.path.removePrefix("$root/")) { f.bytes } }
    }
}
