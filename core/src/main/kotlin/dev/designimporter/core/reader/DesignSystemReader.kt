package dev.designimporter.core.reader

import dev.designimporter.core.icons.MaterialIconIndex
import dev.designimporter.core.model.DesignSystem
import java.nio.file.Path

/** Entry point: reads a Claude Design export (folder or zip) into a [DesignSystem]. */
object DesignSystemReader {
    private val styleBlock = Regex("<style[^>]*>(.*?)</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun read(path: Path): DesignSystem = read(ExportSource.open(path))

    fun read(files: List<ExportFile>, index: MaterialIconIndex = MaterialIconIndex.shared): DesignSystem {
        val b = DesignSystemBuilder()
        val css = CssReader(b)
        val tokens = DtcgJsonReader(b)
        val claudeTokens = ClaudeDesignTokensReader(b)
        val componentFiles = mutableListOf<ExportFile>()
        val htmlFiles = mutableListOf<ExportFile>()
        var hasTokenTypography = false
        val sorted = files.sortedBy { it.path }

        for (file in sorted) {
            when (file.extension) {
                "json" -> {
                    val text = file.text
                    when {
                        ClaudeDesignTokensReader.accepts(text) -> {
                            claudeTokens.read(file)
                            hasTokenTypography = hasTokenTypography || text.contains("\"groups\"") || text.contains("\"styles\"")
                        }
                        ComponentJsonReader.looksLikeComponents(file, text) -> componentFiles += file
                        DtcgJsonReader.looksLikeTokens(text) -> tokens.read(file)
                    }
                }
                "css" -> css.add(file.text)
                "html", "htm" -> {
                    htmlFiles += file
                    styleBlock.findAll(file.text).forEach { css.add(it.groupValues[1]) }
                }
            }
        }
        css.finish(hasTokenTypography)

        val components = ComponentJsonReader(b)
        componentFiles.forEach { components.read(it, b.colorAliases) }

        val fonts = FontReader(b)
        val icons = SvgIconReader(b)
        for (file in sorted) {
            when (file.extension) {
                "ttf", "otf", "woff", "woff2", "eot" -> fonts.read(file)
                "svg" -> icons.read(file)
            }
        }
        val symbols = MaterialSymbolReader(b) { name -> index.byName.containsKey(name.replace("_", "")) }
        htmlFiles.forEach { symbols.scan(it.text, it.path) }

        val strings = StringsReader()
        sorted.filter { it.extension in setOf("html", "htm", "js", "jsx", "ts", "tsx") && !it.path.contains("vendor/") && !it.path.contains("node_modules/") }
            .forEach { strings.scan(it.text) }

        if (files.isEmpty()) b.warnings += "The export is empty"
        return b.build().copy(strings = strings.result())
    }
}
