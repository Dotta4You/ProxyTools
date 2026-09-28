package de.doetchen.projects.proxytools.bungee

import de.doetchen.projects.proxytools.core.CommandActor
import de.doetchen.projects.proxytools.core.MotdService
import de.doetchen.projects.proxytools.core.Platform
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.Favicon
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.ServerPing
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.ProxyPingEvent
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
import javax.imageio.ImageIO

// bStats plugin ID for BungeeCord, see https://bstats.org/plugin/bungeecord/ProxyTools/34376
private const val BSTATS_PLUGIN_ID = 34376

class ProxyToolsBungee : Plugin() {
    private lateinit var core: ProxyToolsCore

    override fun onEnable() {
        core = ProxyToolsCore(BungeePlatform(this))
        proxy.pluginManager.registerListener(this, BungeeListener(core))
        proxy.pluginManager.registerCommand(
            this,
            BungeeCommand("maintenance", core.commands::maintenance, core.commands::suggestMaintenance),
        )
        proxy.pluginManager.registerCommand(
            this,
            BungeeCommand("proxytools", core.commands::proxyTools, core.commands::suggestProxyTools, "pt"),
        )
        setUpMetrics()
    }

    private fun setUpMetrics() {
        if (BSTATS_PLUGIN_ID == 0) return
        val metrics = Metrics(this, BSTATS_PLUGIN_ID)
        metrics.addCustomChart(SimplePie("language") { core.metricsSnapshot()["language"] })
        metrics.addCustomChart(SimplePie("maintenance_enabled") { core.metricsSnapshot()["maintenance"] })
    }
}

// fromLegacy() would be nicer, but older BungeeCord builds don't have it yet
@Suppress("DEPRECATION")
private fun legacy(text: String): Array<BaseComponent> = TextComponent.fromLegacyText(text)

private class BungeePlatform(private val plugin: Plugin) : Platform {
    override val platformName = "BungeeCord"
    override val pluginVersion: String get() = plugin.description.version
    override val dataFolder: Path get() = plugin.dataFolder.toPath()
    override val onlinePlayers get() = ProxyServer.getInstance().players.map(::BungeePlayer)

    override fun info(message: String) = plugin.logger.info(message)
    override fun warn(message: String) = plugin.logger.warning(message)
}

private class BungeePlayer(private val player: ProxiedPlayer) : PlatformPlayer {
    override val uniqueId: UUID get() = player.uniqueId
    override val name: String get() = player.name

    override fun hasPermission(permission: String) = player.hasPermission(permission)
    override fun disconnect(message: String) = player.disconnect(*legacy(message))
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
    // only rebuilt when the source bytes change, Favicon.create() re-encodes the image
    private var cachedFaviconBytes: ByteArray? = null
    private var cachedFavicon: Favicon? = null

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
        // protocol -1 -> client shows it in red as an incompatible version
        override.versionName?.let { ping.version = ServerPing.Protocol(it, -1) }
        favicon(override.faviconBytes)?.let { ping.setFavicon(it) }
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

    // permissions aren't available yet in LoginEvent, so this checks after login instead
    @EventHandler
    fun onPostLogin(event: PostLoginEvent) {
        val player = BungeePlayer(event.player)
        if (core.maintenance.enabled && !core.maintenance.canBypass(player)) {
            player.disconnect(core.maintenance.kickMessage())
        }
    }
}
