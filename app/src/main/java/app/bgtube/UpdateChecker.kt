package app.bgtube

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** 실행 시 GitHub Releases 의 최신 릴리스를 확인하고, 새 버전이면 APK 다운로드/설치를 안내한다. */
object UpdateChecker {
    private const val TAG = "BgTubeUpdate"

    private class Release(val tag: String, val notes: String, val apkUrl: String, val apkName: String)

    private var checked = false
    private var pending: Release? = null // 설치 권한 설정을 다녀온 뒤 이어서 진행

    fun check(activity: AppCompatActivity) {
        if (checked) return
        checked = true
        thread(name = "update-check") {
            try {
                val r = fetchLatest() ?: return@thread
                if (isNewer(r.tag, BuildConfig.VERSION_NAME)) {
                    activity.runOnUiThread { if (!activity.isFinishing) prompt(activity, r) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: $e")
            }
        }
    }

    fun onResume(activity: AppCompatActivity) {
        val r = pending ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            pending = null
            download(activity, r)
        }
    }

    private fun fetchLatest(): Release? {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "BgTube/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(
                        j.getString("tag_name"), j.optString("body"),
                        a.getString("browser_download_url"), a.getString("name"),
                    )
                }
            }
        } finally {
            c.disconnect()
        }
        return null
    }

    internal fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = Regex("\\d+").findAll(v).map { it.value.toLong() }.toList()
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun prompt(activity: AppCompatActivity, r: Release) {
        val notes = r.notes.trim().take(600)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, r.tag))
            .setMessage(
                activity.getString(R.string.update_message, BuildConfig.VERSION_NAME) +
                    if (notes.isNotEmpty()) "\n\n$notes" else "",
            )
            .setPositiveButton(R.string.update_now) { _, _ -> start(activity, r) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun start(activity: AppCompatActivity, r: Release) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // '출처를 알 수 없는 앱 설치' 허용 화면으로 안내. 돌아오면 onResume 에서 이어서 다운로드.
            pending = r
            Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        download(activity, r)
    }

    private fun download(activity: AppCompatActivity, r: Release) {
        val app = activity.applicationContext
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(r.apkUrl))
            .setTitle(r.apkName)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, r.apkName)
        val id = dm.enqueue(req)
        Toast.makeText(app, R.string.update_downloading, Toast.LENGTH_LONG).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(app, R.string.update_failed, Toast.LENGTH_LONG).show()
                    return
                }
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
