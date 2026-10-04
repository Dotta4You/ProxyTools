package de.doetchen.projects.proxytools.core.storage

import de.doetchen.projects.proxytools.core.DataLayout
import de.doetchen.projects.proxytools.core.Platform
import de.doetchen.projects.proxytools.core.config.YamlConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

internal object StorageFactory {
    private const val TABLE_PREFIX = "proxytools_"

    fun signature(config: YamlConfig): String = config.string("storage.type")

    fun create(config: YamlConfig, layout: DataLayout, platform: Platform): PlayerStorage {
        val yamlFile = layout.playersYaml
        val type = config.string("storage.type", "yaml").trim().lowercase()
        val storage = try {
            when (type) {
                "yaml", "" -> return YamlPlayerStorage(yamlFile)
                "h2" -> openH2(layout)
                else -> {
                    platform.warn("Unknown storage.type '$type', using yaml. Available: yaml, h2")
                    return YamlPlayerStorage(yamlFile)
                }
            }
        } catch (e: Exception) {
            return fallback(platform, type, yamlFile, e)
        } catch (e: LinkageError) {
            return fallback(platform, type, yamlFile, e)
        }
        importYaml(storage, yamlFile, platform)
        return storage
    }

    private fun fallback(platform: Platform, type: String, yamlFile: Path, error: Throwable): PlayerStorage {
        platform.warn("Could not open the '$type' storage, using $yamlFile for this session instead: ${error.message}")
        return YamlPlayerStorage(yamlFile)
    }

    private fun importYaml(storage: SqlPlayerStorage, yamlFile: Path, platform: Platform) {
        if (Files.notExists(yamlFile)) return
        try {
            if (!storage.isEmpty()) return
            val records = YamlPlayerStorage(yamlFile).loadAll()
            storage.save(records)
            Files.move(yamlFile, yamlFile.resolveSibling("players.yml.imported"), StandardCopyOption.REPLACE_EXISTING)
            platform.info("Imported ${records.size} players from players.yml into ${storage.name}.")
        } catch (e: Exception) {
            platform.warn("Could not import players.yml into ${storage.name}: ${e.message}")
        }
    }

    private fun openH2(layout: DataLayout): SqlPlayerStorage {
        val path = layout.playersDatabase.toAbsolutePath().toString().replace('\\', '/')
        val url = "jdbc:h2:file:$path;DB_CLOSE_ON_EXIT=FALSE"
        return SqlPlayerStorage("H2 (players.mv.db)", TABLE_PREFIX) {
            org.h2.Driver().connect(url, Properties()) ?: error("H2 driver refused $url")
        }
    }
}
