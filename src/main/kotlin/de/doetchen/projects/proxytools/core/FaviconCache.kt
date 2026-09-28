package de.doetchen.projects.proxytools.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads favicon PNGs from the data folder, re-reading a file only when its mtime changes. Returns
 * the same [ByteArray] instance while unchanged, so adapters can cheaply check by reference equality.
 */
class FaviconCache(private val dataFolder: Path) {
    private data class Cached(val mtime: FileTime, val bytes: ByteArray)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun bytes(fileName: String): ByteArray? {
        val file = dataFolder.resolve(fileName)
        if (Files.notExists(file)) {
            cache.remove(fileName)
            return null
        }
        val mtime = Files.getLastModifiedTime(file)
        val existing = cache[fileName]
        if (existing != null && existing.mtime == mtime) return existing.bytes

        val bytes = Files.readAllBytes(file)
        cache[fileName] = Cached(mtime, bytes)
        return bytes
    }
}
