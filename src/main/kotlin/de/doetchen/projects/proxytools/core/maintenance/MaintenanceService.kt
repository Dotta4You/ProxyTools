package de.doetchen.projects.proxytools.core.maintenance

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.ScheduledTask
import de.doetchen.projects.proxytools.core.text.DurationText
import java.util.concurrent.CopyOnWriteArrayList

internal sealed class ToggleResult {
    data class Changed(val kicked: Int) : ToggleResult()
    data object Unchanged : ToggleResult()
}

internal sealed class TimerResult {
    data class Started(val kicked: Int) : TimerResult()
    data object AlreadyRunning : TimerResult()
}

internal sealed class ScheduleResult {
    data object Scheduled : ScheduleResult()
    data object AlreadyScheduled : ScheduleResult()
    data object AlreadyActive : ScheduleResult()
}

internal class MaintenanceService(private val core: ProxyToolsCore) {
    val enabled: Boolean get() = core.store.enabled

    private val endTasks = CopyOnWriteArrayList<ScheduledTask>()
    private val startTasks = CopyOnWriteArrayList<ScheduledTask>()

    fun redirectTarget(): String? = core.config.string("maintenance.redirect-server").trim().ifBlank { null }

    fun canBypass(player: PlatformPlayer): Boolean = try {
        core.store.onPlayerSeen(player.uniqueId, player.name)
        player.hasPermission(Permissions.MAINTENANCE_BYPASS) || core.store.isWhitelisted(player.uniqueId)
    } catch (e: Exception) {
        core.platform.warn("Could not check maintenance bypass for ${player.name}, denying it: ${e.message}")
        false
    }

    fun kickMessage(): String = safeMessage("maintenance-kick") {
        core.message("maintenance-kick", "duration" to describeDuration()) + reasonText("\n", "maintenance-kick-reason")
    }

    fun redirectMessage(): String = safeMessage("maintenance-redirect") { core.message("maintenance-redirect") + reasonSuffix() }

    private inline fun safeMessage(key: String, build: () -> String): String = try {
        build()
    } catch (e: Exception) {
        core.platform.warn("Could not build the '$key' message: ${e.message}")
        "§cMaintenance"
    }

    @Synchronized
    fun setEnabled(value: Boolean, reason: String? = null): ToggleResult {
        if (enabled == value) return ToggleResult.Unchanged
        if (value) {
            clearSchedule()
        } else {
            cancel(endTasks)
            core.store.setMaintenanceUntil(null)
        }
        core.store.setEnabled(value, reason.takeIf { value })
        return ToggleResult.Changed(if (value) kickIfConfigured() else 0)
    }

    @Synchronized
    fun enableFor(durationMillis: Long, reason: String? = null): TimerResult {
        require(durationMillis > 0) { "durationMillis must be positive" }
        if (core.store.maintenanceUntil != null) return TimerResult.AlreadyRunning
        clearSchedule()
        core.store.setEnabled(true, reason)
        core.store.setMaintenanceUntil(core.platform.now() + durationMillis)
        val kicked = kickIfConfigured()
        armEndTimer()
        return TimerResult.Started(kicked)
    }

    private fun kickIfConfigured() = if (core.config.boolean("maintenance.kick-on-enable", true)) kickNonBypass() else 0

    fun kickNonBypass(): Int {
        val targets = core.platform.onlinePlayers.filterNot(::canBypass)
        val redirect = redirectTarget()
        val kick = kickMessage()
        val notice by lazy { redirectMessage() }
        targets.forEach { player ->
            if (redirect != null && player.redirectTo(redirect)) player.sendMessage(notice) else player.disconnect(kick)
        }
        return targets.size
    }

    @Synchronized
    fun resumeTimerIfNeeded() {
        val until = core.store.maintenanceUntil ?: return
        when {
            !enabled -> core.store.setMaintenanceUntil(null)
            until <= core.platform.now() -> {
                core.store.setMaintenanceUntil(null)
                core.store.setEnabled(false)
                core.platform.info("Maintenance timer elapsed while offline, maintenance is now disabled.")
            }
            else -> armEndTimer()
        }
    }

    fun describeDuration(): String {
        val until = core.store.maintenanceUntil
        if (until == null || !enabled) return core.message("duration-none")
        return core.message("duration-remaining", "time" to DurationText.format(secondsUntil(until)))
    }

    fun reasonSuffix(): String = reasonText("", "reason-suffix")

    private fun reasonText(separator: String, key: String): String =
        core.store.maintenanceReason?.let { separator + core.message(key, "reason" to it) }.orEmpty()

