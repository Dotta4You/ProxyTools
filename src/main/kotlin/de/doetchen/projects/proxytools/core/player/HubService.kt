package de.doetchen.projects.proxytools.core.player

import de.doetchen.projects.proxytools.core.ProxyToolsCore

internal class HubService(private val core: ProxyToolsCore) {
    val enabled: Boolean get() = core.config.boolean("hub.enabled", true)

    fun servers(): List<String> = core.config.stringList("hub.servers")

    fun isHub(serverName: String?): Boolean = serverName != null && servers().any { it.equals(serverName, ignoreCase = true) }

    fun target(): String? {
        val available = servers().filter(core.platform::hasServer)
        return if (core.config.string("hub.mode", "first") == "least-players") {
            available.minByOrNull(core.platform::playerCount)
        } else {
            available.firstOrNull()
        }
    }
}
