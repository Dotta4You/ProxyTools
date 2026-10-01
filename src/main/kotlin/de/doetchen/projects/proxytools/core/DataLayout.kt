package de.doetchen.projects.proxytools.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class DataLayout(private val root: Path) {
    val config: Path = root.resolve("config.yml")
    val icons: Path = root.resolve("icons")
    val maintenance: Path = root.resolve("data/maintenance.yml")
    val playersYaml: Path = root.resolve("data/players.yml")
    val playersDatabase: Path = root.resolve("data/players")

    private val moved = mapOf(
        "data.yml" to "data/maintenance.yml",
        "playerdata.yml" to "data/players.yml",
        "playerdata.yml.imported" to "data/players.yml.imported",
        "playerdata.mv.db" to "data/players.mv.db",
        "icon.png" to "icons/default.png",
        "icon-maintenance.png" to "icons/maintenance.png",
    )

    fun prepare(info: (String) -> Unit, warn: (String) -> Unit) {
        try {
            Files.createDirectories(icons)
            Files.createDirectories(maintenance.parent)
        } catch (e: IOException) {
            warn("Could not create the icons and data folders: ${e.message}")
            return
        }
        moved.forEach { (old, new) ->
            val from = root.resolve(old)
            val to = root.resolve(new)
            if (Files.notExists(from) || Files.exists(to)) return@forEach
            try {
                Files.move(from, to)
                info("Moved $old to $new")
            } catch (e: IOException) {
                warn("Could not move $old to $new: ${e.message}")
            }
        }
    }
}
