package dev.oscar.controlpanel

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.Executors

/**
 * Quick Settings tile for wireless debugging.
 * Root: toggles it. No root: opens the developer options page.
 */
class WifiDebugTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        io.execute {
            Privilege.load(this)
            if (UsbTether.hasRoot()) {
                UsbTether.setWifiDebug(!UsbTether.isWifiDebugOn(this))
                main.postDelayed({ refresh() }, 1000)
            } else {
                main.post { openDeveloperSettings() }
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        io.execute {
            Privilege.load(this)
            val rooted = UsbTether.hasRoot()
            val on = UsbTether.isWifiDebugOn(this)
            main.post { pushState(on, rooted) }
        }
    }

    private fun pushState(on: Boolean, rooted: Boolean) {
        val t = qsTile ?: return
        val ctx = L10n.wrap(this, Prefs(this).lang)
        t.label = ctx.getString(R.string.tile_wifi_debug)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = ctx.getString(
            when {
                on -> R.string.tile_on
                rooted -> R.string.tile_off
                else -> R.string.tile_dev_mode
            }
        )
        t.updateTile()
    }

    private fun openDeveloperSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
