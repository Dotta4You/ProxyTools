package de.doetchen.projects.proxytools.core

import java.nio.file.Files

class ProxyToolsCore(val platform: Platform) {
    @Volatile
    var config: YamlConfig = run { migrateConfig(); loadOrDefaults("config.yml") }
        private set

    @Volatile
    var language: String = resolveLanguageCode(config)
        private set

    @Volatile
    var messages: YamlConfig = loadLanguageFile(language)
        private set

    val store = MaintenanceStore(platform.dataFolder.resolve("data.yml"), platform::warn)
    val maintenance = MaintenanceService(this)
    val motd = MotdService(this)
    val favicons = FaviconCache(platform.dataFolder, platform::warn)
    val commands = CommandHandler(this)

    init {
        guarded("Could not read data.yml, starting with maintenance off") { store.load() }
        guarded("Could not resume the maintenance timer") { maintenance.resumeTimerIfNeeded() }
        guarded("Could not resume the scheduled maintenance window") { maintenance.resumeScheduleIfNeeded() }
        logStartupBanner()
    }

    fun reload(): Boolean = try {
        migrateConfig()
        val newConfig = YamlConfig.load(platform.dataFolder, "config.yml")
        val newLanguage = resolveLanguageCode(newConfig)
        val newMessages = loadLanguageFile(newLanguage)
        store.load()
        config = newConfig
        language = newLanguage
        messages = newMessages
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

    val metricCharts: Map<String, () -> String> = mapOf(
        "language" to { language },
        "maintenance_enabled" to { if (maintenance.enabled) "enabled" else "disabled" },
    )

    fun broadcast(formattedMessage: String) {
        platform.onlinePlayers.forEach { it.sendMessage(formattedMessage) }
        platform.info(formattedMessage)
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
            "${Ansi.GRAY}▶ Language: ${Ansi.RESET}$language${Ansi.GRAY}  ·  Maintenance: ${Ansi.RESET}$stateColor$stateText${Ansi.RESET}",
        )
        if (maintenance.enabled && store.maintenanceUntil != null) {
            platform.info("${Ansi.GRAY}▶ Timer: ${Ansi.RESET}${maintenance.describeDuration()}")
        } else if (!maintenance.enabled && store.scheduledStart != null) {
            platform.info("${Ansi.GRAY}▶ Schedule: ${Ansi.RESET}${maintenance.describeSchedule()}")
        }
        platform.info(rule)
    }

    private fun migrateConfig() = guarded("Could not migrate config.yml, loading it as-is") {
        ConfigMigrator.migrateInPlace(platform.dataFolder.resolve("config.yml"))
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
        platform.warn("Could not load $name, using built-in defaults: ${e.message}")
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
        private const val BUILT_IN_LANGUAGES = "en, de"
        private val LANGUAGE_CODE = Regex("[a-z0-9_-]+")
    }
}
