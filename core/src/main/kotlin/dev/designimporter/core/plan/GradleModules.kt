package dev.designimporter.core.plan

object GradleModules {
    private val androidPlugin = Regex("""com\.android\.(application|library)|android[.-](application|library)""")
    private val namespace = Regex("""namespace\s*=?\s*["']([\w.]+)["']""")

    /**
     * The Android plugin type a build file applies ("application" / "library"), or null.
     * Declarations with `apply false` (the root build file's plugin catalog) don't count.
     */
    fun androidPluginType(buildText: String): String? {
        val applied = buildText.lines()
            .map { it.substringBefore("//") }
            .filterNot { Regex("""\bapply\s*\(?\s*false""").containsMatchIn(it) }
            .joinToString("\n")
        val match = androidPlugin.find(applied) ?: return null
        return match.groupValues.drop(1).first { it.isNotEmpty() }
    }

    fun namespace(buildText: String): String? = namespace.find(buildText)?.groupValues?.get(1)
}
