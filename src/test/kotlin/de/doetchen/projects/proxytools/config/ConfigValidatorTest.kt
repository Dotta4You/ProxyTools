package de.doetchen.projects.proxytools.config

import de.doetchen.projects.proxytools.core.config.ConfigValidator
import de.doetchen.projects.proxytools.core.config.YamlConfig
import de.doetchen.projects.proxytools.testing.CoreTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ConfigValidatorTest : CoreTestBase() {
    @Test
    fun `the bundled config is valid`() {
        assertEquals(emptyList(), ConfigValidator.check(YamlConfig.bundled("config.yml")))
    }

    @Test
    fun `wrong config values are reported instead of silently replaced`() {
        val config = YamlConfig(
            mapOf(
                "motd" to mapOf("mode" to "shuffle", "interval-seconds" to "abc", "max-players" to "lots", "hover" to mapOf("mode" to "all")),
                "maintenance" to mapOf("timer-warnings" to listOf(300, 0, -5)),
                "hub" to mapOf("mode" to "random"),
                "announcements" to mapOf("mode" to "shuffle", "interval-seconds" to 1),
                "slots" to mapOf("max-players" to 10, "reserved" to 20),
                "info-commands" to mapOf(
                    "Bad Name" to mapOf("text" to "x"),
                    "empty" to mapOf("aliases" to listOf("e")),
                    "link" to mapOf("text" to "x", "url" to "javascript:alert(1)", "aliases" to listOf("ok", "no good")),
                ),
            ),
        )
        val warnings = ConfigValidator.check(config).joinToString("\n")
        listOf(
            "motd.mode", "motd.interval-seconds", "motd.max-players", "motd.hover.mode", "maintenance.timer-warnings",
            "hub.mode", "announcements.mode", "announcements.interval-seconds", "slots.reserved",
            "info-commands.Bad Name", "info-commands.empty", "info-commands.link.url", "no good",
        ).forEach { assertTrue(warnings.contains(it), "expected a warning about $it in:\n$warnings") }
        assertFalse(warnings.contains("'ok'"))
    }
}
