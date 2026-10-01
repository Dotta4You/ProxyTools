package de.doetchen.projects.proxytools.command

import de.doetchen.projects.proxytools.core.CommandActor
import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import de.doetchen.projects.proxytools.testing.FakePlayer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class CommandTest : CoreTestBase() {
    @Test
    fun `maintenance command checks permissions`() {
        val core = core()
        val nobody = FakeActor()
        core.commands.maintenance(nobody, listOf("on"))
        assertFalse(core.maintenance.enabled)
        assertTrue(nobody.messages.single().contains("permission"))

        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("on"))
        assertTrue(core.maintenance.enabled)
        core.commands.maintenance(admin, listOf("whitelist", "add", "x"))
        assertTrue(admin.messages.last().contains("permission"), "whitelist needs its own permission")
    }

    @Test
    fun `tab completion respects permissions and prefix`() {
        val core = core()
        platform.players += FakePlayer("Alice")
        val admin = FakeActor(setOf(Permissions.MAINTENANCE, Permissions.MAINTENANCE_WHITELIST))
        assertEquals(listOf("on", "off"), core.commands.suggestMaintenance(admin, listOf("o")))
        assertEquals(listOf("whitelist"), core.commands.suggestMaintenance(FakeActor(setOf(Permissions.MAINTENANCE_WHITELIST)), listOf("")))
        assertEquals(listOf("add", "remove", "list"), core.commands.suggestMaintenance(admin, listOf("whitelist", "")))
        assertEquals(listOf("Alice"), core.commands.suggestMaintenance(admin, listOf("whitelist", "add", "al")))
    }

    @Test
    fun `tab completion offers no duration after schedule cancel`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        assertEquals(listOf("cancel", "10m", "30m", "1h"), core.commands.suggestMaintenance(admin, listOf("schedule", "")))
        assertEquals(listOf("10m", "30m", "1h"), core.commands.suggestMaintenance(admin, listOf("schedule", "10m", "")))
        assertEquals(emptyList(), core.commands.suggestMaintenance(admin, listOf("schedule", "cancel", "")))
    }

    @Test
    fun `reload command`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.RELOAD))
        core.commands.proxyTools(admin, listOf("reload"))
        assertTrue(admin.messages.single().contains("reloaded"))
    }

    @Test
    fun `invalid duration is rejected without changing state`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("on", "10x"))
        assertFalse(core.maintenance.enabled)
        assertTrue(admin.messages.last().contains("10x"))
    }

    @Test
    fun `command rejects setting a new duration while one is already running`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("on", "10m"))
        core.commands.maintenance(admin, listOf("on", "5m"))
        assertTrue(admin.messages.last().contains("10m"), "should report the still-running timer's remaining time")
    }

    @Test
    fun `status permission works independently of the full maintenance permission`() {
        val core = core()
        val viewer = FakeActor(setOf(Permissions.MAINTENANCE_STATUS))
        core.commands.maintenance(viewer, listOf("status"))
        assertTrue(viewer.messages.single().contains("disabled"))

        viewer.messages.clear()
        core.commands.maintenance(viewer, listOf("on"))
        assertFalse(core.maintenance.enabled)
        assertTrue(viewer.messages.single().contains("permission"))
    }

    @Test
    fun `status shows remaining time for a running timer`() {
        val core = core()
        core.maintenance.enableFor(60_000)
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("status"))
        assertTrue(admin.messages.single().contains("1m"))
    }

    @Test
    fun `broadcast requires its own permission and reaches online players`() {
        val core = core()
        val listener = FakePlayer("Listener")
        platform.players += listener

        val nobody = FakeActor()
        core.commands.broadcast(nobody, listOf("hi"))
        assertTrue(nobody.messages.single().contains("permission"))
        assertTrue(listener.received.isEmpty())

        val admin = FakeActor(setOf(Permissions.BROADCAST))
        core.commands.broadcast(admin, listOf("Hello", "everyone"))
        assertTrue(listener.received.single().contains("Hello everyone"))
    }

    @Test
    fun `an argument starting with a digit is the duration, anything else the reason`() {
        val core = core()
        val actor = admin()

        core.commands.maintenance(actor, listOf("on", "1x", "oops"))
        assertFalse(core.maintenance.enabled, "an invalid duration must not enable maintenance")

        core.commands.maintenance(actor, listOf("on", "10m", "Server", "move"))
        assertEquals("Server move", core.store.maintenanceReason)
        assertNotNull(core.store.maintenanceUntil)
    }

    @Test
    fun `a failing command is reported to the sender and the console instead of escaping`() {
        val core = core()
        val messages = mutableListOf<String>()
        val broken = object : CommandActor {
            override val name = "Broken"
            override val serverName: String? = null
            override val uniqueId: UUID? = UUID.randomUUID()
            override fun connectTo(serverName: String) = false
            override fun hasPermission(permission: String): Boolean = error("permission backend is down")
            override fun sendMessage(message: String, openUrl: String?) {
                messages += message
            }
        }
        val maintenance = core.commands.specs().first { it.name == "maintenance" }
        maintenance.execute(broken, listOf("on"))
        assertTrue(platform.warnings.any { it.contains("/maintenance failed") })
        assertTrue(messages.single().contains("went wrong"))
        assertEquals(emptyList(), maintenance.suggest(broken, listOf("")))
    }

    @Test
    fun `every command name and alias is registered exactly once, own info commands included`() {
        val core = core("info-commands:\n  discord:\n    aliases: [dc]\n    text: hi\n")
        val names = core.commands.specs().flatMap { listOf(it.name) + it.aliases }
        assertEquals(names.size, names.toSet().size, "duplicate names: $names")
        listOf("maintenance", "pt", "bc", "tc", "hub", "lobby", "l", "msg", "r", "spy", "ignore", "togglemsg", "discord", "dc")
            .forEach { assertTrue(it in names, "$it is missing") }
    }

    @Test
    fun `console output carries no color codes`() {
        val core = core()
        platform.players += FakePlayer("Staff", setOf(Permissions.TEAMCHAT))
        core.commands.teamChat(FakeActor(setOf(Permissions.TEAMCHAT), "Staff", "lobby"), listOf("&chello"))
        core.commands.broadcast(FakeActor(setOf(Permissions.BROADCAST)), listOf("&aannouncement"))
        val logged = platform.loggedInfo.filter { it.contains("hello") || it.contains("announcement") }
        assertEquals(2, logged.size)
        assertTrue(logged.none { it.contains('§') })
    }
}
