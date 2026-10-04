package de.doetchen.projects.proxytools.update

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import de.doetchen.projects.proxytools.testing.FakePlayer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class UpdateTest : CoreTestBase() {
    private val twoSeconds = 2_000L
    private val tenSeconds = 10_000L
    private val oneHour = 60 * 60 * 1000L
    private val twelveHours = 12 * oneHour

    private fun updateLogs() = platform.loggedInfo.filter { it.contains("new ProxyTools version") }

    private fun online(name: String, permissions: Set<String> = emptySet()) =
        FakePlayer(name, permissions).also { platform.players += it }

    @Test
    fun `a newer release is announced once in the console, not on every check`() {
        latestTag = "v0.2"
        val core = core()

        assertEquals(0, releaseCalls, "the first check waits a few seconds so the start is not slowed down")
        platform.advance(tenSeconds)
        assertEquals("v0.2", core.updates.available?.tag)
        assertEquals(1, updateLogs().size)
        assertTrue(updateLogs().single().contains("github.com/Dotta4You/ProxyTools/releases/tag/v0.2"))

        platform.advance(twelveHours)
        assertEquals(2, releaseCalls, "it checks again after twelve hours")
        assertEquals(1, updateLogs().size)

        latestTag = "v0.3"
        platform.advance(twelveHours)
        assertEquals(2, updateLogs().size, "a release newer still is announced too")
    }

    @Test
    fun `the same or an older release is not an update`() {
        val core = core()
        latestTag = "0.0.0"
        platform.advance(tenSeconds)
        assertNull(core.updates.available)

        latestTag = "v0.0.0-beta"
        platform.advance(twelveHours)
        assertNull(core.updates.available)
        assertTrue(updateLogs().isEmpty())
    }

    @Test
    fun `no release yet and unreadable tags are quiet`() {
        val core = core()
        latestTag = null
        platform.advance(tenSeconds)
        latestTag = "nightly"
        platform.advance(twelveHours)

        assertNull(core.updates.available)
        assertTrue(platform.warnings.isEmpty())
        assertTrue(platform.loggedInfo.none { it.contains("update") })
    }

    @Test
    fun `a failing check is logged once and does not stop later checks`() {
        val core = core()
        releaseFailure = "GitHub answered with HTTP 403"
        platform.advance(tenSeconds)
        platform.advance(twelveHours)
        assertEquals(2, releaseCalls)
        assertEquals(1, platform.loggedInfo.count { it.contains("Could not check for updates") })

        releaseFailure = null
        latestTag = "0.5"
        platform.advance(twelveHours)
        assertEquals("0.5", core.updates.available?.tag)
    }

    @Test
    fun `the checker can be switched off and back on with a reload`() {
        latestTag = "0.5"
        val core = core("update-checker:\n  enabled: false\n")
        platform.advance(tenSeconds + twelveHours)
        assertEquals(0, releaseCalls)

        Files.writeString(folder.resolve("config.yml"), "update-checker:\n  enabled: true\n")
        core.reload()
        platform.advance(tenSeconds)
        assertEquals(1, releaseCalls)

        Files.writeString(folder.resolve("config.yml"), "update-checker:\n  enabled: false\n")
        core.reload()
        platform.advance(twelveHours * 2)
        assertEquals(1, releaseCalls, "no checks happen after it was switched off again")
    }

    @Test
    fun `admins get the update block two seconds after joining, everybody else nothing`() {
        latestTag = "0.5"
        val core = core()
        platform.advance(tenSeconds)
        val admin = online("Admin", setOf(Permissions.UPDATE))
        val player = online("Player")

        core.playerJoined(admin)
        core.playerJoined(player)
        assertTrue(admin.received.isEmpty(), "the message must not drown in the join messages")

        platform.advance(twoSeconds)
        val block = admin.received.single().lines()
        assertEquals(listOf("", "§e§l⚡ UPDATE AVAILABLE!"), block.take(2))
        assertTrue(block[2].contains("Current Version") && block[2].contains("0.0.0"))
        assertTrue(block[3].contains("Latest Version") && block[3].contains("0.5"))
        assertTrue(block[4].contains("https://github.com/Dotta4You/ProxyTools/releases/tag/0.5"))
        assertEquals("https://github.com/Dotta4You/ProxyTools/releases/tag/0.5", admin.urls.single())
        assertTrue(player.received.isEmpty())
    }

    @Test
    fun `a download link from the config replaces the github page in the message and the click`() {
        latestTag = "0.5"
        val core = core("update-checker:\n  enabled: true\n  download-url: 'https://modrinth.com/plugin/proxytools'\n")
        platform.advance(tenSeconds)
        val admin = online("Admin", setOf(Permissions.UPDATE))

        core.playerJoined(admin)
        platform.advance(twoSeconds)

        assertTrue(admin.received.single().contains("https://modrinth.com/plugin/proxytools"))
        assertEquals("https://modrinth.com/plugin/proxytools", admin.urls.single())
    }

    @Test
    fun `an admin who left within the two seconds is not messaged`() {
        latestTag = "0.5"
        val core = core()
        platform.advance(tenSeconds)
        val admin = online("Admin", setOf(Permissions.UPDATE))

        core.playerJoined(admin)
        platform.players.remove(admin)
        platform.advance(twoSeconds)

        assertTrue(admin.received.isEmpty())
    }

    @Test
    fun `nobody is told while the checker is off`() {
        latestTag = "0.5"
        val core = core()
        platform.advance(tenSeconds)
        val admin = online("Admin", setOf(Permissions.UPDATE))

        Files.writeString(folder.resolve("config.yml"), "update-checker:\n  enabled: false\n")
        core.reload()
        core.playerJoined(admin)
        platform.advance(twoSeconds)

        assertTrue(admin.received.isEmpty())
    }

    @Test
    fun `an admin joining refreshes a check that is older than an hour, not a fresh one`() {
        latestTag = "0.5"
        val core = core()
        val admin = online("Admin", setOf(Permissions.UPDATE))

        core.playerJoined(admin)
        platform.advance(twoSeconds)
        assertEquals(1, releaseCalls, "no check has run yet, so the join does one")
        assertEquals(1, admin.received.size)

        platform.advance(tenSeconds)
        assertEquals(2, releaseCalls, "the scheduled start check")

        platform.advance(30 * 60 * 1000L)
        core.playerJoined(admin)
        platform.advance(twoSeconds)
        assertEquals(2, releaseCalls, "a check from half an hour ago is reused")
        assertEquals(2, admin.received.size)

        platform.advance(2 * oneHour)
        latestTag = "0.6"
        core.playerJoined(admin)
        platform.advance(twoSeconds)
        assertEquals(3, releaseCalls, "an old check is refreshed")
        assertTrue(admin.received.last().contains("0.6"))
    }

    @Test
    fun `the update command checks right away and tells the result`() {
        val core = core()
        val actor = FakeActor(setOf(Permissions.UPDATE))

        latestTag = "0.0.0"
        core.commands.proxyTools(actor, listOf("update"))
        platform.advance(0)
        assertTrue(actor.messages.last().contains("up to date"))

        latestTag = "9.9"
        core.commands.proxyTools(actor, listOf("update"))
        platform.advance(0)
        assertTrue(actor.messages.last().contains("9.9"))
        assertEquals("https://github.com/Dotta4You/ProxyTools/releases/tag/9.9", actor.urls.last())

        releaseFailure = "no route to host"
        core.commands.proxyTools(actor, listOf("update"))
        platform.advance(0)
        assertTrue(actor.messages.last().contains("Could not check"))
    }

    @Test
    fun `the update command and its completion need the permission`() {
        val core = core()
        val denied = FakeActor()

        core.commands.proxyTools(denied, listOf("update"))
        platform.advance(0)

        assertTrue(denied.messages.single().contains("permission"))
        assertEquals(0, releaseCalls)
        assertFalse("update" in core.commands.suggestProxyTools(denied, listOf("")))
        assertTrue("update" in core.commands.suggestProxyTools(FakeActor(setOf(Permissions.UPDATE)), listOf("")))
    }
}
