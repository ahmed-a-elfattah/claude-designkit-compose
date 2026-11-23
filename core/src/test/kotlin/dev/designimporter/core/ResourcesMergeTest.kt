package dev.designimporter.core

import dev.designimporter.core.plan.ResourcesMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourcesMergeTest {
    private val legacy = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="mine">Mine</string>
            <!-- Claude Design Importer: begin. Generated; edits between these markers are replaced on re-import -->
            <string name="old">Old</string>
            <!-- Claude Design Importer: end -->
        </resources>
    """.trimIndent() + "\n"

    @Test
    fun replacesABlockWrittenUnderTheOldName() {
        assertTrue(ResourcesMerge.hasBlock(legacy))
        val merged = ResourcesMerge.merge(legacy, "    <string name=\"new\">New</string>\n")!!
        assertTrue(merged.contains("<string name=\"mine\">Mine</string>"))
        assertTrue(merged.contains("<string name=\"new\">New</string>"))
        assertFalse(merged.contains("name=\"old\""))
        assertFalse(merged.contains("Claude Design Importer"))
        assertEquals(1, Regex("Claude DesignKit: begin").findAll(merged).count())
    }

    @Test
    fun removesABlockWrittenUnderTheOldName() {
        val removed = ResourcesMerge.remove(legacy)
        assertFalse(removed.contains("name=\"old\""))
        assertTrue(removed.contains("<string name=\"mine\">Mine</string>"))
    }
}
