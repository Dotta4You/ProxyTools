package de.doetchen.projects.proxytools.core

object Text {
    private const val SECTION = '§'
    private val HEX = Regex("&#([0-9a-fA-F]{6})")
    private val CODE = Regex("&([0-9a-fk-orA-FK-OR])")
    private val GRADIENT = Regex("<gradient:(#[0-9a-fA-F]{6}):(#[0-9a-fA-F]{6})>(.*?)</gradient>")
    private val RAINBOW = Regex("<rainbow>(.*?)</rainbow>")

    /** Converts `&`-codes, `&#RRGGBB` hex, `<gradient:#..:#..>` and `<rainbow>` into §-formatting. */
    fun colorize(input: String): String {
        val expanded = applyRainbow(applyGradients(input))
        val withHex = HEX.replace(expanded) { match ->
            buildString {
                append(SECTION).append('x')
                match.groupValues[1].lowercase().forEach { append(SECTION).append(it) }
            }
        }
        return CODE.replace(withHex) { "$SECTION${it.groupValues[1]}" }
    }

    /** Replaces `%key%` with its value for every pair. */
    fun replace(input: String, vararg placeholders: Pair<String, String>): String {
        var result = input
        for ((key, value) in placeholders) result = result.replace("%$key%", value)
        return result
    }

    private fun applyGradients(input: String) = GRADIENT.replace(input) { match ->
        val from = hexToRgb(match.groupValues[1])
        val to = hexToRgb(match.groupValues[2])
        colorPerCharacter(match.groupValues[3]) { index, length -> lerpHex(from, to, fraction(index, length)) }
    }

    private fun applyRainbow(input: String) = RAINBOW.replace(input) { match ->
        colorPerCharacter(match.groupValues[1]) { index, length ->
            "%06x".format(java.awt.Color.HSBtoRGB(fraction(index, length).toFloat(), 0.85f, 1.0f) and 0xFFFFFF)
        }
    }

    private inline fun colorPerCharacter(text: String, colorAt: (index: Int, length: Int) -> String): String =
        text.mapIndexed { index, char -> "&#${colorAt(index, text.length)}$char" }.joinToString("")

    private fun fraction(index: Int, length: Int): Double = if (length <= 1) 0.0 else index.toDouble() / (length - 1)

    private fun hexToRgb(hex: String): IntArray {
        val clean = hex.removePrefix("#")
        return intArrayOf(clean.substring(0, 2).toInt(16), clean.substring(2, 4).toInt(16), clean.substring(4, 6).toInt(16))
    }

    private fun lerpHex(from: IntArray, to: IntArray, t: Double): String {
        fun channel(i: Int) = (from[i] + (to[i] - from[i]) * t).toInt().coerceIn(0, 255)
        return "%02x%02x%02x".format(channel(0), channel(1), channel(2))
    }
}
