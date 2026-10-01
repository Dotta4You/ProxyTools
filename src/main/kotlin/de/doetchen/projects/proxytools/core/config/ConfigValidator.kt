package de.doetchen.projects.proxytools.core.config

internal object ConfigValidator {
    private val NAME = Regex("[a-z0-9_-]+")

    fun check(config: YamlConfig): List<String> = buildList {
        fun oneOf(path: String, vararg allowed: String) {
            val value = config.get(path)?.toString()?.trim()?.lowercase() ?: return
            if (value !in allowed) add("$path is '$value', expected ${allowed.joinToString(", ")}. The default is used.")
        }

        fun number(path: String, minimum: Int) {
            val raw = config.get(path) ?: return
            val value = (raw as? Number)?.toInt() ?: raw.toString().toIntOrNull()
            when {
                value == null -> add("$path is '$raw', expected a number. The default is used.")
                value < minimum -> add("$path is $value, the minimum is $minimum.")
            }
        }

        fun seconds(path: String) {
            val values = config.get(path) as? List<*> ?: return
            if (values.any { ((it as? Number)?.toInt() ?: it?.toString()?.toIntOrNull() ?: 0) <= 0 }) {
                add("$path may only contain positive numbers of seconds. Other entries are ignored.")
            }
        }

        for (base in listOf("motd", "maintenance.motd")) {
            oneOf("$base.mode", "random", "sequential", "static")
            oneOf("$base.hover.mode", "custom", "players")
            number("$base.interval-seconds", 1)
            val maxPlayers = config.get("$base.max-players")
            if (maxPlayers != null && !maxPlayers.toString().equals("dynamic", ignoreCase = true)) number("$base.max-players", -1)
        }
        oneOf("hub.mode", "first", "least-players")
        oneOf("announcements.mode", "sequential", "random")
        number("announcements.interval-seconds", 5)
        number("slots.max-players", 1)
        number("slots.reserved", 0)
        if (config.int("slots.reserved") > config.int("slots.max-players", 100)) {
            add("slots.reserved is higher than slots.max-players, so only players with proxytools.slots.reserved can join.")
        }
        seconds("maintenance.timer-warnings")
        seconds("maintenance.schedule-warnings")
        addAll(infoCommands(config))
    }

    private fun infoCommands(config: YamlConfig): List<String> = buildList {
        val commands = config.get("info-commands") as? Map<*, *> ?: return@buildList
        commands.forEach { (key, value) ->
            val name = key.toString()
            val data = value as? Map<*, *>
            when {
                !NAME.matches(name) -> add("info-commands.$name is skipped, names may only contain a-z, 0-9, _ and -.")
                data == null || data["text"] == null -> add("info-commands.$name is skipped, it needs a text.")
                else -> {
                    val url = data["url"]?.toString()
                    if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
                        add("info-commands.$name.url must start with http:// or https://, the link is ignored.")
                    }
                    (data["aliases"] as? List<*>).orEmpty().map { it.toString() }.filterNot(NAME::matches).forEach {
                        add("info-commands.$name has the invalid alias '$it', it is ignored.")
                    }
                }
            }
        }
    }
}
