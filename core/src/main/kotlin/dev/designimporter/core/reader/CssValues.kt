package dev.designimporter.core.reader

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object CssValues {
    private const val REM_PX = 16f

    private val named = mapOf(
        "white" to 0xFFFFFFFFL,
        "black" to 0xFF000000L,
        "transparent" to 0x00000000L,
    )

    fun isTransparent(raw: String): Boolean = raw.trim().lowercase() in setOf("transparent", "none")

    fun parseColor(raw: String): Long? {
        val s = raw.trim().lowercase()
        named[s]?.let { return it }
        if (s.startsWith("#")) return parseHex(s.substring(1))
        val fn = Regex("^([a-z]+)\\((.*)\\)$").find(s) ?: return null
        val name = fn.groupValues[1]
        val (channels, alpha) = splitArgs(fn.groupValues[2]) ?: return null
        return when (name) {
            "rgb", "rgba" -> {
                if (channels.size < 3) return null
                val c = channels.take(3).map { channel255(it) ?: return null }
                argb(alpha, c[0], c[1], c[2])
            }
            "hsl", "hsla" -> {
                if (channels.size < 3) return null
                val h = hue(channels[0]) ?: return null
                val sat = percent(channels[1]) ?: return null
                val l = percent(channels[2]) ?: return null
                val (r, g, b) = hslToRgb(h, sat, l)
                argb(alpha, r, g, b)
            }
            "oklch" -> {
                if (channels.size < 3) return null
                val l = lightness(channels[0]) ?: return null
                val c = channels[1].removeSuffix("%").toFloatOrNull()?.let {
                    if (channels[1].endsWith("%")) it / 100f * 0.4f else it
                } ?: return null
                val h = hue(channels[2]) ?: 0f
                val rad = Math.toRadians(h.toDouble())
                oklabToArgb(alpha, l, (c * cos(rad)).toFloat(), (c * sin(rad)).toFloat())
            }
            "oklab" -> {
                if (channels.size < 3) return null
                val l = lightness(channels[0]) ?: return null
                val a = channels[1].toFloatOrNull() ?: return null
                val b = channels[2].toFloatOrNull() ?: return null
                oklabToArgb(alpha, l, a, b)
            }
            else -> null
        }
    }

    private fun parseHex(h: String): Long? {
        if (!h.all { it.isDigit() || it in 'a'..'f' }) return null
        val full = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("") + "ff"
            4 -> h.map { "$it$it" }.joinToString("")
            6 -> h + "ff"
            8 -> h
            else -> return null
        }
        val rgba = full.toLong(16)
        val a = rgba and 0xFF
        return (a shl 24) or (rgba ushr 8)
    }

    /** Returns channel strings and alpha (0..1), accepting both comma and space/slash syntax. */
    private fun splitArgs(args: String): Pair<List<String>, Float>? {
        val parts = args.replace("/", " / ").split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val slash = parts.indexOf("/")
        val channels = if (slash >= 0) parts.subList(0, slash) else parts.take(3)
        val alphaStr = when {
            slash >= 0 -> parts.getOrNull(slash + 1)
            parts.size >= 4 -> parts[3]
            else -> null
        }
        val alpha = alphaStr?.let { if (it.endsWith("%")) it.removeSuffix("%").toFloatOrNull()?.div(100f) else it.toFloatOrNull() } ?: 1f
        return channels to alpha.coerceIn(0f, 1f)
    }

    private fun channel255(s: String): Int? =
        if (s.endsWith("%")) s.removeSuffix("%").toFloatOrNull()?.let { (it / 100f * 255f).roundToInt() }
        else s.toFloatOrNull()?.roundToInt()

    private fun percent(s: String): Float? = s.removeSuffix("%").toFloatOrNull()?.let { if (s.endsWith("%") || it > 1f) it / 100f else it }

    private fun lightness(s: String): Float? = s.removeSuffix("%").toFloatOrNull()?.let { if (s.endsWith("%")) it / 100f else it }

    private fun hue(s: String): Float? = when {
        s.endsWith("deg") -> s.removeSuffix("deg").toFloatOrNull()
        s.endsWith("turn") -> s.removeSuffix("turn").toFloatOrNull()?.times(360f)
        s.endsWith("rad") -> s.removeSuffix("rad").toFloatOrNull()?.let { Math.toDegrees(it.toDouble()).toFloat() }
        s == "none" -> 0f
        else -> s.toFloatOrNull()
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Int, Int, Int> {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val hp = ((h % 360 + 360) % 360) / 60f
        val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0f)
            hp < 2 -> Triple(x, c, 0f)
            hp < 3 -> Triple(0f, c, x)
            hp < 4 -> Triple(0f, x, c)
            hp < 5 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2
        return Triple(((r1 + m) * 255).roundToInt(), ((g1 + m) * 255).roundToInt(), ((b1 + m) * 255).roundToInt())
    }

    private fun oklabToArgb(alpha: Float, l: Float, a: Float, b: Float): Long {
        val l_ = (l + 0.3963377774f * a + 0.2158037573f * b).toDouble().pow(3.0)
        val m_ = (l - 0.1055613458f * a - 0.0638541728f * b).toDouble().pow(3.0)
        val s_ = (l - 0.0894841775f * a - 1.2914855480f * b).toDouble().pow(3.0)
        val r = 4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_
        val g = -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_
        val bl = -0.0041960863 * l_ - 0.7034186147 * m_ + 1.7076147010 * s_
        fun gamma(x: Double): Int {
            val v = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
            return (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        }
        return argb(alpha, gamma(r), gamma(g), gamma(bl))
    }

    private fun argb(alpha: Float, r: Int, g: Int, b: Int): Long {
        val a = (alpha * 255).roundToInt().coerceIn(0, 255).toLong()
        return (a shl 24) or (r.coerceIn(0, 255).toLong() shl 16) or (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()
    }

    /** px/dp/rem/em/pt to dp (1px = 1dp). Bare numbers are treated as px. */
    fun parseDimen(raw: String): Float? {
        val s = raw.trim().lowercase()
        val m = Regex("^(-?\\d*\\.?\\d+)(px|dp|sp|rem|em|pt)?$").find(s) ?: return null
        val v = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2]) {
            "rem", "em" -> v * REM_PX
            "pt" -> v * 4f / 3f
            else -> v
        }
    }

    fun parseFontWeight(raw: String): Int? {
        val s = raw.trim().lowercase().replace(Regex("[\\s_-]"), "")
        s.toIntOrNull()?.let { return it.coerceIn(1, 1000) }
        return when (s) {
            "thin", "hairline" -> 100
            "extralight", "ultralight" -> 200
            "light" -> 300
            "normal", "regular", "book" -> 400
            "medium" -> 500
            "semibold", "demibold" -> 600
            "bold" -> 700
            "extrabold", "ultrabold" -> 800
            "black", "heavy" -> 900
            else -> null
        }
    }

    /** First family in a CSS font-family list, unquoted. */
    fun firstFontFamily(raw: String): String = raw.split(",").first().trim().trim('"', '\'').trim()

    /** Elevation in dp from a box-shadow ("0 4px 12px rgba(...)"): the larger of |y offset| and half the blur. */
    fun shadowToElevation(raw: String): Float? {
        val first = raw.split(Regex(",(?![^(]*\\))")).first()
        if (first.trim().startsWith("inset")) return null
        val dims = first.trim().split(Regex("\\s+(?![^(]*\\))")).mapNotNull { parseDimen(it) }
        if (dims.size < 2) return null
        val y = kotlin.math.abs(dims[1])
        val blur = dims.getOrNull(2) ?: 0f
        return maxOf(y, blur / 2f)
    }
}
