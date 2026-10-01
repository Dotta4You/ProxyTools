package de.doetchen.projects.proxytools.core.storage

import de.doetchen.projects.proxytools.core.ScheduledTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class PlayerDataService(
    private val storage: PlayerStorage,
    private val schedule: (Long, () -> Unit) -> ScheduledTask,
    private val now: () -> Long = System::currentTimeMillis,
    private val onError: (String) -> Unit = {},
) {
    private class Entry(val persistent: Boolean, record: PlayerRecord? = null) {
        @Volatile
        var lastServer: String? = record?.lastServer

        @Volatile
        var messagesDisabled = record?.messagesDisabled ?: false
        val ignored = ConcurrentHashMap<UUID, String>().apply { record?.ignored?.let(::putAll) }

        fun toRecord() = PlayerRecord(lastServer, messagesDisabled, HashMap(ignored))
    }

    val storageName: String get() = storage.name
    val lazy: Boolean get() = storage.lazy

    private val entries = ConcurrentHashMap<UUID, Entry>()
    private val dirty = ConcurrentHashMap.newKeySet<UUID>()
    private val evictable = ConcurrentHashMap.newKeySet<UUID>()
    private val ioLock = Any()
    private var saveTask: ScheduledTask? = null
    private var saveDueAt = Long.MAX_VALUE
    private var lastWarning = Long.MIN_VALUE / 2

    fun load() {
        if (storage.lazy) return
        storage.loadAll().forEach { (id, record) -> entries[id] = Entry(true, record) }
    }

    fun preload(id: UUID) {
        if (!storage.lazy) return
        evictable.remove(id)
        if (entries[id]?.persistent == true) return
        entries[id] = try {
            Entry(true, storage.load(id))
        } catch (e: Exception) {
            warnThrottled("Could not load player data from ${storage.name}, changes are not saved for $id: ${e.message}")
            Entry(false)
        }
    }

    fun release(id: UUID) {
        if (!storage.lazy) return
        if (id in dirty) {
            evictable += id
            scheduleFlush(0)
        } else {
            entries.remove(id)
        }
    }

    fun lastServer(id: UUID): String? = entries[id]?.lastServer

    fun setLastServer(id: UUID, server: String) {
        val entry = entry(id)
        if (entry.lastServer == server) return
        entry.lastServer = server
        markDirty(id, entry)
    }

    fun messagesDisabled(id: UUID): Boolean = entries[id]?.messagesDisabled == true

    fun setMessagesDisabled(id: UUID, disabled: Boolean) {
        val entry = entry(id)
        if (entry.messagesDisabled == disabled) return
        entry.messagesDisabled = disabled
        markDirty(id, entry)
    }

    fun isIgnoring(id: UUID, other: UUID): Boolean = entries[id]?.ignored?.containsKey(other) == true

    fun ignore(id: UUID, other: UUID, name: String) {
        val entry = entry(id)
        entry.ignored[other] = name
        markDirty(id, entry)
    }

    fun unignore(id: UUID, other: UUID): Boolean {
        val entry = entries[id] ?: return false
        if (entry.ignored.remove(other) == null) return false
        markDirty(id, entry)
        return true
    }

    fun unignoreByName(id: UUID, name: String): Boolean {
        val entry = entries[id] ?: return false
        val match = entry.ignored.entries.firstOrNull { it.value.equals(name, ignoreCase = true) } ?: return false
        entry.ignored.remove(match.key)
        markDirty(id, entry)
        return true
    }

    fun ignoredNames(id: UUID): List<String> = entries[id]?.ignored?.values?.sortedBy { it.lowercase() }.orEmpty()

    fun flush() {
        synchronized(ioLock) {
            synchronized(this) {
                saveTask?.cancel()
                saveTask = null
                saveDueAt = Long.MAX_VALUE
            }
            val ids = dirty.toList().also { dirty.removeAll(it.toSet()) }
            val changes = ids.mapNotNull { id -> entries[id]?.takeIf { it.persistent }?.let { id to it.toRecord() } }.toMap()
            if (changes.isNotEmpty()) {
                try {
                    storage.save(changes)
                } catch (e: Exception) {
                    dirty += ids
                    warnThrottled("Could not save player data to ${storage.name}, will retry: ${e.message}")
                    scheduleFlush(RETRY_DELAY_MILLIS)
                    return
                }
            }
            evictable.toList().forEach { id ->
                if (id !in dirty) {
                    entries.remove(id)
                    evictable.remove(id)
                }
            }
        }
    }

    fun close() {
        flush()
        storage.close()
    }

    private fun entry(id: UUID) = entries.computeIfAbsent(id) { Entry(persistent = !storage.lazy) }

    private fun markDirty(id: UUID, entry: Entry) {
        if (!entry.persistent) return
        dirty += id
        scheduleFlush(storage.saveDelayMillis)
    }

    @Synchronized
    private fun scheduleFlush(delayMillis: Long) {
        val due = now() + delayMillis
        if (saveTask != null && saveDueAt <= due) return
        saveTask?.cancel()
        saveDueAt = due
        saveTask = schedule(delayMillis) { flush() }
    }

    private fun warnThrottled(message: String) {
        val time = now()
        if (time - lastWarning < WARNING_INTERVAL_MILLIS) return
        lastWarning = time
        onError(message)
    }

    private companion object {
        const val RETRY_DELAY_MILLIS = 30_000L
        const val WARNING_INTERVAL_MILLIS = 60_000L
    }
}
