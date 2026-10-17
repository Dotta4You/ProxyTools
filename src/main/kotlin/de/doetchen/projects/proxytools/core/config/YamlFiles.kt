package de.doetchen.projects.proxytools.core.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.io.Reader
import java.io.Writer
import java.nio.charset.CharacterCodingException
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
    } catch (e: YAMLException) {
        quarantine(file, e)
    } catch (e: CharacterCodingException) {
        quarantine(file, e)
    }

    private fun quarantine(file: Path, cause: Exception): Nothing {
        val name = file.fileName.toString()
        val broken = generateSequence(0) { it + 1 }
            .map { file.resolveSibling(if (it == 0) "$name.broken" else "$name.broken.$it") }
            .first { Files.notExists(it) }
        runCatching { Files.move(file, broken) }
        throw IllegalStateException("$name is not valid and was renamed to ${broken.fileName}: ${cause.message}", cause)
    }

    fun write(file: Path, data: Map<String, Any?>) {
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        writeAtomically(file) { Yaml(options).dump(data, it) }
    }

    fun writeText(file: Path, text: String) = writeAtomically(file) { it.write(text) }

    private fun writeAtomically(file: Path, body: (Writer) -> Unit) {
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use(body)
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
