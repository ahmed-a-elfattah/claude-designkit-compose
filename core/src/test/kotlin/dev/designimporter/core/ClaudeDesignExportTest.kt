package dev.designimporter.core

import dev.designimporter.core.gen.DesignSystemGenerator
import dev.designimporter.core.gen.GeneratorConfig
import dev.designimporter.core.icons.MatchKind
import dev.designimporter.core.icons.MatchMode
import dev.designimporter.core.icons.MaterialIconIndex
import dev.designimporter.core.icons.MaterialIconMatcher
import dev.designimporter.core.model.ComponentType
import dev.designimporter.core.model.Props
import dev.designimporter.core.model.TokenValue
import dev.designimporter.core.plan.FileAction
import dev.designimporter.core.plan.ImportPlanner
import dev.designimporter.core.plan.ResourceNames
import dev.designimporter.core.plan.ResourcesMerge
import dev.designimporter.core.reader.DesignSystemReader
import dev.designimporter.core.reader.StringKeys
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A real Claude Design export (PayFlow prototype): ds/<name>/tokens.json + CSS + a .dc.html artboard. */
class ClaudeDesignExportTest {
    private val export: Path = Path.of(javaClass.getResource("/claude-design-payflow")!!.toURI())
    private val ds by lazy { DesignSystemReader.read(export) }

    @Test
    fun readsClaudeDesignTokens() {
        assertEquals("PayFlow", ds.name)
        val colors = ds.colors.associateBy { it.name }
        assertEquals(0xFF3538CD, colors.getValue("primary").light)
        assertEquals(0xFFA5A8FF, colors.getValue("primary").dark, "alias {brand-600} resolved per theme")
        assertEquals(0xFF0B0E14, colors.getValue("bg").dark)
        assertTrue(colors.getValue("primary").description!!.startsWith("Primary buttons"))
        assertNotNull(colors["onRefundContainer"], "color defined only in the artboard's <style>")

        val type = ds.typography.associateBy { it.name }
        assertEquals(36f, type.getValue("amountXl").fontSizeSp)
        assertEquals(-0.5f, type.getValue("amountXl").letterSpacingSp)
        assertEquals("IBM Plex Sans Arabic", type.getValue("titleMd").fontFamily)
        assertEquals("IBM Plex Mono", type.getValue("monoSm").fontFamily)
        assertEquals(16, ds.typography.size, "CSS helper classes must not add text styles next to the token type scale")

        assertEquals(2f, ds.spacing.single { it.name == "space0_5" }.dp)
        assertEquals(28f, ds.radii.single { it.name == "xl" }.dp)
        assertEquals(listOf(0f, 1f, 6f, 12f), ds.elevation.map { it.dp })
        assertEquals(48f, ds.sizes.single { it.name == "control" }.dp)
        assertTrue(ds.warnings.any { it.contains("IBM Plex Sans Arabic") })
    }

    @Test
    fun readsPrefixedComponentClassesAndStatusBadges() {
        val button = ds.components.single { it.type == ComponentType.BUTTON }
        assertEquals(listOf("Primary", "Tonal", "Outlined", "Text"), button.variants.map { it.name })
        val outlined = button.variants[2].props
        assertEquals(TokenValue.ColorRef("borderStrong"), outlined[Props.BORDER_COLOR], "inset box-shadow is a border")
        assertEquals(TokenValue.Dimen(48f), outlined[Props.HEIGHT])

        val chip = ds.components.single { it.type == ComponentType.CHIP }
        assertEquals(listOf("Default", "Selected"), chip.variants.map { it.name })
        assertEquals(TokenValue.Dimen(0f), chip.variants[1].props[Props.BORDER_WIDTH])

        val badge = ds.components.single { it.type == ComponentType.BADGE }
        assertEquals(
            setOf("Refund", "Pending", "Authorized", "Succeeded", "Failed", "Cancelled", "Expired", "Refunded", "Recorded"),
            badge.variants.map { it.name }.toSet(),
        )
        assertEquals(TokenValue.ColorRef("warningContainer"), badge.variants.single { it.name == "Pending" }.props[Props.BACKGROUND])
        assertEquals(TokenValue.Dimen(24f), badge.variants.single { it.name == "Pending" }.props[Props.HEIGHT])

        assertEquals(TokenValue.ColorRef("border"), ds.components.single { it.type == ComponentType.CARD }.variants.single().props[Props.BORDER_COLOR])
    }

