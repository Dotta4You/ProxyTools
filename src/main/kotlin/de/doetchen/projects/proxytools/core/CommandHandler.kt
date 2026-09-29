package de.doetchen.projects.proxytools.core

import java.util.UUID

class CommandHandler(private val core: ProxyToolsCore) {
    fun maintenance(actor: CommandActor, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase()
        val allowed = when (sub) {
            in WHITELIST_ALIASES -> actor.hasPermission(Permissions.MAINTENANCE_WHITELIST)
            "status", null -> actor.canSeeStatus()
            else -> actor.hasPermission(Permissions.MAINTENANCE)
        }
        if (!allowed) return actor.reply("no-permission")

        when (sub) {
            "on" -> onCommand(actor, args.getOrNull(1))
            "off" -> setState(actor, false)
            "schedule" -> scheduleCommand(actor, args.drop(1))
            "status", null -> actor.reply(
                if (core.maintenance.enabled) "status-on" else "status-off",
                "duration" to core.maintenance.describeDuration(),
                "schedule" to core.maintenance.describeSchedule(),
            )
            in WHITELIST_ALIASES -> whitelist(actor, args.drop(1))
            else -> actor.reply("usage-maintenance")
        }
    }

    fun suggestMaintenance(actor: CommandActor, args: List<String>): List<String> {
        val sub = args.firstOrNull()?.lowercase()
        val canControl = actor.hasPermission(Permissions.MAINTENANCE)
        val canWhitelist = actor.hasPermission(Permissions.MAINTENANCE_WHITELIST)
        val durations = listOf("10m", "30m", "1h")
        val candidates = when (args.size) {
            0, 1 -> buildList {
                if (actor.canSeeStatus()) add("status")
                if (canControl) addAll(listOf("on", "off", "schedule"))
                if (canWhitelist) add("whitelist")
            }
            2 -> when {
                sub == "on" && canControl -> durations
                sub == "schedule" && canControl -> listOf("cancel") + durations
                sub in WHITELIST_ALIASES && canWhitelist -> listOf("add", "remove", "list")
                else -> emptyList()
            }
            3 -> when {
                sub == "schedule" && canControl && !args[1].equals("cancel", true) -> durations
                sub in WHITELIST_ALIASES && canWhitelist -> when (args[1].lowercase()) {
                    "add" -> core.platform.onlinePlayers.map { it.name }
                    "remove" -> core.store.entries().map { it.name }
                    else -> emptyList()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }
    }

    fun proxyTools(actor: CommandActor, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            null, "info", "version" -> actor.reply(
                "info",
                "version" to core.platform.pluginVersion,
                "platform" to core.platform.platformName,
            )
            "motd" -> core.motd.preview()?.let { actor.reply("motd-preview", "text" to it) } ?: actor.reply("motd-preview-empty")
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
            add("motd")
            if (actor.hasPermission(Permissions.RELOAD)) add("reload")
        }
        return options.filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }
    }

    fun broadcast(actor: CommandActor, args: List<String>) {
        if (!actor.hasPermission(Permissions.BROADCAST)) return actor.reply("no-permission")
        if (args.isEmpty()) return actor.reply("usage-broadcast")
        core.broadcast(core.message("broadcast", "message" to args.joinToString(" ")))
    }

    fun suggestBroadcast(actor: CommandActor, args: List<String>): List<String> = emptyList()

    private fun onCommand(actor: CommandActor, durationArg: String?) {
        if (durationArg == null) return setState(actor, true)
        val millis = DurationText.parseMillis(durationArg) ?: return actor.reply("invalid-duration", "input" to durationArg)
        when (val result = core.maintenance.enableFor(millis)) {
            is TimerResult.Started -> actor.reply(
                "enabled-timed",
                "count" to result.kicked.toString(),
                "duration" to DurationText.format(millis / 1000),
            )
            TimerResult.AlreadyRunning -> actor.reply("timer-already-running", "duration" to core.maintenance.describeDuration())
        }
    }

