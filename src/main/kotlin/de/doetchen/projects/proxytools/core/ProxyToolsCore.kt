package de.doetchen.projects.proxytools.core

import de.doetchen.projects.proxytools.core.chat.AnnouncementService
import de.doetchen.projects.proxytools.core.chat.MessageService
import de.doetchen.projects.proxytools.core.chat.TeamAlertService
import de.doetchen.projects.proxytools.core.command.CommandHandler
import de.doetchen.projects.proxytools.core.command.InfoCommandService
import de.doetchen.projects.proxytools.core.config.ConfigMigrator
import de.doetchen.projects.proxytools.core.config.ConfigValidator
import de.doetchen.projects.proxytools.core.config.YamlConfig
import de.doetchen.projects.proxytools.core.maintenance.MaintenanceService
import de.doetchen.projects.proxytools.core.maintenance.MaintenanceStore
import de.doetchen.projects.proxytools.core.motd.FaviconCache
import de.doetchen.projects.proxytools.core.motd.MotdService
import de.doetchen.projects.proxytools.core.player.HubService
import de.doetchen.projects.proxytools.core.player.LastServerService
import de.doetchen.projects.proxytools.core.player.SlotService
import de.doetchen.projects.proxytools.core.storage.PlayerDataService
import de.doetchen.projects.proxytools.core.storage.StorageFactory
import de.doetchen.projects.proxytools.core.text.Text
import de.doetchen.projects.proxytools.core.update.ReleaseSource
import de.doetchen.projects.proxytools.core.update.UpdateService
import java.nio.file.Files

internal class ProxyToolsCore(val platform: Platform, releases: ReleaseSource) {
    private val layout = DataLayout(platform.dataFolder).also { it.prepare(platform::info, platform::warn) }

    @Volatile
    var config: YamlConfig = run { migrateConfig(); loadOrDefaults("config.yml") }
        private set

    @Volatile
    var language: String = resolveLanguageCode(config)
        private set

    @Volatile
    var messages: YamlConfig = loadLanguageFile(language)
        private set

    val store = MaintenanceStore(layout.maintenance, layout.whitelist, platform::warn)
    val maintenance = MaintenanceService(this)
    val motd = MotdService(this)
    val slots = SlotService(this)
    val announcements = AnnouncementService(this)
    val hub = HubService(this)
    private val storageSignature = StorageFactory.signature(config)
    val playerData = PlayerDataService(
        StorageFactory.create(config, layout, platform),
        platform::runLater,
        platform::now,
        platform::warn,
    )
    val privateMessages = MessageService(this)
    val lastServers = LastServerService(this)
    val teamAlerts = TeamAlertService(this)
    val infoCommands = InfoCommandService(this)
    val updates = UpdateService(this, releases)
    val favicons = FaviconCache(layout.icons, platform::warn, platform::now)
    val commands = CommandHandler(this)

    init {
        validate(config)
        guarded("Could not read the maintenance data, using what could be read") { store.load() }
        guarded("Could not read the saved player settings, starting without them") { playerData.load() }
        adviseOnLargePlayerFile()
        guarded("Could not resume the maintenance timer") { maintenance.resumeTimerIfNeeded() }
        guarded("Could not resume the scheduled maintenance window") { maintenance.resumeScheduleIfNeeded() }
        warnAboutMissingServers()
        guarded("Could not start the announcements") { announcements.restart() }
        updates.restart()
        logStartupBanner()
    }

    fun reload(): Boolean = try {
        migrateConfig()
        val newConfig = YamlConfig.load(platform.dataFolder, "config.yml")
        val newLanguage = resolveLanguageCode(newConfig)
        val newMessages = loadLanguageFile(newLanguage)
        config = newConfig
        language = newLanguage
        messages = newMessages
        validate(newConfig)
        warnAboutMissingServers()
        announcements.restart()
        updates.restart()
        infoCommands.warnIfChanged()
        if (StorageFactory.signature(newConfig) != storageSignature) {
            platform.warn("The storage settings changed, restart the proxy to apply them.")
        }
        true
    } catch (e: Exception) {
        platform.warn("Reload failed, keeping the previous configuration: ${e.message}")
        false
    }

    fun message(key: String, vararg placeholders: Pair<String, String>): String {
        val raw = messages.string("messages.$key", "&cMissing message: $key")
        val prefix = messages.string("prefix")
        return Text.colorize(Text.replace(raw, "prefix" to prefix, *placeholders))
    }

    val metricsEnabled: Boolean get() = config.boolean("bstats", true)

    val metricCharts: Map<String, () -> String> = mapOf(
        "language" to { language },
        "maintenance_enabled" to { if (maintenance.enabled) "enabled" else "disabled" },
        "storage_type" to { playerData.storageName.substringBefore(" (") },
    )

    fun shutdown() = guarded("Could not save player data") { playerData.close() }

    fun userMessage(key: String, userText: String, vararg placeholders: Pair<String, String>): String =
        message(key, "message" to USER_TEXT_MARK, *placeholders).replace(USER_TEXT_MARK, userText.replace('§', ' '))

    fun playerJoined(player: PlatformPlayer) {
        maintenance.notifyBypass(player)
        updates.notifyAdmin(player)
    }

