package com.zcc09.hermespocket

/**
 * Numeric per-component comparison for dotted version strings such as
 * "1.10.0". Pure Kotlin: no Android dependencies, so it is unit-testable
 * on the JVM.
 *
 * [isNewer] returns true only when [candidate] is strictly newer than
 * [current]. Components are compared numerically (1.10.0 > 1.9.0), missing
 * trailing components count as zero, and a leading "v" is ignored. Any
 * unparseable input returns false.
 */
object VersionTools {

    fun isNewer(current: String, candidate: String): Boolean {
        val a = parse(current)
        val b = parse(candidate)
        if (a == null || b == null) return false
        val length = maxOf(a.size, b.size)
        for (i in 0 until length) {
            val x = if (i < a.size) a[i] else 0
            val y = if (i < b.size) b[i] else 0
            if (x != y) return y > x
        }
        return false
    }

    private fun parse(raw: String): List<Int>? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
        if (text.isEmpty()) return null
        return text.split(".").map { part ->
            part.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
    }
}
