package dev.designimporter.core

import dev.designimporter.core.gen.DesignSystemGenerator
import dev.designimporter.core.gen.GeneratedFile
import dev.designimporter.core.gen.GeneratorConfig
import dev.designimporter.core.gen.IconFormat
import dev.designimporter.core.icons.MatchKind
import dev.designimporter.core.icons.MatchMode
import dev.designimporter.core.icons.MaterialIconIndex
import dev.designimporter.core.icons.MaterialIconMatcher
import dev.designimporter.core.model.ComponentType
import dev.designimporter.core.model.Props
import dev.designimporter.core.model.TokenValue
import dev.designimporter.core.plan.FileAction
import dev.designimporter.core.plan.ImportPlanner
import dev.designimporter.core.reader.DesignSystemReader
import dev.designimporter.core.reader.ExportFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportPipelineTest {
    private val fixture: Path = Path.of(javaClass.getResource("/fixture-export")!!.toURI())
    private val ds by lazy { DesignSystemReader.read(fixture) }
    private val index by lazy { MaterialIconIndex.load() }

    @Test
    fun readsColorsWithDarkMode() {
        val colors = ds.colors.associateBy { it.name }
        assertEquals(0xFF4F46E5, colors.getValue("primary").light)
        assertEquals(0xFF818CF8, colors.getValue("primary").dark)
        assertEquals(0xFF4F46E5, colors.getValue("brand500").light)
        assertEquals(0xFFFFFFFF, colors.getValue("onPrimary").light)
        assertEquals(0xFF171717, colors.getValue("textPrimary").light)
        assertEquals(0xFFFAFAFA, colors.getValue("textPrimary").dark)
        assertEquals(0xFFE5E7EB, colors.getValue("border").light)
        assertNotNull(colors.getValue("border").dark)
        assertNotNull(colors["danger"])
        assertNotNull(colors["success"])
    }

    @Test
    fun readsDimensionsAndTypography() {
        assertEquals(listOf("xs" to 4f, "sm" to 8f, "md" to 16f, "lg" to 24f, "xl" to 32f), ds.spacing.map { it.name to it.dp })
        assertEquals(mapOf("sm" to 6f, "md" to 10f, "full" to 9999f), ds.radii.associate { it.name to it.dp })
        assertEquals(6f, ds.elevation.single { it.name == "md" }.dp)
        assertEquals("Inter", ds.defaultFontFamily)

        val type = ds.typography.associateBy { it.name }
        val display = type.getValue("displayLg")
        assertEquals(48f, display.fontSizeSp)
        assertEquals(700, display.fontWeight)
        assertEquals(52.8f, display.lineHeightSp!!, 0.01f)
        assertEquals(-0.96f, display.letterSpacingSp!!, 0.01f)
        assertEquals("Inter", display.fontFamily)
        val heading = type.getValue("headingLg")
        assertEquals(28f, heading.fontSizeSp)
        assertEquals(600, heading.fontWeight)
        assertEquals(36f, heading.lineHeightSp)
        assertEquals(14f, type.getValue("sm").fontSizeSp)
        assertEquals(20f, type.getValue("sm").lineHeightSp)
    }

    @Test
    fun readsComponentsFromCss() {
        val button = ds.components.single { it.type == ComponentType.BUTTON }
        assertEquals(listOf("Primary", "Outline"), button.variants.map { it.name })
        val primary = button.variants[0].props
        assertEquals(TokenValue.ColorRef("primary"), primary[Props.BACKGROUND])
        assertEquals(TokenValue.Dimen(10f), primary[Props.RADIUS])
        assertEquals(TokenValue.Dimen(18f), primary[Props.PADDING_H])
        assertEquals(TokenValue.Dimen(44f), primary[Props.HEIGHT])
        val outline = button.variants[1].props
        assertEquals(TokenValue.Transparent, outline[Props.BACKGROUND])
        assertEquals(TokenValue.ColorRef("textPrimary"), outline[Props.CONTENT])
        assertEquals(TokenValue.ColorRef("border"), outline[Props.BORDER_COLOR])

        assertEquals(listOf("Default"), ds.components.single { it.type == ComponentType.CARD }.variants.map { it.name })
        assertEquals(listOf("Success"), ds.components.single { it.type == ComponentType.BADGE }.variants.map { it.name })
        assertNotNull(ds.components.singleOrNull { it.type == ComponentType.TEXT_FIELD })
    }

    @Test
    fun matchesMaterialIconsByGeometryAndName() {
        assertTrue(index.icons.size > 10_000, "index should be bundled")
        val candidates = DesignSystemGenerator.analyzeIcons(ds, MaterialIconMatcher(index, MatchMode.GEOMETRY_AND_NAME)).associateBy { it.asset.name }

        val search = candidates.getValue("search").match!!
        assertEquals(MatchKind.GEOMETRY, search.kind)
        assertEquals("Search", search.icon.name)
        assertEquals(false, search.icon.extended)

        val back = candidates.getValue("arrow-left").match!!
        assertEquals(MatchKind.NAME, back.kind)
        assertEquals("Icons.AutoMirrored.Filled.ArrowBack", back.icon.expression)

        assertNull(candidates.getValue("brand-logo").match)
        assertNotNull(candidates.getValue("brand-logo").vector)

        val geometryOnly = DesignSystemGenerator.analyzeIcons(ds, MaterialIconMatcher(index, MatchMode.GEOMETRY)).associateBy { it.asset.name }
        assertNull(geometryOnly.getValue("arrow-left").match)
    }

    @Test
    fun generatesThemeIconsAndComponents() {
        val files = generate(IconFormat.IMAGE_VECTOR).associateBy { it.path }
        val dir = "src/main/kotlin/com/example/app/designsystem"

        val color = files.getValue("$dir/Color.kt").text
        assertTrue(color.contains("val primary: Color,"))
        assertTrue(color.contains("primary = Color(0xFF818CF8),"))
        assertTrue(color.contains("onSurface = LightAppColors.textPrimary,"))
        assertTrue(color.contains("outline = LightAppColors.border,"))

        val type = files.getValue("$dir/Type.kt").text
        assertTrue(type.contains("displayLarge = DefaultAppTypography.displayLg,"))
        assertTrue(type.contains("headlineLarge = DefaultAppTypography.headingLg,"))
        assertTrue(type.contains("letterSpacing = (-0.96).sp"))

        val shape = files.getValue("$dir/Shape.kt").text
        assertTrue(shape.contains("full = RoundedCornerShape(percent = 50),"))
        assertTrue(shape.contains("small = RoundedCornerShape(6.dp),"))

        val icons = files.getValue("$dir/AppIcons.kt").text
        assertTrue(icons.contains("val Search: ImageVector get() = Icons.Filled.Search"))
        assertTrue(icons.contains("import androidx.compose.material.icons.automirrored.filled.ArrowBack"))
        assertTrue(icons.contains("val BrandLogo: ImageVector get() = BrandLogoIcon"))
        val logo = files.getValue("$dir/icons/BrandLogoIcon.kt").text
        assertTrue(logo.contains("addGroup(translationX = 2f, translationY = 2f)"))
        assertTrue(logo.contains("fillAlpha = 0.9f"))
        assertTrue(logo.contains("strokeLineJoin = StrokeJoin.Round"))

        val button = files.getValue("$dir/components/AppButton.kt").text
        assertTrue(button.contains("enum class AppButtonVariant { Primary, Outline }"))
        assertTrue(button.contains("container = AppTheme.colors.primary,"))
        assertTrue(button.contains("border = BorderStroke(1.dp, AppTheme.colors.border),"))

        files.values.filter { it.isText }.forEach { assertTrue(it.text.contains(GeneratedFile.HEADER), it.path) }
    }

    @Test
    fun generatesVectorDrawablesInDrawableMode() {
        val files = generate(IconFormat.DRAWABLE).associateBy { it.path }
        val xml = files.getValue("src/main/res/drawable/ic_brand_logo.xml").text
        assertTrue(xml.contains("android:translateX=\"2\""))
        assertTrue(xml.contains("android:strokeLineJoin=\"round\""))
        val icons = files.getValue("src/main/kotlin/com/example/app/designsystem/AppIcons.kt").text
        assertTrue(icons.contains("@Composable get() = ImageVector.vectorResource(R.drawable.ic_brand_logo)"))
        assertTrue(icons.contains("import com.example.app.R"))
    }

    @Test
    fun plannerCreatesUpdatesDeletesAndProtectsHandWrittenFiles() {
        val module = Files.createTempDirectory("module")
        val first = generate(IconFormat.IMAGE_VECTOR)
        val plan1 = ImportPlanner.plan(module, first)
        assertTrue(plan1.all { it.action == FileAction.CREATE })
        ImportPlanner.apply(module, plan1)

        assertTrue(ImportPlanner.plan(module, first).all { it.action == FileAction.UNCHANGED }, "re-import must be a no-op")

        // A hand-written file at a generated path is never overwritten.
        val colorPath = module.resolve("src/main/kotlin/com/example/app/designsystem/Color.kt")
        Files.delete(module.resolve(ImportPlanner.MANIFEST))
        colorPath.writeText("// my own colors\n")
        val conflict = ImportPlanner.plan(module, first).single { it.path.endsWith("designsystem/Color.kt") }
        assertEquals(FileAction.CONFLICT, conflict.action)
        ImportPlanner.apply(module, ImportPlanner.plan(module, first))
        assertEquals("// my own colors\n", colorPath.readText())

        // Icons removed from the design are deleted on re-import.
        val withoutLogo = DesignSystemGenerator.generate(ds.copy(icons = ds.icons.filter { it.name != "brand-logo" }), config(IconFormat.IMAGE_VECTOR), iconPlans(ds.copy(icons = ds.icons.filter { it.name != "brand-logo" })))
        val plan3 = ImportPlanner.plan(module, withoutLogo)
        assertEquals(FileAction.DELETE, plan3.single { it.path.endsWith("icons/BrandLogoIcon.kt") }.action)
    }

    @Test
    fun filesFromTheOldImporterNameAreStillRecognizedAsGenerated() {
        val module = Files.createTempDirectory("module")
        val files = generate(IconFormat.IMAGE_VECTOR)
        ImportPlanner.apply(module, ImportPlanner.plan(module, files))

        // A module imported by an earlier version: old header, no manifest entry to fall back on.
        Files.delete(module.resolve(ImportPlanner.MANIFEST))
        val colorPath = module.resolve("src/main/kotlin/com/example/app/designsystem/Color.kt")
        colorPath.writeText("// Generated by Claude Design Importer — do not edit\npackage com.example.app.designsystem\n")

        val action = ImportPlanner.plan(module, files).single { it.path.endsWith("designsystem/Color.kt") }.action
        assertEquals(FileAction.UPDATE, action)
    }

    @Test
    fun readsZipExportsAndFontFiles() {
        val zip = Files.createTempFile("export", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            Files.walk(fixture).filter { Files.isRegularFile(it) }.forEach { f ->
                out.putNextEntry(ZipEntry("my-design-system/" + fixture.relativize(f).toString().replace('\\', '/')))
                Files.copy(f, out)
                out.closeEntry()
            }
        }
        val fromZip = DesignSystemReader.read(zip)
        assertEquals(ds.colors, fromZip.colors)
        assertEquals(ds.icons.map { it.name }, fromZip.icons.map { it.name })

        val withFonts = DesignSystemReader.read(
            listOf(
                ExportFile("fonts/Inter-SemiBoldItalic.ttf") { byteArrayOf(0, 1, 0, 0) },
                ExportFile("fonts/Inter-Regular.ttf") { byteArrayOf(0, 1, 0, 0) },
                ExportFile("fonts/Inter-Bold.woff2") { byteArrayOf(1) },
            )
        )
        assertEquals(listOf(Triple("Inter", 600, true), Triple("Inter", 400, false)), withFonts.fonts.map { Triple(it.family, it.weight, it.italic) }.sortedByDescending { it.second })
        assertEquals(setOf("inter_semi_bold_italic", "inter_regular"), withFonts.fonts.map { it.resourceName }.toSet())
        assertTrue(withFonts.warnings.any { it.contains("woff2") })
    }

    private fun config(format: IconFormat) = GeneratorConfig(packageName = "com.example.app.designsystem", rPackage = "com.example.app", iconFormat = format)

    private fun iconPlans(source: dev.designimporter.core.model.DesignSystem) =
        DesignSystemGenerator.iconPlans(DesignSystemGenerator.analyzeIcons(source, MaterialIconMatcher(index, MatchMode.GEOMETRY_AND_NAME)))

    private fun generate(format: IconFormat) = DesignSystemGenerator.generate(ds, config(format), iconPlans(ds))
}
