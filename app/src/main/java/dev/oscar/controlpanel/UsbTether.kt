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

    /** Runs [cmd] as root with a 5 s limit. Returns (exit code, output); exit -1 on failure or timeout. */
    fun su(cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        // Wait before reading: a Magisk/KernelSU prompt can block, and readText() would hang with it.
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            -1 to "timeout"
        } else {
            p.exitValue() to p.inputStream.bufferedReader().readText().trim()
        }
    } catch (e: Exception) {
        -1 to (e.message ?: "su failed")
    }

    /** True when `su` grants root (uid=0). Blocks up to 5 s, so call it off the main thread. */
    fun hasRoot(): Boolean {
        val (code, out) = su("id")
        return code == 0 && out.contains("uid=0")
    }

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
