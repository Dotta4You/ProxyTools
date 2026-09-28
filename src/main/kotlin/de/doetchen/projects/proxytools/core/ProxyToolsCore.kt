package de.doetchen.projects.proxytools.core

/** Platform-independent plugin logic; the BungeeCord and Velocity adapters just wire events/commands to it. */
class ProxyToolsCore(val platform: Platform) {
    @Volatile
    var config: YamlConfig = loadOrDefaults("config.yml")
        private set

    /** Resolved language code, e.g. "en". */
    @Volatile
    var language: String = resolveLanguageCode(config)
        private set

    @Volatile
    var messages: YamlConfig = loadLanguageFile(language)
        private set

    val store = MaintenanceStore(platform.dataFolder.resolve("data.yml"))
    val maintenance = MaintenanceService(this)
    val motd = MotdService(this)
    val favicons = FaviconCache(platform.dataFolder)
    val commands = CommandHandler(this)

    init {
        try {
            store.load()
        } catch (e: Exception) {
            platform.warn("Could not read data.yml, starting with maintenance off: ${e.message}")
        }
    }

    /** Re-reads config.yml, the language file and data.yml. Keeps the old values on error. */
    fun reload(): Boolean = try {
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

    /** A colorized message from the language file. `%prefix%` is always available. */
    fun message(key: String, vararg placeholders: Pair<String, String>): String {
        val raw = messages.string("messages.$key", "&cMissing message: $key")
        val prefix = messages.string("prefix")
        return Text.colorize(Text.replace(raw, "prefix" to prefix, *placeholders))
    }

    /** Non-identifying data for the optional bStats charts. */
    fun metricsSnapshot(): Map<String, String> = mapOf(
        "language" to language,
        "maintenance" to if (maintenance.enabled) "enabled" else "disabled",
    )

    private fun loadOrDefaults(name: String): YamlConfig = try {
        YamlConfig.load(platform.dataFolder, name)
    } catch (e: Exception) {
        platform.warn("Could not load $name, using built-in defaults: ${e.message}")
        YamlConfig.bundled(name)
    }

    private fun resolveLanguageCode(config: YamlConfig): String {
        val requested = config.string("language", "en").lowercase().ifBlank { "en" }
        if (YamlConfig.hasBundled("lang/$requested.yml")) return requested
        platform.warn("Unknown language '$requested' in config.yml, falling back to English. Available: $AVAILABLE_LANGUAGES")
        return "en"
    }

    private fun loadLanguageFile(code: String): YamlConfig = try {
        YamlConfig.load(platform.dataFolder, "lang/$code.yml")
    } catch (e: Exception) {
        platform.warn("Could not load lang/$code.yml, falling back to English: ${e.message}")
        YamlConfig.load(platform.dataFolder, "lang/en.yml")
    }

    companion object {
        const val AVAILABLE_LANGUAGES = "en, de"
    }
}
