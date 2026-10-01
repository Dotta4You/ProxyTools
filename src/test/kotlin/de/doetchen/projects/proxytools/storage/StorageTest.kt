package de.doetchen.projects.proxytools.storage

import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.storage.PlayerDataService
import de.doetchen.projects.proxytools.core.storage.PlayerRecord
import de.doetchen.projects.proxytools.core.storage.PlayerStorage
import de.doetchen.projects.proxytools.core.storage.SqlPlayerStorage
import de.doetchen.projects.proxytools.core.storage.YamlPlayerStorage
import de.doetchen.projects.proxytools.testing.CoreTestBase
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FlakyStorage(override val lazy: Boolean = true) : PlayerStorage {
    override val name = "flaky"
    override val saveDelayMillis = 1_000L
    val stored = mutableMapOf<UUID, PlayerRecord>()
    var failSaves = 0
    var failLoads = false
    var saves = 0

    override fun loadAll(): Map<UUID, PlayerRecord> = stored.toMap()

    override fun load(id: UUID): PlayerRecord? {
        if (failLoads) error("database down")
        return stored[id]
    }

    override fun save(changes: Map<UUID, PlayerRecord>) {
        saves++
        if (failSaves > 0) {
            failSaves--
            error("database down")
        }
        stored += changes
    }
}

internal class StorageTest : CoreTestBase() {
    private val id = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private fun store(storage: PlayerStorage) = PlayerDataService(storage, platform::runLater, platform::now, platform::warn)

    private fun h2Memory(name: String, mode: String = ""): SqlPlayerStorage =
        SqlPlayerStorage("h2 test", "t_") {
            org.h2.Driver().connect("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1$mode", java.util.Properties())
        }

    @Test
    fun `sql storage saves, loads, replaces ignore lists and deletes default records`() {
        val storage = h2Memory("sql1")
        assertTrue(storage.isEmpty())
        assertNull(storage.load(id))

        storage.save(mapOf(id to PlayerRecord("survival", true, mapOf(other to "Bob"))))
        assertFalse(storage.isEmpty())
        val loaded = storage.load(id)!!
        assertEquals("survival", loaded.lastServer)
        assertTrue(loaded.messagesDisabled)
        assertEquals(mapOf(other to "Bob"), loaded.ignored)

        storage.save(mapOf(id to PlayerRecord(null, false, mapOf(UUID.randomUUID() to "Carl"))))
        val replaced = storage.load(id)!!
        assertNull(replaced.lastServer)
        assertFalse(replaced.messagesDisabled)
        assertEquals(listOf("Carl"), replaced.ignored.values.toList())

        storage.save(mapOf(id to PlayerRecord(null, false, emptyMap())))
        assertNull(storage.load(id))
        assertTrue(storage.isEmpty())
    }

    @Test
    fun `sql storage works with the MySQL compatibility mode`() {
        val storage = h2Memory("sql2", ";MODE=MySQL")
        storage.save(mapOf(id to PlayerRecord("lobby", false, emptyMap())))
        assertEquals("lobby", storage.load(id)!!.lastServer)
    }

    @Test
    fun `sql storage rejects a table prefix that could break the statements`() {
        assertFailsWith<IllegalArgumentException> {
            SqlPlayerStorage("x", "a; DROP TABLE b;--") { error("never connects") }
        }
    }

    @Test
    fun `sql storage reconnects after the connection broke`() {
        var connects = 0
        val storage = SqlPlayerStorage("h2 test", "r_") {
            connects++
            org.h2.Driver().connect("jdbc:h2:mem:sql3;DB_CLOSE_DELAY=-1", java.util.Properties())
        }
        storage.save(mapOf(id to PlayerRecord("a", false, emptyMap())))
        storage.close()
        assertEquals("a", storage.load(id)!!.lastServer)
        assertEquals(2, connects)
    }

    @Test
    fun `h2 storage keeps player data across restarts and is loaded per player`() {
        val first = core("storage:\n  type: h2\n")
        assertTrue(platform.loggedInfo.any { it.contains("H2") })
        first.playerData.preload(id)
        first.playerData.setLastServer(id, "survival")
        first.playerData.setMessagesDisabled(id, true)
        first.playerData.ignore(id, other, "Bob")
        platform.advance(1_000)
        first.shutdown()

        val second = core("storage:\n  type: h2\n")
        assertNull(second.playerData.lastServer(id), "nothing is loaded before the player logs in")
        second.playerData.preload(id)
        assertEquals("survival", second.playerData.lastServer(id))
        assertTrue(second.playerData.messagesDisabled(id))
        assertTrue(second.playerData.isIgnoring(id, other))
        assertTrue(Files.exists(folder.resolve("data/players.mv.db")))
    }

