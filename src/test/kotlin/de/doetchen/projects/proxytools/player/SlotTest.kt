package de.doetchen.projects.proxytools.player

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakePlayer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class SlotTest : CoreTestBase() {
    private fun slotsCore(): ProxyToolsCore {
        Files.writeString(folder.resolve("config.yml"), "slots:\n  enabled: true\n  max-players: 5\n  reserved: 2\n")
        return core()
    }

    @Test
    fun `slots keep the last places for vips and always let admins in`() {
        val core = slotsCore()
        val vip = FakePlayer("Vip", setOf(Permissions.SLOTS_RESERVED))
        val admin = FakePlayer("Admin", setOf(Permissions.SLOTS_BYPASS))

        repeat(3) { i ->
            val player = FakePlayer("P$i")
            assertNull(core.loginDenial(player))
            platform.players += player
        }
        assertNotNull(core.loginDenial(FakePlayer("Late")), "the two reserved slots are off limits for normal players")
        assertNull(core.loginDenial(vip))
        platform.players += vip
        assertNull(core.loginDenial(FakePlayer("Vip2", setOf(Permissions.SLOTS_RESERVED))))
        platform.players += FakePlayer("Vip2", setOf(Permissions.SLOTS_RESERVED))

        assertNotNull(core.loginDenial(FakePlayer("Vip3", setOf(Permissions.SLOTS_RESERVED))), "full for everyone but admins")
        assertNull(core.loginDenial(admin))
    }

    @Test
    fun `a player already counted as online is not counted twice`() {
        val core = slotsCore()
        repeat(2) { platform.players += FakePlayer("P$it") }
        val joining = FakePlayer("Joining")
        platform.players += joining
        assertNull(core.loginDenial(joining))
    }

    @Test
    fun `slots are ignored when disabled and the server list shows the limit when enabled`() {
        val disabled = core()
        repeat(200) { platform.players += FakePlayer("P$it") }
        assertNull(disabled.loginDenial(FakePlayer("Late")))

        val enabled = slotsCore().also { it.reload() }
        assertEquals(5, enabled.motd.build(0, 100)!!.maxPlayers)
    }
}
