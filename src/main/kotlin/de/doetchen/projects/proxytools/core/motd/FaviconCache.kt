package de.doetchen.projects.proxytools.core.motd

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

internal class FaviconCache(
    private val folder: Path,
    private val onError: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Cached(val mtime: FileTime?, val bytes: ByteArray?, val recheckAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun bytes(fileName: String): ByteArray? {
        val time = now()
        val previous = cache[fileName]
        if (previous != null && time < previous.recheckAt) return previous.bytes
        return refresh(fileName, previous, time).also { cache[fileName] = it }.bytes
    }

    private fun refresh(fileName: String, previous: Cached?, time: Long): Cached = try {
        val file = folder.resolve(fileName)
        if (Files.notExists(file)) {
            Cached(null, null, time + RECHECK_MILLIS)
        } else {
            val mtime = Files.getLastModifiedTime(file)
            require(Files.size(file) <= MAX_BYTES) { "the file is larger than $MAX_BYTES bytes, use a 64x64 png" }
            val unchanged = previous?.bytes != null && previous.mtime == mtime
            Cached(mtime, if (unchanged) previous?.bytes else Files.readAllBytes(file), time + RECHECK_MILLIS)
        }
    } catch (e: Exception) {
        onError("Could not read $fileName, trying again in a minute: ${e.message}")
        Cached(null, null, time + ERROR_RECHECK_MILLIS)
    }

    private companion object {
        const val RECHECK_MILLIS = 1_000L
        const val ERROR_RECHECK_MILLIS = 60_000L
        const val MAX_BYTES = 20_000L
    }
}

internal class FaviconConverter<T : Any>(private val onError: (String) -> Unit, private val convert: (ByteArray) -> T) {
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
