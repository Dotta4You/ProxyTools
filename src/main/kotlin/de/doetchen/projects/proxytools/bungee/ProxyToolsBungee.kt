package de.doetchen.projects.proxytools.bungee

import de.doetchen.projects.proxytools.core.CommandActor
import de.doetchen.projects.proxytools.core.FaviconConverter
import de.doetchen.projects.proxytools.core.MotdService
import de.doetchen.projects.proxytools.core.Platform
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.ScheduledTask
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.Favicon
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.ServerPing
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.ProxyPingEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.plugin.TabExecutor
import net.md_5.bungee.api.scheduler.ScheduledTask as BungeeScheduledTask
import net.md_5.bungee.event.EventHandler
import org.bstats.bungeecord.Metrics
import org.bstats.charts.SimplePie
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

private const val BSTATS_PLUGIN_ID = 34376

class ProxyToolsBungee : Plugin() {
    private lateinit var core: ProxyToolsCore

    override fun onEnable() {
        try {
            core = ProxyToolsCore(BungeePlatform(this))
            proxy.pluginManager.registerListener(this, BungeeListener(core))
            listOf(
                BungeeCommand("maintenance", core.commands::maintenance, core.commands::suggestMaintenance),
                BungeeCommand("proxytools", core.commands::proxyTools, core.commands::suggestProxyTools, "pt"),
                BungeeCommand("broadcast", core.commands::broadcast, core.commands::suggestBroadcast, "bc"),
            ).forEach { proxy.pluginManager.registerCommand(this, it) }
            val metrics = Metrics(this, BSTATS_PLUGIN_ID)
            core.metricCharts.forEach { (id, value) -> metrics.addCustomChart(SimplePie(id, value)) }
        } catch (e: Exception) {
            logger.severe("ProxyTools failed to start, disabling: ${e.message}")
            proxy.pluginManager.unregisterCommands(this)
            proxy.pluginManager.unregisterListeners(this)
        }
    }
}

@Suppress("DEPRECATION")
private fun legacy(text: String): Array<BaseComponent> = TextComponent.fromLegacyText(text)

private class BungeePlatform(private val plugin: Plugin) : Platform {
    override val platformName = "BungeeCord"
    override val pluginVersion: String get() = plugin.description.version
    override val proxyName: String get() = ProxyServer.getInstance().name
    override val proxyVersion: String get() = ProxyServer.getInstance().version
    override val dataFolder: Path get() = plugin.dataFolder.toPath()
    override val onlinePlayers get() = ProxyServer.getInstance().players.map(::BungeePlayer)
    override val configuredMaxPlayers: Int get() = ProxyServer.getInstance().config.playerLimit

    override fun info(message: String) = plugin.logger.info(message)
    override fun warn(message: String) = plugin.logger.warning(message)
    override fun now(): Long = System.currentTimeMillis()

    override fun runLater(delayMillis: Long, task: () -> Unit): ScheduledTask {
        val handle: BungeeScheduledTask =
            ProxyServer.getInstance().scheduler.schedule(plugin, Runnable { task() }, delayMillis, TimeUnit.MILLISECONDS)
        return ScheduledTask { handle.cancel() }
    }
}

private class BungeePlayer(private val player: ProxiedPlayer) : PlatformPlayer {
    override val uniqueId: UUID get() = player.uniqueId
    override val name: String get() = player.name

    override fun hasPermission(permission: String) = player.hasPermission(permission)
    override fun disconnect(message: String) = player.disconnect(*legacy(message))
    override fun sendMessage(message: String) = player.sendMessage(*legacy(message))

    override fun redirectTo(serverName: String): Boolean {
        val target = ProxyServer.getInstance().getServerInfo(serverName) ?: return false
        player.connect(target)
        return true
    }
}

private class BungeeActor(private val sender: CommandSender) : CommandActor {
    override fun hasPermission(permission: String) = sender.hasPermission(permission)
    override fun sendMessage(message: String) = sender.sendMessage(*legacy(message))
}

private class BungeeCommand(
    name: String,
    private val executor: (CommandActor, List<String>) -> Unit,
    private val completer: (CommandActor, List<String>) -> List<String>,
    vararg aliases: String,
) : Command(name, null, *aliases), TabExecutor {
    override fun execute(sender: CommandSender, args: Array<String>) = executor(BungeeActor(sender), args.toList())

    override fun onTabComplete(sender: CommandSender, args: Array<String>): Iterable<String> =
        completer(BungeeActor(sender), args.toList())
}

class BungeeListener(private val core: ProxyToolsCore) : Listener {
    private val favicons = FaviconConverter(core.platform::warn) { Favicon.create(ImageIO.read(ByteArrayInputStream(it))) }

    @EventHandler
    fun onPing(event: ProxyPingEvent) {
        val ping = event.response
        val players = ping.players
        val override = core.motd.build(players.online, players.max) ?: return

        override.description?.let { ping.descriptionComponent = TextComponent(*legacy(it)) }
        override.maxPlayers?.let { players.max = it }
        override.hoverLines?.let { lines ->
            players.sample = lines.map { ServerPing.PlayerInfo(it, MotdService.HOVER_UUID) }.toTypedArray()
        }
        override.versionName?.let { ping.version = ServerPing.Protocol(it, -1) }
        favicons.get(override.faviconBytes)?.let { ping.setFavicon(it) }
    }

    @EventHandler
    fun onPostLogin(event: PostLoginEvent) {
        if (!core.maintenance.enabled || core.maintenance.redirectTarget() != null) return
        val player = BungeePlayer(event.player)
        if (!core.maintenance.canBypass(player)) player.disconnect(core.maintenance.kickMessage())
    }

    @EventHandler
    fun onServerConnect(event: ServerConnectEvent) {
        if (!core.maintenance.enabled) return
        val targetName = core.maintenance.redirectTarget() ?: return
        val player = BungeePlayer(event.player)
        if (core.maintenance.canBypass(player)) return

        val redirect = ProxyServer.getInstance().getServerInfo(targetName)
        if (redirect == null) {
            core.platform.warn("maintenance.redirect-server '$targetName' does not exist; kicking instead.")
            event.player.disconnect(*legacy(core.maintenance.kickMessage()))
            event.isCancelled = true
            return
        }
        if (event.target != redirect) player.sendMessage(core.maintenance.redirectMessage())
        event.target = redirect
    }
}
