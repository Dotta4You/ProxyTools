package de.doetchen.projects.proxytools.core.chat

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class TeamAlertService(private val core: ProxyToolsCore) {
    private val announced = ConcurrentHashMap.newKeySet<UUID>()

    private val enabled: Boolean
        get() = core.config.boolean("teamchat.enabled", true) && core.config.boolean("teamchat.alerts", true)

    fun joined(player: PlatformPlayer) {
        if (!player.hasPermission(Permissions.TEAMCHAT)) return
        announced += player.uniqueId
        if (enabled) notifyTeam("team-joined", player)
    }

    fun left(player: PlatformPlayer) {
        if (!announced.remove(player.uniqueId)) return
        if (enabled) notifyTeam("team-left", player)
    }

    private fun notifyTeam(key: String, player: PlatformPlayer) {
        val text = core.message(key, "player" to player.name)
        core.platform.onlinePlayers
            .filter { it.uniqueId != player.uniqueId && it.hasPermission(Permissions.TEAMCHAT) }
            .forEach { it.sendMessage(text) }
    }
}
