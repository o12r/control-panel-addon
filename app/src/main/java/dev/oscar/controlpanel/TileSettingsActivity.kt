package dev.oscar.controlpanel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.concurrent.Executors

/**
 * Target of a long press on one of our Quick Settings tiles.
 *
 * SystemUI starts the activity with `ACTION_QS_TILE_PREFERENCES` and the tile's
 * ComponentName in [Intent.EXTRA_COMPONENT_NAME]; we immediately forward to the
 * matching system Settings page and close ourselves, so no UI is ever shown here.
 */
class TileSettingsActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (tileComponent()?.className) {
            WifiDebugTileService::class.java.name -> openWirelessDebugging()
            null -> openApp()
            else -> if (!openFirst(SettingsLinks.tethering())) openApp()
        }
        finish()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** Tries the real toggle page, then a privileged `am`, then whatever Developer options exist. */
    private fun openWirelessDebugging() {
        if (open(SettingsLinks.wirelessDebuggingFragment())) return
        io.execute {
            Privilege.load(this)
            val started = Privilege.available() &&
                Privilege.exec(SettingsLinks.wirelessDebuggingCommand(), 10).let {
                    it.first == 0 && !it.second.contains("Error")
                }
            if (!started) {
                runOnUiThread {
                    if (!openFirst(SettingsLinks.developerOptions())) openApp()
                    finish()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun tileComponent(): ComponentName? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
        }

    /**
     * Starts [target] if it resolves. Returns false when the page is missing or not exported,
     * so the caller can fall back to another page.
     */
    private fun open(target: Intent): Boolean {
        if (target.resolveActivity(packageManager) == null) return false
        return try {
            startActivity(Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun openFirst(candidates: List<Intent>): Boolean =
        candidates.any { open(it) }

    /** Last resort: the app's own screen, so the press is never a no-op. */
    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
