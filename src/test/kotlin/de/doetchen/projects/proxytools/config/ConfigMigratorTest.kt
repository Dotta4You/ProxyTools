package de.doetchen.projects.proxytools.config

import de.doetchen.projects.proxytools.core.config.ConfigMigrator
import de.doetchen.projects.proxytools.core.config.MigrationStep
import de.doetchen.projects.proxytools.testing.CoreTestBase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ConfigMigratorTest : CoreTestBase() {
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
