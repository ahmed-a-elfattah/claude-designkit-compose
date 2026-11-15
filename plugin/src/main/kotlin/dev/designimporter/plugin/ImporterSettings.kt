package dev.designimporter.plugin

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.designimporter.core.gen.IconFormat
import dev.designimporter.core.icons.MatchMode

@Service(Service.Level.PROJECT)
@State(name = "ClaudeDesignImporter", storages = [Storage("claudeDesignImporter.xml")])
class ImporterSettings : SimplePersistentStateComponent<ImporterSettings.Options>(Options()) {

    class Options : BaseState() {
        /** Module directory relative to the project root. */
        var modulePath by string()
        var packageName by string()
        var prefix by string("App")
        var iconFormat by enum(IconFormat.IMAGE_VECTOR)
        var matchMode by enum(MatchMode.GEOMETRY_AND_NAME)
        var defaultIconStyle by string("Filled")
        var lastImportPath by string()
        var includeStrings by property(true)
    }

    companion object {
        fun get(project: Project): ImporterSettings = project.service()
    }
}