    fun playerLeft(player: PlatformPlayer) {
        teamAlerts.left(player)
        playerData.release(player.uniqueId)
        privateMessages.forget(player.uniqueId)
    }

    fun loginDenial(player: PlatformPlayer): String? = maintenance.loginDenial(player) ?: slots.denial(player)

    fun broadcast(formattedMessage: String) {
        platform.onlinePlayers.forEach { it.sendMessage(formattedMessage) }
        platform.info(Text.strip(formattedMessage))
    }

    fun teamChat(sender: CommandActor, text: String) {
        val formatted = message("teamchat", "player" to sender.name, "server" to (sender.serverName ?: "-"), "message" to text)
        platform.onlinePlayers.filter { it.hasPermission(Permissions.TEAMCHAT) }.forEach { it.sendMessage(formatted) }
        platform.info(Text.strip(formatted))
    }

    private object Ansi {
        const val RESET = "\u001B[0m"
        const val BOLD = "\u001B[1m"
        const val CYAN = "\u001B[36m"
        const val GRAY = "\u001B[90m"
        const val GREEN = "\u001B[32m"
        const val RED = "\u001B[31m"
    }

    private fun logStartupBanner() {
        val rule = "${Ansi.GRAY}${"─".repeat(44)}${Ansi.RESET}"
        val stateColor = if (maintenance.enabled) Ansi.RED else Ansi.GREEN
        val stateText = if (maintenance.enabled) "enabled" else "disabled"

        platform.info(rule)
        platform.info(
            "${Ansi.CYAN}${Ansi.BOLD}▶ ProxyTools${Ansi.RESET}${Ansi.GRAY} v${platform.pluginVersion} — " +
                "${platform.proxyName} ${platform.proxyVersion}${Ansi.RESET}",
        )
        platform.info(
            "${Ansi.GRAY}▶ Language: ${Ansi.RESET}$language" +
                "${Ansi.GRAY}  ·  Maintenance: ${Ansi.RESET}$stateColor$stateText${Ansi.RESET}",
        )
        platform.info("${Ansi.GRAY}▶ Storage: ${Ansi.RESET}${playerData.storageName}")
        if (maintenance.enabled && store.maintenanceUntil != null) {
            platform.info("${Ansi.GRAY}▶ Timer: ${Ansi.RESET}${maintenance.describeDuration()}")
        } else if (!maintenance.enabled && store.scheduledStart != null) {
            platform.info("${Ansi.GRAY}▶ Schedule: ${Ansi.RESET}${maintenance.describeSchedule()}")
        }
        platform.info(rule)
    }

    private fun validate(config: YamlConfig) {
        ConfigValidator.check(config).forEach { platform.warn("config.yml: $it") }
        commands.specs().flatMap { listOf(it.name) + it.aliases }.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
            .forEach { platform.warn("config.yml: the command /$it is defined more than once, the later definition wins") }
    }

    private fun warnAboutMissingServers() {
        if (hub.enabled && hub.target() == null) {
            platform.warn("None of hub.servers (${hub.servers().joinToString()}) is a registered server, /hub will not work.")
        }

        val target = maintenance.redirectTarget() ?: return
        if (!platform.hasServer(target)) {
            platform.warn("maintenance.redirect-server '$target' is not a registered server, players will be kicked instead.")
        }
    }

    private fun adviseOnLargePlayerFile() {
        if (playerData.lazy || playerData.size < LARGE_PLAYER_FILE) return
        platform.info("data/players.yml holds ${playerData.size} players. Consider storage.type: h2 in the config for faster saving.")
    }

    private fun migrateConfig() = guarded("Could not migrate config.yml, loading it as-is") {
        ConfigMigrator.migrateInPlace(layout.config)
    }

    private inline fun guarded(failureText: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            platform.warn("$failureText: ${e.message}")
        }
    }

    private fun loadOrDefaults(name: String): YamlConfig = try {
        YamlConfig.load(platform.dataFolder, name)
    } catch (e: Exception) {
        platform.warn("$name is not valid, the built-in defaults are used until you fix it: ${e.message}")
        YamlConfig.bundled(name)
    }

    private fun resolveLanguageCode(config: YamlConfig): String {
        val requested = config.string("language", "en").lowercase().ifBlank { "en" }
        val known = LANGUAGE_CODE.matches(requested) &&
            (YamlConfig.hasBundled("lang/$requested.yml") || Files.exists(platform.dataFolder.resolve("lang/$requested.yml")))
        if (known) return requested
        platform.warn("Unknown language '$requested' in config.yml, falling back to English. Built-in: $BUILT_IN_LANGUAGES")
        return "en"
    }

    private fun loadLanguageFile(code: String): YamlConfig = try {
        val name = "lang/$code.yml"
        YamlConfig.load(platform.dataFolder, name, defaultsName = if (YamlConfig.hasBundled(name)) name else "lang/en.yml")
    } catch (e: Exception) {
        platform.warn("Could not load lang/$code.yml, falling back to English: ${e.message}")
        YamlConfig.load(platform.dataFolder, "lang/en.yml")
    }

    companion object {
        private const val LARGE_PLAYER_FILE = 10_000
        private const val USER_TEXT_MARK = "\u0001"
        private const val BUILT_IN_LANGUAGES = "en, de"
        private val LANGUAGE_CODE = Regex("[a-z0-9_-]+")
    }
}
