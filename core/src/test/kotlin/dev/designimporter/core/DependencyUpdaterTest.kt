package dev.designimporter.core

import dev.designimporter.core.gen.MaterialDependencies
import dev.designimporter.core.plan.DependencyUpdater
import dev.designimporter.core.plan.GradleModules
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyUpdaterTest {
    private val build = Path.of("app/build.gradle.kts")
    private val catalog = Path.of("gradle/libs.versions.toml")
    private val buildText = """
        plugins { alias(libs.plugins.android.application) }

        dependencies {
            implementation(platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.material3)
        }
    """.trimIndent()
    private val catalogText = "[versions]\nagp = \"9.5.1\"\n\n[libraries]\nandroidx-compose-bom = { group = \"androidx.compose\", name = \"compose-bom\", version = \"2026.02.01\" }\n"

    @Test
    fun addsExtendedThroughTheVersionCatalog() {
        val edits = DependencyUpdater.plan(build, buildText, catalog, catalogText, MaterialDependencies(core = true, extended = true)).associateBy { it.path }
        assertTrue(edits.getValue(catalog).newText.contains("androidx-compose-material-icons-extended = { group = \"androidx.compose.material\", name = \"material-icons-extended\" }"))
        assertTrue(edits.getValue(build).newText.contains("dependencies {\n    implementation(libs.androidx.compose.material.icons.extended)"))
    }

    @Test
    fun reusesAnExistingCatalogEntry() {
        val withEntry = catalogText + "androidx-compose-material-icons-core = { group = \"androidx.compose.material\", name = \"material-icons-core\" }\n"
        val edits = DependencyUpdater.plan(build, buildText, catalog, withEntry, MaterialDependencies(core = true, extended = false))
        assertEquals(listOf(build), edits.map { it.path })
        assertTrue(edits.single().newText.contains("implementation(libs.androidx.compose.material.icons.core)"))
    }

    @Test
    fun recognizesOnlyModulesThatApplyTheAndroidPlugin() {
        val root = """
            // Top-level build file where you can add configuration options common to all sub-projects/modules.
            plugins {
                alias(libs.plugins.android.application) apply false
                alias(libs.plugins.kotlin.compose) apply false
                id("com.android.library") version "9.5.1" apply false
            }
        """.trimIndent()
        assertEquals(null, GradleModules.androidPluginType(root))
        assertEquals("application", GradleModules.androidPluginType(buildText))
        assertEquals("library", GradleModules.androidPluginType("plugins {\n    id(\"com.android.library\")\n}"))
        assertEquals("library", GradleModules.androidPluginType("apply plugin: 'com.android.library'"))

        val rootFile = Path.of("build.gradle.kts")
        assertTrue(DependencyUpdater.plan(rootFile, root, catalog, catalogText, MaterialDependencies(core = true, extended = true)).isEmpty())
    }

    @Test
    fun usesCoordinatesWithoutCatalogAndSkipsWhenPresent() {
        val edits = DependencyUpdater.plan(build, "plugins { id(\"com.android.application\") }\ndependencies {\n}\n", null, null, MaterialDependencies(core = true, extended = true))
        assertTrue(edits.single().newText.contains("implementation(\"androidx.compose.material:material-icons-extended:1.7.8\")"))

        val present = buildText.replace("material3)", "material3)\n    implementation(libs.androidx.compose.material.icons.extended)")
        assertTrue(DependencyUpdater.plan(build, present, catalog, catalogText, MaterialDependencies(core = true, extended = true)).isEmpty())
        assertTrue(DependencyUpdater.plan(build, buildText, catalog, catalogText, MaterialDependencies(core = false, extended = false)).isEmpty())
    }
}
