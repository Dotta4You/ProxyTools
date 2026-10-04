package de.doetchen.projects.proxytools.storage

import de.doetchen.projects.proxytools.core.storage.PlayerRecord
import de.doetchen.projects.proxytools.core.storage.SqlPlayerStorage
import de.doetchen.projects.proxytools.core.storage.YamlPlayerStorage
import de.doetchen.projects.proxytools.testing.CoreTestBase
import java.nio.file.Files
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PlayerDataTest : CoreTestBase() {
    @Test
    fun `player data is written after a short delay and on shutdown`() {
        val core = core()
        val id = UUID.randomUUID()
        core.playerData.setLastServer(id, "survival")
        assertFalse(Files.exists(folder.resolve("data/players.yml")))
        platform.advance(5_000)
        assertTrue(Files.readString(folder.resolve("data/players.yml")).contains("survival"))

        core.playerData.setLastServer(id, "creative")
        core.shutdown()
        assertTrue(Files.readString(folder.resolve("data/players.yml")).contains("creative"))
    }

    @Test
    fun `an unreachable database is not asked again for a while`() {
        var clock = 0L
        var down = false
        var attempts = 0
        val backend = SqlPlayerStorage("test", "b_", { clock }) {
            attempts++
            if (down) throw SQLException("connection refused")
            org.h2.Driver().connect("jdbc:h2:mem:breaker;DB_CLOSE_DELAY=-1", java.util.Properties())
        }
        backend.close()
        down = true
        attempts = 0

        assertFailsWith<SQLException> { backend.load(UUID.randomUUID()) }
        assertFailsWith<SQLException> { backend.load(UUID.randomUUID()) }
        assertEquals(1, attempts, "the second call must not wait for another connect timeout")

        clock += 15_000
        down = false
        assertNull(backend.load(UUID.randomUUID()))
        assertEquals(2, attempts)
    }

    @Test
    fun `a damaged players file is set aside instead of being overwritten`() {
        Files.createDirectories(folder.resolve("data"))
        Files.writeString(folder.resolve("data/players.yml"), "players: {broken\n")

        val core = core()
        val id = UUID.randomUUID()
        core.playerData.setLastServer(id, "survival")
        platform.advance(5_000)

        assertTrue(platform.warnings.any { it.contains("players.yml") && it.contains("renamed") })
        assertEquals("players: {broken\n", Files.readString(folder.resolve("data/players.yml.broken")))
        assertTrue(Files.readString(folder.resolve("data/players.yml")).contains("survival"))
    }

    @Test
    fun `a large players file suggests the h2 storage once`() {
        val many = (1..10_000).associate { UUID.randomUUID() to PlayerRecord("survival", false, emptyMap()) }
        YamlPlayerStorage(folder.resolve("data/players.yml")).save(many)

        core()

        assertTrue(platform.loggedInfo.any { it.contains("10000 players") && it.contains("storage.type: h2") })
    }

    @Test
    fun `a small players file gets no advice`() {
        YamlPlayerStorage(folder.resolve("data/players.yml")).save(mapOf(UUID.randomUUID() to PlayerRecord("survival", false, emptyMap())))

        core()

        assertTrue(platform.loggedInfo.none { it.contains("storage.type") })
    }
}
