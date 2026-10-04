package de.doetchen.projects.proxytools.bungee

import de.doetchen.projects.proxytools.core.CommandActor
import de.doetchen.projects.proxytools.core.Platform
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.ScheduledTask
import de.doetchen.projects.proxytools.core.command.CommandSpec
import de.doetchen.projects.proxytools.core.motd.FaviconConverter
import de.doetchen.projects.proxytools.core.motd.MotdService
import de.doetchen.projects.proxytools.core.update.GitHubReleases
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.Favicon
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.ServerPing
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.LoginEvent
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.ProxyPingEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.event.ServerSwitchEvent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.plugin.TabExecutor
import net.md_5.bungee.event.EventHandler
import org.bstats.bungeecord.Metrics
import org.bstats.charts.SimplePie
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import net.md_5.bungee.api.scheduler.ScheduledTask as BungeeScheduledTask

private const val BSTATS_PLUGIN_ID = 34376

class ProxyToolsBungee : Plugin() {
    private lateinit var core: ProxyToolsCore

    override fun onEnable() {
        try {
            core = ProxyToolsCore(BungeePlatform(this), GitHubReleases)
            proxy.pluginManager.registerListener(this, BungeeListener(core, this))
            core.commands.specs().forEach { proxy.pluginManager.registerCommand(this, BungeeCommand(it)) }
            if (core.metricsEnabled) {
                val metrics = Metrics(this, BSTATS_PLUGIN_ID)
                core.metricCharts.forEach { (id, value) -> metrics.addCustomChart(SimplePie(id, value)) }
            }
        } catch (e: Exception) {
            logger.severe("ProxyTools failed to start, disabling: ${e.message}")
            proxy.pluginManager.unregisterCommands(this)
            proxy.pluginManager.unregisterListeners(this)
        }
    }

    override fun onDisable() {
        if (::core.isInitialized) core.shutdown()
    }
}

@Suppress("DEPRECATION")
private fun legacy(text: String, openUrl: String? = null): Array<BaseComponent> {
    val components = TextComponent.fromLegacyText(text)
    if (openUrl != null) {
        val click = ClickEvent(ClickEvent.Action.OPEN_URL, openUrl)
        components.forEach { it.clickEvent = click }
    }
    return components
}

private class BungeePlatform(private val plugin: Plugin) : Platform {
    override val platformName = "BungeeCord"
    override val pluginVersion: String get() = plugin.description.version
    override val proxyName: String get() = ProxyServer.getInstance().name
    override val proxyVersion: String get() = ProxyServer.getInstance().version
    override val dataFolder: Path get() = plugin.dataFolder.toPath()
    override val onlinePlayers get() = ProxyServer.getInstance().players.map(::BungeePlayer)
    override val onlineCount: Int get() = ProxyServer.getInstance().onlineCount
    override val configuredMaxPlayers: Int get() = ProxyServer.getInstance().config.playerLimit

    override fun findPlayer(name: String): PlatformPlayer? = ProxyServer.getInstance().getPlayer(name)?.let(::BungeePlayer)
    override fun findPlayer(id: UUID): PlatformPlayer? = ProxyServer.getInstance().getPlayer(id)?.let(::BungeePlayer)
    override fun hasServer(name: String) = ProxyServer.getInstance().getServerInfo(name) != null
    override fun playerCount(serverName: String) = ProxyServer.getInstance().getServerInfo(serverName)?.players?.size ?: 0

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
    override fun sendMessage(message: String, openUrl: String?) = player.sendMessage(*legacy(message, openUrl))

    override fun redirectTo(serverName: String): Boolean {
        val target = ProxyServer.getInstance().getServerInfo(serverName) ?: return false
        player.connect(target)
        return true
    }
}

private class BungeeActor(private val sender: CommandSender) : CommandActor {
    override val name: String get() = sender.name
    override val serverName: String? get() = (sender as? ProxiedPlayer)?.server?.info?.name
    override val uniqueId: UUID? get() = (sender as? ProxiedPlayer)?.uniqueId

    override fun connectTo(serverName: String): Boolean {
        val target = ProxyServer.getInstance().getServerInfo(serverName) ?: return false
        (sender as? ProxiedPlayer)?.connect(target) ?: return false
        return true
    }

    override fun hasPermission(permission: String) = sender.hasPermission(permission)
    override fun sendMessage(message: String, openUrl: String?) = sender.sendMessage(*legacy(message, openUrl))
}

private class BungeeCommand(private val spec: CommandSpec) : Command(spec.name, null, *spec.aliases.toTypedArray()), TabExecutor {
    override fun execute(sender: CommandSender, args: Array<String>) = spec.execute(BungeeActor(sender), args.toList())

    override fun onTabComplete(sender: CommandSender, args: Array<String>): Iterable<String> =
        spec.suggest(BungeeActor(sender), args.toList())
}

internal class BungeeListener(private val core: ProxyToolsCore, private val plugin: Plugin) : Listener {
    private val favicons = FaviconConverter(core.platform::warn) { Favicon.create(ImageIO.read(ByteArrayInputStream(it))) }

    @EventHandler
    fun onPing(event: ProxyPingEvent) {
        val ping = event.response
        val players: ServerPing.Players? = ping.players
        val override = core.motd.build(players?.online ?: 0, players?.max ?: 0) ?: return

        override.description?.let { ping.descriptionComponent = TextComponent(*legacy(it)) }
        override.maxPlayers?.let { players?.max = it }
        override.hoverLines?.let { lines ->
            players?.sample = lines.map { ServerPing.PlayerInfo(it, MotdService.HOVER_UUID) }.toTypedArray()
        }
        override.versionName?.let { ping.version = ServerPing.Protocol(it, -1) }
        favicons.get(override.faviconBytes)?.let { ping.setFavicon(it) }
    }

    @EventHandler
    fun onLogin(event: LoginEvent) {
        if (!core.playerData.lazy) return
        event.registerIntent(plugin)
        ProxyServer.getInstance().scheduler.runAsync(plugin) {
            try {
                core.playerData.preload(event.connection.uniqueId)
            } finally {
                event.completeIntent(plugin)
            }
        }
    }

    @EventHandler
    fun onPostLogin(event: PostLoginEvent) {
        val player = BungeePlayer(event.player)
        val denial = core.loginDenial(player)
        if (denial != null) player.disconnect(denial) else core.teamAlerts.joined(player)
    }

    @EventHandler
    fun onDisconnect(event: PlayerDisconnectEvent) {
        core.playerLeft(BungeePlayer(event.player))
    }

    @EventHandler
    fun onServerSwitch(event: ServerSwitchEvent) {
        if (event.from == null) core.playerJoined(BungeePlayer(event.player))
        event.player.server?.info?.name?.let { core.lastServers.record(event.player.uniqueId, it) }
    }

    @EventHandler
    fun onServerConnect(event: ServerConnectEvent) {
        if (event.reason == ServerConnectEvent.Reason.JOIN_PROXY) {
            core.lastServers.target(event.player.uniqueId)
                ?.let { ProxyServer.getInstance().getServerInfo(it) }
                ?.let { event.target = it }
        }
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
