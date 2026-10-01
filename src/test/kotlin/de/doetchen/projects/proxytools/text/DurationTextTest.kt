package de.doetchen.projects.proxytools.text

import de.doetchen.projects.proxytools.core.text.DurationText
import de.doetchen.projects.proxytools.testing.CoreTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class DurationTextTest : CoreTestBase() {
    @Test
    fun `duration text parses and formats`() {
        assertEquals(90_000L, DurationText.parseMillis("1m30s"))
        assertEquals(3_600_000L, DurationText.parseMillis("1h"))
        assertNull(DurationText.parseMillis("garbage"))
        assertNull(DurationText.parseMillis("0s"))
        assertNull(DurationText.parseMillis("30m1h"))

        assertEquals("1h 2m", DurationText.format(3725))
        assertEquals("45s", DurationText.format(45))
        assertEquals("0s", DurationText.format(0))
    }

    @Test
    fun `durations reject overflow and anything beyond a year`() {
        assertNull(DurationText.parseMillis("99999999999999999999d"))
        assertNull(DurationText.parseMillis("9223372036854775807s"))
        assertNull(DurationText.parseMillis("366d"))
        assertNull(DurationText.parseMillis("0s"))
        assertEquals(365L * 86_400_000, DurationText.parseMillis("365d"))
        assertEquals(90_061_000L, DurationText.parseMillis("1d1h1m1s"))
    }
}
