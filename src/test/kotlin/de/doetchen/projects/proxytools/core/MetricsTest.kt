package de.doetchen.projects.proxytools.core

import de.doetchen.projects.proxytools.testing.CoreTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MetricsTest : CoreTestBase() {
    @Test
    fun `statistics are on by default and can be switched off in the config`() {
        assertTrue(core().metricsEnabled)
        assertFalse(core("bstats: false\n").metricsEnabled)
    }

    @Test
    fun `the charts only carry general settings`() {
        val charts = core().metricCharts.mapValues { it.value() }
        assertEquals(setOf("language", "maintenance_enabled", "storage_type"), charts.keys)
        assertEquals("YAML", charts.getValue("storage_type"))
    }
}
