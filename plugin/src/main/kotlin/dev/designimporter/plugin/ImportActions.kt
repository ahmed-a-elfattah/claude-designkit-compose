package dev.designimporter.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

class ImportAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptor(true, true, false, true, false, false)
            .withTitle("Select Claude Design Export")
            .withDescription("Pick the exported .zip or the unzipped folder")
        val last = ImporterSettings.get(project).state.lastImportPath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val chosen = FileChooser.chooseFile(descriptor, project, last) ?: return
        val path = chosen.toNioPath()
        if (!Files.isDirectory(path) && !path.toString().endsWith(".zip", ignoreCase = true)) {
            Messages.showErrorDialog(project, "Choose a .zip export or a folder.", "Claude Design Import")
            return
        }
        ImportFlow.run(project, path)
    }
}

class ReimportAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val last = e.project?.let { ImporterSettings.get(it).state.lastImportPath }
        e.presentation.isVisible = e.project != null
        e.presentation.isEnabled = last != null && Files.exists(Path.of(last))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val last = ImporterSettings.get(project).state.lastImportPath ?: return
        ImportFlow.run(project, Path.of(last))
    }
}
