package de.doetchen.projects.proxytools.core

import kotlin.random.Random

class AnnouncementService(private val core: ProxyToolsCore) {
    private var task: ScheduledTask? = null
    private var generation = 0
    private var lastIndex = -1

    @Synchronized
    fun restart() {
        task?.cancel()
        task = null
        generation++
        lastIndex = -1
        if (core.config.boolean("announcements.enabled") && messages().isNotEmpty()) schedule(generation)
    }

    private fun schedule(expectedGeneration: Int) {
        val intervalMillis = core.config.int("announcements.interval-seconds", 300).coerceAtLeast(MIN_INTERVAL_SECONDS) * 1000L
        task = core.platform.runLater(intervalMillis) { tick(expectedGeneration) }
    }

    @Synchronized
    private fun tick(expectedGeneration: Int) {
        if (expectedGeneration != generation) return
        announce()
        schedule(expectedGeneration)
    }

    private fun announce() {
        val messages = messages()
        val players = core.platform.onlinePlayers
        if (messages.isEmpty() || players.isEmpty()) return

        lastIndex = when {
            messages.size == 1 -> 0
            core.config.string("announcements.mode", "sequential") == "random" ->
                generateSequence { Random.nextInt(messages.size) }.first { it != lastIndex }
            else -> (lastIndex + 1) % messages.size
        }
        val prefix = core.config.string("announcements.prefix")
        val text = Text.colorize(messages[lastIndex].lines().joinToString("\n") { prefix + it })
        players.forEach { it.sendMessage(text) }
    }

    private fun messages(): List<String> = (core.config.get("announcements.messages") as? List<*>).orEmpty()
        .map { if (it is List<*>) it.joinToString("\n") else it?.toString().orEmpty() }
        .filter { it.isNotBlank() }

    private companion object {
        const val MIN_INTERVAL_SECONDS = 5
    }
}
