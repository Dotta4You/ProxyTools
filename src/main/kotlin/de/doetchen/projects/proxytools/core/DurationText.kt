package de.doetchen.projects.proxytools.core

object DurationText {
    private val PATTERN = Regex("(?i)^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$")

    fun parseMillis(input: String): Long? {
        val (d, h, m, s) = PATTERN.matchEntire(input.trim())?.destructured ?: return null
        val seconds = (d.toLongOrNull() ?: 0) * 86400 +
            (h.toLongOrNull() ?: 0) * 3600 +
            (m.toLongOrNull() ?: 0) * 60 +
            (s.toLongOrNull() ?: 0)
        return seconds.takeIf { it > 0 }?.times(1000)
    }

    fun format(totalSeconds: Long): String {
        val d = totalSeconds / 86400
        val h = (totalSeconds % 86400) / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = buildList {
            if (d > 0) add("${d}d")
            if (h > 0) add("${h}h")
            if (m > 0) add("${m}m")
            if (s > 0 || isEmpty()) add("${s}s")
        }
        return parts.take(2).joinToString(" ")
    }
}
