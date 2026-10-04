package de.doetchen.projects.proxytools.core.update

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.PlatformPlayer
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.core.ScheduledTask

internal class UpdateService(private val core: ProxyToolsCore, private val source: ReleaseSource) {
    class Release(val tag: String, val version: Version) {
        val url: String get() = "https://github.com/${GitHubReleases.REPOSITORY}/releases/tag/$tag"
    }

    sealed class Outcome {
        data object UpToDate : Outcome()
        data class Available(val release: Release) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    val enabled: Boolean get() = core.config.boolean("update-checker.enabled", true)

    @Volatile
    private var latest: Release? = null

    @Volatile
    private var announcedTag: String? = null

    @Volatile
    private var lastCheck = Long.MIN_VALUE / 2

    @Volatile
    private var failureLogged = false
    private var task: ScheduledTask? = null
    private var generation = 0

    val available: Release?
        get() {
            val current = Version.parse(core.platform.pluginVersion) ?: return null
            return latest?.takeIf { it.version > current }
        }

    @Synchronized
    fun restart() {
        task?.cancel()
        task = null
        generation++
        if (enabled) schedule(INITIAL_DELAY_MILLIS, generation)
    }

    fun checkNow(onDone: (Outcome) -> Unit) {
        core.platform.runLater(0) { onDone(check()) }
    }

    fun notifyAdmin(player: PlatformPlayer) {
        if (!enabled || !player.hasPermission(Permissions.UPDATE)) return
        core.platform.runLater(JOIN_DELAY_MILLIS) {
            if (core.platform.now() - lastCheck >= STALE_AFTER_MILLIS) check()
            val release = available ?: return@runLater
            core.platform.findPlayer(player.uniqueId)?.sendMessage(message(release), downloadUrl(release))
        }
    }

    fun downloadUrl(release: Release): String =
        core.config.string("update-checker.download-url").trim().ifEmpty { release.url }

    fun message(release: Release): String = core.message(
        "update-available",
        "current" to core.platform.pluginVersion,
        "latest" to release.tag,
        "url" to downloadUrl(release),
    )

    private fun schedule(delayMillis: Long, expectedGeneration: Int) {
        task = core.platform.runLater(delayMillis) { runScheduled(expectedGeneration) }
    }

    private fun runScheduled(expectedGeneration: Int) {
        if (expectedGeneration != generation) return
        check()
        synchronized(this) {
            if (expectedGeneration == generation && enabled) schedule(INTERVAL_MILLIS, expectedGeneration)
        }
    }

    private fun check(): Outcome = try {
        lastCheck = core.platform.now()
        val tag = source.latestTag()
        latest = tag?.let { text -> Version.parse(text)?.let { Release(text, it) } }
        val newer = available
        if (newer != null && announcedTag != newer.tag) {
            announcedTag = newer.tag
            core.platform.info("A new ProxyTools version is available: ${newer.tag} (you have ${core.platform.pluginVersion}). ${newer.url}")
        }
        if (newer != null) Outcome.Available(newer) else Outcome.UpToDate
    } catch (e: Exception) {
        failed(e)
    } catch (e: LinkageError) {
        failed(e)
    }

    private fun failed(error: Throwable): Outcome {
        val reason = error.message ?: error.javaClass.simpleName
        if (!failureLogged) {
            failureLogged = true
            core.platform.info("Could not check for updates, will try again later: $reason")
        }
        return Outcome.Failed(reason)
    }

    private companion object {
        const val INITIAL_DELAY_MILLIS = 10_000L
        const val INTERVAL_MILLIS = 12 * 60 * 60 * 1000L
        const val JOIN_DELAY_MILLIS = 2_000L
        const val STALE_AFTER_MILLIS = 60 * 60 * 1000L
    }
}
