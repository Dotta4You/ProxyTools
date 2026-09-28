package de.doetchen.projects.proxytools.core

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WhitelistEntry(val uuid: UUID?, val name: String) {
    val isPending: Boolean get() = uuid == null
}

enum class WhitelistAddResult { ADDED, ADDED_PENDING, ALREADY_PRESENT }
enum class WhitelistRemoveResult { REMOVED, MISSING }

/**
 * Runtime state changed via commands (maintenance on/off, whitelist), kept in `data.yml` so
 * `config.yml` stays untouched.
 *
 * The whitelist is UUID-based: a name whitelisted while offline only resolves to a UUID once
 * [onPlayerSeen] sees that player join, so a later name change can't grant them bypass.
 */
class MaintenanceStore(private val file: Path) {
    @Volatile
    var enabled = false
        private set

    // uuid -> last known name, for display in /maintenance whitelist list
    private val resolved = ConcurrentHashMap<UUID, String>()

    // normalized name -> original casing, whitelisted by name but never seen joining yet
    private val pending = ConcurrentHashMap<String, String>()

    fun load() {
        if (Files.notExists(file)) return
        val root = Files.newBufferedReader(file, StandardCharsets.UTF_8).use {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(it)
        } as? Map<*, *> ?: return
        enabled = root["maintenance"] as? Boolean ?: false

        resolved.clear()
        (root["whitelist-resolved"] as? Map<*, *>)?.forEach { (key, value) ->
            runCatching { UUID.fromString(key.toString()) }.getOrNull()?.let { resolved[it] = value.toString() }
        }
        pending.clear()
        (root["whitelist-pending"] as? List<*>)?.forEach { entry -> entry?.toString()?.let { pending[normalize(it)] = it } }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        save()
    }

    fun addResolved(uuid: UUID, name: String): WhitelistAddResult {
        val existing = resolved.put(uuid, name)
        save()
        return if (existing == null) WhitelistAddResult.ADDED else WhitelistAddResult.ALREADY_PRESENT
    }

    fun addPending(name: String): WhitelistAddResult {
        if (resolved.values.any { it.equals(name, ignoreCase = true) }) return WhitelistAddResult.ALREADY_PRESENT
        val existing = pending.putIfAbsent(normalize(name), name)
        if (existing != null) return WhitelistAddResult.ALREADY_PRESENT
        save()
        return WhitelistAddResult.ADDED_PENDING
    }

    fun remove(identifier: String): WhitelistRemoveResult {
        val asUuid = runCatching { UUID.fromString(identifier) }.getOrNull()
        if (asUuid != null && resolved.remove(asUuid) != null) {
            save()
            return WhitelistRemoveResult.REMOVED
        }
        if (pending.remove(normalize(identifier)) != null) {
            save()
            return WhitelistRemoveResult.REMOVED
        }
        val byName = resolved.entries.firstOrNull { it.value.equals(identifier, ignoreCase = true) }
        if (byName != null) {
            resolved.remove(byName.key)
            save()
            return WhitelistRemoveResult.REMOVED
        }
        return WhitelistRemoveResult.MISSING
    }

    fun entries(): List<WhitelistEntry> =
        resolved.map { (uuid, name) -> WhitelistEntry(uuid, name) }.sortedBy { it.name.lowercase() } +
            pending.values.sortedBy { it.lowercase() }.map { WhitelistEntry(null, it) }

    fun isWhitelisted(uuid: UUID): Boolean = resolved.containsKey(uuid)

    /** @return true if a pending name just got resolved to [uuid]. */
    fun onPlayerSeen(uuid: UUID, name: String): Boolean {
        if (pending.remove(normalize(name)) != null) {
            resolved[uuid] = name
            save()
            return true
        }
        if (resolved.containsKey(uuid) && resolved[uuid] != name) {
            resolved[uuid] = name
            save()
        }
        return false
    }

    private fun normalize(entry: String) = entry.trim().lowercase()

    @Synchronized
    private fun save() {
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        val data = linkedMapOf(
            "maintenance" to enabled,
            "whitelist-resolved" to resolved.entries.associate { it.key.toString() to it.value },
            "whitelist-pending" to pending.values.sorted(),
        )
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use { Yaml(options).dump(data, it) }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
    }
}
