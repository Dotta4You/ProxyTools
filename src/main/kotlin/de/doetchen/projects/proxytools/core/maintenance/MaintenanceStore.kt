package de.doetchen.projects.proxytools.core.maintenance

import de.doetchen.projects.proxytools.core.config.YamlFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class WhitelistEntry(val uuid: UUID?, val name: String) {
    val isPending: Boolean get() = uuid == null
}

internal enum class WhitelistAddResult { ADDED, ADDED_PENDING, ALREADY_PRESENT }
internal enum class WhitelistRemoveResult { REMOVED, MISSING }

internal class MaintenanceStore(
    private val stateFile: Path,
    private val whitelistFile: Path,
    private val onSaveError: (String) -> Unit = {},
) {
    @Volatile
    var enabled = false
        private set

    @Volatile
    var maintenanceReason: String? = null
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

    @Volatile
    var scheduledReason: String? = null
        private set

    private val resolved = ConcurrentHashMap<UUID, String>()

    private val pending = ConcurrentHashMap<String, String>()

    fun load() {
        val problems = mutableListOf<String>()
        try {
            loadState()
        } catch (e: Exception) {
            problems += e.message.orEmpty()
        }
        try {
            loadWhitelist()
        } catch (e: Exception) {
            problems += e.message.orEmpty()
        }
        if (problems.isNotEmpty()) throw IllegalStateException(problems.joinToString(" "))
    }

    private fun loadState() {
        if (Files.notExists(stateFile)) return
        val root = YamlFiles.readOrQuarantine(stateFile) ?: return
        enabled = root["maintenance"] as? Boolean ?: false
        maintenanceUntil = (root["maintenance-until"] as? Number)?.toLong()
        scheduledStart = (root["schedule-start"] as? Number)?.toLong()
        scheduledDuration = (root["schedule-duration"] as? Number)?.toLong()
        maintenanceReason = root["maintenance-reason"] as? String
        scheduledReason = root["schedule-reason"] as? String
    }

    private fun loadWhitelist() {
        val fromLegacyFile = Files.notExists(whitelistFile)
        val source = if (fromLegacyFile) {
            runCatching { YamlFiles.read(stateFile) }.getOrNull()
        } else {
            YamlFiles.readOrQuarantine(whitelistFile)
        } ?: return

        resolved.clear()
        (source["whitelist-resolved"] as? Map<*, *>)?.forEach { (key, value) ->
            runCatching { UUID.fromString(key.toString()) }.getOrNull()?.let { resolved[it] = value.toString() }
        }
        pending.clear()
        (source["whitelist-pending"] as? List<*>)?.forEach { entry -> entry?.toString()?.let { pending[normalize(it)] = it } }
        if (fromLegacyFile && (resolved.isNotEmpty() || pending.isNotEmpty())) saveWhitelist()
    }

    fun setEnabled(value: Boolean, reason: String? = null) {
        enabled = value
        maintenanceReason = reason
        saveState()
    }

    fun setMaintenanceUntil(value: Long?) {
        maintenanceUntil = value
        saveState()
    }

    fun setSchedule(start: Long?, durationMillis: Long?, reason: String? = null) {
        scheduledStart = start
        scheduledDuration = durationMillis
        scheduledReason = reason
        saveState()
    }

    fun addResolved(uuid: UUID, name: String): WhitelistAddResult {
        pending.remove(normalize(name))
        if (resolved.putIfAbsent(uuid, name) != null) return WhitelistAddResult.ALREADY_PRESENT
        saveWhitelist()
        return WhitelistAddResult.ADDED
    }

    fun addPending(name: String): WhitelistAddResult {
        if (resolved.values.any { it.equals(name, ignoreCase = true) } || pending.putIfAbsent(normalize(name), name) != null) {
            return WhitelistAddResult.ALREADY_PRESENT
        }
        saveWhitelist()
        return WhitelistAddResult.ADDED_PENDING
    }

    fun remove(identifier: String): WhitelistRemoveResult {
        val uuid = runCatching { UUID.fromString(identifier) }.getOrNull()
        val removed = (uuid != null && resolved.remove(uuid) != null) ||
            pending.remove(normalize(identifier)) != null ||
            resolved.entries.firstOrNull { it.value.equals(identifier, ignoreCase = true) }?.let { resolved.remove(it.key) } != null
        if (!removed) return WhitelistRemoveResult.MISSING
        saveWhitelist()
        return WhitelistRemoveResult.REMOVED
    }

    fun entries(): List<WhitelistEntry> =
        resolved.map { (uuid, name) -> WhitelistEntry(uuid, name) }.sortedBy { it.name.lowercase() } +
            pending.values.sortedBy { it.lowercase() }.map { WhitelistEntry(null, it) }

    fun isWhitelisted(uuid: UUID): Boolean = resolved.containsKey(uuid)

    fun onPlayerSeen(uuid: UUID, name: String): Boolean {
        if (pending.remove(normalize(name)) != null) {
            resolved[uuid] = name
            saveWhitelist()
            return true
        }
        if (resolved.containsKey(uuid) && resolved[uuid] != name) {
            resolved[uuid] = name
            saveWhitelist()
        }
        return false
    }

    private fun normalize(entry: String) = entry.trim().lowercase()

    @Synchronized
    private fun saveState() = write(
        stateFile,
        linkedMapOf(
            "maintenance" to enabled,
            "maintenance-reason" to maintenanceReason,
            "maintenance-until" to maintenanceUntil,
            "schedule-start" to scheduledStart,
            "schedule-duration" to scheduledDuration,
            "schedule-reason" to scheduledReason,
        ),
    )

    @Synchronized
    private fun saveWhitelist() = write(
        whitelistFile,
        linkedMapOf(
            "whitelist-resolved" to resolved.entries.associate { it.key.toString() to it.value },
            "whitelist-pending" to pending.values.sorted(),
        ),
    )

    private fun write(file: Path, data: Map<String, Any?>) {
        try {
            Files.createDirectories(file.parent)
            YamlFiles.write(file, data)
        } catch (e: Exception) {
            onSaveError("Could not save ${file.fileName}: ${e.message}")
        }
    }
}
