package de.doetchen.projects.proxytools.player

import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class HubTest : CoreTestBase() {
    @Test
    fun `hub sends a player to the lobby, not the console, and not someone already there`() {
        val core = core()
        val player = FakeActor(emptySet(), "Alex", "survival")
        core.commands.hub(player, emptyList())
        assertEquals("lobby", player.connectedTo)
        assertTrue(player.messages.single().contains("lobby"))

        val inLobby = FakeActor(emptySet(), "Sam", "Lobby")
        core.commands.hub(inLobby, emptyList())
        assertNull(inLobby.connectedTo)
        assertTrue(inLobby.messages.single().contains("already"))

        val console = FakeActor(uniqueId = null)
        core.commands.hub(console, emptyList())
        assertNull(console.connectedTo)
        assertTrue(console.messages.single().contains("players"))
    }

    @Test
    fun `hub picks the first registered server or the emptiest one`() {
        platform.servers += listOf("lobby-1", "lobby-2")
        platform.serverPlayers += mapOf("lobby-1" to 12, "lobby-2" to 3)

        val first = core("hub:\n  servers: [missing, lobby-1, lobby-2]\n")
        val a = FakeActor(emptySet(), "A", "survival", knownServers = setOf("lobby-1", "lobby-2"))
        first.commands.hub(a, emptyList())
        assertEquals("lobby-1", a.connectedTo)

        Files.writeString(folder.resolve("config.yml"), "hub:\n  mode: least-players\n  servers: [lobby-1, lobby-2]\n")
        first.reload()
        val b = FakeActor(emptySet(), "B", "survival", knownServers = setOf("lobby-1", "lobby-2"))
        first.commands.hub(b, emptyList())
        assertEquals("lobby-2", b.connectedTo)
    }

    @Test
    fun `hub reports when no lobby exists or when it is switched off`() {
        val none = core("hub:\n  servers: [nowhere]\n")
        assertTrue(platform.warnings.any { it.contains("hub.servers") })
        val player = FakeActor(emptySet(), "A", "survival")
        none.commands.hub(player, emptyList())
        assertNull(player.connectedTo)
        assertTrue(player.messages.single().contains("No lobby"))

        platform.warnings.clear()
        val off = core("hub:\n  enabled: false\n  servers: [nowhere]\n")
        assertTrue(platform.warnings.none { it.contains("hub.servers") })
        val other = FakeActor(emptySet(), "B", "survival")
        off.commands.hub(other, emptyList())
        assertNull(other.connectedTo)
        assertTrue(other.messages.single().contains("disabled"))
    }
}
