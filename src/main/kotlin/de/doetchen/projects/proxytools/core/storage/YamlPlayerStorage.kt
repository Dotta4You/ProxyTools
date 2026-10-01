package de.doetchen.projects.proxytools.core.storage

import de.doetchen.projects.proxytools.core.config.YamlFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class YamlPlayerStorage(private val file: Path) : PlayerStorage {
    override val name = "YAML (${file.fileName})"
    override val lazy = false
    override val saveDelayMillis = 5_000L

    private val known = ConcurrentHashMap<UUID, PlayerRecord>()

    override fun loadAll(): Map<UUID, PlayerRecord> {
        known.clear()
        if (Files.exists(file)) {
            val players = YamlFiles.read(file)?.get("players") as? Map<*, *> ?: return emptyMap()
            players.forEach { (key, value) ->
                val id = runCatching { UUID.fromString(key.toString()) }.getOrNull() ?: return@forEach
                val data = value as? Map<*, *> ?: return@forEach
                val ignored = (data["ignored"] as? Map<*, *>).orEmpty().mapNotNull { (other, name) ->
                    runCatching { UUID.fromString(other.toString()) }.getOrNull()?.let { it to name.toString() }
                }.toMap()
                known[id] = PlayerRecord(data["last-server"] as? String, data["messages-disabled"] as? Boolean ?: false, ignored)
            }
        }
        return known.toMap()
    }

    override fun load(id: UUID): PlayerRecord? = known[id]

    @Synchronized
    override fun save(changes: Map<UUID, PlayerRecord>) {
        changes.forEach { (id, record) -> if (record.isDefault) known.remove(id) else known[id] = record }
        val players = known.entries.associate { (id, record) ->
            id.toString() to buildMap<String, Any?> {
                record.lastServer?.let { put("last-server", it) }
                if (record.messagesDisabled) put("messages-disabled", true)
                if (record.ignored.isNotEmpty()) put("ignored", record.ignored.entries.associate { it.key.toString() to it.value })
            }
        }
        Files.createDirectories(file.parent)
        YamlFiles.write(file, mapOf("players" to players))
    }
}
