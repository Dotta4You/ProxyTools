package de.doetchen.projects.proxytools.core.command

import de.doetchen.projects.proxytools.core.CommandActor
import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.maintenance.ScheduleResult
import de.doetchen.projects.proxytools.core.maintenance.TimerResult
import de.doetchen.projects.proxytools.core.maintenance.ToggleResult
import de.doetchen.projects.proxytools.core.maintenance.WhitelistAddResult
import de.doetchen.projects.proxytools.core.maintenance.WhitelistRemoveResult
import de.doetchen.projects.proxytools.core.text.DurationText
import de.doetchen.projects.proxytools.core.text.Text
import de.doetchen.projects.proxytools.core.update.UpdateService
import java.util.UUID

internal class CommandSpec(
    val name: String,
    val aliases: List<String>,
    val execute: (CommandActor, List<String>) -> Unit,
    val suggest: (CommandActor, List<String>) -> List<String>,
)

internal class CommandHandler(private val core: ProxyToolsCore) {
    fun specs(): List<CommandSpec> = buildList {
        add(spec("maintenance", ::maintenance, ::suggestMaintenance))
        add(spec("proxytools", ::proxyTools, ::suggestProxyTools, "pt"))
        add(spec("broadcast", ::broadcast, ::noSuggestions, "bc"))
        add(spec("teamchat", ::teamChat, ::noSuggestions, "tc"))
        add(spec("hub", ::hub, ::noSuggestions, "lobby", "l"))
        add(spec("msg", ::msg, ::suggestMsg, "tell", "w", "whisper", "m"))
        add(spec("reply", ::reply, ::noSuggestions, "r"))
        add(spec("socialspy", ::socialSpy, ::suggestSocialSpy, "spy"))
        add(spec("ignore", ::ignore, ::suggestIgnore))
        add(spec("msgtoggle", ::msgToggle, ::noSuggestions, "togglemsg"))
        core.infoCommands.definitions().forEach { definition ->
            val run = { actor: CommandActor, _: List<String> -> infoCommand(definition.name, actor) }
            add(spec(definition.name, run, ::noSuggestions, *definition.aliases.toTypedArray()))
        }
    }

    fun maintenance(actor: CommandActor, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase()
        val allowed = when (sub) {
            in WHITELIST_ALIASES -> actor.hasPermission(Permissions.MAINTENANCE_WHITELIST)
            "status", null -> actor.canSeeStatus()
            else -> actor.hasPermission(Permissions.MAINTENANCE)
        }
        if (!allowed) return actor.reply("no-permission")

        when (sub) {
            "on" -> onCommand(actor, args.drop(1))
            "off" -> setState(actor, false, null)
            "schedule" -> scheduleCommand(actor, args.drop(1))
            "status", null -> status(actor)
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
            "motd" -> {
                if (!actor.hasPermission(Permissions.MOTD)) return actor.reply("no-permission")
                core.motd.preview()?.let { actor.reply("motd-preview", "text" to it) } ?: actor.reply("motd-preview-empty")
            }
            "reload" -> {
                if (!actor.hasPermission(Permissions.RELOAD)) return actor.reply("no-permission")
                actor.reply(if (core.reload()) "reloaded" else "reload-failed")
            }
            "update" -> {
                if (!actor.hasPermission(Permissions.UPDATE)) return actor.reply("no-permission")
                actor.reply("update-checking")
                core.updates.checkNow { outcome ->
                    when (outcome) {
                        is UpdateService.Outcome.Available -> actor.sendMessage(core.updates.message(outcome.release), core.updates.downloadUrl(outcome.release))
                        UpdateService.Outcome.UpToDate -> actor.reply("update-latest", "current" to core.platform.pluginVersion)
                        is UpdateService.Outcome.Failed -> actor.reply("update-failed")
                    }
                }
            }
            else -> actor.reply("usage-proxytools")
        }
    }

