package de.doetchen.projects.proxytools.velocity

import com.google.inject.Inject
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerPing
import com.velocitypowered.api.util.Favicon
import de.doetchen.projects.proxytools.core.CommandActor
import de.doetchen.projects.proxytools.core.MotdService
import de.doetchen.projects.proxytools.core.Platform
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bstats.charts.SimplePie
import org.bstats.velocity.Metrics
import org.slf4j.Logger
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO

// bStats plugin ID for Velocity, see https://bstats.org/plugin/velocity/ProxyTools/34377
private const val BSTATS_PLUGIN_ID = 34377

// metadata lives in velocity-plugin.json, the annotation processor doesn't run for Kotlin
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
        val version = server.pluginManager.getPlugin("proxytools")
            .flatMap { it.description.version }
            .orElse("unknown")
        core = ProxyToolsCore(VelocityPlatform(server, logger, dataDirectory, version))

        server.eventManager.register(this, VelocityListener(core))
        register("maintenance", core.commands::maintenance, core.commands::suggestMaintenance)
        register("proxytools", core.commands::proxyTools, core.commands::suggestProxyTools, "pt")
        setUpMetrics()
    }

    private fun setUpMetrics() {
        if (BSTATS_PLUGIN_ID == 0) return
        val metrics = metricsFactory.make(this, BSTATS_PLUGIN_ID)
        metrics.addCustomChart(SimplePie("language") { core.metricsSnapshot()["language"] })
        metrics.addCustomChart(SimplePie("maintenance_enabled") { core.metricsSnapshot()["maintenance"] })
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
) : Platform {
    override val platformName = "Velocity"
    override val onlinePlayers get() = server.allPlayers.map(::VelocityPlayer)

    override fun info(message: String) = logger.info(message)
    override fun warn(message: String) = logger.warn(message)
}

private class VelocityPlayer(private val player: Player) : PlatformPlayer {
    override val uniqueId: UUID get() = player.uniqueId
    override val name: String get() = player.username

    override fun hasPermission(permission: String) = player.hasPermission(permission)
    override fun disconnect(message: String) = player.disconnect(component(message))
}

private class VelocityActor(private val source: CommandSource) : CommandActor {
    override fun hasPermission(permission: String) = source.hasPermission(permission)
    override fun sendMessage(message: String) = source.sendMessage(component(message))
}

private class VelocityCommand(
    private val executor: (CommandActor, List<String>) -> Unit,
    private val completer: (CommandActor, List<String>) -> List<String>,
) : SimpleCommand {
    // permission checks happen in the core, so unauthorized users still get a proper message
    override fun hasPermission(invocation: SimpleCommand.Invocation) = true

    override fun execute(invocation: SimpleCommand.Invocation) =
        executor(VelocityActor(invocation.source()), invocation.arguments().toList())

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> =
        completer(VelocityActor(invocation.source()), invocation.arguments().toList())
}

class VelocityListener(private val core: ProxyToolsCore) {
    // only rebuilt when the source bytes change, Favicon.create() re-encodes the image
    private var cachedFaviconBytes: ByteArray? = null
    private var cachedFavicon: Favicon? = null

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
        // protocol -1 -> client shows it in red as an incompatible version
        override.versionName?.let { builder.version(ServerPing.Version(-1, it)) }
        favicon(override.faviconBytes)?.let { builder.favicon(it) }
        event.ping = builder.build()
    }

    private fun favicon(bytes: ByteArray?): Favicon? {
        if (bytes == null) {
            cachedFaviconBytes = null
            cachedFavicon = null
            return null
        }
        if (bytes !== cachedFaviconBytes) {
            cachedFavicon = runCatching { Favicon.create(ImageIO.read(ByteArrayInputStream(bytes))) }
                .onFailure { core.platform.warn("Could not read favicon: ${it.message}") }
                .getOrNull()
            cachedFaviconBytes = bytes
        }
        return cachedFavicon
    }

    @Subscribe
    fun onLogin(event: LoginEvent) {
        if (core.maintenance.enabled && !core.maintenance.canBypass(VelocityPlayer(event.player))) {
            event.result = ResultedEvent.ComponentResult.denied(component(core.maintenance.kickMessage()))
        }
    }
}
