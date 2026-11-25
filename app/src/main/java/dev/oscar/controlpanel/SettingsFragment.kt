package dev.oscar.controlpanel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app UI, built from res/xml/prefs.xml with the standard Preference APIs.
 * Keys are shared with [Prefs]: theme, lang, privilege, autoPrivilege; the switches are
 * not persisted — they always show the live system state.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var updater: Updater

    private lateinit var autoPref: SwitchPreferenceCompat
    private lateinit var privList: ListPreference
    private lateinit var usbPref: SwitchPreferenceCompat
    private lateinit var wifiPref: SwitchPreferenceCompat
    private lateinit var logPref: Preference
    private lateinit var checkPref: Preference
    private lateinit var installPref: Preference
    private lateinit var bannerPref: Preference

    private var update: UpdateInfo? = null
    private var updateError: String? = null
    private var checked = false
    private var checking = false
    private var progress = -1
    private var busyUsb = false
    private var busyWifi = false
    private var rooted = false
    private var usbLog = ""
    private var pollJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "controlpanel"
        setPreferencesFromResource(R.xml.prefs, rootKey)
        val ctx = requireContext()
        updater = Updater(ctx.applicationContext)

        bindPrivilege(ctx)
        bindToggles()
        bindUpdates()
        bindAppearance(ctx)
    }

    // ---------------- privilege ----------------

    private fun bindPrivilege(ctx: Context) {
        autoPref = findPreference("autoPrivilege")!!
        privList = findPreference("privilege")!!
        autoPref.isChecked = Prefs(ctx).autoPrivilege
        privList.value = Prefs(ctx).privilege ?: Privilege.NONE
        privList.isEnabled = !Prefs(ctx).autoPrivilege

        autoPref.setOnPreferenceChangeListener { _, v ->
            privList.isEnabled = !(v as Boolean)
            scope.launch {
                withContext(Dispatchers.IO) { Privilege.load(ctx) }
                refreshPrivilege()
                refreshState()
            }
            true
        }
        privList.setOnPreferenceChangeListener { _, v ->
            val mode = v as String
            Privilege.set(ctx, mode)
            if (mode == Privilege.SHIZUKU && !Privilege.requestShizuku()) {
                toast(getString(R.string.priv_shizuku_missing))
            }
            scope.launch {
                withContext(Dispatchers.IO) { Privilege.load(ctx) }
                refreshPrivilege()
                refreshState()
            }
            true
        }
        refreshPrivilege()
    }

    private fun refreshPrivilege() {
        val label = when {
            autoPref.isChecked -> when (Privilege.mode) {
                Privilege.ROOT -> R.string.priv_root
                Privilege.SHIZUKU -> R.string.priv_shizuku
                else -> R.string.priv_none
            }
            else -> when (privList.value) {
                Privilege.ROOT -> R.string.priv_root
                Privilege.SHIZUKU -> R.string.priv_shizuku
                else -> R.string.priv_none
            }
        }
        privList.summary = getString(R.string.priv_current, getString(label))
    }

    // ---------------- switches ----------------

    private fun bindToggles() {
        usbPref = findPreference("usb_tether")!!
        usbPref.isPersistent = false
        usbPref.setOnPreferenceChangeListener { _, _ -> toggleUsb(); false }

        wifiPref = findPreference("wifi_debug")!!
        wifiPref.isPersistent = false
        wifiPref.setOnPreferenceChangeListener { _, _ -> toggleWifi(); false }

        logPref = findPreference("usb_log")!!
        logPref.isVisible = false
        logPref.setOnPreferenceClickListener {
            if (usbLog.isNotEmpty() && isAdded) {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("usb tether log", usbLog))
                toast(getString(R.string.copy_done))
            }
            true
        }
    }

    /** Without privilege the switch just opens the system page, same as before. */
    private fun toggleUsb() {
        if (busyUsb) return
        val ctx = requireContext().applicationContext
        if (!rooted) {
            openSettings("android.settings.TETHER_SETTINGS")
            return
        }
        busyUsb = true
        val target = !usbPref.isChecked
        usbPref.isEnabled = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) { UsbTether.setOn(ctx, target) }
            // Diagnostics only matter when the switch failed.
            usbLog = if (ok) "" else UsbTether.lastLog
            usbPref.isChecked = withContext(Dispatchers.IO) { UsbTether.isOn(ctx) }
            usbPref.isEnabled = true
            busyUsb = false
            showUsbLog()
            if (!ok) toast(getString(R.string.usb_switch_failed))
            refreshState()
        }
    }

    private fun toggleWifi() {
        if (busyWifi) return
        val ctx = requireContext().applicationContext
        if (!rooted) {
            openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            return
        }
        busyWifi = true
        val target = !wifiPref.isChecked
        wifiPref.isEnabled = false
        scope.launch {
            withContext(Dispatchers.IO) { UsbTether.setWifiDebug(target) }
            delay(1000)
            wifiPref.isChecked = withContext(Dispatchers.IO) { UsbTether.isWifiDebugOn(ctx) }
            wifiPref.isEnabled = true
            busyWifi = false
            refreshState()
        }
    }

    private fun showUsbLog() {
        logPref.isVisible = usbLog.isNotEmpty()
        logPref.summary = usbLog.take(500)
    }

    // ---------------- updates ----------------

    private fun bindUpdates() {
        val version = findPreference<Preference>("version")!!
        version.title = getString(R.string.version_fmt, updater.currentName, updater.currentCode)
        version.isSelectable = false

        bannerPref = findPreference("banner")!!
        bannerPref.isSelectable = false
        bannerPref.isVisible = false

        checkPref = findPreference("check_updates")!!
        checkPref.setOnPreferenceClickListener { if (!checking && progress < 0) startCheck(); true }

        installPref = findPreference("install_update")!!
        installPref.isVisible = false
        installPref.setOnPreferenceClickListener { startInstall(); true }

        startCheck()
    }

    private fun startCheck() {
        if (checking) return
        checking = true
        updateError = null
        checkPref.summary = getString(R.string.checking)
        refreshUpdates()
        scope.launch {
            val hotfix = updater.hotfix()
            val banner = hotfix.banner(isZh())
            val text = when {
                hotfix.minVersionCode > updater.currentCode ->
                    listOfNotNull(banner, getString(R.string.hotfix_required)).joinToString("\n")
                else -> banner
            }
            bannerPref.summary = text
            bannerPref.isVisible = !text.isNullOrBlank()
            update = runCatching { updater.check() }.getOrNull()
            checked = true
            checking = false
            refreshUpdates()
        }
    }

    private fun startInstall() {
        val u = update ?: return
        if (progress >= 0) return
        progress = 0
        updateError = null
        refreshUpdates()
        scope.launch {
            runCatching {
                updater.install(u) { p -> scope.launch { progress = p; refreshUpdates() } }
            }.onFailure { updateError = it.message ?: "?" }
            progress = -1
            refreshUpdates()
        }
    }

    private fun refreshUpdates() {
        val u = update
        checkPref.summary = when {
            checking -> getString(R.string.checking)
            progress in 0..99 -> getString(R.string.downloading, progress)
            updateError != null -> getString(R.string.update_failed, updateError)
            u != null -> getString(R.string.update_available, u.versionName, u.versionCode)
            checked -> getString(R.string.up_to_date)
            else -> null
        }
        installPref.isVisible = u != null && updateError == null
        installPref.isEnabled = progress < 0 && !checking
        checkPref.isEnabled = !checking && progress < 0
    }

    // ---------------- appearance / language ----------------

    private fun bindAppearance(ctx: Context) {
        findPreference<ListPreference>("theme")!!.apply {
            value = Prefs(ctx).theme
            setOnPreferenceChangeListener { _, v ->
                Prefs(ctx).theme = v as String
                activity?.recreate()
                true
            }
        }
        findPreference<ListPreference>("lang")!!.apply {
            value = Prefs(ctx).lang
            setOnPreferenceChangeListener { _, v ->
                Prefs(ctx).lang = v as String
                activity?.recreate()
                true
            }
        }
    }

    // ---------------- live state ----------------

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pollJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /** Keeps the two switches in sync with the system (the tile, the system UI or adb can change them). */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val ctx = context?.applicationContext ?: return@launch
                rooted = withContext(Dispatchers.IO) {
                    Privilege.load(ctx)
                    Privilege.available()
                }
                val on = withContext(Dispatchers.IO) { UsbTether.isOn(ctx) }
                val wifi = withContext(Dispatchers.IO) { UsbTether.isWifiDebugOn(ctx) }
                if (!busyUsb) usbPref.isChecked = on
                if (!busyWifi) wifiPref.isChecked = wifi
                refreshPrivilege()
                refreshState()
                delay(3000)
            }
        }
    }

    private fun refreshState() {
        if (!isAdded) return
        usbPref.summary = getString(if (rooted) R.string.usb_root_yes else R.string.usb_root_no)
        wifiPref.summary = getString(if (rooted) R.string.wifi_root_yes else R.string.wifi_root_no)
    }

    // ---------------- helpers ----------------

    private fun isZh(): Boolean =
        resources.configuration.locales[0].language == "zh"

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { toast(getString(R.string.usb_open_settings)) }
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
