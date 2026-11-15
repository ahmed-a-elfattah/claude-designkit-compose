package dev.designimporter.core.svg

import dev.designimporter.core.reader.CssValues
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Cap { BUTT, ROUND, SQUARE }
enum class Join { MITER, ROUND, BEVEL }

data class Transform(
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
) {
    val isIdentity get() = translateX == 0f && translateY == 0f && scaleX == 1f && scaleY == 1f && rotation == 0f
}

sealed interface VNode

data class VGroup(val transform: Transform, val children: List<VNode>) : VNode

data class VPath(
    val d: String,
    val fill: Long?,
    val fillAlpha: Float,
    val stroke: Long?,
    val strokeAlpha: Float,
    val strokeWidth: Float,
    val cap: Cap,
    val join: Join,
    val miter: Float,
    val evenOdd: Boolean,
) : VNode

data class VectorDoc(
    val widthDp: Float,
    val heightDp: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val root: VGroup,
    val warnings: List<String>,
) {
    fun allPaths(): List<VPath> {
        val out = mutableListOf<VPath>()
        fun walk(n: VNode) {
            when (n) {
                is VPath -> out += n
                is VGroup -> n.children.forEach(::walk)
            }
        }
        walk(root)
        return out
    }

    fun hasTransforms(): Boolean {
        fun walk(n: VNode): Boolean = when (n) {
            is VPath -> false
            is VGroup -> !n.transform.isIdentity || n.children.any(::walk)
        }
        return walk(root)
    }
}

/** Converts SVG markup to a vector tree that maps 1:1 onto ImageVector and VectorDrawable. */
object SvgParser {
    private const val BLACK = 0xFF000000L

    private sealed interface Paint {
        data object None : Paint
        data class Solid(val argb: Long) : Paint
    }

    private data class Style(
        val fill: Paint = Paint.Solid(BLACK),
        val stroke: Paint = Paint.None,
        val strokeWidth: Float = 1f,
        val cap: Cap = Cap.BUTT,
        val join: Join = Join.MITER,
        val miter: Float = 4f,
        val evenOdd: Boolean = false,
        val opacity: Float = 1f,
        val fillOpacity: Float = 1f,
        val strokeOpacity: Float = 1f,
    )

    fun parse(svg: String): VectorDoc {
        val warnings = mutableListOf<String>()
        val root = document(svg).documentElement
        require(root.localNameOrTag() == "svg") { "Not an SVG document" }
        val viewBox = root.attr("viewBox")?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }
        val width = root.attr("width")?.let { CssValues.parseDimen(it) }
        val height = root.attr("height")?.let { CssValues.parseDimen(it) }
        val vw = viewBox?.get(2) ?: width ?: 24f
        val vh = viewBox?.get(3) ?: height ?: 24f
        val origin = if (viewBox != null && (viewBox[0] != 0f || viewBox[1] != 0f)) Transform(-viewBox[0], -viewBox[1]) else Transform()

