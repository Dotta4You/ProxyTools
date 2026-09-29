package de.doetchen.projects.proxytools.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WhitelistEntry(val uuid: UUID?, val name: String) {
    val isPending: Boolean get() = uuid == null
}

enum class WhitelistAddResult { ADDED, ADDED_PENDING, ALREADY_PRESENT }
enum class WhitelistRemoveResult { REMOVED, MISSING }

class MaintenanceStore(private val file: Path, private val onSaveError: (String) -> Unit = {}) {
    @Volatile
    var enabled = false
        private set

    @Volatile
    var maintenanceUntil: Long? = null
        private set

    @Volatile
    var scheduledStart: Long? = null
        private set

    @Volatile
    var scheduledDuration: Long? = null
        private set

    private val resolved = ConcurrentHashMap<UUID, String>()

    private val pending = ConcurrentHashMap<String, String>()

    fun load() {
        if (Files.notExists(file)) return
        val root = YamlFiles.read(file) ?: return
        enabled = root["maintenance"] as? Boolean ?: false
        maintenanceUntil = (root["maintenance-until"] as? Number)?.toLong()
        scheduledStart = (root["schedule-start"] as? Number)?.toLong()
        scheduledDuration = (root["schedule-duration"] as? Number)?.toLong()

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

    fun setMaintenanceUntil(value: Long?) {
        maintenanceUntil = value
        save()
    }

    fun setSchedule(start: Long?, durationMillis: Long?) {
        scheduledStart = start
        scheduledDuration = durationMillis
        save()
    }

    fun addResolved(uuid: UUID, name: String): WhitelistAddResult {
        pending.remove(normalize(name))
        if (resolved.putIfAbsent(uuid, name) != null) return WhitelistAddResult.ALREADY_PRESENT
        save()
        return WhitelistAddResult.ADDED
    }

    fun addPending(name: String): WhitelistAddResult {
        if (resolved.values.any { it.equals(name, ignoreCase = true) } || pending.putIfAbsent(normalize(name), name) != null) {
            return WhitelistAddResult.ALREADY_PRESENT
        }
        save()
        return WhitelistAddResult.ADDED_PENDING
    }

    fun remove(identifier: String): WhitelistRemoveResult {
        val uuid = runCatching { UUID.fromString(identifier) }.getOrNull()
        val removed = (uuid != null && resolved.remove(uuid) != null) ||
            pending.remove(normalize(identifier)) != null ||
            resolved.entries.firstOrNull { it.value.equals(identifier, ignoreCase = true) }?.let { resolved.remove(it.key) } != null
        if (!removed) return WhitelistRemoveResult.MISSING
        save()
        return WhitelistRemoveResult.REMOVED
    }

    fun entries(): List<WhitelistEntry> =
        resolved.map { (uuid, name) -> WhitelistEntry(uuid, name) }.sortedBy { it.name.lowercase() } +
            pending.values.sortedBy { it.lowercase() }.map { WhitelistEntry(null, it) }

    fun isWhitelisted(uuid: UUID): Boolean = resolved.containsKey(uuid)

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
        try {
            Files.createDirectories(file.parent)
            YamlFiles.write(
                file,
                linkedMapOf(
                    "maintenance" to enabled,
                    "maintenance-until" to maintenanceUntil,
                    "schedule-start" to scheduledStart,
                    "schedule-duration" to scheduledDuration,
                    "whitelist-resolved" to resolved.entries.associate { it.key.toString() to it.value },
                    "whitelist-pending" to pending.values.sorted(),
                ),
            )
        } catch (e: Exception) {
            onSaveError("Could not save data.yml: ${e.message}")
        }
    }
}
