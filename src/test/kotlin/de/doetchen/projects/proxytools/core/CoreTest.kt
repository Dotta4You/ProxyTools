package de.doetchen.projects.proxytools.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePlayer(
    override val name: String,
    private val permissions: Set<String> = emptySet(),
    override val uniqueId: UUID = UUID.randomUUID(),
    private val knownServers: Set<String> = setOf("lobby"),
) : PlatformPlayer {
    var kickedWith: String? = null
    var redirectedTo: String? = null
    val received = mutableListOf<String>()

    override fun hasPermission(permission: String) = permission in permissions
    override fun disconnect(message: String) {
        kickedWith = message
    }
    override fun sendMessage(message: String) {
        received += message
    }
    override fun redirectTo(serverName: String): Boolean {
        if (serverName !in knownServers) return false
        redirectedTo = serverName
        return true
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
    override val proxyName = "TestProxy"
    override val proxyVersion = "1.0"
    override val configuredMaxPlayers = 100
    val players = mutableListOf<FakePlayer>()
    val loggedInfo = mutableListOf<String>()
    override val onlinePlayers: Collection<PlatformPlayer> get() = players.toList()

    var clock = 0L
    private data class Pending(val fireAt: Long, val task: () -> Unit, val cancelled: BooleanArray)
    private val scheduled = mutableListOf<Pending>()

    override fun info(message: String) {
        loggedInfo += message
    }
    override fun warn(message: String) = println("WARN: $message")
    override fun now(): Long = clock

    override fun runLater(delayMillis: Long, task: () -> Unit): ScheduledTask {
        val cancelled = booleanArrayOf(false)
        scheduled += Pending(clock + delayMillis, task, cancelled)
        return ScheduledTask { cancelled[0] = true }
    }

    fun advance(byMillis: Long) {
        clock += byMillis
        val due = scheduled.filter { it.fireAt <= clock }
        scheduled.removeAll(due)
        due.filterNot { it.cancelled[0] }.sortedBy { it.fireAt }.forEach { it.task() }
    }
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
    fun `startup banner reports plugin, proxy and language`() {
        core()
        val banner = platform.loggedInfo.joinToString("\n")
        assertTrue(banner.contains("0.0.0"))
        assertTrue(banner.contains("TestProxy"))
        assertTrue(banner.contains("en"))
    }

    @Test
    fun `startup banner surfaces a running timer after a restart`() {
        core().maintenance.enableFor(60_000)
        core()
        assertTrue(platform.loggedInfo.joinToString("\n").contains("1m"))
    }

    @Test
    fun `startup banner surfaces a pending schedule after a restart`() {
        core().maintenance.scheduleStart(60_000, 30_000)
        core()
        assertTrue(platform.loggedInfo.joinToString("\n").contains("1m"))
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
    fun `sequential motd is sticky within an interval, then advances`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  mode: sequential\n  interval-seconds: 5\n  entries:\n    - line1: 'one'\n    - line1: 'two'\n",
        )
        val core = core()
        assertEquals("one", core.motd.build(0, 10)!!.description)
        platform.advance(4_000)
        assertEquals("one", core.motd.build(0, 10)!!.description, "still the same entry within the interval")
        platform.advance(1_000)
        assertEquals("two", core.motd.build(0, 10)!!.description, "next interval, next entry")
    }

    @Test
    fun `static motd always uses the first entry`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  mode: static\n  interval-seconds: 1\n  entries:\n    - line1: 'one'\n    - line1: 'two'\n",
        )
        val core = core()
        repeat(5) {
            assertEquals("one", core.motd.build(0, 10)!!.description)
            platform.advance(1_000)
        }
    }

    @Test
    fun `motd placeholders stay live even though the template is cached per interval`() {
        Files.writeString(folder.resolve("config.yml"), "motd:\n  entries:\n    - line1: '%online%'\n")
        val core = core()
        assertEquals("3", core.motd.build(3, 100)!!.description)
        assertEquals("7", core.motd.build(7, 100)!!.description, "same bucket, but online count must stay live")
    }

    @Test
    fun `random motd is sticky within an interval`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  interval-seconds: 5\n  entries:\n    - line1: 'a'\n    - line1: 'b'\n    - line1: 'c'\n",
        )
        val core = core()
        val first = core.motd.build(0, 10)!!.description
        platform.advance(4_000)
        assertEquals(first, core.motd.build(0, 10)!!.description)
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
    fun `tab completion offers no duration after schedule cancel`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        assertEquals(listOf("cancel", "10m", "30m", "1h"), core.commands.suggestMaintenance(admin, listOf("schedule", "")))
        assertEquals(listOf("10m", "30m", "1h"), core.commands.suggestMaintenance(admin, listOf("schedule", "10m", "")))
        assertEquals(emptyList(), core.commands.suggestMaintenance(admin, listOf("schedule", "cancel", "")))
    }

    @Test
    fun `reload command`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.RELOAD))
        core.commands.proxyTools(admin, listOf("reload"))
        assertTrue(admin.messages.single().contains("reloaded"))
    }

    @Test
    fun `duration text parses and formats`() {
        assertEquals(90_000L, DurationText.parseMillis("1m30s"))
        assertEquals(3_600_000L, DurationText.parseMillis("1h"))
        assertNull(DurationText.parseMillis("garbage"))
        assertNull(DurationText.parseMillis("0s"))
        assertNull(DurationText.parseMillis("30m1h"))

        assertEquals("1h 2m", DurationText.format(3725))
        assertEquals("45s", DurationText.format(45))
        assertEquals("0s", DurationText.format(0))
    }

    @Test
    fun `maintenance timer auto-disables after the duration and reports it`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("on", "5s"))
        assertTrue(admin.messages.last().contains("5s"))
        assertTrue(core.maintenance.enabled)

        platform.advance(4_999)
        assertTrue(core.maintenance.enabled)
        platform.advance(1)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `invalid duration is rejected without changing state`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("on", "notaduration"))
        assertFalse(core.maintenance.enabled)
        assertTrue(admin.messages.last().contains("notaduration"))
    }

    @Test
    fun `enableFor rejects a non-positive duration`() {
        assertFailsWith<IllegalArgumentException> { core().maintenance.enableFor(0) }
    }

    @Test
    fun `enableFor refuses to replace an already-running timer`() {
        val core = core()
        assertTrue(core.maintenance.enableFor(60_000) is TimerResult.Started)
        assertEquals(TimerResult.AlreadyRunning, core.maintenance.enableFor(30_000))

        platform.advance(59_999)
        assertTrue(core.maintenance.enabled, "the second call must not have shortened the original timer")
    }

    @Test
    fun `command rejects setting a new duration while one is already running`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("on", "10m"))
        core.commands.maintenance(admin, listOf("on", "5m"))
        assertTrue(admin.messages.last().contains("10m"), "should report the still-running timer's remaining time")
    }

    @Test
    fun `timer-warnings ignores non-positive and duplicate entries instead of misfiring`() {
        Files.writeString(folder.resolve("config.yml"), "maintenance:\n  timer-warnings: [5, 5, -1, 0]\n")
        val core = core()
        val staff = FakePlayer("Staff", setOf(Permissions.MAINTENANCE_BYPASS))
        platform.players += staff

        core.maintenance.enableFor(10_000)
        platform.advance(5_000)
        assertEquals(1, staff.received.count { it.contains("5s") }, "the duplicate 5 must not fire twice")
        platform.advance(5_000)
        assertFalse(core.maintenance.enabled)
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
    fun `manually turning maintenance off cancels a pending timer`() {
        val core = core()
        core.maintenance.enableFor(5_000)
        core.maintenance.setEnabled(false)
        platform.advance(10_000)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `timer warnings are broadcast to online players before it ends`() {
        Files.writeString(folder.resolve("config.yml"), "maintenance:\n  timer-warnings: [5]\n")
        val core = core()
        val staff = FakePlayer("Staff", setOf(Permissions.MAINTENANCE_BYPASS))
        platform.players += staff

        core.maintenance.enableFor(10_000)
        platform.advance(5_000)
        assertTrue(staff.received.any { it.contains("5s") })
        platform.advance(5_000)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `a running timer resumes after a restart`() {
        core().maintenance.enableFor(60_000)
        val restarted = core()
        assertTrue(restarted.maintenance.enabled)
        platform.advance(59_000)
        assertTrue(restarted.maintenance.enabled)
        platform.advance(1_000)
        assertFalse(restarted.maintenance.enabled)
    }

    @Test
    fun `status permission works independently of the full maintenance permission`() {
        val core = core()
        val viewer = FakeActor(setOf(Permissions.MAINTENANCE_STATUS))
        core.commands.maintenance(viewer, listOf("status"))
        assertTrue(viewer.messages.single().contains("disabled"))

        viewer.messages.clear()
        core.commands.maintenance(viewer, listOf("on"))
        assertFalse(core.maintenance.enabled)
        assertTrue(viewer.messages.single().contains("permission"))
    }

    @Test
    fun `status shows remaining time for a running timer`() {
        val core = core()
        core.maintenance.enableFor(60_000)
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("status"))
        assertTrue(admin.messages.single().contains("1m"))
    }

    @Test
    fun `broadcast requires its own permission and reaches online players`() {
        val core = core()
        val listener = FakePlayer("Listener")
        platform.players += listener

        val nobody = FakeActor()
        core.commands.broadcast(nobody, listOf("hi"))
        assertTrue(nobody.messages.single().contains("permission"))
        assertTrue(listener.received.isEmpty())

        val admin = FakeActor(setOf(Permissions.BROADCAST))
        core.commands.broadcast(admin, listOf("Hello", "everyone"))
        assertTrue(listener.received.single().contains("Hello everyone"))
    }

    @Test
    fun `canBypass fails closed if a permission check throws unexpectedly`() {
        val core = core()
        val broken = object : PlatformPlayer {
            override val uniqueId: UUID = UUID.randomUUID()
            override val name = "Broken"
            override fun hasPermission(permission: String): Boolean = error("boom")
            override fun disconnect(message: String) = Unit
            override fun sendMessage(message: String) = Unit
            override fun redirectTo(serverName: String) = false
        }
        assertFalse(core.maintenance.canBypass(broken))
    }

    @Test
    fun `favicon cache does not throw when the path is a directory instead of a file`() {
        Files.createDirectories(folder.resolve("icon.png"))
        val cache = FaviconCache(folder) { }
        assertNull(cache.bytes("icon.png"))
    }

    @Test
    fun `config migrator stamps a fresh config with the current version`() {
        val file = folder.resolve("config.yml")
        Files.writeString(file, "language: en\n")
        ConfigMigrator.migrateInPlace(file)
        assertTrue(Files.readString(file).contains("config-version: ${ConfigMigrator.CURRENT_VERSION}"))
        assertTrue(Files.readString(file).contains("language: en"), "existing settings must survive the migration")
    }

    @Test
    fun `config migrator leaves an already-current file untouched`() {
        val file = folder.resolve("config.yml")
        val content = "config-version: ${ConfigMigrator.CURRENT_VERSION}\nlanguage: en\n"
        Files.writeString(file, content)
        ConfigMigrator.migrateInPlace(file)
        assertEquals(content, Files.readString(file))
    }

    @Test
    fun `config migrator does nothing if the file does not exist yet`() {
        val file = folder.resolve("does-not-exist.yml")
        ConfigMigrator.migrateInPlace(file)
        assertFalse(Files.exists(file))
    }

    @Test
    fun `config migrator move, remove and transform helpers work on nested paths`() {
        val root: MutableMap<String, Any?> = linkedMapOf(
            "motd" to linkedMapOf<String, Any?>("old-name" to 5, "drop-me" to "x"),
        )
        with(ConfigMigrator) {
            root.moveValue("motd.old-name", "motd.new-name")
            root.removeValue("motd.drop-me")
            root.transformValue("motd.new-name") { (it as Int) * 2 }
        }
        @Suppress("UNCHECKED_CAST")
        val motd = root["motd"] as Map<String, Any?>
        assertEquals(10, motd["new-name"])
        assertFalse(motd.containsKey("old-name"))
        assertFalse(motd.containsKey("drop-me"))
    }

    @Test
    fun `a broken config still fails reload cleanly even with the migrator in front of it`() {
        val core = core()
        Files.writeString(folder.resolve("config.yml"), "motd: [unclosed")
        assertFalse(core.reload())
        assertTrue(core.config.mapList("motd.entries").isNotEmpty())
    }

    @Test
    fun `motd preview command shows the active entry, or says nothing is active`() {
        Files.writeString(folder.resolve("config.yml"), "motd:\n  entries:\n    - line1: 'Hello world'\n")
        val core = core()
        val actor = FakeActor()
        core.commands.proxyTools(actor, listOf("motd"))
        assertTrue(actor.messages.single().contains("Hello world"))

        Files.writeString(folder.resolve("config.yml"), "motd:\n  enabled: false\n")
        core.reload()
        actor.messages.clear()
        core.commands.proxyTools(actor, listOf("motd"))
        assertFalse(actor.messages.single().contains("Hello world"))
    }

    @Test
    fun `scheduled maintenance starts automatically and runs for the given duration`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.commands.maintenance(admin, listOf("schedule", "10m", "5m"))
        assertTrue(admin.messages.last().contains("10m"))
        assertFalse(core.maintenance.enabled)

        platform.advance(10 * 60_000 - 1)
        assertFalse(core.maintenance.enabled)
        platform.advance(1)
        assertTrue(core.maintenance.enabled, "should have started automatically")

        platform.advance(5 * 60_000 - 1)
        assertTrue(core.maintenance.enabled)
        platform.advance(1)
        assertFalse(core.maintenance.enabled, "should have ended automatically after its own duration")
    }

    @Test
    fun `scheduling refuses a second window and refuses while already active`() {
        val core = core()
        assertEquals(ScheduleResult.Scheduled, core.maintenance.scheduleStart(60_000, 60_000))
        assertEquals(ScheduleResult.AlreadyScheduled, core.maintenance.scheduleStart(30_000, 30_000))

        assertTrue(core.maintenance.cancelSchedule())
        assertFalse(core.maintenance.cancelSchedule(), "cancelling twice reports nothing was pending")

        core.maintenance.setEnabled(true)
        assertEquals(ScheduleResult.AlreadyActive, core.maintenance.scheduleStart(60_000, 60_000))
    }

    @Test
    fun `cancelling a schedule prevents it from starting`() {
        val core = core()
        core.maintenance.scheduleStart(60_000, 60_000)
        assertTrue(core.maintenance.cancelSchedule())
        platform.advance(120_000)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `a pending schedule survives a restart`() {
        core().maintenance.scheduleStart(60_000, 30_000)
        val restarted = core()
        assertFalse(restarted.maintenance.enabled)
        platform.advance(60_000)
        assertTrue(restarted.maintenance.enabled)
    }

    @Test
    fun `status reflects a pending schedule`() {
        val core = core()
        val admin = FakeActor(setOf(Permissions.MAINTENANCE))
        core.maintenance.scheduleStart(60_000, 30_000)
        core.commands.maintenance(admin, listOf("status"))
        assertTrue(admin.messages.single().contains("1m"))
    }

    @Test
    fun `redirect-server sends players to a backend instead of kicking them, by default kicking still applies`() {
        val core = core()
        val redirected = FakePlayer("Redirected")
        platform.players += redirected

        core.maintenance.setEnabled(true)
        assertNotNull(redirected.kickedWith)
        assertNull(redirected.redirectedTo)
        core.maintenance.setEnabled(false)
        redirected.kickedWith = null

        Files.writeString(folder.resolve("config.yml"), "maintenance:\n  redirect-server: lobby\n")
        val reloaded = run { core.reload(); core }
        reloaded.maintenance.setEnabled(true)
        assertEquals("lobby", redirected.redirectedTo)
        assertNull(redirected.kickedWith)
        assertTrue(redirected.received.isNotEmpty(), "should be told why they were moved")
    }

    @Test
    fun `redirect falls back to kicking if the configured server does not exist`() {
        Files.writeString(folder.resolve("config.yml"), "maintenance:\n  redirect-server: does-not-exist\n")
        val core = core()
        val player = FakePlayer("Solo")
        platform.players += player

        core.maintenance.setEnabled(true)
        assertNotNull(player.kickedWith)
        assertNull(player.redirectedTo)
    }

    @Test
    fun `dynamic max-players tracks the live online count, not the cached template`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "motd:\n  entries:\n    - line1: 'x'\n  max-players: dynamic\n  max-players-headroom: 3\n",
        )
        val core = core()
        assertEquals(8, core.motd.build(5, 100)!!.maxPlayers)
        assertEquals(13, core.motd.build(10, 100)!!.maxPlayers, "must update every ping, not just once per interval")
    }

    @Test
    fun `fixed and unset max-players still work alongside dynamic`() {
        Files.writeString(folder.resolve("config.yml"), "motd:\n  entries:\n    - line1: 'x'\n  max-players: 50\n")
        assertEquals(50, core().motd.build(5, 100)!!.maxPlayers)

        Files.writeString(folder.resolve("config.yml"), "motd:\n  entries:\n    - line1: 'x'\n  max-players: -1\n")
        assertNull(core().motd.build(5, 100)!!.maxPlayers)
    }

    @Test
    fun `starting maintenance manually cancels a pending schedule instead of leaving it dangling`() {
        val core = core()
        core.maintenance.scheduleStart(60_000, 30_000)
        core.maintenance.setEnabled(true)
        assertTrue(core.maintenance.describeSchedule().contains("no window"), "the superseded schedule must not linger in status")

        platform.advance(120_000)
        assertTrue(core.maintenance.enabled, "should still be indefinitely on, unaffected by the old schedule")
    }

    @Test
    fun `starting maintenance with a duration cancels a pending schedule too`() {
        val core = core()
        core.maintenance.scheduleStart(60_000, 30_000)
        core.commands.maintenance(FakeActor(setOf(Permissions.MAINTENANCE)), listOf("on", "5m"))

        platform.advance(60_000)
        assertTrue(core.maintenance.enabled, "manual timer must still be running")
        platform.advance(4 * 60_000)
        assertFalse(core.maintenance.enabled)
    }

    @Test
    fun `a schedule can be set again after maintenance that superseded it ends`() {
        val core = core()
        core.maintenance.scheduleStart(60_000, 30_000)
        core.maintenance.setEnabled(true)
        core.maintenance.setEnabled(false)
        assertEquals(ScheduleResult.Scheduled, core.maintenance.scheduleStart(10_000, 10_000))
    }

    @Test
    fun `a custom language file is used and falls back to english for missing keys`() {
        Files.createDirectories(folder.resolve("lang"))
        Files.writeString(folder.resolve("lang/fr.yml"), "prefix: \"\"\nmessages:\n  reloaded: \"rechargé\"\n")
        Files.writeString(folder.resolve("config.yml"), "language: fr\n")
        val core = core()
        assertEquals("fr", core.language)
        assertTrue(core.message("reloaded").contains("rechargé"))
        assertTrue(core.message("already-on").contains("already"))
    }

    @Test
    fun `adding an existing uuid keeps the stored name`() {
        val core = core()
        val uuid = UUID.randomUUID()
        core.store.addResolved(uuid, "Alice")
        assertEquals(WhitelistAddResult.ALREADY_PRESENT, core.store.addResolved(uuid, "?"))
        assertEquals("Alice", core.store.entries().single().name)
    }

    @Test
    fun `config migrator runs every step from the file's version and keeps unrelated values`() {
        val file = folder.resolve("config.yml")
        Files.writeString(file, "config-version: 1\nold: 5\nkeep: x\n")
        val steps = listOf<MigrationStep>(
            { root -> with(ConfigMigrator) { root.moveValue("old", "new") } },
            { root -> with(ConfigMigrator) { root.transformValue("new") { (it as Int) * 2 } } },
        )
        ConfigMigrator.migrateInPlace(file, steps)
        val text = Files.readString(file)
        assertTrue(text.contains("config-version: 3"))
        assertTrue(text.contains("new: 10"))
        assertTrue(text.contains("keep: x"))
        assertFalse(text.contains("old:"))
    }

    @Test
    fun `stamping a config without steps keeps its comments`() {
        val file = folder.resolve("config.yml")
        Files.writeString(file, "# my note\nlanguage: de\n")
        ConfigMigrator.migrateInPlace(file)
        val text = Files.readString(file)
        assertTrue(text.contains("# my note"))
        assertTrue(text.contains("config-version: ${ConfigMigrator.CURRENT_VERSION}"))
    }
}
