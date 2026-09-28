package de.doetchen.projects.proxytools.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePlayer(
    override val name: String,
    private val permissions: Set<String> = emptySet(),
    override val uniqueId: UUID = UUID.randomUUID(),
) : PlatformPlayer {
    var kickedWith: String? = null

    override fun hasPermission(permission: String) = permission in permissions
    override fun disconnect(message: String) {
        kickedWith = message
    }
}

private class FakeActor(private val permissions: Set<String> = emptySet()) : CommandActor {
    val messages = mutableListOf<String>()

    override fun hasPermission(permission: String) = permission in permissions
    override fun sendMessage(message: String) {
        messages += message
    }
}

private class FakePlatform(override val dataFolder: Path) : Platform {
    override val platformName = "Test"
    override val pluginVersion = "0.0.0"
    val players = mutableListOf<FakePlayer>()
    override val onlinePlayers: Collection<PlatformPlayer> get() = players.toList()

    override fun info(message: String) = Unit
    override fun warn(message: String) = println("WARN: $message")
}

class CoreTest {
    private val folder: Path = Files.createTempDirectory("proxytools-test")
    private val platform = FakePlatform(folder)
    private fun core() = ProxyToolsCore(platform)

    @Test
    fun `colorize converts legacy and hex codes`() {
        assertEquals("§aHi §lthere", Text.colorize("&aHi &lthere"))
        assertEquals("§x§f§f§a§a§0§0x", Text.colorize("&#FFAA00x"))
        assertEquals("a & b &z", Text.colorize("a & b &z"))
    }

    @Test
    fun `gradient colors each character between the two hex colors`() {
        val result = Text.colorize("<gradient:#ff0000:#0000ff>ab</gradient>")
        // a -> red, b -> blue
        assertEquals("§x§f§f§0§0§0§0a§x§0§0§0§0§f§fb", result)
    }

    @Test
    fun `rainbow colorizes every character and leaves the rest untouched`() {
        val result = Text.colorize("<rainbow>ab</rainbow> plain")
        assertTrue(result.startsWith("§x"))
        assertTrue(result.endsWith(" plain"))
    }

    @Test
    fun `placeholders are replaced`() {
        assertEquals("3/10", Text.replace("%online%/%max%", "online" to "3", "max" to "10"))
    }

    @Test
    fun `default files are created and valid`() {
        val core = core()
        assertTrue(Files.exists(folder.resolve("config.yml")))
        assertTrue(Files.exists(folder.resolve("lang/en.yml")))
        assertTrue(core.config.mapList("motd.entries").isNotEmpty())
        assertTrue(core.config.boolean("maintenance.kick-on-enable"))
    }

    @Test
    fun `missing keys fall back to bundled defaults`() {
        Files.writeString(folder.resolve("config.yml"), "motd:\n  max-players: 42\n")
        val core = core()
        assertEquals(42, core.config.int("motd.max-players"))
        assertTrue(core.config.mapList("maintenance.motd.entries").isNotEmpty())
    }

    @Test
    fun `broken config keeps previous values on reload`() {
        val core = core()
        Files.writeString(folder.resolve("config.yml"), "motd: [unclosed")
        assertFalse(core.reload())
        assertTrue(core.config.mapList("motd.entries").isNotEmpty())
    }

    @Test
    fun `german language file is used when configured, unknown language falls back to english`() {
        Files.writeString(folder.resolve("config.yml"), "language: de\n")
        val german = core()
        assertTrue(german.message("reloaded").contains("neu geladen"))

        Files.writeString(folder.resolve("config.yml"), "language: fr\n")
        val fallback = core()
        assertTrue(fallback.message("reloaded").contains("reloaded"))
    }

    @Test
    fun `normal ping uses motd, maintenance ping uses maintenance motd`() {
        val core = core()
        val normal = assertNotNull(core.motd.build(5, 100))
        assertNotNull(normal.description)
        assertNull(normal.versionName)
        assertNull(normal.hoverLines)

        core.maintenance.setEnabled(true)
        val maintenance = assertNotNull(core.motd.build(5, 100))
        assertTrue(maintenance.description!!.contains("Maintenance"))
        assertEquals("§cMaintenance", maintenance.versionName)
        assertNotNull(maintenance.hoverLines)
    }

