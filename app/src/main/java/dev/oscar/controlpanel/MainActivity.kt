package dev.oscar.controlpanel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase, Prefs(newBase).lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var theme by remember { mutableStateOf(prefs.theme) }
    val dark = when (theme) {
        "light" -> false
        "dark", "black" -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = when {
        dark && theme == "black" -> trueBlack(darkColorScheme())
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Panel(
                theme = theme,
                onTheme = { theme = it; prefs.theme = it },
                lang = prefs.lang,
                onLang = { prefs.lang = it; (ctx as? Activity)?.recreate() },
            )
        }
    }
}

/** True black (AMOLED): pure black backgrounds, dark grey containers. */
fun trueBlack(d: ColorScheme) = d.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1A1A1A),
)

@Composable
fun Panel(theme: String, onTheme: (String) -> Unit, lang: String, onLang: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val zh = ctx.resources.configuration.locales[0].language == "zh"
    val updater = remember { Updater(ctx) }

    // USB tethering state
    var rooted by remember { mutableStateOf(false) }
    var usbOn by remember { mutableStateOf(false) }
    var usbBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        rooted = withContext(Dispatchers.IO) { UsbTether.hasRoot() }
        while (true) {
            usbOn = withContext(Dispatchers.IO) { UsbTether.isOn(ctx) }
            delay(3000)
        }
    }

    // Updates and hotfix
    var hotfix by remember { mutableStateOf(Hotfix.NONE) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1) }
    var updateError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        hotfix = updater.hotfix()
        update = runCatching { updater.check() }.getOrNull()
        checked = true
    }

    val banner = hotfix.banner(zh)
    val required = hotfix.minVersionCode > updater.currentCode

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        banner?.let { Section { Text(it) } }
        if (required) Section { Text(stringResource(R.string.hotfix_required)) }

        Section(title = stringResource(R.string.usb_title)) {
            Text(stringResource(if (usbOn) R.string.tile_on else R.string.tile_off))
            Text(
                stringResource(if (rooted) R.string.usb_root_yes else R.string.usb_root_no),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = !usbBusy,
                onClick = {
                    if (rooted) {
                        usbBusy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { UsbTether.setOn(!usbOn) }
                            delay(2000)
                            usbOn = withContext(Dispatchers.IO) { UsbTether.isOn(ctx) }
                            usbBusy = false
                        }
                    } else {
                        ctx.startActivity(
                            Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            ) {
                Text(
                    stringResource(
                        when {
                            !rooted -> R.string.usb_open_settings
                            usbOn -> R.string.usb_turn_off
                            else -> R.string.usb_turn_on
                        }
                    )
                )
            }
        }

        WifiDebugSection(rooted = rooted)

        Section(title = stringResource(R.string.updates)) {
            Text(stringResource(R.string.version_fmt, updater.currentName, updater.currentCode))
            if (checked && update == null && updateError == null) {
                Text(stringResource(R.string.up_to_date))
            }
            update?.let { Text(stringResource(R.string.update_available, it.versionName, it.versionCode)) }
            if (progress in 0..99) Text(stringResource(R.string.downloading, progress))
            updateError?.let { Text(stringResource(R.string.update_failed, it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !checking && progress < 0,
                    onClick = {
                        checking = true
                        updateError = null
                        scope.launch {
                            update = runCatching { updater.check() }.getOrNull()
                            checked = true
                            checking = false
                        }
                    },
                ) { Text(stringResource(if (checking) R.string.checking else R.string.check_updates)) }
                if (update != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = progress < 0,
                        onClick = {
                            val u = update ?: return@Button
                            progress = 0
                            updateError = null
                            scope.launch {
                                runCatching {
                                    updater.install(u) { p -> scope.launch { progress = p } }
                                }.onFailure { updateError = it.message ?: "?" }
                                progress = -1
                            }
                        },
                    ) { Text(stringResource(R.string.install)) }
                }
            }
        }

        Section(title = stringResource(R.string.appearance)) {
            listOf(
                "system" to R.string.theme_system,
                "light" to R.string.theme_light,
                "dark" to R.string.theme_dark,
                "black" to R.string.theme_black,
            ).forEach { (key, label) ->
                Choice(stringResource(label), selected = theme == key) { onTheme(key) }
            }
        }

        Section(title = stringResource(R.string.language)) {
            listOf(
                "system" to R.string.lang_system,
                "en" to R.string.lang_en,
                "zh" to R.string.lang_zh,
            ).forEach { (key, label) ->
                Choice(stringResource(label), selected = lang == key) { onLang(key) }
            }
        }
    }
}

@Composable
fun WifiDebugSection(rooted: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            on = withContext(Dispatchers.IO) { UsbTether.isWifiDebugOn(ctx) }
            delay(3000)
        }
    }
    Section(title = stringResource(R.string.wifi_title)) {
        Text(stringResource(if (on) R.string.tile_on else R.string.tile_off))
        Text(
            stringResource(if (rooted) R.string.wifi_root_yes else R.string.wifi_root_no),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = !busy,
            onClick = {
                if (rooted) {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { UsbTether.setWifiDebug(!on) }
                        delay(1000)
                        on = withContext(Dispatchers.IO) { UsbTether.isWifiDebugOn(ctx) }
                        busy = false
                    }
                } else {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        ) {
            Text(
                stringResource(
                    when {
                        !rooted -> R.string.wifi_open_dev
                        on -> R.string.wifi_turn_off
                        else -> R.string.wifi_turn_on
                    }
                )
            )
        }
    }
}

@Composable
fun Section(title: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
