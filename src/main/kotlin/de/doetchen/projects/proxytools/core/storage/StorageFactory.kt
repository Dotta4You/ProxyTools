package de.doetchen.projects.proxytools.core.storage

import de.doetchen.projects.proxytools.core.DataLayout
import de.doetchen.projects.proxytools.core.Platform
import de.doetchen.projects.proxytools.core.config.YamlConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

internal object StorageFactory {
    private val DATABASE_NAME = Regex("[A-Za-z0-9_$-]+")
    private const val TABLE_PREFIX = "proxytools_"
    private val HOST = Regex("[A-Za-z0-9.:_-]+|\\[[0-9A-Fa-f:.]+]")

    fun signature(config: YamlConfig): String = listOf(
        "type", "mysql.host", "mysql.port", "mysql.database", "mysql.username", "mysql.password", "mysql.use-ssl",
    ).joinToString("|") { config.string("storage.$it") }

    fun create(config: YamlConfig, layout: DataLayout, platform: Platform): PlayerStorage {
        val yamlFile = layout.playersYaml
        val type = config.string("storage.type", "yaml").trim().lowercase()
        val storage = try {
            when (type) {
                "yaml", "" -> return YamlPlayerStorage(yamlFile)
                "h2" -> openH2(layout)
                "mysql", "mariadb" -> openMariaDb(config)
                else -> {
                    platform.warn("Unknown storage.type '$type', using yaml. Available: yaml, h2, mysql")
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

    private fun openMariaDb(config: YamlConfig): SqlPlayerStorage {
        val host = config.string("storage.mysql.host", "localhost").trim()
        val port = config.int("storage.mysql.port", 3306)
        val database = config.string("storage.mysql.database", "proxytools").trim()
        require(HOST.matches(host)) { "storage.mysql.host '$host' is not a valid host name" }
        require(DATABASE_NAME.matches(database)) { "storage.mysql.database '$database' contains unsupported characters" }
        val sslMode = if (config.boolean("storage.mysql.use-ssl")) "trust" else "disable"
        val url = "jdbc:mariadb://$host:$port/$database?connectTimeout=5000&socketTimeout=15000&sslMode=$sslMode"
        val properties = Properties().apply {
            setProperty("user", config.string("storage.mysql.username", "root"))
            setProperty("password", config.string("storage.mysql.password"))
        }
        return SqlPlayerStorage("MySQL/MariaDB ($host:$port/$database)", TABLE_PREFIX) {
            org.mariadb.jdbc.Driver().connect(url, properties) ?: error("MariaDB driver refused $url")
        }
    }
}
