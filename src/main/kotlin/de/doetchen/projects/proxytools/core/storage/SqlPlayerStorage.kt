package de.doetchen.projects.proxytools.core.storage

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

internal class SqlPlayerStorage(
    override val name: String,
    tablePrefix: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val connect: () -> Connection,
) : PlayerStorage {
    override val lazy = true
    override val saveDelayMillis = 1_000L

    private val players = "${tablePrefix}players"
    private val ignores = "${tablePrefix}ignores"
    private val lock = Any()
    private var connection: Connection? = null
    private var retryAfter = 0L

    init {
        require(TABLE_PREFIX.matches(tablePrefix)) { "table-prefix may only contain letters, digits and underscores" }
        withConnection { conn ->
            conn.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS $players (uuid CHAR(36) NOT NULL PRIMARY KEY, " +
                        "last_server VARCHAR(64) NULL, messages_disabled BOOLEAN NOT NULL DEFAULT FALSE)",
                )
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS $ignores (owner CHAR(36) NOT NULL, ignored CHAR(36) NOT NULL, " +
                        "ignored_name VARCHAR(64) NOT NULL, PRIMARY KEY (owner, ignored))",
                )
            }
        }
    }

    override fun loadAll(): Map<UUID, PlayerRecord> = emptyMap()

    fun isEmpty(): Boolean = withConnection { conn ->
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT 1 FROM $players LIMIT 1").use { !it.next() }
        }
    }

    override fun load(id: UUID): PlayerRecord? = withConnection { conn ->
        var lastServer: String? = null
        var messagesDisabled = false
        var found = false
        conn.prepareStatement("SELECT last_server, messages_disabled FROM $players WHERE uuid = ?").use { statement ->
            statement.setString(1, id.toString())
            statement.executeQuery().use {
                if (it.next()) {
                    found = true
                    lastServer = it.getString(1)
                    messagesDisabled = it.getBoolean(2)
                }
            }
        }
        val ignored = LinkedHashMap<UUID, String>()
        conn.prepareStatement("SELECT ignored, ignored_name FROM $ignores WHERE owner = ?").use { statement ->
            statement.setString(1, id.toString())
            statement.executeQuery().use {
                while (it.next()) {
                    runCatching { UUID.fromString(it.getString(1)) }.getOrNull()?.let { other -> ignored[other] = it.getString(2) }
                }
            }
        }
        if (found || ignored.isNotEmpty()) PlayerRecord(lastServer, messagesDisabled, ignored) else null
    }

    override fun save(changes: Map<UUID, PlayerRecord>) = withConnection { conn ->
        conn.autoCommit = false
        try {
            changes.forEach { (id, record) -> write(conn, id.toString(), record) }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            runCatching { conn.autoCommit = true }
        }
    }

    private fun write(conn: Connection, id: String, record: PlayerRecord) {
        conn.prepareStatement("DELETE FROM $ignores WHERE owner = ?").use {
            it.setString(1, id)
            it.executeUpdate()
        }
        if (record.isDefault) {
            conn.prepareStatement("DELETE FROM $players WHERE uuid = ?").use {
                it.setString(1, id)
                it.executeUpdate()
            }
            return
        }
        val updated = conn.prepareStatement("UPDATE $players SET last_server = ?, messages_disabled = ? WHERE uuid = ?").use {
            it.setString(1, record.lastServer)
            it.setBoolean(2, record.messagesDisabled)
            it.setString(3, id)
            it.executeUpdate()
        }
        if (updated == 0) {
            conn.prepareStatement("INSERT INTO $players (uuid, last_server, messages_disabled) VALUES (?, ?, ?)").use {
                it.setString(1, id)
                it.setString(2, record.lastServer)
                it.setBoolean(3, record.messagesDisabled)
                it.executeUpdate()
            }
        }
        if (record.ignored.isNotEmpty()) {
            conn.prepareStatement("INSERT INTO $ignores (owner, ignored, ignored_name) VALUES (?, ?, ?)").use { statement ->
                record.ignored.forEach { (other, otherName) ->
                    statement.setString(1, id)
                    statement.setString(2, other.toString())
                    statement.setString(3, otherName.take(64))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { connection?.close() }
            connection = null
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T = synchronized(lock) {
        val conn = connection?.takeIf { runCatching { it.isValid(VALIDATION_TIMEOUT_SECONDS) }.getOrDefault(false) }
            ?: run {
                runCatching { connection?.close() }
                open()
            }
        try {
            block(conn)
        } catch (e: SQLException) {
            runCatching { conn.close() }
            connection = null
            throw e
        }
    }

    private fun open(): Connection {
        val time = now()
        if (time < retryAfter) throw SQLException("database unavailable, next attempt in ${(retryAfter - time) / 1000 + 1}s")
        return try {
            connect().also { connection = it }
        } catch (e: Exception) {
            retryAfter = time + RETRY_MILLIS
            throw e
        }
    }

    private companion object {
        val TABLE_PREFIX = Regex("[A-Za-z0-9_]*")
        const val VALIDATION_TIMEOUT_SECONDS = 2
        const val RETRY_MILLIS = 15_000L
    }
}