    @Test
    fun `an existing players yml is imported once into an empty database`() {
        YamlPlayerStorage(folder.resolve("data/players.yml")).save(mapOf(id to PlayerRecord("creative", false, mapOf(other to "Bob"))))
        val core = core("storage:\n  type: h2\n")
        assertTrue(platform.loggedInfo.any { it.contains("Imported 1 players") })
        assertFalse(Files.exists(folder.resolve("data/players.yml")))
        assertTrue(Files.exists(folder.resolve("data/players.yml.imported")))
        core.playerData.preload(id)
        assertEquals("creative", core.playerData.lastServer(id))
        assertTrue(core.playerData.isIgnoring(id, other))
    }

    @Test
    fun `an unreachable mysql server falls back to the yaml file instead of breaking`() {
        val core = core("storage:\n  type: mysql\n  mysql:\n    host: 127.0.0.1\n    port: 1\n")
        assertTrue(platform.warnings.any { it.contains("Could not open the 'mysql' storage") })
        core.playerData.setLastServer(id, "survival")
        platform.advance(5_000)
        assertTrue(Files.readString(folder.resolve("data/players.yml")).contains("survival"))
    }

    @Test
    fun `invalid storage settings fall back with a warning`() {
        core("storage:\n  type: nonsense\n")
        assertTrue(platform.warnings.any { it.contains("Unknown storage.type 'nonsense'") })
        platform.warnings.clear()
        core("storage:\n  type: mysql\n  mysql:\n    database: 'bad name;'\n")
        assertTrue(platform.warnings.any { it.contains("unsupported characters") })
    }

    @Test
    fun `changing the storage settings in a reload asks for a restart`() {
        val core = core("language: en\n")
        Files.writeString(folder.resolve("config.yml"), "storage:\n  type: h2\n")
        core.reload()
        assertTrue(platform.warnings.any { it.contains("restart") })
    }

    @Test
    fun `failed saves are retried and not lost`() {
        val storage = FlakyStorage().apply { failSaves = 1 }
        val store = store(storage)
        store.preload(id)
        store.setLastServer(id, "survival")
        platform.advance(1_000)
        assertEquals(1, storage.saves)
        assertTrue(storage.stored.isEmpty())
        assertEquals(1, platform.warnings.size)

        platform.advance(30_000)
        assertEquals("survival", storage.stored[id]!!.lastServer)
    }

    @Test
    fun `a player whose data could not be loaded does not overwrite the stored record`() {
        val storage = FlakyStorage().apply {
            stored[id] = PlayerRecord("survival", true, mapOf(other to "Bob"))
            failLoads = true
        }
        val store = store(storage)
        store.preload(id)
        store.setLastServer(id, "creative")
        platform.advance(10_000)
        store.flush()
        assertEquals(0, storage.saves)
        assertEquals("survival", storage.stored[id]!!.lastServer)
        assertEquals("creative", store.lastServer(id), "still usable for this session")
    }

    @Test
    fun `releasing a player saves pending changes and frees the memory`() {
        val storage = FlakyStorage()
        val store = store(storage)
        store.preload(id)
        store.setLastServer(id, "survival")
        store.release(id)
        platform.advance(0)
        assertEquals("survival", storage.stored[id]!!.lastServer)
        assertNull(store.lastServer(id))

        store.preload(id)
        assertEquals("survival", store.lastServer(id))
        store.release(id)
        assertNull(store.lastServer(id), "an untouched entry is dropped right away")
    }

    @Test
    fun `rejoining before the save finished keeps the player loaded`() {
        val storage = FlakyStorage()
        val store = store(storage)
        store.preload(id)
        store.setLastServer(id, "survival")
        store.release(id)
        store.preload(id)
        platform.advance(0)
        assertEquals("survival", store.lastServer(id))
    }

    @Test
    fun `writes are batched into one save`() {
        val storage = FlakyStorage()
        val store = store(storage)
        store.preload(id)
        store.preload(other)
        repeat(20) { store.setLastServer(id, "s$it") }
        store.setLastServer(other, "lobby")
        platform.advance(1_000)
        assertEquals(1, storage.saves)
        assertEquals("s19", storage.stored[id]!!.lastServer)
        assertNotNull(storage.stored[other])
    }

    @Test
    fun `the yaml storage keeps everything in memory and writes after its delay`() {
        val core = core("language: en\n")
        core.playerData.setLastServer(id, "survival")
        assertFalse(Files.exists(folder.resolve("data/players.yml")))
        platform.advance(5_000)
        assertTrue(Files.readString(folder.resolve("data/players.yml")).contains("survival"))

        core.shutdown()
        val restarted = ProxyToolsCore(platform)
        assertEquals("survival", restarted.playerData.lastServer(id))
    }
}