    @Test
    fun `motd can be disabled and placeholders use the effective max`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  entries:\n    - line1: 'a %online%/%max%'\n  max-players: 7\n",
        )
        assertEquals("a 2/7", core().motd.build(2, 100)!!.description)

        Files.writeString(folder.resolve("config.yml"), "motd:\n  enabled: false\n")
        assertNull(core().motd.build(2, 100))
    }

    @Test
    fun `sequential mode cycles through entries in order`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  mode: sequential\n  entries:\n    - line1: 'one'\n    - line1: 'two'\n",
        )
        val core = core()
        val descriptions = (1..4).map { core.motd.build(0, 10)!!.description }
        assertEquals(listOf("one", "two", "one", "two"), descriptions)
    }

    @Test
    fun `hover mode players leaves the sample untouched`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  hover:\n    enabled: true\n    mode: players\n",
        )
        assertNull(core().motd.build(2, 100)!!.hoverLines)
    }

    @Test
    fun `favicon falls back to the normal icon during maintenance if no maintenance icon exists`() {
        Files.write(folder.resolve("icon.png"), byteArrayOf(1, 2, 3))
        val core = core()
        assertEquals(listOf<Byte>(1, 2, 3), core.motd.build(0, 10)!!.faviconBytes!!.toList())

        core.maintenance.setEnabled(true)
        assertEquals(listOf<Byte>(1, 2, 3), core.motd.build(0, 10)!!.faviconBytes!!.toList())

        Files.write(folder.resolve("icon-maintenance.png"), byteArrayOf(9, 9))
        assertEquals(listOf<Byte>(9, 9), core.motd.build(0, 10)!!.faviconBytes!!.toList())
    }

    @Test
    fun `favicon cache only re-reads a changed file`() {
        val cache = FaviconCache(folder)
        assertNull(cache.bytes("icon.png"))

        val file = folder.resolve("icon.png")
        Files.write(file, byteArrayOf(1))
        val first = cache.bytes("icon.png")
        assertEquals(listOf<Byte>(1), first!!.toList())
        assertTrue(first === cache.bytes("icon.png"), "unchanged file should return the same instance")

        // Bump the mtime explicitly: some filesystems have a coarser timestamp resolution than a
        // fast test can otherwise produce between two writes.
        Files.write(file, byteArrayOf(2))
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000))
        val second = cache.bytes("icon.png")
        assertEquals(listOf<Byte>(2), second!!.toList())
        assertFalse(first === second, "changed file should be re-read")
    }

    @Test
    fun `enabling maintenance kicks everybody without bypass and reports the count`() {
        val core = core()
        val normal = FakePlayer("Normal")
        val staff = FakePlayer("Staff", setOf(Permissions.MAINTENANCE_BYPASS))
        val listed = FakePlayer("Listed")
        platform.players += listOf(normal, staff, listed)
        core.store.addResolved(listed.uniqueId, "Listed")

        val result = core.maintenance.setEnabled(true) as ToggleResult.Changed
        assertEquals(1, result.kicked)
        assertTrue(core.maintenance.setEnabled(true) is ToggleResult.Unchanged)

        assertNotNull(normal.kickedWith)
        assertNull(staff.kickedWith)
        assertNull(listed.kickedWith)
    }

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
        // a different player using the same name must NOT be treated as whitelisted after someone else claimed it
        assertFalse(core.store.isWhitelisted(impostor))

        val realAlice = UUID.randomUUID()
        assertTrue(core.store.onPlayerSeen(realAlice, "Alice"))
        assertTrue(core.store.isWhitelisted(realAlice))

        // a later account reusing the name (e.g. after a rename) is not automatically whitelisted
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
    fun `state survives a restart`() {
        val uuid = UUID.randomUUID()
        core().apply {
            store.addResolved(uuid, "alice")
            store.addPending("bob")
            maintenance.setEnabled(true)
        }
        val restarted = core()
        assertTrue(restarted.maintenance.enabled)
        assertTrue(restarted.store.isWhitelisted(uuid))
        assertEquals(setOf("alice", "bob"), restarted.store.entries().map { it.name }.toSet())
    }

    @Test
    fun `maintenance command checks permissions`() {
        val core = core()
        val nobody = FakeActor()
        core.commands.maintenance(nobody, listOf("on"))
        assertFalse(core.maintenance.enabled)
        assertTrue(nobody.messages.single().contains("permission"))

        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("on"))
        assertTrue(core.maintenance.enabled)
        core.commands.maintenance(admin, listOf("whitelist", "add", "x"))
        assertTrue(admin.messages.last().contains("permission"), "whitelist needs its own permission")
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
    fun `tab completion respects permissions and prefix`() {
        val core = core()
        platform.players += FakePlayer("Alice")
        val admin = FakeActor(setOf(Permissions.MAINTENANCE, Permissions.MAINTENANCE_WHITELIST))
        assertEquals(listOf("on", "off"), core.commands.suggestMaintenance(admin, listOf("o")))
        assertEquals(listOf("whitelist"), core.commands.suggestMaintenance(FakeActor(setOf(Permissions.MAINTENANCE_WHITELIST)), listOf("")))
        assertEquals(listOf("add", "remove", "list"), core.commands.suggestMaintenance(admin, listOf("whitelist", "")))
        assertEquals(listOf("Alice"), core.commands.suggestMaintenance(admin, listOf("whitelist", "add", "al")))
    }

    @Test
    fun `reload command`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.RELOAD))
        core.commands.proxyTools(admin, listOf("reload"))
        assertTrue(admin.messages.single().contains("reloaded"))
    }
}
