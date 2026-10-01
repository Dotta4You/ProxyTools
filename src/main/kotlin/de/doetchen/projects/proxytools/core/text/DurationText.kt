package de.doetchen.projects.proxytools.core.text

internal object DurationText {
    private val PATTERN = Regex("(?i)^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$")
    private val UNIT_SECONDS = listOf(86400L, 3600L, 60L, 1L)
    private const val MAX_SECONDS = 365L * 86400

    fun parseMillis(input: String): Long? {
        val groups = PATTERN.matchEntire(input.trim())?.destructured?.toList() ?: return null
        var seconds = 0L
        for ((text, unit) in groups.zip(UNIT_SECONDS)) {
            if (text.isEmpty()) continue
            val amount = text.toLongOrNull()?.takeIf { it <= MAX_SECONDS } ?: return null
            seconds += amount * unit
        }
        return seconds.takeIf { it in 1..MAX_SECONDS }?.times(1000)
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
