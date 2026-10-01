package de.doetchen.projects.proxytools.core.player

import de.doetchen.projects.proxytools.core.ProxyToolsCore
import java.util.UUID

internal class LastServerService(private val core: ProxyToolsCore) {
    val enabled: Boolean get() = core.config.boolean("remember-last-server.enabled")

    fun target(id: UUID): String? {
        if (!enabled) return null
        return core.playerData.lastServer(id)?.takeIf(core.platform::hasServer)
    }

    fun record(id: UUID, server: String) {
        if (!enabled) return
        if (core.config.stringList("remember-last-server.exclude").any { it.equals(server, ignoreCase = true) }) return
        if (core.maintenance.enabled && server.equals(core.maintenance.redirectTarget(), ignoreCase = true)) return
        core.playerData.setLastServer(id, server)
    }
}
