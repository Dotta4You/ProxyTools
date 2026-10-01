package de.doetchen.projects.proxytools.config

import de.doetchen.projects.proxytools.testing.CoreTestBase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ConfigTest : CoreTestBase() {
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
        val core = core("motd:\n  max-players: 42\n")
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
        val german = core("language: de\n")
        assertTrue(german.message("reloaded").contains("neu geladen"))

        val fallback = core("language: fr\n")
        assertTrue(fallback.message("reloaded").contains("reloaded"))
    }

    @Test
    fun `a broken config still fails reload cleanly even with the migrator in front of it`() {
        val core = core()
        Files.writeString(folder.resolve("config.yml"), "motd: [unclosed")
        assertFalse(core.reload())
        assertTrue(core.config.mapList("motd.entries").isNotEmpty())
    }

    @Test
    fun `a custom language file is used and falls back to english for missing keys`() {
        Files.createDirectories(folder.resolve("lang"))
        Files.writeString(folder.resolve("lang/fr.yml"), "prefix: \"\"\nmessages:\n  reloaded: \"rechargé\"\n")
        val core = core("language: fr\n")
        assertEquals("fr", core.language)
        assertTrue(core.message("reloaded").contains("rechargé"))
        assertTrue(core.message("already-on").contains("already"))
    }

    @Test
    fun `invalid settings are logged at startup and on reload`() {
        core("motd:\n  mode: shuffle\n")
        assertTrue(platform.warnings.any { it.startsWith("config.yml: motd.mode") })

        platform.warnings.clear()
        val core = core("language: en\n")
        assertTrue(platform.warnings.isEmpty())
        Files.writeString(folder.resolve("config.yml"), "hub:\n  mode: random\n")
        assertTrue(core.reload())
        assertTrue(platform.warnings.any { it.contains("hub.mode") })
    }
}
