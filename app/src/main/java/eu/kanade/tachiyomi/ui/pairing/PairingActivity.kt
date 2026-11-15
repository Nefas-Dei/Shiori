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
        val credentials = ShioriPairing.parse(intent?.data?.toString())
        if (credentials == null) {
            toast("Pairing failed: invalid QR code")
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                trackerManager.custom.login(credentials.baseUrl, credentials.token)
                toast("Shiori Sync connected to ${Uri.parse(credentials.baseUrl).host ?: credentials.baseUrl}")
                logcat(LogPriority.INFO) { "Shiori Sync paired via QR code" }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Shiori Sync pairing failed" }
                toast("Pairing failed: ${e.message ?: "connection error"}")
            } finally {
                finish()
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
