package de.doetchen.projects.proxytools.core.command

import de.doetchen.projects.proxytools.core.ProxyToolsCore

internal class InfoCommand(
    val name: String,
    val aliases: List<String>,
    val text: String,
    val url: String?,
    val permission: String?,
)

internal class InfoCommandParse(val commands: List<InfoCommand>, val problems: List<String>)

internal object InfoCommandParser {
    private val VALID_NAME = Regex("[a-z0-9_-]+")

    fun parse(raw: Any?): InfoCommandParse {
        val commands = mutableListOf<InfoCommand>()
        val problems = mutableListOf<String>()
        (raw as? Map<*, *>).orEmpty().forEach { (key, value) ->
            val label = key.toString()
            val name = label.lowercase()
            val data = value as? Map<*, *>
            val text = when (val text = data?.get("text")) {
                is List<*> -> text.joinToString("\n")
                null -> null
                else -> text.toString()
            }
            when {
                !VALID_NAME.matches(name) -> problems += "info-commands.$label is skipped, names may only contain a-z, 0-9, _ and -."
                data == null || text == null -> problems += "info-commands.$label is skipped, it needs a text."
                else -> {
                    val url = data["url"]?.toString()
                    if (url != null && !isWebUrl(url)) {
                        problems += "info-commands.$label.url must start with http:// or https://, the link is ignored."
                    }
                    val aliases = (data["aliases"] as? List<*>).orEmpty().map { it.toString().lowercase() }
                    aliases.filterNot(VALID_NAME::matches).forEach {
                        problems += "info-commands.$label has the invalid alias '$it', it is ignored."
                    }
                    commands += InfoCommand(
                        name = name,
                        aliases = aliases.filter(VALID_NAME::matches),
                        text = text,
                        url = url?.takeIf(::isWebUrl),
                        permission = data["permission"]?.toString()?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        return InfoCommandParse(commands, problems)
    }

    private fun isWebUrl(url: String) = url.startsWith("http://") || url.startsWith("https://")
}

internal class InfoCommandService(private val core: ProxyToolsCore) {
    private val startupNames = names(definitions())

    fun definitions(): List<InfoCommand> = InfoCommandParser.parse(core.config.get("info-commands")).commands

    fun find(name: String): InfoCommand? = definitions().firstOrNull { it.name == name }

    fun warnIfChanged() {
        if (names(definitions()) != startupNames) {
            core.platform.warn("info-commands names or aliases changed, restart the proxy to register them.")
        }
    }

    private fun names(commands: List<InfoCommand>) = commands.map { it.name to it.aliases }.toSet()
}
