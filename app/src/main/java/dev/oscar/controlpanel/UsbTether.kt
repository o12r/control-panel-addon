package dev.oscar.controlpanel

import android.content.Context
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * USB tethering helpers.
 *
 * Normal apps cannot start or stop USB tethering (it needs TETHER_PRIVILEGED).
 * With root we toggle it through the USB function list: `svc usb setFunctions rndis,...`.
 * Without root the app only reads the state and opens the system tethering page.
 */
object UsbTether {

    /**
     * True when a USB tether interface (rndis* / usb*) is up.
     * Uses the public NetworkInterface list (ConnectivityManager.getTetheredIfaces is hidden API).
     */
    fun isOn(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any {
            (it.name.startsWith("rndis") || it.name.startsWith("usb")) && it.isUp
        }
    } catch (e: Exception) {
        false
    }

    /** Runs [cmd] with the selected privilege (root or Shizuku). */
    fun su(cmd: String): Pair<Int, String> = Privilege.exec(cmd)

    /** True when the selected privilege (root or Shizuku) is usable. Call off the main thread. */
    fun hasRoot(): Boolean = Privilege.available()

    /**
     * Turns USB tethering on or off by editing the USB function list.
     * Keeps the other functions (for example adb or mtp). Call off the main thread.
     */
    fun setOn(enable: Boolean): Boolean {
        val (_, cur) = su("getprop sys.usb.config")
        val others = cur.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "rndis" && it != "none" }
        val list = if (enable) listOf("rndis") + others else others
        val arg = if (list.isEmpty()) "none" else list.joinToString(",")
        return su("svc usb setFunctions $arg").first == 0
    }

    /** True when wireless debugging is on (readable by any app). */
    fun isWifiDebugOn(ctx: Context): Boolean = try {
        android.provider.Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
    } catch (e: Exception) {
        false
    }

    /** Turns wireless debugging on or off as root. Call off the main thread. */
    fun setWifiDebug(enable: Boolean): Boolean =
        su("settings put global adb_wifi_enabled ${if (enable) 1 else 0}").first == 0
}
