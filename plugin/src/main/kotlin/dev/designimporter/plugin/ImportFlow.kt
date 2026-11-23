package dev.designimporter.plugin

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.designimporter.core.model.DesignSystem
import dev.designimporter.core.plan.FileAction
import dev.designimporter.core.reader.DesignSystemReader
import java.io.IOException
import java.nio.file.Path

object ImportFlow {
    private const val TITLE = "Claude Design Import"

    fun run(project: Project, export: Path) {
        val projectDir = project.basePath?.let { Path.of(it) } ?: return
        FileDocumentManager.getInstance().saveAllDocuments()

        val (ds, modules) = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Pair<DesignSystem, List<AndroidModule>>, Exception> {
                    val ds = DesignSystemReader.read(export)
                    MaterialIndexHolder.index
                    ds to AndroidModules.find(projectDir)
                },
                "Reading Claude Design export",
                true,
                project,
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Could not read the export:\n${e.message ?: e}", TITLE)
            return
        }
        if (modules.isEmpty()) {
            Messages.showErrorDialog(project, "No Android module (com.android.application or com.android.library) was found in this project.", TITLE)
            return
        }

        val settings = ImporterSettings.get(project)
        settings.state.lastImportPath = export.toString()
        val dialog = PreviewDialog(project, ImportSession(ds, projectDir), modules, settings.state, projectDir)
        if (!dialog.showAndGet()) return
        val result = dialog.result ?: return
        dialog.saveOptions(settings.state)
        apply(project, result)
    }

    private fun apply(project: Project, r: ImportResult) {
        var themeFile: VirtualFile? = null
        try {
            WriteCommandAction.writeCommandAction(project).withName("Import Claude Design System").run<IOException> {
                val base = VfsUtil.createDirectories(r.module.dir.toString())
                for (c in r.changes) {
                    when (c.action) {
                        FileAction.CREATE, FileAction.UPDATE -> {
                            val parent = c.path.substringBeforeLast('/', "")
                            val dir = if (parent.isEmpty()) base else VfsUtil.createDirectoryIfMissing(base, parent)
                            val name = c.path.substringAfterLast('/')
                            val file = dir.findChild(name) ?: dir.createChildData(ImportFlow, name)
                            file.setBinaryContent(c.file!!.bytes)
                        }
                        FileAction.DELETE -> base.findFileByRelativePath(c.path)?.let { file ->
                            var dir = file.parent
                            file.delete(ImportFlow)
                            while (dir != null && dir != base && dir.children.isEmpty()) {
                                val parent = dir.parent
                                dir.delete(ImportFlow)
                                dir = parent
                            }
                        }
                        FileAction.UNCHANGED, FileAction.CONFLICT -> Unit
                    }
                }
                for (edit in r.dependencyEdits) {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(edit.path) ?: continue
                    val doc = FileDocumentManager.getInstance().getDocument(vf) ?: continue
                    doc.setText(StringUtil.convertLineSeparators(edit.newText))
                    FileDocumentManager.getInstance().saveDocument(doc)
                }
                themeFile = base.findFileByRelativePath("${r.config.packageDir}/${r.config.themeName}.kt")
                    ?: base.findFileByRelativePath("${r.config.packageDir}/Theme.kt")
            }
        } catch (e: IOException) {
            notify(project, "Import failed", e.message ?: e.toString(), NotificationType.ERROR)
            return
        }

        val counts = r.changes.filter { it.path != dev.designimporter.core.plan.ImportPlanner.MANIFEST }.groupingBy { it.action }.eachCount()
        val parts = buildList {
            add("${counts[FileAction.CREATE] ?: 0} new, ${counts[FileAction.UPDATE] ?: 0} updated, ${counts[FileAction.DELETE] ?: 0} removed")
            counts[FileAction.CONFLICT]?.let { add("$it hand-written files left untouched") }
            val material = r.icons.count { it.material != null }
            add("$material icons reuse Material, ${r.icons.size - material} generated")
            if (r.dependencyEdits.isNotEmpty()) add("added ${if (r.dependencies.extended) "material-icons-extended" else "material-icons-core"}; sync Gradle")
        }
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Claude DesignKit")
            .createNotification("Design system imported", parts.joinToString("<br>"), NotificationType.INFORMATION)
        themeFile?.let { f ->
            notification.addAction(NotificationAction.createSimple("Open ${r.config.themeName}") { FileEditorManager.getInstance(project).openFile(f, true) })
        }
        if (r.dependencyEdits.isNotEmpty()) {
            notification.addAction(NotificationAction.createSimpleExpiring("Sync now") { syncGradle() })
        }
        notification.notify(project)
    }

    private fun syncGradle() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("Android.SyncProject") ?: manager.getAction("ExternalSystem.RefreshAllProjects") ?: return
        manager.tryToExecute(action, null, null, ActionPlaces.NOTIFICATION, true)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Claude DesignKit")
            .createNotification(title, content, type).notify(project)
    }
}
