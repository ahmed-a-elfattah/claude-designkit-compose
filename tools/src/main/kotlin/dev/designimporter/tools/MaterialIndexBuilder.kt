package dev.designimporter.tools

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.designimporter.core.svg.IconHash
import dev.designimporter.core.svg.PathCmd
import java.io.File
import java.util.zip.ZipFile

private val styleObjects: Map<String, Any> = mapOf(
    "filled" to Icons.Filled, "outlined" to Icons.Outlined, "rounded" to Icons.Rounded,
    "sharp" to Icons.Sharp, "twotone" to Icons.TwoTone,
    "automirrored.filled" to Icons.AutoMirrored.Filled, "automirrored.outlined" to Icons.AutoMirrored.Outlined,
    "automirrored.rounded" to Icons.AutoMirrored.Rounded, "automirrored.sharp" to Icons.AutoMirrored.Sharp,
    "automirrored.twotone" to Icons.AutoMirrored.TwoTone,
)
private val styleNames = mapOf(
    "filled" to "Filled", "outlined" to "Outlined", "rounded" to "Rounded", "sharp" to "Sharp", "twotone" to "TwoTone",
)

private data class Entry(val style: String, val name: String, val extended: Boolean, val hash: String)

/** Loads every Material icon from the icon jars, hashes its geometry and writes the index JSON. */
fun main(args: Array<String>) {
    val out = File(args.single())
    val jars = System.getProperty("java.class.path").split(File.pathSeparator)
        .filter { it.contains("material-icons-core") || it.contains("material-icons-extended") }
    require(jars.isNotEmpty()) { "material icon jars not on the classpath" }

    val entryPattern = Regex("^androidx/compose/material/icons/((?:automirrored/)?(?:filled|outlined|rounded|sharp|twotone))/(\\w+)Kt\\.class$")
    val entries = mutableListOf<Entry>()
    for (jar in jars) {
        val extended = jar.contains("material-icons-extended")
        ZipFile(jar).use { zip ->
            for (e in zip.entries()) {
                val m = entryPattern.matchEntire(e.name) ?: continue
                val stylePkg = m.groupValues[1].replace('/', '.')
                val name = m.groupValues[2]
                val receiver = styleObjects[stylePkg] ?: continue
                val vector = loadIcon("androidx.compose.material.icons.$stylePkg.${name}Kt", name, receiver) ?: continue
                val style = stylePkg.split('.').joinToString(".") { if (it == "automirrored") "AutoMirrored" else styleNames.getValue(it) }
                entries += Entry(style, name, extended, hash(vector))
            }
        }
    }

    // Drop deprecated non-mirrored duplicates of AutoMirrored icons.
    val mirrored = entries.filter { it.style.startsWith("AutoMirrored.") }.map { it.style.removePrefix("AutoMirrored.") to it.name }.toSet()
    val kept = entries.filter { it.style.startsWith("AutoMirrored.") || (it.style to it.name) !in mirrored }
        .distinctBy { it.style to it.name }
        .sortedWith(compareBy({ it.style }, { it.name }))

    val icons = JsonArray()
    kept.forEach { e ->
        icons.add(JsonArray().apply { add(e.style); add(e.name); add(if (e.extended) "extended" else "core"); add(e.hash) })
    }
    val root = JsonObject().apply {
        addProperty("version", "1.7")
        add("icons", icons)
    }
    out.parentFile.mkdirs()
    out.writeText(GsonBuilder().disableHtmlEscaping().create().toJson(root))
    println("Wrote ${kept.size} icons (${kept.count { !it.extended }} core) to $out")
}

private fun loadIcon(className: String, name: String, receiver: Any): ImageVector? = runCatching {
    val cls = Class.forName(className)
    val getter = cls.methods.firstOrNull { it.name == "get$name" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(receiver) }
        ?: return null
    getter.invoke(null, receiver) as ImageVector
}.getOrNull()

private fun hash(vector: ImageVector): String {
    val paths = mutableListOf<List<PathCmd>>()
    fun walk(node: VectorNode) {
        when (node) {
            is VectorGroup -> node.forEach { walk(it) }
            is VectorPath -> paths += node.pathData.map(::toCmd)
        }
    }
    walk(vector.root)
    return IconHash.of(paths, vector.viewportWidth)
}

private fun f(vararg v: Float) = v
private fun flag(b: Boolean) = if (b) 1f else 0f

private fun toCmd(n: PathNode): PathCmd = when (n) {
    is PathNode.MoveTo -> PathCmd('M', f(n.x, n.y))
    is PathNode.RelativeMoveTo -> PathCmd('m', f(n.dx, n.dy))
    is PathNode.LineTo -> PathCmd('L', f(n.x, n.y))
    is PathNode.RelativeLineTo -> PathCmd('l', f(n.dx, n.dy))
    is PathNode.HorizontalTo -> PathCmd('H', f(n.x))
    is PathNode.RelativeHorizontalTo -> PathCmd('h', f(n.dx))
    is PathNode.VerticalTo -> PathCmd('V', f(n.y))
    is PathNode.RelativeVerticalTo -> PathCmd('v', f(n.dy))
    is PathNode.CurveTo -> PathCmd('C', f(n.x1, n.y1, n.x2, n.y2, n.x3, n.y3))
    is PathNode.RelativeCurveTo -> PathCmd('c', f(n.dx1, n.dy1, n.dx2, n.dy2, n.dx3, n.dy3))
    is PathNode.ReflectiveCurveTo -> PathCmd('S', f(n.x1, n.y1, n.x2, n.y2))
    is PathNode.RelativeReflectiveCurveTo -> PathCmd('s', f(n.dx1, n.dy1, n.dx2, n.dy2))
    is PathNode.QuadTo -> PathCmd('Q', f(n.x1, n.y1, n.x2, n.y2))
    is PathNode.RelativeQuadTo -> PathCmd('q', f(n.dx1, n.dy1, n.dx2, n.dy2))
    is PathNode.ReflectiveQuadTo -> PathCmd('T', f(n.x, n.y))
    is PathNode.RelativeReflectiveQuadTo -> PathCmd('t', f(n.dx, n.dy))
    is PathNode.ArcTo -> PathCmd('A', f(n.horizontalEllipseRadius, n.verticalEllipseRadius, n.theta, flag(n.isMoreThanHalf), flag(n.isPositiveArc), n.arcStartX, n.arcStartY))
    is PathNode.RelativeArcTo -> PathCmd('a', f(n.horizontalEllipseRadius, n.verticalEllipseRadius, n.theta, flag(n.isMoreThanHalf), flag(n.isPositiveArc), n.arcStartDx, n.arcStartDy))
    PathNode.Close -> PathCmd('Z', FloatArray(0))
}