    fun suggestProxyTools(actor: CommandActor, args: List<String>): List<String> {
        if (args.size > 1) return emptyList()
        val options = buildList {
            add("info")
            if (actor.hasPermission(Permissions.MOTD)) add("motd")
            if (actor.hasPermission(Permissions.RELOAD)) add("reload")
            if (actor.hasPermission(Permissions.UPDATE)) add("update")
        }
        return options.filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }
    }

    fun broadcast(actor: CommandActor, args: List<String>) {
        if (!actor.hasPermission(Permissions.BROADCAST)) return actor.reply("no-permission")
        if (args.isEmpty()) return actor.reply("usage-broadcast")
        core.broadcast(core.message("broadcast", "message" to args.joinToString(" ")))
    }

    fun teamChat(actor: CommandActor, args: List<String>) {
        if (!core.config.boolean("teamchat.enabled", true)) return actor.reply("teamchat-disabled")
        if (!actor.hasPermission(Permissions.TEAMCHAT)) return actor.reply("no-permission")
        if (args.isEmpty()) return actor.reply("usage-teamchat")
        core.teamChat(actor, args.joinToString(" "))
    }

    fun hub(actor: CommandActor, args: List<String>) {
        when {
            !core.hub.enabled -> actor.reply("hub-disabled")
            !actor.isPlayer -> actor.reply("players-only")
            core.hub.isHub(actor.serverName) -> actor.reply("hub-already")
            else -> {
                val target = core.hub.target()
                if (target != null && actor.connectTo(target)) actor.reply("hub-connecting") else actor.reply("hub-unavailable")
            }
        }
    }

    fun msg(actor: CommandActor, args: List<String>) {
        if (!core.privateMessages.enabled) return actor.reply("msg-disabled")
        if (!actor.isPlayer) return actor.reply("players-only")
        if (args.size < 2) return actor.reply("usage-msg")
        val target = core.platform.findPlayer(args[0]) ?: return actor.reply("player-not-found", "player" to args[0])
        if (target.uniqueId == actor.uniqueId) return actor.reply("msg-self")
        if (core.privateMessages.isBlocked(actor, target)) return actor.reply("msg-blocked")
        core.privateMessages.send(actor, target, args.drop(1).joinToString(" "))
    }

    fun reply(actor: CommandActor, args: List<String>) {
        if (!core.privateMessages.enabled) return actor.reply("msg-disabled")
        if (!actor.isPlayer) return actor.reply("players-only")
        if (args.isEmpty()) return actor.reply("usage-reply")
        if (!core.privateMessages.hasPartner(actor)) return actor.reply("reply-nobody")
        val target = core.privateMessages.replyTarget(actor) ?: return actor.reply("reply-offline")
        if (core.privateMessages.isBlocked(actor, target)) return actor.reply("msg-blocked")
        core.privateMessages.send(actor, target, args.joinToString(" "))
    }

    fun socialSpy(actor: CommandActor, args: List<String>) {
        if (!actor.hasPermission(Permissions.SOCIALSPY)) return actor.reply("no-permission")
        val id = actor.uniqueId ?: return actor.reply("players-only")
        val enable = when (args.firstOrNull()?.lowercase()) {
            null -> null
            "on" -> true
            "off" -> false
            else -> return actor.reply("usage-socialspy")
        }
        actor.reply(if (core.privateMessages.setSpy(id, enable)) "socialspy-on" else "socialspy-off")
    }

    fun ignore(actor: CommandActor, args: List<String>) {
        if (!core.privateMessages.enabled) return actor.reply("msg-disabled")
        val id = actor.uniqueId ?: return actor.reply("players-only")
        val arg = args.firstOrNull() ?: return actor.reply("usage-ignore")
        val data = core.playerData
        if (arg.equals("list", ignoreCase = true)) {
            val names = data.ignoredNames(id)
            if (names.isEmpty()) return actor.reply("ignore-list-empty")
            return actor.reply("ignore-list", "players" to names.joinToString(", "))
        }
        val online = core.platform.findPlayer(arg)
        when {
            online?.uniqueId == id -> actor.reply("msg-self")
            online != null && data.isIgnoring(id, online.uniqueId) -> {
                data.unignore(id, online.uniqueId)
                actor.reply("ignore-removed", "player" to online.name)
            }
            online != null -> {
                data.ignore(id, online.uniqueId, online.name)
                actor.reply("ignore-added", "player" to online.name)
            }
            data.unignoreByName(id, arg) -> actor.reply("ignore-removed", "player" to arg)
            else -> actor.reply("player-not-found", "player" to arg)
        }
    }

    fun msgToggle(actor: CommandActor, args: List<String>) {
        if (!core.privateMessages.enabled) return actor.reply("msg-disabled")
        val id = actor.uniqueId ?: return actor.reply("players-only")
        val disable = !core.playerData.messagesDisabled(id)
        core.playerData.setMessagesDisabled(id, disable)
        actor.reply(if (disable) "msgtoggle-off" else "msgtoggle-on")
    }

    fun infoCommand(name: String, actor: CommandActor) {
        val command = core.infoCommands.find(name) ?: return actor.reply("command-removed")
        if (command.permission != null && !actor.hasPermission(command.permission)) return actor.reply("no-permission")
        val text = Text.replace(command.text, "player" to actor.name, "online" to core.platform.onlineCount.toString())
        actor.sendMessage(Text.colorize(text), command.url)
    }

    fun suggestIgnore(actor: CommandActor, args: List<String>): List<String> {
        if (args.size > 1) return emptyList()
        val own = actor.uniqueId?.let(core.playerData::ignoredNames).orEmpty()
        return (listOf("list") + core.platform.onlinePlayers.map { it.name }.filter { it != actor.name } + own).distinct()
            .filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }
    }

    fun suggestMsg(actor: CommandActor, args: List<String>): List<String> {
        if (args.size > 1) return emptyList()
        return core.platform.onlinePlayers.map { it.name }
            .filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) && it != actor.name }
    }

    fun suggestSocialSpy(actor: CommandActor, args: List<String>): List<String> {
        if (args.size > 1 || !actor.hasPermission(Permissions.SOCIALSPY)) return emptyList()
        return listOf("on", "off").filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }
    }

    fun noSuggestions(actor: CommandActor, args: List<String>): List<String> = emptyList()

    private fun spec(
        name: String,
        execute: (CommandActor, List<String>) -> Unit,
        suggest: (CommandActor, List<String>) -> List<String>,
        vararg aliases: String,
    ) = CommandSpec(
        name,
        aliases.toList(),
        { actor, args ->
            try {
                execute(actor, args)
            } catch (e: Exception) {
                core.platform.warn("/$name failed: $e")
                actor.sendMessage(core.message("command-error"))
            }
        },
        { actor, args ->
            try {
                suggest(actor, args)
            } catch (e: Exception) {
                emptyList()
            }
        },
    )

    private fun status(actor: CommandActor) {
        val placeholders = arrayOf(
            "duration" to core.maintenance.describeDuration(),
            "schedule" to core.maintenance.describeSchedule(),
        )
        if (core.maintenance.enabled) {
            actor.sendMessage(core.message("status-on", *placeholders) + core.maintenance.reasonSuffix())
        } else {
            actor.reply("status-off", *placeholders)
        }
    }

    private fun onCommand(actor: CommandActor, args: List<String>) {
        val durationArg = args.firstOrNull()?.takeIf { it.firstOrNull()?.isDigit() == true }
        val reason = args.drop(if (durationArg != null) 1 else 0).joinToString(" ").ifBlank { null }
        if (durationArg == null) return setState(actor, true, reason)
        val millis = DurationText.parseMillis(durationArg) ?: return actor.reply("invalid-duration", "input" to durationArg)
        when (val result = core.maintenance.enableFor(millis, reason)) {
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

        when (core.maintenance.scheduleStart(delayMillis, durationMillis, args.drop(2).joinToString(" ").ifBlank { null })) {
            ScheduleResult.Scheduled -> actor.reply(
                "schedule-set",
                "delay" to DurationText.format(delayMillis / 1000),
                "duration" to DurationText.format(durationMillis / 1000),
            )
            ScheduleResult.AlreadyScheduled -> actor.reply("schedule-already-set", "schedule" to core.maintenance.describeSchedule())
            ScheduleResult.AlreadyActive -> actor.reply("already-on")
        }
    }

    private fun setState(actor: CommandActor, enable: Boolean, reason: String?) {
        when (val result = core.maintenance.setEnabled(enable, reason)) {
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
        val online = core.platform.findPlayer(arg)
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
