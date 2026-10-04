package de.doetchen.projects.proxytools.core.chat

import de.doetchen.projects.proxytools.core.CommandActor
import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class MessageService(private val core: ProxyToolsCore) {
    private val partners = ConcurrentHashMap<UUID, UUID>()
    private val spies = ConcurrentHashMap.newKeySet<UUID>()

    val enabled: Boolean get() = core.config.boolean("msg.enabled", true)

    fun send(sender: CommandActor, target: PlatformPlayer, text: String) {
        val senderId = sender.uniqueId ?: return
        partners[senderId] = target.uniqueId
        partners[target.uniqueId] = senderId

        val names = arrayOf("sender" to sender.name, "receiver" to target.name)
        sender.sendMessage(core.userMessage("msg-sent", text, *names))
        target.sendMessage(core.userMessage("msg-received", text, *names))

        val spyLine = core.userMessage("socialspy-format", text, *names)
        spies.filter { it != senderId && it != target.uniqueId }
            .mapNotNull(core.platform::findPlayer)
            .filter { it.hasPermission(Permissions.SOCIALSPY) }
            .forEach { it.sendMessage(spyLine) }
    }

    fun isBlocked(sender: CommandActor, target: PlatformPlayer): Boolean {
        if (sender.hasPermission(Permissions.MSG_BYPASS)) return false
        val senderId = sender.uniqueId
        val data = core.playerData
        return data.messagesDisabled(target.uniqueId) || (senderId != null && data.isIgnoring(target.uniqueId, senderId))
    }

    fun hasPartner(actor: CommandActor): Boolean = actor.uniqueId?.let(partners::containsKey) == true

    fun replyTarget(actor: CommandActor): PlatformPlayer? {
        val partner = actor.uniqueId?.let(partners::get) ?: return null
        return core.platform.findPlayer(partner)
    }

    fun forget(id: UUID) {
        partners.remove(id)
        spies -= id
    }

    fun setSpy(id: UUID, enable: Boolean?): Boolean {
        val on = enable ?: (id !in spies)
        if (on) spies += id else spies -= id
        return on
    }
}
