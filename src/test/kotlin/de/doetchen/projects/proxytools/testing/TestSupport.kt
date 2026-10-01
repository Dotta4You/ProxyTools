package de.doetchen.projects.proxytools.testing

import de.doetchen.projects.proxytools.core.CommandActor
import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.Platform
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.ScheduledTask
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest

internal open class FakePlayer(
    override val name: String,
    private val permissions: Set<String> = emptySet(),
    override val uniqueId: UUID = UUID.randomUUID(),
    private val knownServers: Set<String> = setOf("lobby"),
) : PlatformPlayer {
    var kickedWith: String? = null
    var redirectedTo: String? = null
    val received = mutableListOf<String>()

    override fun hasPermission(permission: String) = permission in permissions
    override fun disconnect(message: String) {
        kickedWith = message
    }
    override fun sendMessage(message: String) {
        received += message
    }
    override fun redirectTo(serverName: String): Boolean {
        if (serverName !in knownServers) return false
        redirectedTo = serverName
        return true
    }
}

internal class FakeActor(
    private val permissions: Set<String> = emptySet(),
    override val name: String = "Tester",
    override val serverName: String? = null,
    override val uniqueId: UUID? = UUID.randomUUID(),
    private val knownServers: Set<String> = setOf("lobby"),
) : CommandActor {
    val messages = mutableListOf<String>()
    var connectedTo: String? = null

    override fun connectTo(serverName: String): Boolean {
        if (serverName !in knownServers) return false
        connectedTo = serverName
        return true
    }

    override fun hasPermission(permission: String) = permission in permissions
    val urls = mutableListOf<String?>()
    override fun sendMessage(message: String, openUrl: String?) {
        messages += message
        urls += openUrl
    }
}

internal class FakePlatform(override val dataFolder: Path) : Platform {
    override val platformName = "Test"
    override val pluginVersion = "0.0.0"
    override val proxyName = "TestProxy"
    override val proxyVersion = "1.0"
    override val configuredMaxPlayers = 100
    val players = mutableListOf<FakePlayer>()
    val loggedInfo = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val servers = mutableSetOf("lobby")
    override val onlinePlayers: Collection<PlatformPlayer> get() = players.toList()
    override val onlineCount: Int get() = players.size
    override fun findPlayer(name: String): PlatformPlayer? = players.firstOrNull { it.name.equals(name, ignoreCase = true) }
    override fun findPlayer(id: UUID): PlatformPlayer? = players.firstOrNull { it.uniqueId == id }

    var clock = 0L
    private data class Pending(val fireAt: Long, val task: () -> Unit, val cancelled: BooleanArray)
    private val scheduled = mutableListOf<Pending>()

    override fun info(message: String) {
        loggedInfo += message
    }
    override fun warn(message: String) {
        warnings += message
    }
    override fun hasServer(name: String) = name in servers
    val serverPlayers = mutableMapOf<String, Int>()
    override fun playerCount(serverName: String) = serverPlayers[serverName] ?: 0
    override fun now(): Long = clock

    override fun runLater(delayMillis: Long, task: () -> Unit): ScheduledTask {
        val cancelled = booleanArrayOf(false)
        scheduled += Pending(clock + delayMillis, task, cancelled)
        return ScheduledTask { cancelled[0] = true }
    }

    fun advance(byMillis: Long) {
        clock += byMillis
        val due = scheduled.filter { it.fireAt <= clock }
        scheduled.removeAll(due)
        due.filterNot { it.cancelled[0] }.sortedBy { it.fireAt }.forEach { it.task() }
    }
}

internal class Chatter(val actor: FakeActor, val player: FakePlayer)

internal abstract class CoreTestBase {
    protected val folder: Path = Files.createTempDirectory("proxytools-test")
    protected val platform = FakePlatform(folder)
    private val cores = mutableListOf<ProxyToolsCore>()

    protected fun core(config: String? = null): ProxyToolsCore {
        if (config != null) Files.writeString(folder.resolve("config.yml"), config)
        return ProxyToolsCore(platform).also { cores += it }
    }

    protected fun admin() = FakeActor(setOf(Permissions.MAINTENANCE))

    protected fun chatter(name: String, permissions: Set<String> = emptySet()): Chatter {
        val id = UUID.randomUUID()
        val player = FakePlayer(name, permissions, id)
        platform.players += player
        return Chatter(FakeActor(permissions, name, "lobby", id), player)
    }

    @AfterTest
    fun shutdownCores() {
        cores.forEach { it.shutdown() }
    }
}