    private fun scheduleCommand(actor: CommandActor, args: List<String>) {
        if (args.firstOrNull()?.equals("cancel", ignoreCase = true) == true) {
            return actor.reply(if (core.maintenance.cancelSchedule()) "schedule-cancelled" else "schedule-not-set")
        }
        val delayArg = args.getOrNull(0)
        val durationArg = args.getOrNull(1)
        if (delayArg == null || durationArg == null) return actor.reply("usage-schedule")
        val delayMillis = DurationText.parseMillis(delayArg) ?: return actor.reply("invalid-duration", "input" to delayArg)
        val durationMillis = DurationText.parseMillis(durationArg) ?: return actor.reply("invalid-duration", "input" to durationArg)

        when (core.maintenance.scheduleStart(delayMillis, durationMillis)) {
            ScheduleResult.Scheduled -> actor.reply(
                "schedule-set",
                "delay" to DurationText.format(delayMillis / 1000),
                "duration" to DurationText.format(durationMillis / 1000),
            )
            ScheduleResult.AlreadyScheduled -> actor.reply("schedule-already-set", "schedule" to core.maintenance.describeSchedule())
            ScheduleResult.AlreadyActive -> actor.reply("already-on")
        }
    }

    private fun setState(actor: CommandActor, enable: Boolean) {
        when (val result = core.maintenance.setEnabled(enable)) {
            is ToggleResult.Changed -> actor.reply(if (enable) "enabled" else "disabled", "count" to result.kicked.toString())
            ToggleResult.Unchanged -> actor.reply(if (enable) "already-on" else "already-off")
        }
    }

    private fun whitelist(actor: CommandActor, args: List<String>) {
        val target = args.getOrNull(1)
        when (val action = args.firstOrNull()?.lowercase()) {
            "add", "remove" -> when {
                target == null -> actor.reply("usage-whitelist")
                action == "add" -> addToWhitelist(actor, target)
                else -> when (core.store.remove(target)) {
                    WhitelistRemoveResult.REMOVED -> actor.reply("whitelist-removed", "player" to target)
                    WhitelistRemoveResult.MISSING -> actor.reply("whitelist-missing", "player" to target)
                }
            }
            "list" -> {
                val entries = core.store.entries()
                if (entries.isEmpty()) return actor.reply("whitelist-empty")
                val pendingSuffix = core.message("whitelist-pending-suffix")
                val players = entries.joinToString(", ") { it.name + if (it.isPending) pendingSuffix else "" }
                actor.reply("whitelist-list", "count" to entries.size.toString(), "players" to players)
            }
            else -> actor.reply("usage-whitelist")
        }
    }

    private fun addToWhitelist(actor: CommandActor, arg: String) {
        val uuid = runCatching { UUID.fromString(arg) }.getOrNull()
        val online = core.platform.onlinePlayers.firstOrNull { it.name.equals(arg, ignoreCase = true) }
        val result = when {
            uuid != null -> core.store.addResolved(uuid, "?")
            online != null -> core.store.addResolved(online.uniqueId, online.name)
            else -> core.store.addPending(arg)
        }
        when (result) {
            WhitelistAddResult.ADDED -> actor.reply("whitelist-added", "player" to arg)
            WhitelistAddResult.ADDED_PENDING -> actor.reply("whitelist-added-pending", "player" to arg)
            WhitelistAddResult.ALREADY_PRESENT -> actor.reply("whitelist-already", "player" to arg)
        }
    }

    private fun CommandActor.canSeeStatus() = hasPermission(Permissions.MAINTENANCE) || hasPermission(Permissions.MAINTENANCE_STATUS)

    private fun CommandActor.reply(key: String, vararg placeholders: Pair<String, String>) =
        sendMessage(core.message(key, *placeholders))

    private companion object {
        val WHITELIST_ALIASES = setOf("whitelist", "wl")
    }
}
