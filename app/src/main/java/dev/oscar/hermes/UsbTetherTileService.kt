package dev.oscar.hermes

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.Executors

/**
 * Quick Settings tile (pull-down panel) for USB tethering.
 * Root: toggles USB tethering. No root: opens the system tethering page.
 */
class UsbTetherTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        io.execute {
            if (UsbTether.hasRoot()) {
                UsbTether.setOn(!UsbTether.isOn(this))
                // Tethering takes a moment to come up or go down.
                main.postDelayed({ refresh() }, 2000)
            } else {
                main.post { openSystemTethering() }
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** Reads root + tether state off the main thread, then updates the tile on it. */
    private fun refresh() {
        io.execute {
            val rooted = UsbTether.hasRoot()
            val on = UsbTether.isOn(this)
            main.post { pushState(on, rooted) }
        }
    }

    private fun pushState(on: Boolean, rooted: Boolean) {
        val t = qsTile ?: return
        val ctx = L10n.ctx(this)
        t.label = ctx.getString(R.string.tile_usb_tether)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = ctx.getString(
            when {
                on -> R.string.tile_on
                rooted -> R.string.tile_off
                else -> R.string.tile_settings_mode
            }
        )
        t.updateTile()
    }

    private fun openSystemTethering() {
        val intent = Intent("android.settings.TETHER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