    fun loginDenial(player: PlatformPlayer): String? =
        if (enabled && redirectTarget() == null && !canBypass(player)) kickMessage() else null

    fun notifyBypass(player: PlatformPlayer) {
        if (enabled && canBypass(player)) player.sendMessage(core.message("maintenance-bypass") + reasonSuffix())
    }

    @Synchronized
    fun scheduleStart(delayMillis: Long, durationMillis: Long, reason: String? = null): ScheduleResult {
        require(delayMillis > 0 && durationMillis > 0) { "delayMillis and durationMillis must be positive" }
        if (enabled) return ScheduleResult.AlreadyActive
        if (core.store.scheduledStart != null) return ScheduleResult.AlreadyScheduled
        core.store.setSchedule(core.platform.now() + delayMillis, durationMillis, reason)
        armStartTimer()
        return ScheduleResult.Scheduled
    }

    @Synchronized
    fun cancelSchedule(): Boolean {
        if (core.store.scheduledStart == null) return false
        clearSchedule()
        return true
    }

    fun describeSchedule(): String {
        val start = core.store.scheduledStart
        val duration = core.store.scheduledDuration
        if (enabled || start == null || duration == null) return core.message("schedule-none")
        return core.message(
            "schedule-pending",
            "delay" to DurationText.format(secondsUntil(start)),
            "duration" to DurationText.format(duration / 1000),
        )
    }

    @Synchronized
    fun resumeScheduleIfNeeded() {
        val start = core.store.scheduledStart ?: return
        val duration = core.store.scheduledDuration ?: return
        when {
            enabled -> core.store.setSchedule(null, null)
            start <= core.platform.now() -> {
                val reason = core.store.scheduledReason
                core.store.setSchedule(null, null)
                core.platform.info("A scheduled maintenance window was reached while offline; starting it now.")
                enableFor(duration, reason)
            }
            else -> armStartTimer()
        }
    }

    private fun secondsUntil(targetMillis: Long) = ((targetMillis - core.platform.now()) / 1000).coerceAtLeast(0)

    private fun clearSchedule() {
        if (core.store.scheduledStart == null && core.store.scheduledDuration == null) return
        cancel(startTasks)
        core.store.setSchedule(null, null)
    }

    private fun armEndTimer() {
        val until = core.store.maintenanceUntil ?: return
        countdown(until, endTasks, "maintenance.timer-warnings", ::broadcastWarning, ::onTimerElapsed)
    }

    private fun armStartTimer() {
        val start = core.store.scheduledStart ?: return
        countdown(start, startTasks, "maintenance.schedule-warnings", ::broadcastScheduleWarning, ::onScheduledStart)
    }

    private fun countdown(
        targetMillis: Long,
        tasks: CopyOnWriteArrayList<ScheduledTask>,
        warningsConfigKey: String,
        onWarning: (secondsLeft: Long) -> Unit,
        onDue: () -> Unit,
    ) {
        val remaining = targetMillis - core.platform.now()
        if (remaining <= 0) return
        core.config.intList(warningsConfigKey).filter { it > 0 }.distinct().forEach { warnBeforeSeconds ->
            val delay = remaining - warnBeforeSeconds * 1000L
            if (delay > 0) tasks += core.platform.runLater(delay) { onWarning(warnBeforeSeconds.toLong()) }
        }
        tasks += core.platform.runLater(remaining) { onDue() }
    }

    private fun cancel(tasks: CopyOnWriteArrayList<ScheduledTask>) {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    private fun broadcastWarning(secondsLeft: Long) {
        if (!enabled) return
        core.broadcast(core.message("timer-warning", "time" to DurationText.format(secondsLeft)))
    }

    @Synchronized
    private fun onTimerElapsed() {
        endTasks.clear()
        if (!enabled) return
        core.store.setMaintenanceUntil(null)
        core.store.setEnabled(false)
        core.broadcast(core.message("timer-ended"))
    }

    private fun broadcastScheduleWarning(secondsLeft: Long) {
        if (enabled || core.store.scheduledStart == null) return
        core.broadcast(core.message("schedule-warning", "time" to DurationText.format(secondsLeft)))
    }

    @Synchronized
    private fun onScheduledStart() {
        startTasks.clear()
        val duration = core.store.scheduledDuration
        if (enabled || core.store.scheduledStart == null || duration == null) return
        val reason = core.store.scheduledReason
        core.store.setSchedule(null, null)
        enableFor(duration, reason)
    }
}
