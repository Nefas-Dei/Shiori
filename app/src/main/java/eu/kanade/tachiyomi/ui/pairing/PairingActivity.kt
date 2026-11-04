package eu.kanade.tachiyomi.ui.pairing

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

/**
 * Consumes `shiori://pair?host=<baseUrl>&token=<token>` links encoded in the pairing QR codes
 * shown by the browser extension, then logs the Shiori Sync tracker in with those credentials.
 */
class PairingActivity : AppCompatActivity() {

    private val trackerManager: TrackerManager by injectLazy()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        val host = data?.getQueryParameter("host")?.trim().orEmpty()
        val token = data?.getQueryParameter("token")?.trim().orEmpty()

        val baseUrl = normalizeBaseUrl(host)
        if (baseUrl == null || token.isBlank()) {
            toast("Pairing failed: invalid QR code")
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                trackerManager.custom.login(baseUrl, token)
                toast("Shiori Sync connected to ${Uri.parse(baseUrl).host ?: baseUrl}")
                logcat(LogPriority.INFO) { "Shiori Sync paired via QR code" }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Shiori Sync pairing failed" }
                toast("Pairing failed: ${e.message ?: "connection error"}")
            } finally {
                finish()
            }
        }
    }

    private fun normalizeBaseUrl(raw: String): String? {
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
