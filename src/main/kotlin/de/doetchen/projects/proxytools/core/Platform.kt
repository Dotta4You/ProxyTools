package de.doetchen.projects.proxytools.core

import java.nio.file.Path
import java.util.UUID

internal interface Platform {
    val platformName: String
    val pluginVersion: String
    val proxyName: String
    val proxyVersion: String
    val dataFolder: Path
    val onlinePlayers: Collection<PlatformPlayer>
    val onlineCount: Int

    val configuredMaxPlayers: Int

    fun findPlayer(name: String): PlatformPlayer?

    fun findPlayer(id: UUID): PlatformPlayer?

    fun hasServer(name: String): Boolean

    fun playerCount(serverName: String): Int

    fun info(message: String)
    fun warn(message: String)

    fun now(): Long

    fun runLater(delayMillis: Long, task: () -> Unit): ScheduledTask
}

internal fun interface ScheduledTask {
    fun cancel()
}

internal interface PlatformPlayer {
    val uniqueId: UUID
    val name: String

    fun hasPermission(permission: String): Boolean

    fun disconnect(message: String)

    fun sendMessage(message: String)

    fun redirectTo(serverName: String): Boolean
}

internal interface CommandActor {
    val name: String
    val serverName: String?
    val uniqueId: UUID?
    val isPlayer: Boolean get() = uniqueId != null

    fun connectTo(serverName: String): Boolean

    fun hasPermission(permission: String): Boolean

    fun sendMessage(message: String, openUrl: String? = null)
}
