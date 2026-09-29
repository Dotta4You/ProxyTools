package de.doetchen.projects.proxytools.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

class FaviconCache(private val dataFolder: Path, private val onError: (String) -> Unit = {}) {
    private class Cached(val mtime: FileTime, val bytes: ByteArray)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun bytes(fileName: String): ByteArray? {
        val file = dataFolder.resolve(fileName)
        return try {
            if (Files.notExists(file)) {
                cache.remove(fileName)
                return null
            }
            val mtime = Files.getLastModifiedTime(file)
            cache[fileName]?.takeIf { it.mtime == mtime }?.let { return it.bytes }
            Files.readAllBytes(file).also { cache[fileName] = Cached(mtime, it) }
        } catch (e: Exception) {
            onError("Could not read $fileName: ${e.message}")
            cache.remove(fileName)
            null
        }
    }
}

class FaviconConverter<T : Any>(private val onError: (String) -> Unit, private val convert: (ByteArray) -> T) {
    private class Entry<T>(val bytes: ByteArray, val value: T?)

    @Volatile
    private var entry: Entry<T>? = null

    fun get(bytes: ByteArray?): T? {
        if (bytes == null) {
            entry = null
            return null
        }
        entry?.takeIf { it.bytes === bytes }?.let { return it.value }
        val value = runCatching { convert(bytes) }.onFailure { onError("Could not read favicon: ${it.message}") }.getOrNull()
        entry = Entry(bytes, value)
        return value
    }
}
