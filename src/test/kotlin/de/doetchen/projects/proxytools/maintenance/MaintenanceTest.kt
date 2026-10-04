package de.doetchen.projects.proxytools.maintenance

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.maintenance.TimerResult
import de.doetchen.projects.proxytools.core.maintenance.ToggleResult
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import de.doetchen.projects.proxytools.testing.FakePlayer
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MaintenanceTest : CoreTestBase() {
    @Test
    fun `enabling maintenance kicks everybody without bypass and reports the count`() {
        val core = core()
        val normal = FakePlayer("Normal")
        val staff = FakePlayer("Staff", setOf(Permissions.MAINTENANCE_BYPASS))
        val listed = FakePlayer("Listed")
        platform.players += listOf(normal, staff, listed)
        core.store.addResolved(listed.uniqueId, "Listed")

        val result = core.maintenance.setEnabled(true) as ToggleResult.Changed
        assertEquals(1, result.kicked)
        assertTrue(core.maintenance.setEnabled(true) is ToggleResult.Unchanged)

        assertNotNull(normal.kickedWith)
        assertNull(staff.kickedWith)
        assertNull(listed.kickedWith)
    }

    @Test
    fun `state survives a restart`() {
        val uuid = UUID.randomUUID()
        core().apply {
            store.addResolved(uuid, "alice")
            store.addPending("bob")
            maintenance.setEnabled(true)
        }
        val restarted = core()
        assertTrue(restarted.maintenance.enabled)
        assertTrue(restarted.store.isWhitelisted(uuid))
        assertEquals(setOf("alice", "bob"), restarted.store.entries().map { it.name }.toSet())
    }

    @Test
    fun `maintenance timer auto-disables after the duration and reports it`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("on", "5s"))
        assertTrue(admin.messages.last().contains("5s"))
        assertTrue(core.maintenance.enabled)

        platform.advance(4_999)
        assertTrue(core.maintenance.enabled)
        platform.advance(1)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `enableFor rejects a non-positive duration`() {
        assertFailsWith<IllegalArgumentException> { core().maintenance.enableFor(0) }
    }

    @Test
    fun `enableFor refuses to replace an already-running timer`() {
        val core = core()
        assertTrue(core.maintenance.enableFor(60_000) is TimerResult.Started)
        assertEquals(TimerResult.AlreadyRunning, core.maintenance.enableFor(30_000))

        platform.advance(59_999)
        assertTrue(core.maintenance.enabled, "the second call must not have shortened the original timer")
    }

    @Test
    fun `timer-warnings ignores non-positive and duplicate entries instead of misfiring`() {
        val core = core("maintenance:\n  timer-warnings: [5, 5, -1, 0]\n")
        val staff = FakePlayer("Staff", setOf(Permissions.MAINTENANCE_BYPASS))
        platform.players += staff

        core.maintenance.enableFor(10_000)
        platform.advance(5_000)
        assertEquals(1, staff.received.count { it.contains("5s") }, "the duplicate 5 must not fire twice")
        platform.advance(5_000)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `manually turning maintenance off cancels a pending timer`() {
        val core = core()
        core.maintenance.enableFor(5_000)
        core.maintenance.setEnabled(false)
        platform.advance(10_000)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `timer warnings are broadcast to online players before it ends`() {
        val core = core("maintenance:\n  timer-warnings: [5]\n")
        val staff = FakePlayer("Staff", setOf(Permissions.MAINTENANCE_BYPASS))
        platform.players += staff

        core.maintenance.enableFor(10_000)
        platform.advance(5_000)
        assertTrue(staff.received.any { it.contains("5s") })
        platform.advance(5_000)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `a running timer resumes after a restart`() {
        core().maintenance.enableFor(60_000)
        val restarted = core()
        assertTrue(restarted.maintenance.enabled)
        platform.advance(59_000)
        assertTrue(restarted.maintenance.enabled)
        platform.advance(1_000)
        assertFalse(restarted.maintenance.enabled)
    }

    @Test
    fun `canBypass fails closed if a permission check throws unexpectedly`() {
        val core = core()
        val broken = object : PlatformPlayer {
            override val uniqueId: UUID = UUID.randomUUID()
            override val name = "Broken"
            override fun hasPermission(permission: String): Boolean = error("boom")
            override fun disconnect(message: String) = Unit
            override fun sendMessage(message: String, openUrl: String?) = Unit
            override fun redirectTo(serverName: String) = false
        }
        assertFalse(core.maintenance.canBypass(broken))
    }

    @Test
    fun `redirect-server sends players to a backend instead of kicking them, by default kicking still applies`() {
        val core = core()
        val redirected = FakePlayer("Redirected")
        platform.players += redirected

        core.maintenance.setEnabled(true)
        assertNotNull(redirected.kickedWith)
        assertNull(redirected.redirectedTo)
        core.maintenance.setEnabled(false)
        redirected.kickedWith = null

        Files.writeString(folder.resolve("config.yml"), "maintenance:\n  redirect-server: lobby\n")
        val reloaded = run { core.reload(); core }
        reloaded.maintenance.setEnabled(true)
        assertEquals("lobby", redirected.redirectedTo)
        assertNull(redirected.kickedWith)
        assertTrue(redirected.received.isNotEmpty(), "should be told why they were moved")
    }

    @Test
    fun `redirect falls back to kicking if the configured server does not exist`() {
        val core = core("maintenance:\n  redirect-server: does-not-exist\n")
        val player = FakePlayer("Solo")
        platform.players += player

        core.maintenance.setEnabled(true)
        assertNotNull(player.kickedWith)
        assertNull(player.redirectedTo)
    }

    @Test
    fun `maintenance reason is shown in the kick screen and status, and cleared when turned off`() {
        val core = core()
        val player = FakePlayer("Normal")
        platform.players += player
        val actor = admin()

        core.commands.maintenance(actor, listOf("on", "Database", "update"))
        assertTrue(player.kickedWith!!.contains("Database update"))
        core.commands.maintenance(actor, listOf("status"))
        assertTrue(actor.messages.last().contains("Database update"))

        core.commands.maintenance(actor, listOf("off"))
        core.commands.maintenance(actor, listOf("on"))
        assertNull(core.store.maintenanceReason)
        assertFalse(core.maintenance.kickMessage().contains("Reason"))
        assertFalse(core.maintenance.reasonSuffix().isNotEmpty())
    }

    @Test
    fun `the reason survives a restart and is used by a scheduled window`() {
        val core = core()
        core.maintenance.scheduleStart(60_000, 30_000, "Backup")
        platform.advance(60_000)
        assertEquals("Backup", core.store.maintenanceReason)
        assertEquals("Backup", ProxyToolsCore(platform, releases).store.maintenanceReason)
    }

    @Test
    fun `bypass players get a chat notice while maintenance is on`() {
        val core = core()
        val staff = FakePlayer("Staff", setOf(Permissions.MAINTENANCE_BYPASS))
        val normal = FakePlayer("Normal")
        core.maintenance.notifyBypass(staff)
        assertTrue(staff.received.isEmpty())

        core.maintenance.setEnabled(true, "Upgrade")
        core.maintenance.notifyBypass(staff)
        core.maintenance.notifyBypass(normal)
        assertTrue(staff.received.single().contains("Upgrade"))
        assertTrue(normal.received.isEmpty())
    }

    @Test
    fun `login denial covers maintenance only when no redirect server is set`() {
        val core = core()
        val normal = FakePlayer("Normal")
        assertNull(core.loginDenial(normal))
        core.maintenance.setEnabled(true)
        assertNotNull(core.loginDenial(normal))

        Files.writeString(folder.resolve("config.yml"), "maintenance:\n  redirect-server: lobby\n")
        core.reload()
        assertNull(core.loginDenial(normal))
    }

    @Test
    fun `an unknown redirect server is reported on start and reload`() {
        val core = core("maintenance:\n  redirect-server: nowhere\n")
        assertTrue(platform.warnings.any { it.contains("nowhere") })

        platform.warnings.clear()
        Files.writeString(folder.resolve("config.yml"), "maintenance:\n  redirect-server: lobby\n")
        core.reload()
        assertTrue(platform.warnings.none { it.contains("redirect-server") })
    }

    @Test
    fun `a damaged maintenance file is set aside instead of being overwritten`() {
        Files.createDirectories(folder.resolve("data"))
        Files.writeString(folder.resolve("data/maintenance.yml"), "whitelist-resolved: [this is: not valid\n")

        val core = core()

        assertTrue(platform.warnings.any { it.contains("maintenance.yml") && it.contains("renamed") })
        assertFalse(core.maintenance.enabled)
        assertTrue(Files.exists(folder.resolve("data/maintenance.yml.broken")))
        core.maintenance.setEnabled(true)
        assertTrue(Files.readString(folder.resolve("data/maintenance.yml.broken")).contains("this is: not valid"))
        assertTrue(Files.readString(folder.resolve("data/maintenance.yml")).contains("maintenance: true"))
    }
}
