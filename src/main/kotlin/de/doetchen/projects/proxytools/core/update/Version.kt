package de.doetchen.projects.proxytools.core.update

internal class Version private constructor(
    private val numbers: List<Int>,
    private val suffix: String?,
) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val difference = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return when {
            suffix == other.suffix -> 0
            suffix == null -> 1
            other.suffix == null -> -1
            else -> suffix.compareTo(other.suffix, ignoreCase = true)
        }
    }

    companion object {
        private val PATTERN = Regex("(\\d+(?:\\.\\d+)*)(?:[-+_ ]?(.+))?")

        fun parse(text: String): Version? {
            val match = PATTERN.matchEntire(text.trim().removePrefix("v").removePrefix("V")) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            return Version(numbers, match.groupValues[2].ifEmpty { null })
        }
    }
}
