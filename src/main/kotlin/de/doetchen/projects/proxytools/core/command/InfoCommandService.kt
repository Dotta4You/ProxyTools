package de.doetchen.projects.proxytools.core.command

import de.doetchen.projects.proxytools.core.ProxyToolsCore

internal class InfoCommand(
    val name: String,
    val aliases: List<String>,
    val text: String,
    val url: String?,
    val permission: String?,
)

internal class InfoCommandService(private val core: ProxyToolsCore) {
    private val startupNames = definitions().map { it.name to it.aliases }.toSet()

    fun definitions(): List<InfoCommand> = (core.config.get("info-commands") as? Map<*, *>).orEmpty().mapNotNull { (key, value) ->
        val name = key.toString().lowercase().takeIf(VALID_NAME::matches) ?: return@mapNotNull null
        val data = value as? Map<*, *> ?: return@mapNotNull null
        val text = when (val raw = data["text"]) {
            is List<*> -> raw.joinToString("\n")
            null -> return@mapNotNull null
            else -> raw.toString()
        }
        InfoCommand(
            name = name,
            aliases = (data["aliases"] as? List<*>).orEmpty().map { it.toString().lowercase() }.filter(VALID_NAME::matches),
            text = text,
            url = data["url"]?.toString()?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            permission = data["permission"]?.toString()?.takeIf { it.isNotBlank() },
        )
    }

    fun find(name: String): InfoCommand? = definitions().firstOrNull { it.name == name }

    fun warnIfChanged() {
        if (definitions().map { it.name to it.aliases }.toSet() != startupNames) {
            core.platform.warn("info-commands names or aliases changed, restart the proxy to register them.")
        }
    }

    private companion object {
        val VALID_NAME = Regex("[a-z0-9_-]+")
    }
}
