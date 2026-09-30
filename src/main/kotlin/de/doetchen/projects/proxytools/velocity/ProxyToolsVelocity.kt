package de.doetchen.projects.proxytools.velocity

import com.google.inject.Inject
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerPing
import com.velocitypowered.api.util.Favicon
import de.doetchen.projects.proxytools.core.CommandActor
import de.doetchen.projects.proxytools.core.FaviconConverter
import de.doetchen.projects.proxytools.core.MotdService
import de.doetchen.projects.proxytools.core.Platform
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bstats.charts.SimplePie
import org.bstats.velocity.Metrics
import org.slf4j.Logger
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

private const val BSTATS_PLUGIN_ID = 34377

@Plugin(id = "proxytools", name = "ProxyTools")
class ProxyToolsVelocity @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDirectory: Path,
    private val metricsFactory: Metrics.Factory,
) {
    private lateinit var core: ProxyToolsCore

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        try {
            val version = server.pluginManager.getPlugin("proxytools")
                .flatMap { it.description.version }
                .orElse("unknown")
            core = ProxyToolsCore(VelocityPlatform(server, logger, dataDirectory, version, this))

            server.eventManager.register(this, VelocityListener(core, server))
            register("maintenance", core.commands::maintenance, core.commands::suggestMaintenance)
            register("proxytools", core.commands::proxyTools, core.commands::suggestProxyTools, "pt")
            register("broadcast", core.commands::broadcast, core.commands::noSuggestions, "bc")
            register("teamchat", core.commands::teamChat, core.commands::noSuggestions, "tc")
            val metrics = metricsFactory.make(this, BSTATS_PLUGIN_ID)
            core.metricCharts.forEach { (id, value) -> metrics.addCustomChart(SimplePie(id, value)) }
        } catch (e: Exception) {
            logger.error("ProxyTools failed to start, disabling", e)
            server.eventManager.unregisterListeners(this)
            listOf("maintenance", "proxytools", "pt", "broadcast", "bc", "teamchat", "tc")
                .forEach { server.commandManager.unregister(it) }
        }
    }

    private fun register(
        name: String,
        executor: (CommandActor, List<String>) -> Unit,
        completer: (CommandActor, List<String>) -> List<String>,
        vararg aliases: String,
    ) {
        val meta = server.commandManager.metaBuilder(name).aliases(*aliases).plugin(this).build()
        server.commandManager.register(meta, VelocityCommand(executor, completer))
    }
}

private val LEGACY = LegacyComponentSerializer.legacySection()

private fun component(legacyText: String): Component = LEGACY.deserialize(legacyText)

private class VelocityPlatform(
    private val server: ProxyServer,
    private val logger: Logger,
    override val dataFolder: Path,
    override val pluginVersion: String,
    private val plugin: Any,
) : Platform {
    override val platformName = "Velocity"
    override val proxyName: String get() = server.version.name
    override val proxyVersion: String get() = server.version.version
    override val onlinePlayers get() = server.allPlayers.map { VelocityPlayer(it, server) }
    override val configuredMaxPlayers: Int get() = server.configuration.showMaxPlayers

    override fun hasServer(name: String) = server.getServer(name).isPresent

    override fun info(message: String) = logger.info(message)
    override fun warn(message: String) = logger.warn(message)
    override fun now(): Long = System.currentTimeMillis()

    override fun runLater(delayMillis: Long, task: () -> Unit): ScheduledTask {
        val handle = server.scheduler.buildTask(plugin, Runnable { task() }).delay(delayMillis, TimeUnit.MILLISECONDS).schedule()
        return ScheduledTask { handle.cancel() }
    }
}

private class VelocityPlayer(private val player: Player, private val server: ProxyServer) : PlatformPlayer {
    override val uniqueId: UUID get() = player.uniqueId
    override val name: String get() = player.username

    override fun hasPermission(permission: String) = player.hasPermission(permission)
    override fun disconnect(message: String) = player.disconnect(component(message))
    override fun sendMessage(message: String) = player.sendMessage(component(message))

    override fun redirectTo(serverName: String): Boolean {
        val target = server.getServer(serverName).orElse(null) ?: return false
        player.createConnectionRequest(target).connect()
        return true
    }
}

private class VelocityActor(private val source: CommandSource) : CommandActor {
    override val name: String get() = (source as? Player)?.username ?: "Console"
    override val serverName: String? get() = (source as? Player)?.currentServer?.orElse(null)?.serverInfo?.name
    override fun hasPermission(permission: String) = source.hasPermission(permission)
    override fun sendMessage(message: String) = source.sendMessage(component(message))
}

private class VelocityCommand(
    private val executor: (CommandActor, List<String>) -> Unit,
    private val completer: (CommandActor, List<String>) -> List<String>,
) : SimpleCommand {
    override fun hasPermission(invocation: SimpleCommand.Invocation) = true

    override fun execute(invocation: SimpleCommand.Invocation) =
        executor(VelocityActor(invocation.source()), invocation.arguments().toList())

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> =
        completer(VelocityActor(invocation.source()), invocation.arguments().toList())
}

class VelocityListener(private val core: ProxyToolsCore, private val server: ProxyServer) {
    private val favicons = FaviconConverter(core.platform::warn) { Favicon.create(ImageIO.read(ByteArrayInputStream(it))) }

    @Subscribe
    fun onPing(event: ProxyPingEvent) {
        val ping = event.ping
        val players = ping.players.orElse(null)
        val override = core.motd.build(players?.online ?: 0, players?.max ?: 0) ?: return

        val builder = ping.asBuilder()
        override.description?.let { builder.description(component(it)) }
        override.maxPlayers?.let { builder.maximumPlayers(it) }
        override.hoverLines?.let { lines ->
            builder.clearSamplePlayers()
            builder.samplePlayers(*lines.map { ServerPing.SamplePlayer(it, MotdService.HOVER_UUID) }.toTypedArray())
        }
        override.versionName?.let { builder.version(ServerPing.Version(-1, it)) }
        favicons.get(override.faviconBytes)?.let { builder.favicon(it) }
        event.ping = builder.build()
    }

    @Subscribe
    fun onLogin(event: LoginEvent) {
        core.loginDenial(VelocityPlayer(event.player, server))?.let {
            event.result = ResultedEvent.ComponentResult.denied(component(it))
        }
    }

    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        if (event.previousServer == null) core.maintenance.notifyBypass(VelocityPlayer(event.player, server))
    }

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        if (!core.maintenance.enabled) return
        val targetName = core.maintenance.redirectTarget() ?: return
        val player = VelocityPlayer(event.player, server)
        if (core.maintenance.canBypass(player)) return

        val redirect = server.getServer(targetName).orElse(null)
        if (redirect == null) {
            core.platform.warn("maintenance.redirect-server '$targetName' does not exist; kicking instead.")
            event.player.disconnect(component(core.maintenance.kickMessage()))
            event.result = ServerPreConnectEvent.ServerResult.denied()
            return
        }
        if (event.originalServer != redirect) player.sendMessage(core.maintenance.redirectMessage())
        event.result = ServerPreConnectEvent.ServerResult.allowed(redirect)
    }
}
