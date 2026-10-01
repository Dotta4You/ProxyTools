package de.doetchen.projects.proxytools.motd

import de.doetchen.projects.proxytools.core.motd.FaviconCache
import de.doetchen.projects.proxytools.testing.CoreTestBase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FaviconCacheTest : CoreTestBase() {
    @Test
    fun `favicon falls back to the normal icon during maintenance if no maintenance icon exists`() {
        val core = core()
        Files.write(folder.resolve("icons/default.png"), byteArrayOf(1, 2, 3))
        assertEquals(listOf<Byte>(1, 2, 3), core.motd.build(0, 10)!!.faviconBytes!!.toList())

        core.maintenance.setEnabled(true)
        assertEquals(listOf<Byte>(1, 2, 3), core.motd.build(0, 10)!!.faviconBytes!!.toList())

        Files.write(folder.resolve("icons/maintenance.png"), byteArrayOf(9, 9))
        platform.advance(1_000)
        assertEquals(listOf<Byte>(9, 9), core.motd.build(0, 10)!!.faviconBytes!!.toList())
    }

    @Test
    fun `favicon cache only re-reads a changed file`() {
        var clock = 0L
        val cache = FaviconCache(folder, now = { clock })
        assertNull(cache.bytes("default.png"))

        val file = folder.resolve("default.png")
        Files.write(file, byteArrayOf(1))
        assertNull(cache.bytes("default.png"), "the missing file is only looked up again after a second")
        clock += 1_000
        val first = cache.bytes("default.png")
        assertEquals(listOf<Byte>(1), first!!.toList())
        clock += 1_000
        assertTrue(first === cache.bytes("default.png"), "unchanged file should return the same instance")

        Files.write(file, byteArrayOf(2))
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000))
        assertTrue(first === cache.bytes("default.png"), "a change is only noticed after the recheck delay")
        clock += 1_000
        val second = cache.bytes("default.png")
        assertEquals(listOf<Byte>(2), second!!.toList())
        assertFalse(first === second, "changed file should be re-read")
    }

    @Test
    fun `favicon cache does not throw when the path is a directory instead of a file`() {
        Files.createDirectories(folder.resolve("default.png"))
        val cache = FaviconCache(folder, onError = { })
        assertNull(cache.bytes("default.png"))
    }

    @Test
    fun `an oversized icon is refused and reported only once a minute`() {
        var clock = 0L
        val errors = mutableListOf<String>()
        val cache = FaviconCache(folder, errors::add) { clock }
        Files.write(folder.resolve("default.png"), ByteArray(30_000))
        assertNull(cache.bytes("default.png"))
        assertNull(cache.bytes("default.png"))
        clock += 30_000
        assertNull(cache.bytes("default.png"))
        assertEquals(1, errors.size)

        clock += 31_000
        Files.write(folder.resolve("default.png"), ByteArray(100))
        assertNotNull(cache.bytes("default.png"))
    }
}
