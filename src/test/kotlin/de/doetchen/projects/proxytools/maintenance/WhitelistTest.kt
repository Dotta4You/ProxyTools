package de.doetchen.projects.proxytools.maintenance

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.maintenance.WhitelistAddResult
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import de.doetchen.projects.proxytools.testing.FakePlayer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class WhitelistTest : CoreTestBase() {
    @Test
    fun `whitelist resolves by uuid, not by name`() {
        val core = core()
        val alice = UUID.randomUUID()
        core.store.addResolved(alice, "Alice")
        assertTrue(core.store.isWhitelisted(alice))
        assertFalse(core.store.isWhitelisted(UUID.randomUUID()))
    }

    @Test
    fun `pending name entry is resolved to the joining player's uuid`() {
        val core = core()
        core.store.addPending("Alice")
        val impostor = UUID.randomUUID()
        assertFalse(core.store.isWhitelisted(impostor))

        val realAlice = UUID.randomUUID()
        assertTrue(core.store.onPlayerSeen(realAlice, "Alice"))
        assertTrue(core.store.isWhitelisted(realAlice))

        assertFalse(core.store.isWhitelisted(impostor))
    }

    @Test
    fun `renaming an already-whitelisted player keeps them whitelisted under the new name`() {
        val core = core()
        val uuid = UUID.randomUUID()
        core.store.addResolved(uuid, "OldName")
        core.store.onPlayerSeen(uuid, "NewName")
        assertTrue(core.store.isWhitelisted(uuid))
        assertEquals(listOf("NewName"), core.store.entries().map { it.name })
    }

    @Test
    fun `whitelist add resolves online players immediately and stores offline ones as pending`() {
        val core = core()
        platform.players += FakePlayer("Alice")
        val admin = FakeActor(setOf(Permissions.MAINTENANCE_WHITELIST))

        core.commands.maintenance(admin, listOf("whitelist", "add", "Alice"))
        assertTrue(admin.messages.last().contains("Alice") && !admin.messages.last().contains("pending", ignoreCase = true))

        core.commands.maintenance(admin, listOf("whitelist", "add", "Bob"))
        assertTrue(admin.messages.last().contains("Bob"))
        assertEquals(1, core.store.entries().count { it.isPending })

        core.commands.maintenance(admin, listOf("whitelist", "list"))
        assertTrue(admin.messages.last().contains("Alice"))
        assertTrue(admin.messages.last().contains("Bob"))
    }

    @Test
    fun `whitelist remove works by name and by uuid`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE_WHITELIST))
        val uuid = UUID.randomUUID()
        core.store.addResolved(uuid, "Alice")

        core.commands.maintenance(admin, listOf("whitelist", "remove", "alice"))
        assertFalse(core.store.isWhitelisted(uuid))

        core.store.addResolved(uuid, "Alice")
        core.commands.maintenance(admin, listOf("whitelist", "remove", uuid.toString()))
        assertFalse(core.store.isWhitelisted(uuid))
    }

    @Test
    fun `adding a player by uuid supersedes an earlier pending entry under their name`() {
        val core = core()
        core.store.addPending("Alice")
        val realUuid = UUID.randomUUID()
        core.store.addResolved(realUuid, "Alice")

        val impostor = UUID.randomUUID()
        assertFalse(core.store.isWhitelisted(impostor))
        assertFalse(core.store.onPlayerSeen(impostor, "Alice"))
        assertFalse(core.store.isWhitelisted(impostor))

        assertTrue(core.store.isWhitelisted(realUuid))
        assertEquals(listOf("Alice"), core.store.entries().map { it.name }, "no leftover duplicate entry")
    }

    @Test
    fun `adding an existing uuid keeps the stored name`() {
        val core = core()
        val uuid = UUID.randomUUID()
        core.store.addResolved(uuid, "Alice")
        assertEquals(WhitelistAddResult.ALREADY_PRESENT, core.store.addResolved(uuid, "?"))
        assertEquals("Alice", core.store.entries().single().name)
    }
}
