package de.doetchen.projects.proxytools.core.player

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore

internal class SlotService(private val core: ProxyToolsCore) {
    val enabled: Boolean get() = core.config.boolean("slots.enabled")

    fun limit(): Int = core.config.int("slots.max-players", 100).coerceAtLeast(1)

    fun reserved(): Int = core.config.int("slots.reserved", 0).coerceIn(0, limit())

    fun denial(player: PlatformPlayer): String? {
        if (!enabled || player.hasPermission(Permissions.SLOTS_BYPASS)) return null
        val others = core.platform.onlineCount - if (core.platform.findPlayer(player.uniqueId) != null) 1 else 0
        val key = when {
            others >= limit() -> "slots-full"
            others >= limit() - reserved() && !player.hasPermission(Permissions.SLOTS_RESERVED) -> "slots-reserved"
            else -> return null
        }
        return core.message(key)
    }
}
