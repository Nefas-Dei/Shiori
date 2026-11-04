package eu.kanade.tachiyomi.data.track.custom

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.max
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * Custom tracker backed by the self-hosted Shiori sync server.
 *
 * Credentials use the existing tracker storage: username is the server base URL and password is
 * the Bearer token. The server stores one entry per `(site, series_key)` and resolves writes with
 * last-write-wins on `updated_at`.
 */
class CustomTracker(id: Long) : BaseTracker(id, "Shiori Sync"), DeletableTracker {

    private val json: Json by injectLazy()
    private val jsonMime = "application/json; charset=utf-8".toMediaType()

    override fun getLogo() = R.drawable.ic_tracker_shiori

    override fun getStatusList() = listOf(READING, COMPLETED, ON_HOLD, PLAN_TO_READ, DROPPED)

    override fun getStatus(status: Long): StringResource? {
        return when (status) {
            READING -> MR.strings.reading
            COMPLETED -> MR.strings.completed
            ON_HOLD -> MR.strings.on_hold
            PLAN_TO_READ -> MR.strings.plan_to_read
            DROPPED -> MR.strings.dropped
            else -> null
        }
    }

    override fun getReadingStatus() = READING
    override fun getRereadingStatus() = READING
    override fun getCompletionStatus() = COMPLETED

    override fun getScoreList(): List<String> = listOf("")
    override fun indexToScore(index: Int): Double = 0.0
    override fun get10PointScore(track: DomainTrack): Double = 0.0
    override fun displayScore(track: DomainTrack): String = "-"

    override suspend fun login(username: String, password: String) {
        val baseUrl = normalizeBaseUrl(username)
        val token = password.trim()
        if (token.isBlank()) error("Sync token is required")

        // /health is public, so hit an authenticated endpoint to validate the token too.
        withTimeout(REQUEST_TIMEOUT_MS) {
            client.newCall(GET("$baseUrl$API_PATH/progress?limit=1", authHeaders(token)))
                .awaitSuccess()
                .use { it.body.string() }
        }

        saveCredentials(baseUrl, token)
        saveDisplayUsername(baseUrl.toHttpUrlOrNull()?.host ?: baseUrl)
    }

