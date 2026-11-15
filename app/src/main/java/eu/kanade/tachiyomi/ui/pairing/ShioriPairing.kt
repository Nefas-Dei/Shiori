package eu.kanade.tachiyomi.ui.pairing

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Helpers for the `shiori://pair?host=<baseUrl>&token=<token>` pairing URIs that
 * the browser extension shows as QR codes. Both the in-app scanner result and the
 * deep link entry point use the same parsing.
 */
object ShioriPairing {

    data class Credentials(val baseUrl: String, val token: String)

    /**
     * Parses pairing content (QR payload or deep link URI). Returns null for
     * anything that is not a well-formed Shiori pairing URI.
     */
    fun parse(content: String?): Credentials? {
        if (content.isNullOrBlank()) return null
        val uri = Uri.parse(content.trim())
        if (uri.scheme != "shiori" || uri.host != "pair") return null
        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        if (token.isBlank()) return null
        val baseUrl = normalizeBaseUrl(host) ?: return null
        return Credentials(baseUrl, token)
    }

    /**
     * Normalizes a user/QRI-supplied server base URL: adds a scheme, strips
     * `/api/v1` and trailing slashes. Returns null when the URL is not usable.
     */
    fun normalizeBaseUrl(raw: String): String? {
        var value = raw.trim().trimEnd('/')
        if (value.isBlank()) return null
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://$value"
        }
        val parsed = value.toHttpUrlOrNull() ?: return null
        val path = parsed.encodedPath.trimEnd('/')
        val cleanedPath = if (path.endsWith("/api/v1")) path.removeSuffix("/api/v1") else path
        return parsed.newBuilder()
            .encodedPath(cleanedPath.ifEmpty { "/" })
            .build()
            .toString()
            .trimEnd('/')
    }
}
