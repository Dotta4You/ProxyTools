package de.doetchen.projects.proxytools.core.text

import kotlin.math.floor

internal object Text {
    private const val SECTION = '§'
    private val HEX = Regex("&#([0-9a-fA-F]{6})")
    private val CODE = Regex("&([0-9a-fk-orA-FK-OR])")
    private val GRADIENT = Regex("<gradient:(#[0-9a-fA-F]{6}):(#[0-9a-fA-F]{6})>(.*?)</gradient>")
    private val RAINBOW = Regex("<rainbow>(.*?)</rainbow>")
    private val TOKEN = Regex("&[0-9a-fk-orA-FK-OR]|.", RegexOption.DOT_MATCHES_ALL)
    private val SECTION_CODE = Regex("§[0-9a-fk-orxA-FK-ORX]")

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

    fun strip(input: String): String = SECTION_CODE.replace(input, "")

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
        colorPerCharacter(match.groupValues[1]) { index, length -> "%06x".format(hsbToRgb(index.toDouble() / length, 0.85, 1.0)) }
    }

    private fun colorPerCharacter(text: String, colorAt: (index: Int, length: Int) -> String): String {
        val tokens = TOKEN.findAll(text).map { it.value }.toList()
        val visible = tokens.count { !isCode(it) }
        val formats = StringBuilder()
        val result = StringBuilder()
        var index = 0
        for (token in tokens) {
            if (isCode(token)) {
                when (token[1].lowercaseChar()) {
                    in 'k'..'o' -> formats.append(token)
                    'r' -> formats.setLength(0)
                }
                continue
            }
            result.append("&#").append(colorAt(index++, visible)).append(formats).append(token)
        }
        return result.toString()
    }

    private fun isCode(token: String) = token.length == 2 && token[0] == '&'

    private fun fraction(index: Int, length: Int): Double = if (length <= 1) 0.0 else index.toDouble() / (length - 1)

    private fun hexToRgb(hex: String): IntArray {
        val clean = hex.removePrefix("#")
        return intArrayOf(clean.substring(0, 2).toInt(16), clean.substring(2, 4).toInt(16), clean.substring(4, 6).toInt(16))
    }

    private fun lerpHex(from: IntArray, to: IntArray, t: Double): String {
        fun channel(i: Int) = (from[i] + (to[i] - from[i]) * t).toInt().coerceIn(0, 255)
        return "%02x%02x%02x".format(channel(0), channel(1), channel(2))
    }

    private fun hsbToRgb(hue: Double, saturation: Double, brightness: Double): Int {
        val sector = (hue - floor(hue)) * 6
        val fraction = sector - floor(sector)
        val p = brightness * (1 - saturation)
        val q = brightness * (1 - saturation * fraction)
        val t = brightness * (1 - saturation * (1 - fraction))
        val (r, g, b) = when (sector.toInt()) {
            0 -> Triple(brightness, t, p)
            1 -> Triple(q, brightness, p)
            2 -> Triple(p, brightness, t)
            3 -> Triple(p, q, brightness)
            4 -> Triple(t, p, brightness)
            else -> Triple(brightness, p, q)
        }
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun channel(value: Double) = (value * 255 + 0.5).toInt().coerceIn(0, 255)
}
