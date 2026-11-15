package dev.designimporter.core

import dev.designimporter.core.reader.CssValues
import dev.designimporter.core.svg.PathData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValuesAndPathsTest {
    @Test
    fun parsesColors() {
        assertEquals(0xFF4F46E5, CssValues.parseColor("#4F46E5"))
        assertEquals(0xFFFFFFFF, CssValues.parseColor("#fff"))
        assertEquals(0x22000000, CssValues.parseColor("#00000022"))
        assertEquals(0xFF16A34A, CssValues.parseColor("rgb(22 163 74)"))
        assertEquals(0x80FF0000, CssValues.parseColor("rgba(255, 0, 0, 0.5)"))
        assertEquals(0xFF737373, CssValues.parseColor("hsl(0 0% 45%)"))
        assertEquals(0xFFFFFFFF, CssValues.parseColor("oklch(1 0 0)"))
        assertEquals(0xFF000000, CssValues.parseColor("oklch(0% 0 0)"))
        assertNull(CssValues.parseColor("var(--x)"))
    }

    @Test
    fun parsesDimensions() {
        assertEquals(16f, CssValues.parseDimen("1rem"))
        assertEquals(12f, CssValues.parseDimen("12px"))
        assertEquals(12f, CssValues.parseDimen("9pt"))
        assertEquals(6f, CssValues.shadowToElevation("0 4px 12px rgba(0,0,0,.1)"))
        assertNull(CssValues.shadowToElevation("inset 0 0 0 1px #e3e6eb"))
        assertEquals(600, CssValues.parseFontWeight("Semi Bold"))
    }

    @Test
    fun normalizesNames() {
        assertEquals("brandPrimary", Names.tokenIdentifier("color.brand.primary", setOf("color")))
        assertEquals("space4", Names.tokenIdentifier("space-4", setOf("space")))
        assertEquals("space0_5", Names.tokenIdentifier("space-0-5", setOf("space")))
        assertEquals("inter_semi_bold", Names.resourceName("Inter-SemiBold"))
        assertEquals("r_2x_icon", Names.resourceName("2x-icon"))
        assertEquals("continue_text", Names.resourceName("Continue"))
        assertEquals("new_text", Names.resourceName("New"))
    }

    @Test
    fun parsesCompactPathSyntax() {
        val cmds = PathData.parse("M1.5.5l-1-2a1 1 0 00 1 1z")
        assertEquals(listOf('M', 'l', 'a', 'Z'), cmds.map { it.c })
        assertEquals(listOf(1.5f, 0.5f), cmds[0].args.toList())
        assertEquals(listOf(1f, 1f, 0f, 0f, 0f, 1f, 1f), cmds[2].args.toList())
    }

    @Test
    fun absoluteFormIsIndependentOfRelativeCommands() {
        val a = PathData.format(PathData.toAbsolute(PathData.parse("M10 10h4v4h-4z")))
        val b = PathData.format(PathData.toAbsolute(PathData.parse("M10 10L14 10L14 14L10 14Z")))
        assertEquals(a, b)
    }
}
