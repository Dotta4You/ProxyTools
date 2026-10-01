package de.doetchen.projects.proxytools.core.motd

import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.config.YamlConfig
import de.doetchen.projects.proxytools.core.text.Text
import java.util.UUID
import kotlin.random.Random

internal class PingOverride(
    val description: String?,
    val maxPlayers: Int?,
    val versionName: String?,
    val hoverLines: List<String>?,
    val faviconBytes: ByteArray?,
)

private sealed class MaxPlayersMode {
    data object Unset : MaxPlayersMode()
    data class Fixed(val value: Int) : MaxPlayersMode()
    data class Dynamic(val headroom: Int) : MaxPlayersMode()
}

internal class MotdService(private val core: ProxyToolsCore) {
    private class Template(
        val description: String?,
        val hoverLines: List<String>?,
        val versionName: String?,
        val maxPlayersMode: MaxPlayersMode,
    )

    private class Cached(val config: YamlConfig, val base: String, val bucket: Long, val template: Template)

    @Volatile
    private var cached: Cached? = null

    fun build(online: Int, proxyMax: Int): PingOverride? = try {
        buildOverride(online, proxyMax)
    } catch (e: Exception) {
        core.platform.warn("Could not build the MOTD, leaving the server list unchanged: ${e.message}")
        null
    }

    fun preview(): String? = try {
        buildOverride(core.platform.onlinePlayers.size, core.platform.configuredMaxPlayers)?.description
    } catch (e: Exception) {
        core.platform.warn("Could not build the MOTD preview: ${e.message}")
        null
    }

    private fun buildOverride(online: Int, proxyMax: Int): PingOverride? {
        val maintenance = core.maintenance.enabled
        val template = templateFor(if (maintenance) "maintenance.motd" else "motd", maintenance)

        val maxOverride = when (val mode = template.maxPlayersMode) {
            MaxPlayersMode.Unset -> null
            is MaxPlayersMode.Fixed -> mode.value
            is MaxPlayersMode.Dynamic -> online + mode.headroom
        }
        val placeholders = arrayOf(
            "online" to online.toString(),
            "max" to (maxOverride ?: proxyMax).toString(),
            "reason" to (core.store.maintenanceReason?.let(Text::colorize) ?: ""),
        )
        val description = template.description?.let { Text.replace(it, *placeholders) }
        val hover = template.hoverLines?.map { Text.replace(it, *placeholders) }
        val favicon = (if (maintenance) core.favicons.bytes("maintenance.png") else null) ?: core.favicons.bytes("default.png")

        if (description == null && maxOverride == null && template.versionName == null && hover == null && favicon == null) return null
        return PingOverride(description, maxOverride, template.versionName, hover, favicon)
    }

    private fun templateFor(base: String, maintenance: Boolean): Template {
        val config = core.config
        val intervalSeconds = config.int("$base.interval-seconds", DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1)
        val bucket = core.platform.now() / (intervalSeconds * 1000L)
        cached?.let { if (it.config === config && it.base == base && it.bucket == bucket) return it.template }

        val enabled = config.boolean("$base.enabled", true)
        val template = Template(
            description = if (enabled) pickEntry(config, base, bucket)?.let(Text::colorize) else null,
            hoverLines = if (enabled && showsCustomHover(config, base)) {
                config.stringList("$base.hover.lines").map(Text::colorize)
            } else {
                null
            },
            versionName = if (maintenance) versionText(config) else null,
            maxPlayersMode = if (enabled) maxPlayersMode(config, base) else slotsLimit(),
        )
        cached = Cached(config, base, bucket, template)
        return template
    }

    private fun maxPlayersMode(config: YamlConfig, base: String): MaxPlayersMode {
        if ((config.get("$base.max-players") as? String).equals("dynamic", ignoreCase = true)) {
            return MaxPlayersMode.Dynamic(config.int("$base.max-players-headroom", 1).coerceAtLeast(0))
        }
        val fixed = config.int("$base.max-players", -1)
        return if (fixed >= 0) MaxPlayersMode.Fixed(fixed) else slotsLimit()
    }

    private fun showsCustomHover(config: YamlConfig, base: String) =
        config.boolean("$base.hover.enabled") && config.string("$base.hover.mode", "custom") == "custom"

    private fun versionText(config: YamlConfig) =
        config.string("maintenance.version-text").takeIf { it.isNotBlank() }?.let(Text::colorize)

    private fun slotsLimit(): MaxPlayersMode =
        if (core.slots.enabled) MaxPlayersMode.Fixed(core.slots.limit()) else MaxPlayersMode.Unset

    private fun pickEntry(config: YamlConfig, base: String, bucket: Long): String? {
        val entries = config.mapList("$base.entries")
        if (entries.isEmpty()) return null
        val index = when (config.string("$base.mode", "random")) {
            "static" -> 0
            "sequential" -> (bucket % entries.size).toInt()
            else -> Random(bucket * 1_000_003L + base.hashCode()).nextInt(entries.size)
        }
        return listOf("line1", "line2")
            .mapNotNull { entries[index][it]?.toString() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifEmpty { null }
    }

    companion object {
        val HOVER_UUID: UUID = UUID(0L, 0L)
        private const val DEFAULT_INTERVAL_SECONDS = 4
    }
}
