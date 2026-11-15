package dev.designimporter.core.svg

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class PathCmd(val c: Char, val args: FloatArray) {
    override fun toString() = "$c${args.joinToString(" ")}"
}

object PathData {
    private val argCounts = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7, 'Z' to 0)

    fun parse(d: String): List<PathCmd> {
        val out = mutableListOf<PathCmd>()
        var i = 0
        var cmd: Char? = null

        fun skipSeparators() {
            while (i < d.length && (d[i].isWhitespace() || d[i] == ',')) i++
        }

        fun readNumber(): Float? {
            skipSeparators()
            val start = i
            if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
            while (i < d.length && d[i].isDigit()) i++
            if (i < d.length && d[i] == '.') {
                i++
                while (i < d.length && d[i].isDigit()) i++
            }
            if (i < d.length && (d[i] == 'e' || d[i] == 'E') && i + 1 < d.length && (d[i + 1].isDigit() || d[i + 1] == '-' || d[i + 1] == '+')) {
                i += 2
                while (i < d.length && d[i].isDigit()) i++
            }
            return if (i == start) null else d.substring(start, i).toFloatOrNull()
        }

        fun readFlag(): Float? {
            skipSeparators()
            if (i < d.length && (d[i] == '0' || d[i] == '1')) return (d[i++] - '0').toFloat()
            return null
        }

        while (true) {
            skipSeparators()
            if (i >= d.length) break
            val ch = d[i]
            if (ch.isLetter()) {
                if (ch.uppercaseChar() !in argCounts) break
                cmd = ch
                i++
                if (ch == 'Z' || ch == 'z') {
                    out += PathCmd('Z', FloatArray(0))
                    continue
                }
            }
            val c = cmd ?: break
            val n = argCounts.getValue(c.uppercaseChar())
            if (n == 0) break
            val args = FloatArray(n)
            for (k in 0 until n) {
                args[k] = (if (c.uppercaseChar() == 'A' && (k == 3 || k == 4)) readFlag() else readNumber()) ?: return out
            }
            out += PathCmd(c, args)
            if (c == 'M') cmd = 'L' else if (c == 'm') cmd = 'l'
        }
        return out
    }

    /** Absolute commands; H/V become L so equivalent paths normalize the same. */
    fun toAbsolute(cmds: List<PathCmd>): List<PathCmd> {
        val out = ArrayList<PathCmd>(cmds.size)
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        for (cmd in cmds) {
            val rel = cmd.c.isLowerCase()
            val a = cmd.args
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (cmd.c.uppercaseChar()) {
                'M' -> { cx = a[0] + ox; cy = a[1] + oy; sx = cx; sy = cy; out += PathCmd('M', floatArrayOf(cx, cy)) }
                'L' -> { cx = a[0] + ox; cy = a[1] + oy; out += PathCmd('L', floatArrayOf(cx, cy)) }
                'H' -> { cx = a[0] + ox; out += PathCmd('L', floatArrayOf(cx, cy)) }
                'V' -> { cy = a[0] + oy; out += PathCmd('L', floatArrayOf(cx, cy)) }
                'C' -> {
                    out += PathCmd('C', floatArrayOf(a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy, a[4] + ox, a[5] + oy))
                    cx = a[4] + ox; cy = a[5] + oy
                }
                'S', 'Q' -> {
                    out += PathCmd(cmd.c.uppercaseChar(), floatArrayOf(a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy))
                    cx = a[2] + ox; cy = a[3] + oy
                }
                'T' -> { cx = a[0] + ox; cy = a[1] + oy; out += PathCmd('T', floatArrayOf(cx, cy)) }
                'A' -> {
                    cx = a[5] + ox; cy = a[6] + oy
                    out += PathCmd('A', floatArrayOf(a[0], a[1], a[2], a[3], a[4], cx, cy))
                }
                'Z' -> { cx = sx; cy = sy; out += PathCmd('Z', FloatArray(0)) }
            }
        }
        return out
    }

    fun format(cmds: List<PathCmd>, scale: Float = 1f, decimals: Int = 3): String = buildString {
        for (cmd in cmds) {
            append(cmd.c)
            cmd.args.forEachIndexed { k, v ->
                if (k > 0) append(' ')
                val isArc = cmd.c.uppercaseChar() == 'A'
                val value = when {
                    isArc && (k == 3 || k == 4) -> v
                    isArc && k == 2 -> v
                    else -> v * scale
                }
                append(num(value, decimals))
            }
        }
    }

    fun num(v: Float, decimals: Int = 3): String {
        val factor = Math.pow(10.0, decimals.toDouble())
        val rounded = (v * factor).roundToLong() / factor
        if (abs(rounded) < 1e-9) return "0"
        return String.format(Locale.ROOT, "%.${decimals}f", rounded).trimEnd('0').trimEnd('.')
    }
}

object IconHash {
    /** Stable hash of an icon's geometry, normalized to a 24-unit viewport. */
    fun of(paths: List<List<PathCmd>>, viewportWidth: Float): String {
        val scale = if (viewportWidth > 0f) 24f / viewportWidth else 1f
        val normalized = paths.joinToString("|") { PathData.format(PathData.toAbsolute(it), scale, decimals = 2) }
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
