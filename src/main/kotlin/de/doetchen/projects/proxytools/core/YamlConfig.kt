package de.doetchen.projects.proxytools.core

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Read-only YAML view with dotted-path access; missing keys fall back to [fallback] (the bundled default). */
class YamlConfig(private val values: Map<String, Any?>, private val fallback: YamlConfig? = null) {

    fun get(path: String): Any? {
        var current: Any? = values
        for (key in path.split('.')) {
            current = (current as? Map<*, *>)?.get(key) ?: return fallback?.get(path)
        }
        return current
    }

    /** Lists are joined with newlines, so multi-line texts can be written as YAML lists. */
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

    fun boolean(path: String, default: Boolean = false): Boolean = when (val value = get(path)) {
        is Boolean -> value
        else -> value?.toString()?.toBooleanStrictOrNull() ?: default
    }

    fun mapList(path: String): List<Map<String, Any?>> =
        (get(path) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { map ->
            map.entries.associate { it.key.toString() to it.value }
        }

    companion object {
        /** The default file shipped inside the jar. */
        fun bundled(name: String): YamlConfig {
            val stream = YamlConfig::class.java.getResourceAsStream("/$name")
                ?: error("Bundled resource $name is missing")
            return YamlConfig(stream.use { parse(it.reader(StandardCharsets.UTF_8)) })
        }

        fun hasBundled(name: String): Boolean = YamlConfig::class.java.getResource("/$name") != null

        /** Loads `<folder>/<name>`, copying the bundled default there first if it doesn't exist yet. */
        fun load(folder: Path, name: String): YamlConfig {
            val defaults = bundled(name)
            val file = folder.resolve(name)
            if (Files.notExists(file)) {
                Files.createDirectories(file.parent)
                YamlConfig::class.java.getResourceAsStream("/$name")!!.use { Files.copy(it, file) }
            }
            val user = Files.newBufferedReader(file, StandardCharsets.UTF_8).use { parse(it) }
            return YamlConfig(user, defaults)
        }

        private fun parse(reader: Reader): Map<String, Any?> {
            val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(reader) as? Map<*, *> ?: return emptyMap()
            return root.entries.associate { it.key.toString() to it.value }
        }
    }
}