    override suspend fun search(query: String): List<TrackSearch> {
        if (!isLoggedIn) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            val response = withTimeout(REQUEST_TIMEOUT_MS) {
                client.newCall(GET(apiUrl("/progress?q=$encoded&limit=200"), authHeaders()))
                    .awaitSuccess()
            }
            val entries = response.use { parseEntries(it.body.string()) }
            entries.mapNotNull(::entryToSearch)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Shiori Sync search failed" }
            emptyList()
        }
    }

    override suspend fun searchNovels(query: String): List<TrackSearch> = search(query)

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        if (!isLoggedIn) return track
        // Search results carry their binding already; refresh fills it for older/restored tracks.
        return runCatching { refresh(track) }.getOrElse { track }
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (!isLoggedIn) return track
        if (track.progress_site.isBlank() || track.progress_series_key.isBlank()) return track

        if (didReadChapter) {
            track.progress_percent = 100.0
        }

        val updatedAt = max(System.currentTimeMillis(), track.progress_updated_at + 1)
        val body = buildJsonObject {
            put("site", track.progress_site)
            put("series_key", track.progress_series_key)
            put("series_title", track.title)
            put("series_url", track.tracking_url)
            put("chapter", track.last_chapter_read)
            put("scroll_percent", track.progress_percent.coerceIn(0.0, 100.0))
            put("paragraph_index", track.progress_paragraph.toInt().coerceAtLeast(-1))
            put("status", statusToApi(track.status))
            put("updated_at", updatedAt)
            put("device", "android")
        }.toString().toRequestBody(jsonMime)

        val response = withTimeout(REQUEST_TIMEOUT_MS) {
            client.newCall(PUT(apiUrl("/progress"), authHeaders(), body)).await()
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful && it.code != HTTP_CONFLICT) {
                throw HttpException(it.code)
            }
            parseEntry(text)?.let { entry -> applyEntry(track, entry) }
        }
        return track
    }

    override suspend fun refresh(track: Track): Track {
        if (!isLoggedIn) return track
        val entry = findEntry(
            site = track.progress_site,
            seriesKey = track.progress_series_key,
            title = track.title,
            seriesUrl = track.tracking_url,
        ) ?: return track
        applyEntry(track, entry)
        return track
    }

    override suspend fun delete(track: DomainTrack) {
        if (!isLoggedIn) return
        val entry = findEntry(
            site = track.progressSite,
            seriesKey = track.progressSeriesKey,
            title = track.title,
            seriesUrl = track.remoteUrl,
        ) ?: return
        val id = entry.string("id") ?: return
        val response = withTimeout(REQUEST_TIMEOUT_MS) {
            client.newCall(DELETE(apiUrl("/progress/${URLEncoder.encode(id, Charsets.UTF_8.name())}"), authHeaders()))
                .await()
        }
        response.use {
            if (!it.isSuccessful && it.code != 404) {
                throw HttpException(it.code)
            }
            it.body.string()
        }
    }

    private suspend fun findEntry(
        site: String,
        seriesKey: String,
        title: String,
        seriesUrl: String,
    ): JsonObject? {
        val query = if (site.isNotBlank() && seriesKey.isNotBlank()) {
            "site=${URLEncoder.encode(site, Charsets.UTF_8.name())}" +
                "&q=${URLEncoder.encode(seriesKey, Charsets.UTF_8.name())}&limit=100"
        } else {
            "q=${URLEncoder.encode(title, Charsets.UTF_8.name())}&limit=200"
        }

        val response = withTimeout(REQUEST_TIMEOUT_MS) {
            client.newCall(GET(apiUrl("/progress?$query"), authHeaders())).awaitSuccess()
        }
        val entries = response.use { parseEntries(it.body.string()) }
        return entries.firstOrNull { entry ->
            val entrySite = entry.string("site")
            val entryKey = entry.string("series_key")
            when {
                site.isNotBlank() && seriesKey.isNotBlank() -> entrySite == site && entryKey == seriesKey
                seriesUrl.isNotBlank() -> entry.string("series_url") == seriesUrl
                else -> entry.string("series_title").equals(title, ignoreCase = true)
            }
        }
    }

    private fun entryToSearch(entry: JsonObject): TrackSearch? {
        val site = entry.string("site") ?: return null
        val seriesKey = entry.string("series_key") ?: return null
        return TrackSearch.create(this.id).apply {
            remote_id = remoteIdFor(entry.string("id").orEmpty())
            title = entry.string("series_title") ?: seriesKey
            tracking_url = entry.string("series_url").orEmpty()
            last_chapter_read = entry.number("chapter") ?: 0.0
            progress_site = site
            progress_series_key = seriesKey
            progress_percent = entry.number("scroll_percent") ?: 0.0
            progress_paragraph = entry.long("paragraph_index") ?: -1L
            progress_updated_at = entry.long("updated_at") ?: 0L
            status = statusFromApi(entry.string("status"))
        }
    }

    private fun applyEntry(track: Track, entry: JsonObject) {
        val site = entry.string("site") ?: return
        val seriesKey = entry.string("series_key") ?: return
        track.remote_id = remoteIdFor(entry.string("id").orEmpty())
        track.title = entry.string("series_title") ?: track.title
        track.tracking_url = entry.string("series_url") ?: track.tracking_url
        track.last_chapter_read = entry.number("chapter") ?: track.last_chapter_read
        track.status = statusFromApi(entry.string("status"))
        track.progress_site = site
        track.progress_series_key = seriesKey
        track.progress_percent = entry.number("scroll_percent") ?: 0.0
        track.progress_paragraph = entry.long("paragraph_index") ?: -1L
        track.progress_updated_at = entry.long("updated_at") ?: 0L
    }

    private fun parseEntries(body: String): List<JsonObject> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        return (root["entries"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    private fun parseEntry(body: String): JsonObject? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        return root["entry"] as? JsonObject
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun statusFromApi(status: String?): Long = when (status?.lowercase()) {
        "completed" -> COMPLETED
        "on_hold" -> ON_HOLD
        "planned" -> PLAN_TO_READ
        "dropped" -> DROPPED
        else -> READING
    }

    private fun statusToApi(status: Long): String = when (status) {
        COMPLETED -> "completed"
        ON_HOLD -> "on_hold"
        PLAN_TO_READ -> "planned"
        DROPPED -> "dropped"
        else -> "reading"
    }

    private fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (value.isBlank()) error("Sync server URL is required")
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://$value"
        }
        val parsed = value.toHttpUrlOrNull() ?: error("Sync server URL is invalid")
        val path = parsed.encodedPath.trimEnd('/')
        val cleanedPath = if (path.endsWith(API_PATH)) path.removeSuffix(API_PATH) else path
        return parsed.newBuilder()
            .encodedPath(cleanedPath.ifEmpty { "/" })
            .build()
            .toString()
            .trimEnd('/')
    }

    private fun apiUrl(path: String): String = "${getUsername().trimEnd('/')}$API_PATH$path"

    private fun authHeaders(token: String = getPassword()): Headers = Headers.Builder()
        .add("Authorization", "Bearer $token")
        .add("Accept", "application/json")
        .build()

    /**
     * The server derives its entry id from a hash and the database only has a numeric remote id,
     * so use a stable 63-bit digest for the local tracker identity.
     */
    private fun remoteIdFor(entryId: String): Long {
        if (entryId.isBlank()) return 0L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(entryId.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xff)
        }
        return (value and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
    }

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val PLAN_TO_READ = 4L
        const val DROPPED = 5L

        private const val API_PATH = "/api/v1"
        private const val HTTP_CONFLICT = 409
        private const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
