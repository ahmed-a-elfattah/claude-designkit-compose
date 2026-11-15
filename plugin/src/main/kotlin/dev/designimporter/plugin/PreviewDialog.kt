package dev.designimporter.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import dev.designimporter.core.gen.GeneratorConfig
import dev.designimporter.core.gen.IconCandidate
import dev.designimporter.core.gen.IconFormat
import dev.designimporter.core.icons.MatchKind
import dev.designimporter.core.icons.MatchMode
import dev.designimporter.core.plan.FileAction
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

class PreviewDialog(
    project: Project,
    private val session: ImportSession,
    private val modules: List<AndroidModule>,
    options: ImporterSettings.Options,
    private val projectDir: java.nio.file.Path,
) : DialogWrapper(project, true) {

    private val styles = arrayOf("Filled", "Outlined", "Rounded", "Sharp", "TwoTone")
    private val matchModes = listOf(MatchMode.GEOMETRY_AND_NAME to "Same shape or same name", MatchMode.GEOMETRY to "Same shape only", MatchMode.OFF to "Never (generate all)")
    private val formats = listOf(IconFormat.IMAGE_VECTOR to "ImageVector (Kotlin)", IconFormat.DRAWABLE to "Vector drawable (res/drawable XML)")

    private val moduleCombo = ComboBox(modules.toTypedArray())
    private val packageField = JBTextField()
    private val prefixField = JBTextField()
    private val formatCombo = ComboBox(formats.map { it.second }.toTypedArray())
    private val matchCombo = ComboBox(matchModes.map { it.second }.toTypedArray())
    private val styleCombo = ComboBox(styles)
    private val summary = JBLabel()
    private val iconModel = IconTableModel()
    private val stringModel = StringTableModel()
    private val stringsCheck = JBCheckBox("Generate localized strings (values/ and values-<locale>/design_strings.xml)")
    private val fileModel = FileTableModel()
    private val warningModel = DefaultListModel<String>()
    private val tabs = JBTabbedPane()

    var result: ImportResult? = null
        private set
    private var packageEdited = false
    private var settingPackage = false

    init {
        title = "Import Claude Design System"
        setOKButtonText("Import")

        modules.firstOrNull { projectDir.relativize(it.dir).toString() == options.modulePath }?.let { moduleCombo.selectedItem = it }
        packageEdited = !options.packageName.isNullOrBlank()
        setPackage(options.packageName?.takeIf { it.isNotBlank() } ?: defaultPackage())
        prefixField.text = options.prefix ?: "App"
        formatCombo.selectedIndex = formats.indexOfFirst { it.first == options.iconFormat }.coerceAtLeast(0)
        matchCombo.selectedIndex = matchModes.indexOfFirst { it.first == options.matchMode }.coerceAtLeast(0)
        styleCombo.selectedItem = session.ds.iconStyleHint ?: options.defaultIconStyle ?: "Filled"
        stringsCheck.isSelected = options.includeStrings
        stringsCheck.isEnabled = session.ds.strings.isNotEmpty()
        session.includeStrings = options.includeStrings
        stringsCheck.addActionListener {
            session.includeStrings = stringsCheck.isSelected
            stringModel.fireTableDataChanged()
            refresh()
        }

        moduleCombo.addActionListener {
            if (!packageEdited) setPackage(defaultPackage())
            refresh()
        }
        packageField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!settingPackage) packageEdited = true
                refresh()
            }
        })
        prefixField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        formatCombo.addActionListener { refresh() }
        matchCombo.addActionListener { refresh(reanalyze = true) }
        styleCombo.addActionListener { refresh(reanalyze = true) }

        init()
        refresh(reanalyze = true)
    }

    private val module get() = moduleCombo.selectedItem as? AndroidModule
    private val matchMode get() = matchModes[matchCombo.selectedIndex.coerceAtLeast(0)].first
    private val iconFormat get() = formats[formatCombo.selectedIndex.coerceAtLeast(0)].first

    private fun defaultPackage() = (module?.namespace ?: "com.example.app") + ".designsystem"

    private fun setPackage(value: String) {
        settingPackage = true
        packageField.text = value
        settingPackage = false
    }

    private fun config(): GeneratorConfig? {
        val m = module ?: return null
        val pkg = packageField.text.trim()
        val prefix = prefixField.text.trim()
        if (!PACKAGE.matches(pkg) || !PREFIX.matches(prefix)) return null
        return GeneratorConfig(
            packageName = pkg,
            rPackage = m.namespace ?: pkg.substringBeforeLast('.'),
            prefix = prefix,
            iconFormat = iconFormat,
            kotlinSourceDir = m.kotlinSourceDir,
        )
    }

    private fun refresh(reanalyze: Boolean = false) {
        if (reanalyze) {
            session.analyze(matchMode, styleCombo.selectedItem as String)
            iconModel.fireTableDataChanged()
        }
        val cfg = config()
        val m = module
        result = if (cfg != null && m != null) runCatching { session.build(m, cfg) }.getOrNull() else null
        fileModel.fireTableDataChanged()
        warningModel.clear()
        session.warnings().forEach { warningModel.addElement(it) }
        updateSummary()
        stringModel.fireTableDataChanged()
        if (tabs.tabCount == 4) {
            tabs.setTitleAt(0, "Icons (${session.candidates.size})")
            tabs.setTitleAt(1, "Strings (${session.stringKeys.size}/${session.ds.strings.size})")
            tabs.setTitleAt(2, "Files (${result?.changes?.count { it.action != FileAction.UNCHANGED } ?: 0} changes)")
            tabs.setTitleAt(3, "Warnings (${warningModel.size()})")
        }
    }

    private fun updateSummary() {
        val ds = session.ds
        val r = result
        val material = r?.icons?.count { it.material != null } ?: 0
        val extended = r?.icons?.count { it.material?.extended == true } ?: 0
        val generated = r?.icons?.count { it.material == null } ?: 0
        val components = ds.components.joinToString(", ") { c -> "${c.type.name.lowercase().replace('_', ' ')} (${c.variants.size})" }.ifEmpty { "defaults only" }
        val counts = r?.changes?.groupingBy { it.action }?.eachCount().orEmpty()
        val deps = when {
            r == null -> ""
            r.dependencyEdits.isNotEmpty() -> "<br>Adds <code>${if (r.dependencies.extended) "material-icons-extended" else "material-icons-core"}</code> to ${r.module.buildFile.fileName}"
            else -> ""
        }
        summary.text = "<html>" +
            "${ds.colors.size} colors (${ds.colors.count { it.dark != null }} with dark values) · ${ds.typography.size} text styles · " +
            "${ds.radii.size} radii · ${ds.spacing.size} spacing · ${ds.elevation.size} elevation · ${ds.sizes.size} sizes · ${ds.fonts.size} font files<br>" +
            "Icons: <b>$material reuse Material</b>${if (extended > 0) " ($extended from extended)" else ""}, $generated generated · Components: $components<br>" +
            "Strings: ${session.stringKeys.size} of ${ds.strings.size}" +
            (ds.strings.flatMap { it.translations.keys }.distinct().takeIf { it.isNotEmpty() }?.let { " · translations: ${it.joinToString()}" } ?: "") + "<br>" +
            "Files: ${counts[FileAction.CREATE] ?: 0} new, ${counts[FileAction.UPDATE] ?: 0} updated, ${counts[FileAction.DELETE] ?: 0} removed, " +
            "${counts[FileAction.UNCHANGED] ?: 0} unchanged${counts[FileAction.CONFLICT]?.let { ", <font color='#d9822b'>$it hand-written files skipped</font>" } ?: ""}" +
            deps + "</html>"
    }

    override fun doValidate(): ValidationInfo? = when {
        module == null -> ValidationInfo("Pick an Android module", moduleCombo)
        !PACKAGE.matches(packageField.text.trim()) -> ValidationInfo("Not a valid package name", packageField)
        !PREFIX.matches(prefixField.text.trim()) -> ValidationInfo("Use a capitalized identifier, e.g. App", prefixField)
        result == null -> ValidationInfo("Could not prepare the import")
        else -> null
    }

    fun saveOptions(options: ImporterSettings.Options) {
        module?.let { options.modulePath = projectDir.relativize(it.dir).toString() }
        options.packageName = packageField.text.trim().takeIf { packageEdited }
        options.prefix = prefixField.text.trim()
        options.iconFormat = iconFormat
        options.matchMode = matchMode
        options.defaultIconStyle = styleCombo.selectedItem as String
        options.includeStrings = stringsCheck.isSelected
    }

    override fun createCenterPanel(): JComponent {
        val iconTable = JBTable(iconModel).apply {
            columnModel.getColumn(0).preferredWidth = 70
            columnModel.getColumn(0).maxWidth = 90
            columnModel.getColumn(1).preferredWidth = 160
            columnModel.getColumn(2).preferredWidth = 280
        }
        val fileTable = JBTable(fileModel).apply {
            columnModel.getColumn(0).maxWidth = 120
        }
        val stringTable = JBTable(stringModel).apply {
            columnModel.getColumn(0).maxWidth = 70
            columnModel.getColumn(1).preferredWidth = 180
            columnModel.getColumn(2).preferredWidth = 300
            columnModel.getColumn(3).preferredWidth = 300
        }
        tabs.addTab("Icons", JBScrollPane(iconTable))
        tabs.addTab("Strings", JBScrollPane(stringTable))
        tabs.addTab("Files", JBScrollPane(fileTable))
        tabs.addTab("Warnings", JBScrollPane(JBList(warningModel)))
        refresh()

        return panel {
            group("Target") {
                row("Module:") { cell(moduleCombo).align(AlignX.FILL) }
                row("Package:") { cell(packageField).align(AlignX.FILL) }
                row("Name prefix:") {
                    cell(prefixField).columns(12)
                    comment("Generates ${prefixField.text.ifBlank { "App" }}Theme, …Colors, …Icons, …Button")
                }
            }
            group("Icons") {
                row("Custom icons as:") { cell(formatCombo) }
                row("Reuse Material icon when:") {
                    cell(matchCombo)
                    label("Preferred style:")
                    cell(styleCombo)
                }
            }
            group("Strings") {
                row { cell(stringsCheck) }
            }
            row { cell(summary).align(AlignX.FILL) }
            row { cell(tabs).align(Align.FILL) }.resizableRow()
        }.apply { preferredSize = Dimension(900, 640) }
    }

    private inner class IconTableModel : AbstractTableModel() {
        private val columns = arrayOf("Material", "Icon", "Uses", "Match")
        private fun row(i: Int): IconCandidate = session.candidates[i]

        override fun getRowCount() = session.candidates.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0 && row(rowIndex).match != null

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val c = row(rowIndex)
            val match = c.match
            val useMaterial = match != null && session.useMaterial[c.asset.name] != false
            return when (columnIndex) {
                0 -> useMaterial
                1 -> c.propertyName
                2 -> when {
                    match != null && useMaterial -> match.icon.expression + if (match.icon.extended) "  (extended)" else ""
                    c.vector != null -> if (iconFormat == IconFormat.DRAWABLE) "generated vector drawable" else "generated ImageVector"
                    c.asset.isSymbol -> "skipped: no Compose equivalent, export it as SVG"
                    else -> "skipped: ${c.error ?: "unsupported SVG"}"
                }
                else -> when {
                    match == null -> "—"
                    match.kind == MatchKind.GEOMETRY -> "identical shape"
                    match.kind == MatchKind.SYMBOL -> "Material Symbol used by the design"
                    else -> "same name (check it looks right)"
                }
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            session.useMaterial[row(rowIndex).asset.name] = aValue == true
            fireTableRowsUpdated(rowIndex, rowIndex)
            refresh()
        }
    }

    private inner class StringTableModel : AbstractTableModel() {
        private val columns = arrayOf("Include", "Key", "Default text", "Translations")
        private fun row(i: Int) = session.ds.strings[i]

        override fun getRowCount() = session.ds.strings.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0 && session.includeStrings

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val s = row(rowIndex)
            return when (columnIndex) {
                0 -> session.includeStrings && session.useString[s.key] != false
                1 -> "R.string." + (session.stringKeys[s.key] ?: s.key)
                2 -> s.text
                else -> s.translations.entries.joinToString("  ·  ") { "${it.key}: ${it.value}" }
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            session.useString[row(rowIndex).key] = aValue == true
            refresh()
        }
    }

    private inner class FileTableModel : AbstractTableModel() {
        private val rows get() = result?.changes.orEmpty().sortedBy { it.action.ordinal }
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Action" else "Path"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val c = rows[rowIndex]
            if (columnIndex == 1) return c.path
            return when (c.action) {
                FileAction.CREATE -> "new"
                FileAction.UPDATE -> if (c.merged) "merge block" else "update"
                FileAction.UNCHANGED -> "unchanged"
                FileAction.CONFLICT -> if (c.merged) "skip (no <resources>)" else "skip (hand-written)"
                FileAction.DELETE -> "remove"
            }
        }
    }

    companion object {
        private val PACKAGE = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")
        private val PREFIX = Regex("^[A-Z][A-Za-z0-9]*$")
    }
}
