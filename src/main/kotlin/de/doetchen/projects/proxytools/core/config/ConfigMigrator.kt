package de.doetchen.projects.proxytools.core.config

import java.nio.file.Files
import java.nio.file.Path

internal typealias MigrationStep = (MutableMap<String, Any?>) -> Unit

internal object ConfigMigrator {
    private val steps: List<MigrationStep> = emptyList()

    val CURRENT_VERSION = steps.size + 1

    private val VERSION_LINE = Regex("(?m)^config-version:.*$")

    fun migrateInPlace(file: Path, steps: List<MigrationStep> = this.steps) {
        if (Files.notExists(file)) return
        @Suppress("UNCHECKED_CAST")
        val root = YamlFiles.read(file) as? MutableMap<String, Any?> ?: return

        val target = steps.size + 1
        val version = (root["config-version"] as? Number)?.toInt() ?: 0
        if (version >= target) return

        val pending = steps.drop((version - 1).coerceAtLeast(0))
        if (pending.isEmpty()) return stamp(file, target)
        pending.forEach { it(root) }
        root["config-version"] = target
        YamlFiles.write(file, root)
    }

    private fun stamp(file: Path, version: Int) {
        val text = Files.readString(file)
        val line = "config-version: $version"
        Files.writeString(file, if (VERSION_LINE.containsMatchIn(text)) VERSION_LINE.replace(text, line) else "$line\n$text")
    }

    fun MutableMap<String, Any?>.moveValue(fromPath: String, toPath: String) {
        val value = removeValue(fromPath) ?: return
        val keys = toPath.split('.')
        walk(keys.dropLast(1), createMissing = true)!![keys.last()] = value
    }

    fun MutableMap<String, Any?>.removeValue(path: String): Any? {
        val keys = path.split('.')
        return walk(keys.dropLast(1))?.remove(keys.last())
    }

    fun MutableMap<String, Any?>.transformValue(path: String, transform: (Any?) -> Any?) {
        val keys = path.split('.')
        val parent = walk(keys.dropLast(1)) ?: return
        if (parent.containsKey(keys.last())) parent[keys.last()] = transform(parent[keys.last()])
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.walk(keys: List<String>, createMissing: Boolean = false): MutableMap<String, Any?>? {
        var current: MutableMap<String, Any?> = this
        for (key in keys) {
            val existing = current[key]
            current = when {
                existing is MutableMap<*, *> -> existing as MutableMap<String, Any?>
                existing == null && createMissing -> LinkedHashMap<String, Any?>().also { current[key] = it }
                else -> return null
            }
        }
        return current
    }
}
