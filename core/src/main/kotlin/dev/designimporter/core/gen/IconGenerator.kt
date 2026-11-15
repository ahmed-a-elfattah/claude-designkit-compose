package dev.designimporter.core.gen

import dev.designimporter.core.Names
import dev.designimporter.core.icons.MaterialIcon
import dev.designimporter.core.svg.Cap
import dev.designimporter.core.svg.Join
import dev.designimporter.core.svg.Transform
import dev.designimporter.core.svg.VGroup
import dev.designimporter.core.svg.VNode
import dev.designimporter.core.svg.VPath
import dev.designimporter.core.svg.VectorDoc

/** An icon ready for generation: either a Material reference or a parsed custom vector. */
class IconPlan(val name: String, val propertyName: String, val material: MaterialIcon?, val vector: VectorDoc?)

internal class IconGenerator(private val icons: List<IconPlan>, private val cfg: GeneratorConfig) {
    private val iconsPackage = "${cfg.packageName}.icons"
    private val iconsDir = "${cfg.packageDir}/icons"

    fun generate(): List<GeneratedFile> {
        val files = mutableListOf<GeneratedFile>()
        val f = KotlinFile(cfg.packageName).import("androidx.compose.ui.graphics.vector.ImageVector")
        f.line("object ${cfg.prefix}Icons {")
        val usedFileNames = mutableSetOf<String>()
        icons.forEachIndexed { i, icon ->
            if (i > 0) f.line()
            val prop = Names.escape(icon.propertyName)
            when {
                icon.material != null -> {
                    f.import("androidx.compose.material.icons.Icons", icon.material.importPath)
                    f.line("    val $prop: ImageVector get() = ${icon.material.expression}")
                }
                icon.vector != null && cfg.iconFormat == IconFormat.DRAWABLE -> {
                    val res = Names.unique("ic_${Names.resourceName(icon.name)}", usedFileNames)
                    files += GeneratedFile.text("${cfg.resDir}/drawable/$res.xml", vectorDrawable(icon.vector))
                    f.import("androidx.compose.runtime.Composable", "androidx.compose.ui.res.vectorResource", "${cfg.rPackage}.R")
                    f.line("    val $prop: ImageVector")
                    f.line("        @Composable get() = ImageVector.vectorResource(R.drawable.$res)")
                }
                icon.vector != null -> {
                    val backing = Names.unique("${icon.propertyName}Icon", usedFileNames)
                    files += GeneratedFile.text("$iconsDir/$backing.kt", imageVector(backing, icon.propertyName, icon.vector))
                    f.import("$iconsPackage.$backing")
                    f.line("    val $prop: ImageVector get() = $backing")
                }
            }
        }
        f.line("}")
        files += GeneratedFile.text("${cfg.packageDir}/${cfg.prefix}Icons.kt", f.render())
        return files
    }

    private fun imageVector(backing: String, name: String, doc: VectorDoc): String {
        val f = KotlinFile(iconsPackage).import(
            "androidx.compose.ui.graphics.vector.ImageVector",
            "androidx.compose.ui.graphics.vector.addPathNodes",
            "androidx.compose.ui.unit.dp",
        )
        f.line("internal val $backing: ImageVector by lazy {")
        f.line("    ImageVector.Builder(")
        f.line("        name = \"$name\",")
        f.line("        defaultWidth = ${dp(doc.widthDp)},")
        f.line("        defaultHeight = ${dp(doc.heightDp)},")
        f.line("        viewportWidth = ${flt(doc.viewportWidth)},")
        f.line("        viewportHeight = ${flt(doc.viewportHeight)},")
        f.line("    ).apply {")
        emitKotlin(doc.root, f, "        ")
        f.line("    }.build()")
        f.line("}")
        return f.render()
    }

