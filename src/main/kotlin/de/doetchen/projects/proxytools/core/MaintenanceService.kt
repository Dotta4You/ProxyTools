package de.doetchen.projects.proxytools.core

sealed class ToggleResult {
    data class Changed(val kicked: Int) : ToggleResult()
    data object Unchanged : ToggleResult()
}

class MaintenanceService(private val core: ProxyToolsCore) {
    val enabled: Boolean get() = core.store.enabled

    fun canBypass(player: PlatformPlayer): Boolean {
        core.store.onPlayerSeen(player.uniqueId, player.name)
        return player.hasPermission(Permissions.MAINTENANCE_BYPASS) || core.store.isWhitelisted(player.uniqueId)
    }

    fun kickMessage(): String = core.message("maintenance-kick")

    /** @return [ToggleResult.Unchanged] if maintenance already had the requested state. */
    fun setEnabled(value: Boolean): ToggleResult {
        if (enabled == value) return ToggleResult.Unchanged
        core.store.setEnabled(value)
        val kicked = if (value && core.config.boolean("maintenance.kick-on-enable", true)) kickNonBypass() else 0
        return ToggleResult.Changed(kicked)
    }

    fun kickNonBypass(): Int {
        val message = kickMessage()
        val targets = core.platform.onlinePlayers.filterNot(::canBypass)
        targets.forEach { it.disconnect(message) }
        return targets.size
    }
}
