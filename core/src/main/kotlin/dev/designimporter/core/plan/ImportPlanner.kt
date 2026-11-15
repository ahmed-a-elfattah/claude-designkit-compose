package dev.designimporter.core.plan

import dev.designimporter.core.gen.GeneratedFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

enum class FileAction { CREATE, UPDATE, UNCHANGED, CONFLICT, DELETE }

/** [merged]: only the importer's block inside a resources file changes, the rest of the file is kept. */
class PlannedChange(val path: String, val action: FileAction, val file: GeneratedFile?, val merged: Boolean = false)

/**
 * Diffs generated files against the module on disk. A file is ours (safe to overwrite or delete)
 * when the previous import's manifest lists it or it carries the generated header; anything else is a conflict.
 * Block files (strings.xml) are never owned: only the marked block inside them is replaced or removed.
 */
object ImportPlanner {
    const val MANIFEST = ".claude-design-import"
    private const val BLOCK_PREFIX = "block:"

    fun plan(moduleDir: Path, files: List<GeneratedFile>): List<PlannedChange> {
        val previous = readManifest(moduleDir)
        val previousBlocks = readBlocks(moduleDir)
        val (blocks, whole) = files.partition { it.mergeBlock }

        val changes = whole.map { f ->
            val target = moduleDir.resolve(f.path)
            val action = when {
                !target.exists() -> FileAction.CREATE
                target.readBytes().contentEquals(f.bytes) -> FileAction.UNCHANGED
                isOurs(target, f.path, previous) -> FileAction.UPDATE
                else -> FileAction.CONFLICT
            }
            PlannedChange(f.path, action, f)
        }.toMutableList()

        for (f in blocks) {
            val target = moduleDir.resolve(f.path)
            val existing = if (target.exists()) target.readText() else null
            val merged = ResourcesMerge.merge(existing, f.text)
            changes += when {
                merged == null -> PlannedChange(f.path, FileAction.CONFLICT, null, merged = true)
                existing == null -> PlannedChange(f.path, FileAction.CREATE, GeneratedFile.text(f.path, merged), merged = true)
                existing == merged -> PlannedChange(f.path, FileAction.UNCHANGED, GeneratedFile.text(f.path, merged), merged = true)
                else -> PlannedChange(f.path, FileAction.UPDATE, GeneratedFile.text(f.path, merged), merged = true)
            }
        }

        val newPaths = whole.map { it.path }.toSet()
        previous.filter { it !in newPaths }.forEach { path ->
            val target = moduleDir.resolve(path)
            if (target.exists() && isOurs(target, path, previous)) changes += PlannedChange(path, FileAction.DELETE, null)
        }
        val newBlocks = blocks.map { it.path }.toSet()
        previousBlocks.filter { it !in newBlocks }.forEach { path ->
            val target = moduleDir.resolve(path)
            if (!target.exists()) return@forEach
            val text = target.readText()
            if (!ResourcesMerge.hasBlock(text)) return@forEach
            val removed = ResourcesMerge.remove(text)
            changes += if (ResourcesMerge.isEmpty(removed)) PlannedChange(path, FileAction.DELETE, null, merged = true)
            else PlannedChange(path, FileAction.UPDATE, GeneratedFile.text(path, removed), merged = true)
        }

        val owned = changes.filter { !it.merged && it.action != FileAction.CONFLICT && it.action != FileAction.DELETE }.map { it.path }.sorted()
        val blockLines = changes.filter { it.merged && it.path in newBlocks && it.action != FileAction.CONFLICT }.map { BLOCK_PREFIX + it.path }.sorted()
        val manifest = GeneratedFile.text(MANIFEST, "# ${GeneratedFile.HEADER}\n" + (owned + blockLines).joinToString("\n") + "\n")
        val manifestTarget = moduleDir.resolve(MANIFEST)
        val manifestAction = when {
            !manifestTarget.exists() -> FileAction.CREATE
            manifestTarget.readBytes().contentEquals(manifest.bytes) -> FileAction.UNCHANGED
            else -> FileAction.UPDATE
        }
        changes += PlannedChange(MANIFEST, manifestAction, manifest)
        return changes
    }

    /** Writes the plan with java.nio (CLI / tests). The IDE plugin applies plans through the VFS instead. */
    fun apply(moduleDir: Path, changes: List<PlannedChange>) {
        for (c in changes) {
            val target = moduleDir.resolve(c.path)
            when (c.action) {
                FileAction.CREATE, FileAction.UPDATE -> {
                    Files.createDirectories(target.parent)
                    Files.write(target, c.file!!.bytes)
                }
                FileAction.DELETE -> {
                    Files.deleteIfExists(target)
                    var dir = target.parent
                    while (dir != null && dir.startsWith(moduleDir) && dir != moduleDir && Files.list(dir).use { it.findAny().isEmpty }) {
                        Files.delete(dir)
                        dir = dir.parent
                    }
                }
                FileAction.UNCHANGED, FileAction.CONFLICT -> Unit
            }
        }
    }

    private fun manifestLines(moduleDir: Path): List<String> {
        val f = moduleDir.resolve(MANIFEST)
        if (!f.exists()) return emptyList()
        return Files.readAllLines(f).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains("..") }
    }

    fun readManifest(moduleDir: Path): Set<String> = manifestLines(moduleDir).filterNot { it.startsWith(BLOCK_PREFIX) }.toSet()

    fun readBlocks(moduleDir: Path): Set<String> = manifestLines(moduleDir).filter { it.startsWith(BLOCK_PREFIX) }.map { it.removePrefix(BLOCK_PREFIX) }.toSet()

    private fun isOurs(target: Path, path: String, previous: Set<String>): Boolean {
        if (path in previous) return true
        val head = Files.newInputStream(target).use { it.readNBytes(400) }.toString(Charsets.UTF_8)
        return head.contains(GeneratedFile.HEADER)
    }
}
