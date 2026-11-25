package dev.oscar.controlpanel

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * The app's only screen: a standard Android Preference page (see [SettingsFragment]).
 *
 * Opened from the launcher icon, from an Xposed manager's module page (Vector / LSPosed read
 * `de.robv.android.xposed.category.MODULE_SETTINGS`), from the system's app-info "Settings" row,
 * and as the fallback of the tiles' long-press.
 */
class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase, Prefs(newBase).lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(themeRes())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
            if (Prefs(this).privilege == null) askFirstRun()
        }
    }

    private fun themeRes(): Int = when (Prefs(this).theme) {
        "light" -> R.style.Theme_Panel_Light
        "dark" -> R.style.Theme_Panel_Dark
        "black" -> R.style.Theme_Panel_Black
        else -> if (nightMode()) R.style.Theme_Panel_Dark else R.style.Theme_Panel_Light
    }

    private fun nightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /** First-run choice: root, Shizuku, or later (no privilege). Shown until a choice is saved. */
    private fun askFirstRun() {
        AlertDialog.Builder(this)
            .setTitle(R.string.first_run_title)
            .setMessage(R.string.first_run_text)
            .setPositiveButton(R.string.priv_root) { _, _ -> choose(Privilege.ROOT) }
            .setNeutralButton(R.string.priv_shizuku) { _, _ -> choose(Privilege.SHIZUKU) }
            .setNegativeButton(R.string.first_run_later) { _, _ -> choose(Privilege.NONE) }
            .setCancelable(false)
            .show()
    }

    private fun choose(mode: String) {
        if (mode == Privilege.SHIZUKU) Privilege.requestShizuku()
        Privilege.set(this, mode)
        recreate()
    }
}
