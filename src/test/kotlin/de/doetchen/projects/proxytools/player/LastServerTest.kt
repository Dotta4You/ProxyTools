package de.doetchen.projects.proxytools.player

import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.testing.CoreTestBase
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class LastServerTest : CoreTestBase() {
    @Test
    fun `last server is remembered, validated and survives a restart`() {
        Files.writeString(folder.resolve("config.yml"), "remember-last-server:\n  enabled: true\n  exclude: [limbo]\n")
        platform.servers += listOf("survival", "limbo")
        val core = core()
        val id = UUID.randomUUID()
        assertNull(core.lastServers.target(id))

        core.lastServers.record(id, "survival")
        assertEquals("survival", core.lastServers.target(id))
        core.lastServers.record(id, "LIMBO")
        assertEquals("survival", core.lastServers.target(id))

        platform.advance(5_000)
        assertEquals("survival", ProxyToolsCore(platform).lastServers.target(id))

        platform.servers -= "survival"
        assertNull(core.lastServers.target(id), "a server that no longer exists is ignored")
    }

    @Test
    fun `last server is off by default and the maintenance lobby is not remembered`() {
        platform.servers += "survival"
        val id = UUID.randomUUID()
        val off = core()
        off.lastServers.record(id, "survival")
        assertNull(off.lastServers.target(id))

        Files.writeString(
            folder.resolve("config.yml"),
            "remember-last-server:\n  enabled: true\nmaintenance:\n  redirect-server: lobby\n",
        )
        val on = core()
        on.lastServers.record(id, "survival")
        on.maintenance.setEnabled(true)
        on.lastServers.record(id, "lobby")
        assertEquals("survival", on.lastServers.target(id))
    }
}