        val children = children(root, applyStyle(root, Style(), warnings), warnings)
        return VectorDoc(
            widthDp = width ?: vw,
            heightDp = height ?: vh,
            viewportWidth = vw,
            viewportHeight = vh,
            root = VGroup(origin, children),
            warnings = warnings.distinct(),
        )
    }

    private fun document(svg: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
        .parse(InputSource(StringReader(svg)))

    private fun Element.localNameOrTag(): String = (localName ?: tagName).substringAfter(':')

    private fun Element.attr(name: String): String? {
        styleMap()[name]?.let { return it }
        return getAttribute(name).takeIf { it.isNotEmpty() }
    }

    private fun Element.styleMap(): Map<String, String> =
        getAttribute("style").split(';').mapNotNull { decl ->
            val i = decl.indexOf(':')
            if (i <= 0) null else decl.substring(0, i).trim() to decl.substring(i + 1).trim()
        }.toMap()

    private fun children(el: Element, style: Style, warnings: MutableList<String>): List<VNode> {
        val out = mutableListOf<VNode>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val child = nodes.item(i) as? Element ?: continue
            if (child.attr("display") == "none" || child.attr("visibility") == "hidden") continue
            if (child.hasAttribute("clip-path") || child.hasAttribute("mask")) warnings += "clip-path/mask is not supported and was ignored"
            val s = applyStyle(child, style, warnings)
            val transform = child.attr("transform")?.let { parseTransform(it, warnings) } ?: Transform()
            val node: VNode? = when (val tag = child.localNameOrTag()) {
                "g", "a", "svg" -> {
                    val nested = if (tag == "svg") {
                        Transform(child.attr("x")?.toFloatOrNull() ?: 0f, child.attr("y")?.toFloatOrNull() ?: 0f)
                    } else transform
                    VGroup(nested, children(child, s, warnings))
                }
                "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> {
                    val d = shapeToPath(tag, child) ?: continue
                    toPath(d, s)?.let { if (transform.isIdentity) it else VGroup(transform, listOf(it)) }
                }
                "use" -> { warnings += "<use> is not supported and was ignored"; null }
                "text" -> { warnings += "<text> is not supported; convert text to outlines"; null }
                "image" -> { warnings += "embedded <image> is not supported"; null }
                else -> null
            }
            if (node != null) out += node
        }
        return out
    }

    private fun toPath(d: String, s: Style): VPath? {
        val fill = (s.fill as? Paint.Solid)?.argb
        val stroke = (s.stroke as? Paint.Solid)?.argb?.takeIf { s.strokeWidth > 0f }
        if (fill == null && stroke == null) return null
        fun alphaOf(c: Long?) = ((c ?: BLACK) ushr 24 and 0xFF) / 255f
        return VPath(
            d = d,
            fill = fill?.let { it or 0xFF000000L },
            fillAlpha = s.opacity * s.fillOpacity * alphaOf(fill),
            stroke = stroke?.let { it or 0xFF000000L },
            strokeAlpha = s.opacity * s.strokeOpacity * alphaOf(stroke),
            strokeWidth = s.strokeWidth,
            cap = s.cap,
            join = s.join,
            miter = s.miter,
            evenOdd = s.evenOdd,
        )
    }

    private fun applyStyle(el: Element, parent: Style, warnings: MutableList<String>): Style {
        fun paint(v: String?, current: Paint): Paint = when {
            v == null || v == "inherit" -> current
            v == "none" || v == "transparent" -> Paint.None
            v == "currentColor" -> Paint.Solid(BLACK)
            v.startsWith("url(") -> { warnings += "gradients/patterns are not supported; used a solid color"; Paint.Solid(BLACK) }
            else -> CssValues.parseColor(v)?.let { Paint.Solid(it) } ?: current
        }
        return parent.copy(
            fill = paint(el.attr("fill"), parent.fill),
            stroke = paint(el.attr("stroke"), parent.stroke),
            strokeWidth = el.attr("stroke-width")?.let { CssValues.parseDimen(it) } ?: parent.strokeWidth,
            cap = when (el.attr("stroke-linecap")) { "round" -> Cap.ROUND; "square" -> Cap.SQUARE; "butt" -> Cap.BUTT; else -> parent.cap },
            join = when (el.attr("stroke-linejoin")) { "round" -> Join.ROUND; "bevel" -> Join.BEVEL; "miter" -> Join.MITER; else -> parent.join },
            miter = el.attr("stroke-miterlimit")?.toFloatOrNull() ?: parent.miter,
            evenOdd = el.attr("fill-rule")?.let { it == "evenodd" } ?: parent.evenOdd,
            opacity = parent.opacity * (el.attr("opacity")?.toFloatOrNull() ?: 1f),
            fillOpacity = el.attr("fill-opacity")?.toFloatOrNull() ?: parent.fillOpacity,
            strokeOpacity = el.attr("stroke-opacity")?.toFloatOrNull() ?: parent.strokeOpacity,
        )
    }

    private fun shapeToPath(tag: String, el: Element): String? {
        fun f(name: String) = el.attr(name)?.let { CssValues.parseDimen(it) } ?: 0f
        fun n(v: Float) = PathData.num(v)
        return when (tag) {
            "path" -> el.attr("d")?.takeIf { it.isNotBlank() }
            "rect" -> {
                val x = f("x"); val y = f("y"); val w = f("width"); val h = f("height")
                if (w <= 0f || h <= 0f) return null
                var rx = el.attr("rx")?.let { CssValues.parseDimen(it) }
                var ry = el.attr("ry")?.let { CssValues.parseDimen(it) }
                if (rx == null) rx = ry
                if (ry == null) ry = rx
                val r1 = (rx ?: 0f).coerceAtMost(w / 2)
                val r2 = (ry ?: 0f).coerceAtMost(h / 2)
                if (r1 <= 0f || r2 <= 0f) {
                    "M${n(x)},${n(y)}H${n(x + w)}V${n(y + h)}H${n(x)}Z"
                } else {
                    val a = "A${n(r1)},${n(r2)} 0 0 1"
                    "M${n(x + r1)},${n(y)}H${n(x + w - r1)}$a ${n(x + w)},${n(y + r2)}V${n(y + h - r2)}$a ${n(x + w - r1)},${n(y + h)}" +
                        "H${n(x + r1)}$a ${n(x)},${n(y + h - r2)}V${n(y + r2)}$a ${n(x + r1)},${n(y)}Z"
                }
            }
            "circle", "ellipse" -> {
                val cx = f("cx"); val cy = f("cy")
                val rx = if (tag == "circle") f("r") else f("rx")
                val ry = if (tag == "circle") f("r") else f("ry")
                if (rx <= 0f || ry <= 0f) return null
                "M${n(cx - rx)},${n(cy)}A${n(rx)},${n(ry)} 0 1 0 ${n(cx + rx)},${n(cy)}A${n(rx)},${n(ry)} 0 1 0 ${n(cx - rx)},${n(cy)}Z"
            }
            "line" -> "M${n(f("x1"))},${n(f("y1"))}L${n(f("x2"))},${n(f("y2"))}"
            "polyline", "polygon" -> {
                val pts = el.attr("points")?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() } ?: return null
                if (pts.size < 4) return null
                buildString {
                    append("M${n(pts[0])},${n(pts[1])}")
                    for (k in 2 until pts.size - 1 step 2) append("L${n(pts[k])},${n(pts[k + 1])}")
                    if (tag == "polygon") append("Z")
                }
            }
            else -> null
        }
    }

    /** Parses an SVG transform list into translate/rotate/scale (the shape ImageVector groups support). */
    private fun parseTransform(raw: String, warnings: MutableList<String>): Transform {
        // Affine matrix [a c e; b d f]
        var m = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        fun mul(n: FloatArray) {
            m = floatArrayOf(
                m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
                m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
                m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5],
            )
        }
        Regex("""(\w+)\s*\(([^)]*)\)""").findAll(raw).forEach { match ->
            val a = match.groupValues[2].split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            when (match.groupValues[1]) {
                "matrix" -> if (a.size == 6) mul(a.toFloatArray())
                "translate" -> mul(floatArrayOf(1f, 0f, 0f, 1f, a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f }))
                "scale" -> { val sx = a.getOrElse(0) { 1f }; mul(floatArrayOf(sx, 0f, 0f, a.getOrElse(1) { sx }, 0f, 0f)) }
                "rotate" -> {
                    val rad = Math.toRadians(a.getOrElse(0) { 0f }.toDouble())
                    val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
                    val cx = a.getOrElse(1) { 0f }; val cy = a.getOrElse(2) { 0f }
                    mul(floatArrayOf(1f, 0f, 0f, 1f, cx, cy))
                    mul(floatArrayOf(c, s, -s, c, 0f, 0f))
                    mul(floatArrayOf(1f, 0f, 0f, 1f, -cx, -cy))
                }
                "skewX", "skewY" -> warnings += "skew transforms are approximated"
            }
        }
        val (a, b, c, d) = listOf(m[0], m[1], m[2], m[3])
        val sx = sqrt(a * a + b * b)
        if (sx == 0f) return Transform()
        val sy = (a * d - b * c) / sx
        if (kotlin.math.abs(a * c + b * d) > 1e-3f * sx * kotlin.math.abs(sy)) warnings += "skew transforms are approximated"
        return Transform(
            translateX = m[4],
            translateY = m[5],
            scaleX = sx,
            scaleY = sy,
            rotation = Math.toDegrees(atan2(b, a).toDouble()).toFloat(),
        )
    }
}
