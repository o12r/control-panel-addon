package dev.oscar.controlpanel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A downloadable build from the GitHub release (asset named like control-panel-addon-<versionCode>.apk). */
data class UpdateInfo(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String)

/** Remote config from hotfix.json on the main branch: banners and a minimum version. */
data class Hotfix(val bannerEn: String?, val bannerZh: String?, val minVersionCode: Int) {
    fun banner(zh: Boolean): String? =
        (if (zh) bannerZh ?: bannerEn else bannerEn ?: bannerZh)?.takeIf { it.isNotBlank() }

    companion object {
        val NONE = Hotfix(null, null, 0)
    }
}

/**
 * In-app updates and hotfix config, read from GitHub:
 *  - releases/latest of [REPO] for the APK (asset name ends in -<versionCode>.apk)
 *  - hotfix.json on the main branch of [REPO]
 */
class Updater(private val ctx: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)

    val currentName: String = info.versionName ?: "?"

    @Suppress("DEPRECATION")
    val currentCode: Int = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode

    private fun getJson(r: Request): JSONObject? = runCatching {
        http.newCall(r).execute().use { if (it.isSuccessful) JSONObject(it.body!!.string()) else null }
    }.getOrNull()

    /** The newer build if one exists, else null. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val o = getJson(
            Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json").build()
        ) ?: return@withContext null
        val assets = o.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val code = Regex("""-(\d+)\.apk$""").find(a.optString("name"))?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val u = UpdateInfo(
                code, o.optString("tag_name").removePrefix("v"), o.optString("body"),
                a.getString("browser_download_url"),
            )
            return@withContext if (u.versionCode > currentCode) u else null
        }
        null
    }

    suspend fun hotfix(): Hotfix = withContext(Dispatchers.IO) {
        val o = getJson(Request.Builder().url("https://raw.githubusercontent.com/$REPO/main/hotfix.json").build())
            ?: return@withContext Hotfix.NONE
        val b = o.optJSONObject("banner")
        fun s(k: String): String? =
            if (b == null || b.isNull(k)) null else b.optString(k).takeIf { it.isNotBlank() }
        Hotfix(s("en"), s("zh"), o.optInt("min_version_code", 0))
    }

    /** Downloads the APK, resuming with Range after dropped connections. */
    private suspend fun download(u: UpdateInfo, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "update-${u.versionCode}.apk")
        var attempt = 0
        while (true) {
            val have = file.length()
            val req = Request.Builder().url(u.apkUrl)
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            try {
                var finished = false
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 416) { finished = true; return@use }
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body!!
                    val append = resp.code == 206 && have > 0
                    if (!append) file.delete()
                    val start = if (append) have else 0L
                    val len = body.contentLength()
                    val total = if (len > 0) start + len else -1L
                    FileOutputStream(file, append).use { out ->
                        val src = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var done = start
                        var n: Int
                        while (src.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress((done * 100 / total).toInt())
                        }
                    }
                    finished = total < 0 || file.length() >= total
                }
                if (finished) return@withContext file
                if (++attempt > 5) throw IOException("Download incomplete")
            } catch (e: IOException) {
                if (++attempt > 5) throw e
                delay(minOf(20_000L, 1500L shl (attempt - 1)))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        file
    }

    /** Downloads the APK and hands it to the system installer. The user confirms there. */
    suspend fun install(u: UpdateInfo, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val file = download(u, progress)
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file.inputStream().use { input ->
                s.openWrite("base.apk", 0, file.length()).use { input.copyTo(it); s.fsync(it) }
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
        file.delete()
    }

    companion object {
        const val REPO = "o12r/control-panel-addon"
    }
}

/** Receives the installer result and opens the confirm screen when the system asks for it. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = (if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> android.widget.Toast.makeText(
                context,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
