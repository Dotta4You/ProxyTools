package de.doetchen.projects.proxytools.motd

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MotdTest : CoreTestBase() {
    @Test
    fun `normal ping uses motd, maintenance ping uses maintenance motd`() {
        val core = core()
        val normal = assertNotNull(core.motd.build(5, 100))
        assertNotNull(normal.description)
        assertNull(normal.versionName)
        assertNull(normal.hoverLines)

        core.maintenance.setEnabled(true)
        val maintenance = assertNotNull(core.motd.build(5, 100))
        assertTrue(maintenance.description!!.contains("Maintenance"))
        assertEquals("§cMaintenance", maintenance.versionName)
        assertNotNull(maintenance.hoverLines)
    }

    @Test
    fun `motd can be disabled and placeholders use the effective max`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  entries:\n    - line1: 'a %online%/%max%'\n  max-players: 7\n",
        )
        assertEquals("a 2/7", core().motd.build(2, 100)!!.description)

        Files.writeString(folder.resolve("config.yml"), "motd:\n  enabled: false\n")
        assertNull(core().motd.build(2, 100))
    }

    @Test
    fun `sequential motd is sticky within an interval, then advances`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  mode: sequential\n  interval-seconds: 5\n  entries:\n    - line1: 'one'\n    - line1: 'two'\n",
        )
        val core = core()
        assertEquals("one", core.motd.build(0, 10)!!.description)
        platform.advance(4_000)
        assertEquals("one", core.motd.build(0, 10)!!.description, "still the same entry within the interval")
        platform.advance(1_000)
        assertEquals("two", core.motd.build(0, 10)!!.description, "next interval, next entry")
    }

    @Test
    fun `static motd always uses the first entry`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  mode: static\n  interval-seconds: 1\n  entries:\n    - line1: 'one'\n    - line1: 'two'\n",
        )
        val core = core()
        repeat(5) {
            assertEquals("one", core.motd.build(0, 10)!!.description)
            platform.advance(1_000)
        }
    }

    @Test
    fun `motd placeholders stay live even though the template is cached per interval`() {
        val core = core("motd:\n  entries:\n    - line1: '%online%'\n")
        assertEquals("3", core.motd.build(3, 100)!!.description)
        assertEquals("7", core.motd.build(7, 100)!!.description, "same bucket, but online count must stay live")
    }

    @Test
    fun `random motd is sticky within an interval`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  interval-seconds: 5\n  entries:\n    - line1: 'a'\n    - line1: 'b'\n    - line1: 'c'\n",
        )
        val core = core()
        val first = core.motd.build(0, 10)!!.description
        platform.advance(4_000)
        assertEquals(first, core.motd.build(0, 10)!!.description)
    }

    @Test
    fun `hover mode players leaves the sample untouched`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  hover:\n    enabled: true\n    mode: players\n",
        )
        assertNull(core().motd.build(2, 100)!!.hoverLines)
    }

    @Test
    fun `motd preview command shows the active entry, or says nothing is active`() {
        val core = core("motd:\n  entries:\n    - line1: 'Hello world'\n")
        val denied = FakeActor()
        core.commands.proxyTools(denied, listOf("motd"))
        assertTrue(denied.messages.single().contains("permission"))
        assertFalse(denied.messages.single().contains("Hello world"))
        assertEquals(listOf("info"), core.commands.suggestProxyTools(denied, listOf("")))

        val actor = FakeActor(setOf(Permissions.MOTD))
        core.commands.proxyTools(actor, listOf("motd"))
        assertTrue(actor.messages.single().contains("Hello world"))
        assertEquals(listOf("info", "motd"), core.commands.suggestProxyTools(actor, listOf("")))

        Files.writeString(folder.resolve("config.yml"), "motd:\n  enabled: false\n")
        core.reload()
        actor.messages.clear()
        core.commands.proxyTools(actor, listOf("motd"))
        assertFalse(actor.messages.single().contains("Hello world"))
    }

    @Test
    fun `dynamic max-players tracks the live online count, not the cached template`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  entries:\n    - line1: 'x'\n  max-players: dynamic\n  max-players-headroom: 3\n",
        )
        val core = core()
        assertEquals(8, core.motd.build(5, 100)!!.maxPlayers)
        assertEquals(13, core.motd.build(10, 100)!!.maxPlayers, "must update every ping, not just once per interval")
    }

    @Test
    fun `fixed and unset max-players still work alongside dynamic`() {
        Files.writeString(folder.resolve("config.yml"), "motd:\n  entries:\n    - line1: 'x'\n  max-players: 50\n")
        assertEquals(50, core().motd.build(5, 100)!!.maxPlayers)

        Files.writeString(folder.resolve("config.yml"), "motd:\n  entries:\n    - line1: 'x'\n  max-players: -1\n")
        assertNull(core().motd.build(5, 100)!!.maxPlayers)
    }

    @Test
    fun `motd placeholder reason is filled during maintenance`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "maintenance:\n  motd:\n    entries:\n      - line1: 'Closed: %reason%'\n",
        )
        val core = core()
        core.maintenance.setEnabled(true, "Upgrade")
        assertEquals("Closed: Upgrade", core.motd.build(0, 10)!!.description)
    }
}
