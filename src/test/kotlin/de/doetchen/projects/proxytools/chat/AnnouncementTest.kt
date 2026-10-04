package de.doetchen.projects.proxytools.chat

import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakePlayer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class AnnouncementTest : CoreTestBase() {
    private fun announcementsCore(mode: String = "sequential", interval: Int = 10): ProxyToolsCore {
        Files.writeString(
            folder.resolve("config.yml"),
            "announcements:\n  enabled: true\n  mode: $mode\n  interval-seconds: $interval\n  prefix: '[I] '\n" +
                "  messages:\n    - 'one'\n    - 'two'\n    - - 'three a'\n      - 'three b'\n",
        )
        return core()
    }

    @Test
    fun `announcements rotate in order, prefix every line and skip empty networks`() {
        announcementsCore()
        platform.advance(10_000)
        val player = FakePlayer("Online")
        platform.players += player
        platform.advance(10_000)
        platform.advance(10_000)
        platform.advance(10_000)
        assertEquals(listOf("[I] one", "[I] two", "[I] three a\n[I] three b"), player.received)
    }

    @Test
    fun `random announcements never repeat the previous one directly`() {
        announcementsCore("random")
        val player = FakePlayer("Online")
        platform.players += player
        repeat(40) { platform.advance(10_000) }
        assertEquals(40, player.received.size)
        assertTrue(player.received.zipWithNext().all { (a, b) -> a != b })
    }

    @Test
    fun `announcements are off by default and a reload restarts instead of duplicating them`() {
        val player = FakePlayer("Online")
        platform.players += player
        core()
        platform.advance(600_000)
        assertTrue(player.received.isEmpty())

        val core = announcementsCore()
        repeat(3) { core.reload() }
        platform.advance(10_000)
        assertEquals(1, player.received.size)
    }

    @Test
    fun `announcements keep running after one round failed`() {
        Files.writeString(
            folder.resolve("config.yml"),
            "announcements:\n  enabled: true\n  interval-seconds: 10\n  prefix: ''\n  messages: ['one', 'two']\n",
        )
        val core = ProxyToolsCore(platform, releases)
        var failures = 1
        val flaky = object : FakePlayer("Flaky") {
            override fun sendMessage(message: String, openUrl: String?) {
                if (failures-- > 0) error("connection reset")
                super.sendMessage(message, openUrl)
            }
        }
        platform.players += flaky

        platform.advance(10_000)
        assertTrue(platform.warnings.any { it.contains("announcement") })
        platform.advance(10_000)
        assertEquals(1, flaky.received.size, "the next round still happens")
        core.announcements.restart()
    }
}
