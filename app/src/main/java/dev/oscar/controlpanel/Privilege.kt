package dev.oscar.controlpanel

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Which privilege runs the shell commands: root (su), Shizuku, or none.
 * Without a privilege the app only reads state and opens the system settings pages.
 */
object Privilege {
    const val ROOT = "root"
    const val SHIZUKU = "shizuku"
    const val NONE = "none"
    private const val REQ_CODE = 1001

    /** Current mode. Loaded from prefs with [load]. */
    @Volatile
    var mode: String = NONE

    /** Loads the mode: auto-probed (root, then Shizuku) when auto-detect is on, else the saved choice. */
    fun load(ctx: Context) {
        val prefs = Prefs(ctx)
        mode = if (prefs.autoPrivilege) detect() else prefs.privilege ?: NONE
    }

    /** Probes for the highest usable privilege right now: root, then Shizuku, else none. Blocks; call off the main thread. */
    fun detect(): String = when {
        rootExec("id").let { it.first == 0 && it.second.contains("uid=0") } -> ROOT
        shizukuReady() -> SHIZUKU
        else -> NONE
    }

    fun set(ctx: Context, m: String) {
        Prefs(ctx).privilege = m
        mode = m
    }

    /** True when the current mode can run privileged commands now. Blocks; call off the main thread. */
    fun available(): Boolean = when (mode) {
        ROOT -> rootExec("id").let { it.first == 0 && it.second.contains("uid=0") }
        SHIZUKU -> shizukuReady()
        else -> false
    }

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** Asks Shizuku to grant this app permission. Returns false if Shizuku isn't running. */
    fun requestShizuku(): Boolean = try {
        if (!Shizuku.pingBinder()) false else {
            Shizuku.requestPermission(REQ_CODE)
            true
        }
    } catch (e: Throwable) {
        false
    }

    /** Runs [cmd] with the current privilege, 5 s limit. Returns (exit code, output); -1 on failure. */
    fun exec(cmd: String, timeoutSec: Long = 5): Pair<Int, String> = when (mode) {
        ROOT -> rootExec(cmd, timeoutSec)
        SHIZUKU -> shizukuExec(cmd, timeoutSec)
        else -> -1 to "no privilege"
    }

    private fun rootExec(cmd: String, timeoutSec: Long = 5): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        // Wait before reading: a Magisk/KernelSU prompt can block, and readText() would hang with it.
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            -1 to "timeout"
        } else {
            p.exitValue() to p.inputStream.bufferedReader().readText().trim()
        }
    } catch (e: Exception) {
        -1 to (e.message ?: "su failed")
    }

    /**
     * Runs through Shizuku's remote process. Shizuku marks newProcess as non-public in some
     * releases, so it is called by reflection.
     */
    private fun shizukuExec(cmd: String, timeoutSec: Long): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            -1 to "timeout"
        } else {
            p.exitValue() to p.inputStream.bufferedReader().readText().trim()
        }
    } catch (e: Throwable) {
        -1 to (e.message ?: "shizuku failed")
    }
}
