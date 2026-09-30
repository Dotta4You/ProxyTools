package de.doetchen.projects.proxytools.core

import java.nio.file.Path
import java.util.UUID

interface Platform {
    val platformName: String
    val pluginVersion: String
    val proxyName: String
    val proxyVersion: String
    val dataFolder: Path
    val onlinePlayers: Collection<PlatformPlayer>

    val configuredMaxPlayers: Int

    fun hasServer(name: String): Boolean

    fun info(message: String)
    fun warn(message: String)

    fun now(): Long

    fun runLater(delayMillis: Long, task: () -> Unit): ScheduledTask
}

fun interface ScheduledTask {
    fun cancel()
}

interface PlatformPlayer {
    val uniqueId: UUID
    val name: String

    fun hasPermission(permission: String): Boolean

    fun disconnect(message: String)

    fun sendMessage(message: String)

    fun redirectTo(serverName: String): Boolean
}

interface CommandActor {
    val name: String
    val serverName: String?

    fun hasPermission(permission: String): Boolean

    fun sendMessage(message: String)
}

object Permissions {
    const val MAINTENANCE = "proxytools.maintenance"
    const val MAINTENANCE_STATUS = "proxytools.maintenance.status"
    const val MAINTENANCE_WHITELIST = "proxytools.maintenance.whitelist"
    const val MAINTENANCE_BYPASS = "proxytools.maintenance.bypass"
    const val SLOTS_RESERVED = "proxytools.slots.reserved"
    const val SLOTS_BYPASS = "proxytools.slots.bypass"
    const val RELOAD = "proxytools.reload"
    const val BROADCAST = "proxytools.broadcast"
    const val TEAMCHAT = "proxytools.teamchat"
}
