package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.AppVersion
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object VersionChecker {
    const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    const val FAILURE_BACKOFF_MS = 15L * 60 * 1000
    const val RELEASES_URL = "https://api.github.com/repos/lamb-liver/appforsale/releases/latest"
    const val MAX_BODY_BYTES = 64 * 1024

    suspend fun cachedOrFetch(
        prefs: AppUiPreferences,
        currentVersion: String,
        nowMillis: Long,
        fetch: suspend () -> String? = { fetchLatestTag() },
    ): String? {
        val cache = prefs.versionCache()
        cache.retryAtMillis?.let { if (nowMillis < it) return cache.latestTag }
        cache.checkedAtMillis?.let { checked ->
            if (nowMillis - checked < CACHE_TTL_MS) return cache.latestTag
        }
        return try {
            val tag = withContext(Dispatchers.IO) { fetch() }?.takeIf { AppVersion.parse(it) != null }
            if (tag != null) {
                prefs.saveVersionCache(tag, nowMillis)
                tag
            } else {
                prefs.saveVersionRetry(nowMillis + FAILURE_BACKOFF_MS)
                cache.latestTag
            }
        } catch (_: Throwable) {
            prefs.saveVersionRetry(nowMillis + FAILURE_BACKOFF_MS)
            cache.latestTag
        }
    }

    fun newerThanCurrent(currentVersion: String, latestTag: String?): String? {
        val tag = latestTag ?: return null
        return tag.takeIf { AppVersion.isNewer(currentVersion, it) }
    }

    internal fun fetchLatestTag(): String? {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            instanceFollowRedirects = false
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val declared = connection.contentLength
            if (declared > MAX_BODY_BYTES) return null
            val body = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(4 * 1024)
                val out = StringBuilder()
                while (true) {
                    val n = reader.read(chars)
                    if (n < 0) break
                    if (out.length + n > MAX_BODY_BYTES) return null
                    out.append(chars, 0, n)
                }
                out.toString()
            }
            latestTagFromReleaseJson(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun latestTagFromReleaseJson(body: String): String? {
        if (body.length > MAX_BODY_BYTES) return null
        return try {
            val json = JSONObject(body)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) null
            else json.optString("tag_name").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun fetchLatestTagAsync(): String? = withContext(Dispatchers.IO) { fetchLatestTag() }
}
