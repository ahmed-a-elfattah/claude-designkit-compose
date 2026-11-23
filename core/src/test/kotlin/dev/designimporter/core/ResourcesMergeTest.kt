package dev.designimporter.core

import dev.designimporter.core.plan.ResourcesMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResourcesMergeTest {
    /** strings.xml as left by a release before the DesignKit rename: the user's own string plus an old-marker block. */
    private val legacy = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<resources>
        |    <string name="my_own">Mine</string>
        |    <!-- Claude Design Importer: begin. Generated; edits between these markers are replaced on re-import -->
        |    <string name="old_entry">Old</string>
        |    <string name="back_text">Back (old)</string>
        |    <!-- Claude Design Importer: end -->
        |</resources>
        |""".trimMargin()

    @Test
    fun mergeReplacesLegacyBlock() {
        val body = "    <string name=\"transactions\">Transactions</string>\n    <string name=\"back_text\">Back</string>\n"
        val merged = assertNotNull(ResourcesMerge.merge(legacy, body))

        assertTrue(merged.contains("<string name=\"my_own\">Mine</string>"), merged)
        assertFalse(merged.contains("old_entry"), merged)
        assertFalse(merged.contains("Back (old)"), merged)
        assertTrue(merged.contains("<string name=\"transactions\">Transactions</string>"), merged)
        assertTrue(merged.contains("<string name=\"back_text\">Back</string>"), merged)
        assertFalse(merged.contains("Claude Design Importer"), merged)
        assertEquals(1, Regex.escape("Claude DesignKit: begin").toRegex().findAll(merged).count(), merged)
        assertEquals(1, Regex.escape("Claude DesignKit: end").toRegex().findAll(merged).count(), merged)

        // Re-importing over the new block is stable.
        assertEquals(merged, ResourcesMerge.merge(merged, body))
    }

    @Test
    fun removeDropsLegacyBlock() {
        val removed = ResourcesMerge.remove(legacy)
        assertTrue(removed.contains("<string name=\"my_own\">Mine</string>"), removed)
        assertFalse(removed.contains("old_entry"), removed)
        assertFalse(removed.contains("Claude Design Importer"), removed)
        assertFalse(ResourcesMerge.hasBlock(removed), removed)
    }
}
