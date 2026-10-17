package de.doetchen.projects.proxytools.core.config

import de.doetchen.projects.proxytools.core.command.InfoCommandParser

internal object ConfigValidator {
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
        addAll(InfoCommandParser.parse(config.get("info-commands")).problems)
    }
}
