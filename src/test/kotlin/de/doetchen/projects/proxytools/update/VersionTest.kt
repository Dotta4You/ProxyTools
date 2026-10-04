package de.doetchen.projects.proxytools.update

import de.doetchen.projects.proxytools.core.update.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class VersionTest {
    private fun v(text: String) = Version.parse(text)!!

    @Test
    fun `numbers are compared part by part, not as text`() {
        assertTrue(v("0.10") > v("0.9"))
        assertTrue(v("1.2.3") > v("1.2.2"))
        assertTrue(v("2.0") > v("1.99.99"))
    }

    @Test
    fun `a leading v and missing trailing zeros do not matter`() {
        assertEquals(0, v("v1.0").compareTo(v("1.0.0")))
        assertEquals(0, v("V0.1").compareTo(v("0.1.0.0")))
    }

    @Test
    fun `a pre-release is older than the release it leads to`() {
        assertTrue(v("1.0.0-beta") < v("1.0.0"))
        assertTrue(v("0.1-SNAPSHOT") < v("0.1"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-beta"))
        assertTrue(v("1.0.1-beta") > v("1.0.0"))
    }

    @Test
    fun `text that is not a version is not parsed`() {
        assertNull(Version.parse("unknown"))
        assertNull(Version.parse(""))
        assertNull(Version.parse("latest"))
        assertNull(Version.parse("99999999999.1"))
        assertNotNull(Version.parse("1.0"))
    }
}
