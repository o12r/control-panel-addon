package dev.oscar.controlpanel

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Settings pages the Quick Settings tiles link to on long press.
 *
 * Each entry is an ordered candidate list: the first one that resolves is used, so
 * OEM Settings that lack a deep link degrade to the closest page instead of failing.
 */
object SettingsLinks {

    /** AOSP fragment behind Developer options → "Wireless debugging". */
    private const val WIRELESS_DEBUGGING_FRAGMENT =
        "com.android.settings.development.WirelessDebuggingFragment"

    /** Preference key of the "Wireless debugging" row on the Developer options page. */
    private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"

    /**
     * Developer options → Wireless debugging, the toggle page itself.
     * Settings' SubSettings is not exported, so a plain app cannot start this directly:
     * [TileSettingsActivity] retries it through root/Shizuku and falls back to [developerOptions].
     */
    fun wirelessDebuggingFragment(): Intent = Intent()
        .setClassName("com.android.settings", "com.android.settings.SubSettings")
        .putExtra(":settings:show_fragment", WIRELESS_DEBUGGING_FRAGMENT)

    /** The same page as an `am` command line, for a root/Shizuku shell. */
    fun wirelessDebuggingCommand(): String =
        "am start -n com.android.settings/.SubSettings" +
            " --es ':settings:show_fragment' $WIRELESS_DEBUGGING_FRAGMENT"

    /** Developer options, scrolled to the wireless debugging row where the OEM honours the hint. */
    fun developerOptions(): List<Intent> = listOf(
        Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", WIRELESS_DEBUGGING_KEY),
        Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )

    /** Settings → Tethering & portable hotspot. */
    fun tethering(): List<Intent> = listOf(
        Intent("android.settings.TETHER_SETTINGS"),
        Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
}

/**
 * Opens the first resolvable intent in [candidates] from a tile, collapsing the panel first.
 * Must be called on the main thread.
 */
fun TileService.openFirstSettings(candidates: List<Intent>, requestCode: Int): Boolean {
    val intent = candidates
        .firstOrNull { it.resolveActivity(packageManager) != null }
        ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
        val pi = PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pi)
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
    return true
}
