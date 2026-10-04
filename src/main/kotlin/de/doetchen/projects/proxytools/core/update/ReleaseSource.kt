package de.doetchen.projects.proxytools.core.update

import java.net.HttpURLConnection
import java.net.URI

internal fun interface ReleaseSource {
    fun latestTag(): String?
}

internal object GitHubReleases : ReleaseSource {
    const val REPOSITORY = "Dotta4You/ProxyTools"

    private const val TIMEOUT_MILLIS = 5_000
    private const val MAX_BYTES = 64 * 1024
    private val TAG = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

    override fun latestTag(): String? {
        val connection = URI("https://api.github.com/repos/$REPOSITORY/releases/latest").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "ProxyTools-UpdateChecker")
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.use { it.readNBytes(MAX_BYTES) }.toString(Charsets.UTF_8)
                    TAG.find(body)?.groupValues?.get(1) ?: error("the answer from GitHub has no version")
                }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> error("GitHub answered with HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }
}
