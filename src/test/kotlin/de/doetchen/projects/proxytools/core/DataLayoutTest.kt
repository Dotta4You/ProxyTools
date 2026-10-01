package de.doetchen.projects.proxytools.core

import de.doetchen.projects.proxytools.testing.CoreTestBase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DataLayoutTest : CoreTestBase() {
    @Test
    fun `a fresh start creates the folders for icons and data`() {
        core()
        assertTrue(Files.isDirectory(folder.resolve("icons")))
        assertTrue(Files.isDirectory(folder.resolve("data")))
    }

    @Test
    fun `files from the old flat layout are moved into place`() {
        Files.writeString(folder.resolve("data.yml"), "maintenance: true\nmaintenance-reason: Upgrade\n")
        Files.write(folder.resolve("icon.png"), byteArrayOf(1, 2, 3))
        Files.write(folder.resolve("icon-maintenance.png"), byteArrayOf(4))
        Files.writeString(folder.resolve("playerdata.yml"), "players: {}\n")

        val core = core()

        listOf("data.yml", "icon.png", "icon-maintenance.png", "playerdata.yml").forEach {
            assertFalse(Files.exists(folder.resolve(it)), "$it should have moved")
        }
        assertTrue(Files.exists(folder.resolve("icons/default.png")))
        assertTrue(Files.exists(folder.resolve("icons/maintenance.png")))
        assertTrue(Files.exists(folder.resolve("data/players.yml")))
        assertTrue(core.maintenance.enabled, "the moved maintenance state is read on the same start")
        assertEquals("Upgrade", core.store.maintenanceReason)
        assertTrue(platform.loggedInfo.any { it.contains("Moved data.yml to data/maintenance.yml") })
    }

    @Test
    fun `a file that already exists at the new place is never overwritten`() {
        Files.createDirectories(folder.resolve("data"))
        Files.writeString(folder.resolve("data/maintenance.yml"), "maintenance: false\n")
        Files.writeString(folder.resolve("data.yml"), "maintenance: true\n")

        val core = core()

        assertFalse(core.maintenance.enabled)
        assertTrue(Files.exists(folder.resolve("data.yml")), "the old file stays for the admin to look at")
    }
}