    private fun emitKotlin(node: VNode, f: KotlinFile, indent: String) {
        when (node) {
            is VGroup -> {
                val args = groupArgs(node.transform)
                if (args.isEmpty()) {
                    node.children.forEach { emitKotlin(it, f, indent) }
                } else {
                    f.line("${indent}addGroup(${args.joinToString(", ")})")
                    node.children.forEach { emitKotlin(it, f, indent) }
                    f.line("${indent}clearGroup()")
                }
            }
            is VPath -> {
                val args = mutableListOf("pathData = addPathNodes(\"${node.d.replace("\"", "").replace(Regex("\\s+"), " ").trim()}\")")
                node.fill?.let {
                    f.import("androidx.compose.ui.graphics.Color", "androidx.compose.ui.graphics.SolidColor")
                    args += "fill = SolidColor(${colorLiteral(it)})"
                    if (node.fillAlpha < 1f) args += "fillAlpha = ${flt(node.fillAlpha)}"
                }
                node.stroke?.let {
                    f.import("androidx.compose.ui.graphics.Color", "androidx.compose.ui.graphics.SolidColor")
                    args += "stroke = SolidColor(${colorLiteral(it)})"
                    if (node.strokeAlpha < 1f) args += "strokeAlpha = ${flt(node.strokeAlpha)}"
                    args += "strokeLineWidth = ${flt(node.strokeWidth)}"
                    if (node.cap != Cap.BUTT) {
                        f.import("androidx.compose.ui.graphics.StrokeCap")
                        args += "strokeLineCap = StrokeCap.${if (node.cap == Cap.ROUND) "Round" else "Square"}"
                    }
                    if (node.join != Join.MITER) {
                        f.import("androidx.compose.ui.graphics.StrokeJoin")
                        args += "strokeLineJoin = StrokeJoin.${if (node.join == Join.ROUND) "Round" else "Bevel"}"
                    }
                    if (node.miter != 4f) args += "strokeLineMiter = ${flt(node.miter)}"
                }
                if (node.evenOdd) {
                    f.import("androidx.compose.ui.graphics.PathFillType")
                    args += "pathFillType = PathFillType.EvenOdd"
                }
                f.line("${indent}addPath(")
                args.forEach { f.line("$indent    $it,") }
                f.line("$indent)")
            }
        }
    }

    private fun groupArgs(t: Transform): List<String> = buildList {
        if (t.rotation != 0f) add("rotate = ${flt(t.rotation)}")
        if (t.scaleX != 1f) add("scaleX = ${flt(t.scaleX)}")
        if (t.scaleY != 1f) add("scaleY = ${flt(t.scaleY)}")
        if (t.translateX != 0f) add("translationX = ${flt(t.translateX)}")
        if (t.translateY != 0f) add("translationY = ${flt(t.translateY)}")
    }

    private fun vectorDrawable(doc: VectorDoc): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<!-- ${GeneratedFile.HEADER} -->\n")
        append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        append("    android:width=\"${dp(doc.widthDp)}\"\n")
        append("    android:height=\"${dp(doc.heightDp)}\"\n")
        append("    android:viewportWidth=\"${num(doc.viewportWidth)}\"\n")
        append("    android:viewportHeight=\"${num(doc.viewportHeight)}\">\n")
        emitXml(doc.root, this, "    ")
        append("</vector>\n")
    }

    private fun num(v: Float) = dev.designimporter.core.svg.PathData.num(v)
    private fun hex(argb: Long) = "#%08X".format(argb)
    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private fun emitXml(node: VNode, sb: StringBuilder, indent: String) {
        when (node) {
            is VGroup -> {
                val t = node.transform
                if (t.isIdentity) {
                    node.children.forEach { emitXml(it, sb, indent) }
                    return
                }
                sb.append("$indent<group")
                if (t.rotation != 0f) sb.append(" android:rotation=\"${num(t.rotation)}\"")
                if (t.scaleX != 1f) sb.append(" android:scaleX=\"${num(t.scaleX)}\"")
                if (t.scaleY != 1f) sb.append(" android:scaleY=\"${num(t.scaleY)}\"")
                if (t.translateX != 0f) sb.append(" android:translateX=\"${num(t.translateX)}\"")
                if (t.translateY != 0f) sb.append(" android:translateY=\"${num(t.translateY)}\"")
                sb.append(">\n")
                node.children.forEach { emitXml(it, sb, "$indent    ") }
                sb.append("$indent</group>\n")
            }
            is VPath -> {
                val a = "\n$indent    "
                sb.append("$indent<path${a}android:pathData=\"${xmlEscape(node.d.replace(Regex("\\s+"), " ").trim())}\"")
                node.fill?.let {
                    sb.append("${a}android:fillColor=\"${hex(it)}\"")
                    if (node.fillAlpha < 1f) sb.append("${a}android:fillAlpha=\"${num(node.fillAlpha)}\"")
                }
                node.stroke?.let {
                    sb.append("${a}android:strokeColor=\"${hex(it)}\"")
                    if (node.strokeAlpha < 1f) sb.append("${a}android:strokeAlpha=\"${num(node.strokeAlpha)}\"")
                    sb.append("${a}android:strokeWidth=\"${num(node.strokeWidth)}\"")
                    if (node.cap != Cap.BUTT) sb.append("${a}android:strokeLineCap=\"${node.cap.name.lowercase()}\"")
                    if (node.join != Join.MITER) sb.append("${a}android:strokeLineJoin=\"${node.join.name.lowercase()}\"")
                    if (node.miter != 4f) sb.append("${a}android:strokeMiterLimit=\"${num(node.miter)}\"")
                }
                if (node.evenOdd) sb.append("${a}android:fillType=\"evenOdd\"")
                sb.append(" />\n")
            }
        }
    }
}
