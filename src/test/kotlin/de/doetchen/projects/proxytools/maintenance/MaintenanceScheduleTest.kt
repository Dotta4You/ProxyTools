package de.doetchen.projects.proxytools.maintenance

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.maintenance.ScheduleResult
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MaintenanceScheduleTest : CoreTestBase() {
    @Test
    fun `scheduled maintenance starts automatically and runs for the given duration`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("schedule", "10m", "5m"))
        assertTrue(admin.messages.last().contains("10m"))
        assertFalse(core.maintenance.enabled)

        platform.advance(10 * 60_000 - 1)
        assertFalse(core.maintenance.enabled)
        platform.advance(1)
        assertTrue(core.maintenance.enabled, "should have started automatically")

        platform.advance(5 * 60_000 - 1)
        assertTrue(core.maintenance.enabled)
        platform.advance(1)
        assertFalse(core.maintenance.enabled, "should have ended automatically after its own duration")
    }

    @Test
    fun `scheduling refuses a second window and refuses while already active`() {
        val core = core()
        assertEquals(ScheduleResult.Scheduled, core.maintenance.scheduleStart(60_000, 60_000))
        assertEquals(ScheduleResult.AlreadyScheduled, core.maintenance.scheduleStart(30_000, 30_000))

        assertTrue(core.maintenance.cancelSchedule())
        assertFalse(core.maintenance.cancelSchedule(), "cancelling twice reports nothing was pending")

        core.maintenance.setEnabled(true)
        assertEquals(ScheduleResult.AlreadyActive, core.maintenance.scheduleStart(60_000, 60_000))
    }

    @Test
    fun `cancelling a schedule prevents it from starting`() {
        val core = core()
        core.maintenance.scheduleStart(60_000, 60_000)
        assertTrue(core.maintenance.cancelSchedule())
        platform.advance(120_000)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `a pending schedule survives a restart`() {
        core().maintenance.scheduleStart(60_000, 30_000)
        val restarted = core()
        assertFalse(restarted.maintenance.enabled)
        platform.advance(60_000)
        assertTrue(restarted.maintenance.enabled)
    }

    @Test
    fun `status reflects a pending schedule`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.maintenance.scheduleStart(60_000, 30_000)
        core.commands.maintenance(admin, listOf("status"))
        assertTrue(admin.messages.single().contains("1m"))
    }

    @Test
    fun `starting maintenance manually cancels a pending schedule instead of leaving it dangling`() {
        val core = core()
        core.maintenance.scheduleStart(60_000, 30_000)
        core.maintenance.setEnabled(true)
        assertTrue(core.maintenance.describeSchedule().contains("no window"), "the superseded schedule must not linger in status")

        platform.advance(120_000)
        assertTrue(core.maintenance.enabled, "should still be indefinitely on, unaffected by the old schedule")
    }

    @Test
    fun `starting maintenance with a duration cancels a pending schedule too`() {
        val core = core()
        core.maintenance.scheduleStart(60_000, 30_000)
        core.commands.maintenance(FakeActor(setOf(Permissions.MAINTENANCE)), listOf("on", "5m"))

        platform.advance(60_000)
        assertTrue(core.maintenance.enabled, "manual timer must still be running")
        platform.advance(4 * 60_000)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `a schedule can be set again after maintenance that superseded it ends`() {
        val core = core()
        core.maintenance.scheduleStart(60_000, 30_000)
        core.maintenance.setEnabled(true)
        core.maintenance.setEnabled(false)
        assertEquals(ScheduleResult.Scheduled, core.maintenance.scheduleStart(10_000, 10_000))
    }
}
