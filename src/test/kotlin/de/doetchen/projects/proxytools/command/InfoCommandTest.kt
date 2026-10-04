package de.doetchen.projects.proxytools.command

import de.doetchen.projects.proxytools.core.config.ConfigValidator
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class InfoCommandTest : CoreTestBase() {
    private val infoConfig = """
        info-commands:
          Discord:
            aliases: [DC, 'bad name']
            text:
              - '&9Hi %player%'
              - 'online: %online%'
            url: https://discord.example.com
          vip:
            text: 'only vips'
            url: javascript:alert(1)
            permission: vip.use
          broken:
            aliases: [x]
    """.trimIndent() + "\n"

    @Test
    fun `info commands are read from the config`() {
        val definitions = core(infoConfig).infoCommands.definitions().associateBy { it.name }

        assertEquals(setOf("discord", "vip"), definitions.keys)
        assertEquals(listOf("dc"), definitions.getValue("discord").aliases)
        assertEquals("https://discord.example.com", definitions.getValue("discord").url)
        assertNull(definitions.getValue("vip").url, "only http and https links are accepted")
        assertEquals("vip.use", definitions.getValue("vip").permission)
    }

    @Test
    fun `an info command shows its text with a link, respects its permission and can be removed`() {
        val core = core(infoConfig)
        chatter("Other")
        val actor = FakeActor(emptySet(), "Alex")

        core.commands.infoCommand("discord", actor)
        assertEquals("§9Hi Alex\nonline: 1", actor.messages.single())
        assertEquals("https://discord.example.com", actor.urls.single())

        core.commands.infoCommand("vip", actor)
        assertTrue(actor.messages.last().contains("permission"))
        val vip = FakeActor(setOf("vip.use"), "Vip")
        core.commands.infoCommand("vip", vip)
        assertEquals("only vips", vip.messages.single())

        Files.writeString(folder.resolve("config.yml"), "info-commands: {}\n")
        core.reload()
        core.commands.infoCommand("discord", actor)
        assertTrue(actor.messages.last().contains("no longer available"))
        assertTrue(platform.warnings.any { it.contains("restart") })
    }

    @Test
    fun `the example in the bundled config works when enabled as described`() {
        val lines = javaClass.getResourceAsStream("/config.yml")!!.reader().readText().lines()
        val start = lines.indexOfFirst { it.startsWith("# info-commands:") }
        val example = lines.drop(start).takeWhile { it.startsWith("#") }.map { it.removePrefix("# ") }
        val enabled = (lines.take(start) + example + lines.drop(start + example.size).filterNot { it == "info-commands: {}" })
            .joinToString("\n")

        val core = core(enabled)

        val discord = core.infoCommands.definitions().single()
        assertEquals("discord", discord.name)
        assertEquals(listOf("dc"), discord.aliases)
        assertEquals("https://discord.example.com", discord.url)
        assertNull(discord.permission)
        assertEquals(emptyList(), ConfigValidator.check(core.config))
    }

    @Test
    fun `an info command that reuses a built-in command name is reported`() {
        core("info-commands:\n  msg:\n    text: hi\n  fresh:\n    aliases: [hub]\n    text: hi\n")
        assertTrue(platform.warnings.any { it.contains("/msg is defined more than once") })
        assertTrue(platform.warnings.any { it.contains("/hub is defined more than once") })
        assertTrue(platform.warnings.none { it.contains("/fresh") })
    }
}
