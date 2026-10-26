/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.plugin.driver

import android.os.Build
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.fetchStringFromUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DriverFeed"

/**
 * A driver plugin APK listed on a verified release feed.
 */
data class DriverFeedEntry(
    /** Asset file name, e.g. "Turnip.25.1.1.R28B.SDK29.apk" */
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Release publish time (millis) if known */
    val publishedAt: Long?,
    /** Human readable source label, e.g. "FCL-Team/FCLDriverPlugin" */
    val sourceLabel: String,
    /** Minimum Android SDK parsed from the asset name, null when not encoded */
    val minSdk: Int?,
    /** Mesa/Turnip version parsed from the name, e.g. "25.1.1" */
    val versionName: String?,
    /** Driver family parsed from the name: Turnip / PurpleVK / other */
    val driverKind: String,
    /** Adreno generation variant parsed from the name: "A6XX" / "A7XX" / null */
    val adrenoVariant: String?
) {
    /** Whether this entry can run on the current device. */
    fun isCompatibleWith(deviceSdk: Int = Build.VERSION.SDK_INT): Boolean {
        if (minSdk != null && deviceSdk < minSdk) return false
        return true
    }
}

/**
 * Fetches GPU driver plugin APKs from verified community release feeds.
 *
 * The default (and currently only) feed is the official FCL team driver
 * plugin repository, whose artifacts follow the FCL plugin spec and are
 * loaded automatically by the launcher's plugin system after install.
 */
object DriverFeed {
    /** Verified GitHub release feeds for FCL-compatible driver plugins. */
    private val SOURCES = listOf(
        "FCL-Team/FCLDriverPlugin"
    )

    private val API_TEMPLATE = "https://api.github.com/repos/%s/releases?per_page=10"

    /**
     * Fetches all driver APK entries from every source, newest first.
     * @throws Exception on network/parsing failure (caller decides how to surface)
     */
    suspend fun fetch(): List<DriverFeedEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<DriverFeedEntry>()
        var lastError: Exception? = null
        for (source in SOURCES) {
            try {
                entries += fetchSource(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.error(TAG, "Failed to fetch driver feed from $source", e)
                lastError = e
            }
        }
        if (entries.isEmpty() && lastError != null) throw lastError
        entries.sortedByDescending { it.publishedAt ?: 0L }
    }

    private suspend fun fetchSource(source: String): List<DriverFeedEntry> {
        val url = API_TEMPLATE.format(source)
        var lastException: Exception? = null
        repeat(2) { attempt ->
            try {
                val body = fetchStringFromUrl(url)
                return parseReleases(body, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt == 0) delay(1200L)
            }
        }
        throw lastException ?: IllegalStateException("Failed to fetch $url")
    }

    private fun parseReleases(body: String, source: String): List<DriverFeedEntry> {
        if (body.isBlank()) throw IllegalStateException("Empty driver feed response")
        val releases = JSONArray(body)
        val result = mutableListOf<DriverFeedEntry>()
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            val publishedAt = runCatching {
                val raw = release.optString("published_at", "")
                if (raw.isBlank()) null
                else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(raw)?.time
            }.getOrNull()
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url", "")
                if (url.isBlank()) continue
                result += parseEntry(
                    name = name,
                    downloadUrl = url,
                    sizeBytes = asset.optLong("size", 0L),
                    publishedAt = publishedAt,
                    sourceLabel = source
                )
            }
        }
        return result
    }

    private fun parseEntry(
        name: String,
        downloadUrl: String,
        sizeBytes: Long,
        publishedAt: Long?,
        sourceLabel: String
    ): DriverFeedEntry {
        val stem = name.removeSuffix(".apk").removeSuffix(".APK")
        val parts = stem.split('.')
        // Expected: Kind[.Version[.Tag[.Variant[.SDKxx]]]]
        val kind = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Driver"
        val minSdk = parts.firstNotNullOfOrNull { p ->
            if (p.startsWith("SDK", ignoreCase = true)) p.removePrefix("SDK").removePrefix("sdk").toIntOrNull() else null
        }
        val version = parts.getOrNull(1)?.takeIf { Regex("^\\d").matches(it) }
        val variant = parts.firstNotNullOfOrNull { p ->
            if (Regex("^A\\dXX$", RegexOption.IGNORE_CASE).matches(p)) p.uppercase() else null
        }
        return DriverFeedEntry(
            name = name,
            downloadUrl = downloadUrl,
            sizeBytes = sizeBytes,
            publishedAt = publishedAt,
            sourceLabel = sourceLabel,
            minSdk = minSdk,
            versionName = version,
            driverKind = kind,
            adrenoVariant = variant
        )
    }

    /** Formats a millis timestamp as a short local date, or null. */
    fun formatDate(time: Long?): String? {
        if (time == null || time <= 0L) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(time))
        }.getOrNull()
    }
}
