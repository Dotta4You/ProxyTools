package de.doetchen.projects.proxytools.command

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
}
