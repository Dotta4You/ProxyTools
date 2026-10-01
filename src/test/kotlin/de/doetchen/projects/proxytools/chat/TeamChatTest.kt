package de.doetchen.projects.proxytools.chat

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import de.doetchen.projects.proxytools.testing.FakePlayer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TeamChatTest : CoreTestBase() {
    @Test
    fun `team chat reaches only team members, includes the server name and needs the permission`() {
        val core = core()
        val staff = FakePlayer("Staff", setOf(Permissions.TEAMCHAT))
        val normal = FakePlayer("Normal")
        platform.players += listOf(staff, normal)

        val denied = FakeActor(emptySet(), "Normal", "lobby")
        core.commands.teamChat(denied, listOf("hi"))
        assertTrue(normal.received.isEmpty())
        assertTrue(denied.messages.single().contains("permission"))

        val sender = FakeActor(setOf(Permissions.TEAMCHAT), "Staff", "lobby")
        core.commands.teamChat(sender, listOf("hello", "team"))
        val message = staff.received.single()
        assertTrue(message.contains("Staff") && message.contains("lobby") && message.contains("hello team"))
        assertTrue(normal.received.isEmpty())
        assertTrue(platform.loggedInfo.any { it.contains("hello team") })

        core.commands.teamChat(sender, emptyList())
        assertTrue(sender.messages.last().contains("/teamchat"))
    }

    @Test
    fun `team chat can be switched off in the config and is on by default`() {
        val staff = FakePlayer("Staff", setOf(Permissions.TEAMCHAT))
        platform.players += staff
        val sender = FakeActor(setOf(Permissions.TEAMCHAT), "Staff", "lobby")
        val core = core()

        core.commands.teamChat(sender, listOf("on"))
        assertEquals(1, staff.received.size)

        Files.writeString(folder.resolve("config.yml"), "teamchat:\n  enabled: false\n")
        core.reload()
        core.commands.teamChat(sender, listOf("off"))
        assertEquals(1, staff.received.size)
        assertTrue(sender.messages.last().contains("disabled"))
    }

    @Test
    fun `team members see other team members join and leave, nobody else does`() {
        val core = core()
        val lead = chatter("Lead", setOf(Permissions.TEAMCHAT))
        val normal = chatter("Normal")
        val newcomer = FakePlayer("Newcomer", setOf(Permissions.TEAMCHAT))
        val player = FakePlayer("Player")

        platform.players += newcomer
        core.teamAlerts.joined(newcomer)
        assertTrue(lead.player.received.single().contains("Newcomer"))
        assertTrue(normal.player.received.isEmpty())
        assertTrue(newcomer.received.isEmpty())

        core.teamAlerts.joined(player)
        core.teamAlerts.left(player)
        assertEquals(1, lead.player.received.size)

        core.teamAlerts.left(newcomer)
        assertEquals(2, lead.player.received.size)
        core.teamAlerts.left(newcomer)
        assertEquals(2, lead.player.received.size, "a second leave must not be announced")
    }

    @Test
    fun `team alerts follow the config switch`() {
        val core = core("teamchat:\n  alerts: false\n")
        val lead = chatter("Lead", setOf(Permissions.TEAMCHAT))
        core.teamAlerts.joined(FakePlayer("Other", setOf(Permissions.TEAMCHAT)))
        assertTrue(lead.player.received.isEmpty())
    }
}
