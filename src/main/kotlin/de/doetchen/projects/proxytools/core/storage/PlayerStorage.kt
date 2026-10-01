package de.doetchen.projects.proxytools.core.storage

import java.util.UUID

internal class PlayerRecord(val lastServer: String?, val messagesDisabled: Boolean, val ignored: Map<UUID, String>) {
    val isDefault: Boolean get() = lastServer == null && !messagesDisabled && ignored.isEmpty()
}

internal interface PlayerStorage {
    val name: String
    val lazy: Boolean
    val saveDelayMillis: Long

    fun loadAll(): Map<UUID, PlayerRecord>

    fun load(id: UUID): PlayerRecord?

    fun save(changes: Map<UUID, PlayerRecord>)

    fun close() {}
}
