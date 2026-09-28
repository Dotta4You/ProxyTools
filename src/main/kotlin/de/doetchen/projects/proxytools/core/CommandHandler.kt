package de.doetchen.projects.proxytools.core

import java.util.UUID

/** Logic of `/maintenance` and `/proxytools`, shared by both platforms. */
class CommandHandler(private val core: ProxyToolsCore) {

    fun maintenance(actor: CommandActor, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase()
        val required = if (sub == "whitelist" || sub == "wl") Permissions.MAINTENANCE_WHITELIST else Permissions.MAINTENANCE
        if (!actor.hasPermission(required)) return actor.reply("no-permission")

        when (sub) {
            "on" -> toggle(actor, true)
            "off" -> toggle(actor, false)
            "toggle" -> toggle(actor, !core.maintenance.enabled)
            "status", null -> actor.reply(if (core.maintenance.enabled) "status-on" else "status-off")
            "whitelist", "wl" -> whitelist(actor, args.drop(1))
            else -> actor.reply("usage-maintenance")
        }
    }

    fun suggestMaintenance(actor: CommandActor, args: List<String>): List<String> {
        val last = args.lastOrNull().orEmpty()
        val candidates = when (args.size) {
            0, 1 -> buildList {
                if (actor.hasPermission(Permissions.MAINTENANCE)) addAll(listOf("on", "off", "toggle", "status"))
                if (actor.hasPermission(Permissions.MAINTENANCE_WHITELIST)) add("whitelist")
            }
            2 -> if (isWhitelist(args[0]) && actor.hasPermission(Permissions.MAINTENANCE_WHITELIST)) {
                listOf("add", "remove", "list")
            } else {
                emptyList()
            }
            3 -> if (isWhitelist(args[0]) && actor.hasPermission(Permissions.MAINTENANCE_WHITELIST)) {
                when (args[1].lowercase()) {
                    "add" -> core.platform.onlinePlayers.map { it.name }
                    "remove" -> core.store.entries().map { it.name }
                    else -> emptyList()
                }
            } else {
                emptyList()
            }
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(last, ignoreCase = true) }
    }

    fun proxyTools(actor: CommandActor, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            null, "info", "version" -> actor.reply(
                "info",
                "version" to core.platform.pluginVersion,
                "platform" to core.platform.platformName,
            )
            "reload" -> {
                if (!actor.hasPermission(Permissions.RELOAD)) return actor.reply("no-permission")
                actor.reply(if (core.reload()) "reloaded" else "reload-failed")
            }
            else -> actor.reply("usage-proxytools")
        }
    }

    fun suggestProxyTools(actor: CommandActor, args: List<String>): List<String> {
        if (args.size > 1) return emptyList()
        val options = buildList {
            add("info")
            if (actor.hasPermission(Permissions.RELOAD)) add("reload")
        }
        return options.filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }
    }

    private fun toggle(actor: CommandActor, enable: Boolean) {
        when (val result = core.maintenance.setEnabled(enable)) {
            is ToggleResult.Changed -> actor.reply(if (enable) "enabled" else "disabled", "count" to result.kicked.toString())
            ToggleResult.Unchanged -> actor.reply(if (enable) "already-on" else "already-off")
        }
    }

    private fun whitelist(actor: CommandActor, args: List<String>) {
        val target = args.getOrNull(1)
        when (args.firstOrNull()?.lowercase()) {
            "add" -> if (target == null) actor.reply("usage-whitelist") else addToWhitelist(actor, target)
            "remove" -> if (target == null) {
                actor.reply("usage-whitelist")
            } else {
                when (core.store.remove(target)) {
                    WhitelistRemoveResult.REMOVED -> actor.reply("whitelist-removed", "player" to target)
                    WhitelistRemoveResult.MISSING -> actor.reply("whitelist-missing", "player" to target)
                }
            }
            "list" -> {
                val entries = core.store.entries()
                if (entries.isEmpty()) {
                    actor.reply("whitelist-empty")
                } else {
                    val pendingSuffix = core.message("whitelist-pending-suffix")
                    val players = entries.joinToString(", ") { it.name + if (it.isPending) pendingSuffix else "" }
                    actor.reply("whitelist-list", "count" to entries.size.toString(), "players" to players)
                }
            }
            else -> actor.reply("usage-whitelist")
        }
    }

    private fun addToWhitelist(actor: CommandActor, arg: String) {
        val asUuid = runCatching { UUID.fromString(arg) }.getOrNull()
        val result = if (asUuid != null) {
            core.store.addResolved(asUuid, "?")
        } else {
            val online = core.platform.onlinePlayers.firstOrNull { it.name.equals(arg, ignoreCase = true) }
            if (online != null) core.store.addResolved(online.uniqueId, online.name) else core.store.addPending(arg)
        }
        when (result) {
            WhitelistAddResult.ADDED -> actor.reply("whitelist-added", "player" to arg)
            WhitelistAddResult.ADDED_PENDING -> actor.reply("whitelist-added-pending", "player" to arg)
            WhitelistAddResult.ALREADY_PRESENT -> actor.reply("whitelist-already", "player" to arg)
        }
    }

    private fun isWhitelist(arg: String) = arg.equals("whitelist", true) || arg.equals("wl", true)

    private fun CommandActor.reply(key: String, vararg placeholders: Pair<String, String>) =
        sendMessage(core.message(key, *placeholders))
}
