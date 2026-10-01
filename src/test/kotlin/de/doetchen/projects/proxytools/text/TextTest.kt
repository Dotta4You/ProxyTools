package de.doetchen.projects.proxytools.text

import de.doetchen.projects.proxytools.core.text.Text
import de.doetchen.projects.proxytools.testing.CoreTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TextTest : CoreTestBase() {
    @Test
    fun `colorize converts legacy and hex codes`() {
        assertEquals("§aHi §lthere", Text.colorize("&aHi &lthere"))
        assertEquals("§x§f§f§a§a§0§0x", Text.colorize("&#FFAA00x"))
        assertEquals("a & b &z", Text.colorize("a & b &z"))
    }

    @Test
    fun `gradient colors each character between the two hex colors`() {
        val result = Text.colorize("<gradient:#ff0000:#0000ff>ab</gradient>")
        assertEquals("§x§f§f§0§0§0§0a§x§0§0§0§0§f§fb", result)
    }

    @Test
    fun `rainbow colorizes every character and leaves the rest untouched`() {
        val result = Text.colorize("<rainbow>ab</rainbow> plain")
        assertTrue(result.startsWith("§x"))
        assertTrue(result.endsWith(" plain"))
    }

    @Test
    fun `placeholders are replaced`() {
        assertEquals("3/10", Text.replace("%online%/%max%", "online" to "3", "max" to "10"))
    }

    @Test
    fun `formatting codes inside a gradient stay active and do not count as characters`() {
        assertEquals(
            "§x§f§f§0§0§0§0§lA§x§0§0§0§0§f§f§lB",
            Text.colorize("<gradient:#ff0000:#0000ff>&lAB</gradient>"),
        )
        assertEquals(
            "§x§f§f§0§0§0§0A§x§0§0§0§0§f§f§lB",
            Text.colorize("<gradient:#ff0000:#0000ff>A&lB</gradient>"),
        )
        assertEquals(
            "§x§f§f§0§0§0§0§lA§x§0§0§0§0§f§f" + "B",
            Text.colorize("<gradient:#ff0000:#0000ff>&lA&rB</gradient>"),
        )
    }

    @Test
    fun `color codes inside a gradient are dropped and emoji stay in one piece`() {
        assertEquals("§x§f§f§0§0§0§0A", Text.colorize("<gradient:#ff0000:#0000ff>&cA</gradient>"))
        val rainbow = Text.colorize("<rainbow>😀a</rainbow>")
        assertTrue(rainbow.contains("😀"), "a surrogate pair must not be split by a color code")
    }

    @Test
    fun `rainbow covers the whole hue circle without the desktop classes`() {
        val colors = Regex("§x((?:§[0-9a-f]){6})").findAll(Text.colorize("<rainbow>abcdefghijkl</rainbow>"))
            .map { it.groupValues[1].replace("§", "") }.toList()
        assertEquals(12, colors.size)
        assertEquals("ff2626", colors.first())
        assertEquals(colors.size, colors.toSet().size, "every character gets its own color")
    }

    @Test
    fun `strip removes every color and hex code`() {
        assertEquals("Hi there", Text.strip(Text.colorize("&aHi &#FF0000there")))
        assertEquals("plain", Text.strip("plain"))
    }
}
