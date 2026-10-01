package de.doetchen.projects.proxytools.core.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal class YamlConfig(private val values: Map<String, Any?>, private val fallback: YamlConfig? = null) {
    fun get(path: String): Any? {
        var current: Any? = values
        for (key in path.split('.')) {
            current = (current as? Map<*, *>)?.get(key) ?: return fallback?.get(path)
        }
        return current
    }

    fun string(path: String, default: String = ""): String = when (val value = get(path)) {
        null -> default
        is List<*> -> value.joinToString("\n")
        else -> value.toString()
    }

    fun stringList(path: String): List<String> = when (val value = get(path)) {
        is List<*> -> value.filterNotNull().map { it.toString() }
        null -> emptyList()
        else -> listOf(value.toString())
    }

    fun int(path: String, default: Int = 0): Int = when (val value = get(path)) {
        is Number -> value.toInt()
        else -> value?.toString()?.toIntOrNull() ?: default
    }

    fun intList(path: String): List<Int> = when (val value = get(path)) {
        is List<*> -> value.mapNotNull { (it as? Number)?.toInt() ?: it?.toString()?.toIntOrNull() }
        null -> emptyList()
        else -> listOfNotNull(value.toString().toIntOrNull())
    }

    fun boolean(path: String, default: Boolean = false): Boolean = when (val value = get(path)) {
        is Boolean -> value
        else -> value?.toString()?.toBooleanStrictOrNull() ?: default
    }

    fun mapList(path: String): List<Map<String, Any?>> =
        (get(path) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { map ->
            map.entries.associate { it.key.toString() to it.value }
        }

    companion object {
        fun bundled(name: String): YamlConfig {
            val stream = YamlConfig::class.java.getResourceAsStream("/$name") ?: error("Bundled resource $name is missing")
            return YamlConfig(stream.use { toValues(YamlFiles.parse(it.reader(StandardCharsets.UTF_8))) })
        }

        fun hasBundled(name: String): Boolean = YamlConfig::class.java.getResource("/$name") != null

        fun load(folder: Path, name: String, defaultsName: String = name): YamlConfig {
            val defaults = bundled(defaultsName)
            val file = folder.resolve(name)
            if (Files.notExists(file)) {
                Files.createDirectories(file.parent)
                val bundled = YamlConfig::class.java.getResourceAsStream("/$name") ?: error("Bundled resource $name is missing")
                bundled.use { Files.copy(it, file) }
            }
            return YamlConfig(toValues(YamlFiles.read(file)), defaults)
        }

        private fun toValues(root: Map<*, *>?): Map<String, Any?> =
            root?.entries?.associate { it.key.toString() to it.value }.orEmpty()
    }
}
