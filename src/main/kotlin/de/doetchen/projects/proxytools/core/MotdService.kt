package de.doetchen.projects.proxytools.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

/** What to change in a server-list ping; a `null` field means "leave the proxy's value alone". */
class PingOverride(
    val description: String?,
    val maxPlayers: Int?,
    val versionName: String?,
    val hoverLines: List<String>?,
    val faviconBytes: ByteArray?,
)

class MotdService(private val core: ProxyToolsCore) {
    private val sequentialIndex = ConcurrentHashMap<String, AtomicInteger>()

    /** @return null if nothing should be changed for this ping. */
    fun build(online: Int, proxyMax: Int): PingOverride? {
        val config = core.config
        val maintenance = core.maintenance.enabled
        val base = if (maintenance) "maintenance.motd" else "motd"
        val enabled = config.boolean("$base.enabled", true)

        val maxOverride = if (enabled) config.int("$base.max-players", -1).takeIf { it >= 0 } else null
        val placeholders = arrayOf("online" to online.toString(), "max" to (maxOverride ?: proxyMax).toString())

        val description = if (enabled) pickEntry(base)?.let { Text.colorize(Text.replace(it, *placeholders)) } else null
        val hover = if (enabled && config.boolean("$base.hover.enabled") && config.string("$base.hover.mode", "custom") == "custom") {
            config.stringList("$base.hover.lines").map { Text.colorize(Text.replace(it, *placeholders)) }
        } else {
            null
        }
        val versionName = if (maintenance) {
            config.string("maintenance.version-text").takeIf { it.isNotBlank() }?.let { Text.colorize(it) }
        } else {
            null
        }
        val favicon = (if (maintenance) core.favicons.bytes("icon-maintenance.png") else null) ?: core.favicons.bytes("icon.png")

        if (description == null && maxOverride == null && versionName == null && hover == null && favicon == null) return null
        return PingOverride(description, maxOverride, versionName, hover, favicon)
    }

    private fun pickEntry(base: String): String? {
        val entries = core.config.mapList("$base.entries")
        if (entries.isEmpty()) return null
        val index = if (core.config.string("$base.mode", "random") == "sequential") {
            val counter = sequentialIndex.getOrPut(base) { AtomicInteger(-1) }
            Math.floorMod(counter.incrementAndGet(), entries.size)
        } else {
            ThreadLocalRandom.current().nextInt(entries.size)
        }
        val entry = entries[index]
        return listOf(entry["line1"], entry["line2"])
            .mapNotNull { it?.toString() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifEmpty { null }
    }

    companion object {
        val HOVER_UUID: UUID = UUID(0L, 0L)
    }
}