    @Test
    fun mapsMaterialSymbolsToComposeIcons() {
        val names = ds.icons.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("payments", "receipt_long", "qr_code_2", "arrow_back", "chevron_right", "content_copy")))
        assertTrue("bad" !in names && "ok" !in names && "pending" !in names, "status strings are not icons")
        assertEquals("Outlined", ds.iconStyleHint)

        val matcher = MaterialIconMatcher(MaterialIconIndex.shared, MatchMode.GEOMETRY_AND_NAME)
        val candidates = DesignSystemGenerator.analyzeIcons(ds, matcher)
        assertTrue(candidates.all { it.match?.kind == MatchKind.SYMBOL }, candidates.filter { it.match == null }.joinToString { it.asset.name })
        val byName = candidates.associateBy { it.asset.name }
        assertEquals("Icons.Outlined.Payments", byName.getValue("payments").match!!.icon.expression)
        assertEquals("Icons.AutoMirrored.Outlined.ArrowBack", byName.getValue("arrow_back").match!!.icon.expression)
        assertEquals("Icons.Outlined.QrCode2", byName.getValue("qr_code_2").match!!.icon.expression)

        val rounded = MaterialIconMatcher(MaterialIconIndex.shared, MatchMode.GEOMETRY_AND_NAME, "Rounded", symbolStyleOverride = "Rounded")
        assertEquals("Icons.Rounded.Payments", DesignSystemGenerator.analyzeIcons(ds, rounded).first { it.asset.name == "payments" }.match!!.icon.expression)

        val off = DesignSystemGenerator.analyzeIcons(ds, null)
        assertTrue(off.all { it.match == null && it.error != null })
        assertNull(DesignSystemGenerator.iconPlans(off).firstOrNull())
    }

    @Test
    fun extractsBilingualStrings() {
        val byText = ds.strings.associateBy { it.text }
        assertTrue(ds.strings.size > 300, "L(en, ar) calls and {en, ar} objects: ${ds.strings.size}")
        assertEquals("رجوع", byText.getValue("Back").translations["ar"])
        assertEquals("back", byText.getValue("Back").key)
        assertEquals("فيزا •• 4242", byText.getValue("Visa •• 4242").translations["ar"], "object form")
        assertEquals(ds.strings.size, ds.strings.map { it.key }.toSet().size, "keys are unique")

        // L('Capture 50% · ', '…') + money(x) → format string with the literal % escaped
        val capture = ds.strings.single { it.text.startsWith("Capture 50") }
        assertEquals("Capture 50%% · %1\$s", capture.text)
        assertEquals(1, capture.args)
        assertTrue(capture.translations.getValue("ar").endsWith("%1\$s"))

        val renamed = StringKeys.avoid(ds.strings, setOf("back"))
        assertEquals("back_text", renamed.single { it.text == "Back" }.key)
    }

    @Test
    fun mergesStringsIntoTheModulesStringsXml() {
        val module = java.nio.file.Files.createTempDirectory("module")
        val values = module.resolve("src/main/res/values").also { java.nio.file.Files.createDirectories(it) }
        val own = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"app_name\">PayFlow</string>\n</resources>\n"
        values.resolve("strings.xml").writeText(own)
        // A separate file from an earlier importer version must be cleaned up.
        values.resolve("design_strings.xml").writeText("<resources/>")
        module.resolve(ImportPlanner.MANIFEST).writeText("src/main/res/values/design_strings.xml\n")

        val cfg = GeneratorConfig(packageName = "com.example.app.designsystem", rPackage = "com.example.app")
        fun files(strings: List<dev.designimporter.core.model.LocalizedString>) =
            DesignSystemGenerator.generate(ds, cfg, emptyList(), StringKeys.avoid(strings, ResourceNames.existingStrings(module)))

        val plan = ImportPlanner.plan(module, files(ds.strings))
        assertEquals(FileAction.UPDATE, plan.single { it.path == "src/main/res/values/strings.xml" }.action)
        assertEquals(FileAction.CREATE, plan.single { it.path == "src/main/res/values-ar/strings.xml" }.action)
        assertEquals(FileAction.DELETE, plan.single { it.path.endsWith("design_strings.xml") }.action)
        ImportPlanner.apply(module, plan)

        val en = values.resolve("strings.xml").readText()
        assertTrue(en.startsWith(own.substringBefore("</resources>")), "the user's own entries stay first and untouched")
        assertTrue(en.contains(ResourcesMerge.BEGIN) && en.trimEnd().endsWith("</resources>"))
        assertTrue(en.contains("<string name=\"back\">Back</string>"))
        assertTrue(module.resolve("src/main/res/values-ar/strings.xml").readText().contains("<string name=\"back\">رجوع</string>"))

        // Re-import: our own keys are not mistaken for the user's (no back_text), nothing changes.
        assertTrue(ImportPlanner.plan(module, files(ds.strings)).all { it.action == FileAction.UNCHANGED })

        // The user edits their part; a re-import with fewer strings rewrites only the block.
        values.resolve("strings.xml").writeText(en.replace("PayFlow", "PayFlow Merchant"))
        ImportPlanner.apply(module, ImportPlanner.plan(module, files(ds.strings.take(3))))
        val after = values.resolve("strings.xml").readText()
        assertTrue(after.contains("PayFlow Merchant") && after.split("<string ").size == 1 + 1 + 3)

        // Strings turned off: the block goes, the user's file stays; the ar file only had our block, so it is removed.
        ImportPlanner.apply(module, ImportPlanner.plan(module, files(emptyList())))
        assertEquals(own.replace("PayFlow", "PayFlow Merchant"), values.resolve("strings.xml").readText())
        assertTrue(!module.resolve("src/main/res/values-ar/strings.xml").toFile().exists())
    }

    @Test
    fun writesValidStringResources() {
        val cfg = GeneratorConfig(packageName = "com.example.app.designsystem", rPackage = "com.example.app")
        val files = DesignSystemGenerator.generate(ds, cfg, emptyList()).filter { it.mergeBlock }.associate { it.path to ResourcesMerge.merge(null, it.text)!! }
        val en = files.getValue("src/main/res/values/strings.xml")
        val ar = files.getValue("src/main/res/values-ar/strings.xml")
        assertTrue(en.contains("<string name=\"back\">Back</string>"))
        assertTrue(ar.contains("<string name=\"back\">رجوع</string>"))
        assertTrue(en.contains("<string name=\"capture_50\">Capture 50%% · %1\$s</string>"))
        assertTrue(en.contains("Today\\'s") || !en.contains("Today's"), "apostrophes escaped")
        // Every value parses as XML and no raw quote/ampersand slips through.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(en.byteInputStream())
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ar.byteInputStream())
        Regex("<string name=\"[^\"]+\"[^>]*>(.*)</string>").findAll(en + ar).forEach { m ->
            assertTrue(Regex("(?<!\\\\)['\"]").find(m.groupValues[1].trim('"')) == null, m.value)
        }
        assertTrue(DesignSystemGenerator.generate(ds, cfg, emptyList(), strings = emptyList()).none { it.mergeBlock })
    }

    @Test
    fun generatesDocumentedThemeWithSizes() {
        val cfg = GeneratorConfig(packageName = "com.abdelfattah.ahmed.payflow.designsystem", rPackage = "com.abdelfattah.ahmed.payflow")
        val plans = DesignSystemGenerator.iconPlans(DesignSystemGenerator.analyzeIcons(ds, MaterialIconMatcher(MaterialIconIndex.shared, MatchMode.GEOMETRY_AND_NAME)))
        val files = DesignSystemGenerator.generate(ds, cfg, plans).associate { it.path.substringAfterLast("designsystem/") to it.text }

        assertTrue(files.getValue("Color.kt").contains("    /** Primary buttons, active nav item, links, focus. Never for money-received meaning (that is success). */\n    val primary: Color,"))
        assertTrue(files.getValue("Color.kt").contains("outline = LightAppColors.borderStrong,"))
        assertTrue(files.getValue("Color.kt").contains("outlineVariant = LightAppColors.border,"))
        assertTrue(files.getValue("Sizes.kt").contains("val control: Dp = 48.dp,"))
        assertTrue(files.getValue("Theme.kt").contains("val sizes: AppSizes"))
        assertTrue(files.getValue("Type.kt").contains("headlineLarge = DefaultAppTypography.headlineLg,"))
        assertTrue(files.getValue("AppIcons.kt").contains("val Payments: ImageVector get() = Icons.Outlined.Payments"))
        assertTrue(files.getValue("components/AppBadge.kt").contains("enum class AppBadgeVariant {"))
        assertTrue(files.getValue("components/AppBadge.kt").contains("height = 24.dp,"))
        assertTrue(files.keys.none { it.startsWith("icons/") }, "nothing to generate: every icon is a Material icon")
    }
}
