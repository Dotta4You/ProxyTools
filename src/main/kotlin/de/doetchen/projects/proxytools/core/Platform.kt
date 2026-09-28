package de.doetchen.projects.proxytools.core

import java.nio.file.Path
import java.util.UUID

/** Everything the core needs from the proxy it runs on. Implemented once for BungeeCord and once for Velocity. */
interface Platform {
    val platformName: String
    val pluginVersion: String
    val dataFolder: Path
    val onlinePlayers: Collection<PlatformPlayer>

    fun info(message: String)
    fun warn(message: String)
}

interface PlatformPlayer {
    val uniqueId: UUID
    val name: String

    fun hasPermission(permission: String): Boolean

    /** [message] uses legacy section-sign formatting (see [Text.colorize]). */
    fun disconnect(message: String)
}

/** A command sender (player or console). */
interface CommandActor {
    fun hasPermission(permission: String): Boolean

    /** [message] uses legacy section-sign formatting (see [Text.colorize]). */
    fun sendMessage(message: String)
}

object Permissions {
    const val MAINTENANCE = "proxytools.maintenance"
    const val MAINTENANCE_WHITELIST = "proxytools.maintenance.whitelist"
    const val MAINTENANCE_BYPASS = "proxytools.maintenance.bypass"
    const val RELOAD = "proxytools.reload"
}
