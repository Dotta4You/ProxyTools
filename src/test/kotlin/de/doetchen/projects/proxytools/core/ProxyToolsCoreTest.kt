package de.doetchen.projects.proxytools

import de.doetchen.projects.proxytools.testing.CoreTestBase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ProxyToolsCoreTest : CoreTestBase() {
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
    fun `a reload keeps the running maintenance state instead of re-reading the file`() {
        val core = core()
        core.maintenance.setEnabled(true, "Upgrade")
        Files.writeString(folder.resolve("data/maintenance.yml"), "maintenance: false\n")
        assertTrue(core.reload())
        assertTrue(core.maintenance.enabled)
        assertEquals("Upgrade", core.store.maintenanceReason)
    }

    @Test
    fun `player data is only preloaded for backends that load per player`() {
        assertFalse(core().playerData.lazy)
        val h2 = core("storage:\n  type: h2\n")
        assertTrue(h2.playerData.lazy)
        h2.shutdown()
    }
}
