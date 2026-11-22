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
    fun setOn(ctx: Context, enable: Boolean): Boolean {
        val log = StringBuilder()
        val (_, cur) = su("getprop sys.usb.config")
        log.append("sys.usb.config=").append(cur).append('\n')
        val others = cur.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "rndis" && it != "none" }
        val list = if (enable) listOf("rndis") + others else others
        val arg = if (list.isEmpty()) "none" else list.joinToString(",")

        // Only setting the USB function to rndis exposes a bare adapter with no DHCP/NAT, so the
        // PC shows "Unidentified network". The real switch is the tethering service, reached via
        // TetherCli run in app_process as root/shell. The function-only commands are a last resort.
        val apk = ctx.applicationInfo.sourceDir
        val cli = "CLASSPATH=$apk app_process /system/bin dev.oscar.controlpanel.TetherCli " +
            if (enable) "start" else "stop"
        val methods = listOf(cli, "svc usb setFunctions $arg")
        var ok = false
        for (cmd in methods) {
            val (code, out) = Privilege.exec(cmd, 20)
            log.append("\$ ").append(cmd).append("\n  exit=").append(code)
            if (out.isNotEmpty()) log.append(" out=").append(out.take(200))
            log.append('\n')
            ok = waitForState(ctx, enable)
            log.append("  -> ").append(if (ok) "state changed" else "state unchanged").append('\n')
            if (ok) break
        }
        log.append("sys.usb.state=").append(su("getprop sys.usb.state").second).append('\n')
        log.append("ifaces=").append(
            try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .joinToString(",") { it.name + if (it.isUp) "(up)" else "" }
            } catch (e: Exception) {
                "?"
            }
        )
        lastLog = log.toString()
        return ok
    }

    /** Output of the last [setOn] attempt: commands, exit codes and verification result. */
    @Volatile
    var lastLog: String = ""

    /** Polls up to 3 s for the tether interface to reach the wanted state. */
    private fun waitForState(ctx: Context, enable: Boolean): Boolean {
        repeat(6) {
            Thread.sleep(500)
            if (isOn(ctx) == enable) return true
        }
        return false
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
