package dev.oscar.controlpanel

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** User settings: theme ("system", "light", "dark", "black") and language ("system", "en", "zh"). */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("controlpanel", Context.MODE_PRIVATE)

    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) = p.edit().putString("theme", v).apply()

    /** "root", "shizuku" or "none"; null until the first-run choice is made. */
    var privilege: String?
        get() = p.getString("privilege", null)
        set(v) = p.edit().putString("privilege", v).apply()

    var lang: String
        get() = p.getString("lang", "system") ?: "system"
        set(v) = p.edit().putString("lang", v).apply()
}

/** In-app language override. Wraps a context so its resources use the chosen language. */
object L10n {
    fun wrap(base: Context, lang: String): Context {
        val loc = when (lang) {
            "en" -> Locale.ENGLISH
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> return base
        }
        val cfg = Configuration(base.resources.configuration).apply { setLocale(loc) }
        return base.createConfigurationContext(cfg)
    }
}
