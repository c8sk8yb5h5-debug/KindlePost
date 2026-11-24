package com.kindlepost

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val versionName: String, val versionCode: Int, val downloadUrl: String, val notes: String)

object UpdateManager {
    private const val RELEASE_API = "https://api.github.com/repos/c8sk8yb5h5-debug/KindlePost/releases/tags/continuous"

    fun check(context: Context): AppUpdate? {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "KindlePost-Android")
        }
        try {
            if (connection.responseCode !in 200..299) error("更新服务器暂不可用（HTTP ${connection.responseCode}）")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val apk = release.getJSONArray("assets").let { assets ->
                (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == "KindlePost.apk" }
            } ?: error("更新包尚未发布")
            val versionCode = release.optJSONObject("body")?.optInt("versionCode", -1) ?: -1
            val versionName = release.optString("name", release.optString("tag_name", "新版本"))
            val body = release.optString("body", "")
            val assetUrl = apk.optString("browser_download_url")
            val assetSize = apk.optLong("size", 0L)
            if (!assetUrl.startsWith("https://github.com/") || assetSize <= 0) error("更新包信息无效")
            val parsedCode = Regex("""versionCode\\s*[:=]\\s*(\\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: versionCode
            if (parsedCode <= currentVersionCode(context)) return null
            return AppUpdate(versionName, parsedCode, assetUrl, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun currentVersionCode(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    fun downloadAndInstall(context: Context, update: AppUpdate) {
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("KindlePost 更新")
            .setDescription("正在下载 ${update.versionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "KindlePost-update.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().putLong("download_id", id).apply()
    }

    fun installDownloaded(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
