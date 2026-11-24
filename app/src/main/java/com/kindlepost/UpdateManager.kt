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

data class AppUpdate(val versionName: String, val versionCode: Int, val downloadUrl: String)

object UpdateManager {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/c8sk8yb5h5-debug/KindlePost/main/version.json"

    fun check(context: Context): AppUpdate? {
        val connection = (URL("$MANIFEST_URL?cacheBust=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "KindlePost-Android")
        }
        try {
            if (connection.responseCode !in 200..299) error("更新服务器暂不可用（HTTP ${connection.responseCode}）")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val versionCode = json.getInt("versionCode")
            val versionName = json.getString("versionName")
            val downloadUrl = json.getString("downloadUrl")
            require(downloadUrl.startsWith("https://github.com/c8sk8yb5h5-debug/KindlePost/releases/download/")) { "更新地址无效" }
            val current = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            return if (versionCode > current) AppUpdate(versionName, versionCode, downloadUrl) else null
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, update: AppUpdate): Long {
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("KindlePost ${update.versionName}")
            .setDescription("正在下载更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "KindlePost-update.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    fun install(context: Context) {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "KindlePost-update.apk")
        check(file.exists() && file.length() > 0) { "更新文件不存在，请重新下载" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
