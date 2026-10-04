package de.doetchen.projects.proxytools.core.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object YamlFiles {
    fun parse(reader: Reader): Map<*, *>? = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(reader) as? Map<*, *>

    fun read(file: Path): Map<*, *>? = Files.newBufferedReader(file, StandardCharsets.UTF_8).use(::parse)

    fun readOrQuarantine(file: Path): Map<*, *>? = try {
        read(file)
    } catch (e: Exception) {
        val broken = file.resolveSibling(file.fileName.toString() + ".broken")
        runCatching { Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING) }
        throw IllegalStateException("${file.fileName} is not valid and was renamed to ${broken.fileName}: ${e.message}", e)
    }

    fun write(file: Path, data: Map<String, Any?>) {
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use { Yaml(options).dump(data, it) }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
