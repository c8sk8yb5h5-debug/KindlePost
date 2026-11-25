package com.kindlepost

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val versionName: String, val versionCode: Int, val downloadUrl: String)

object UpdateManager {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/c8sk8yb5h5-debug/KindlePost/main/version.json"
    private const val PACKAGE_NAME = "com.kindlepost"

    fun check(context: Context): AppUpdate? {
        val connection = (URL("$MANIFEST_URL?cacheBust=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
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

    fun download(context: Context, update: AppUpdate) {
        val target = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "KindlePost-update.apk")
        target.parentFile?.mkdirs()
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "KindlePost-Android")
            setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("下载失败（HTTP $code）")
            val contentType = connection.contentType.orEmpty()
            if (contentType.contains("text/html", ignoreCase = true)) error("服务器返回网页而不是 APK，请稍后重试")
            val temp = File(target.parentFile, "KindlePost-update.apk.part")
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (!temp.exists() || temp.length() < 100_000L) {
                temp.delete()
                error("更新文件不完整")
            }
            val signature = temp.inputStream().use { input -> ByteArray(4).also { if (input.read(it) != 4) error("更新文件无效") } }
            if (signature[0] != 0x50.toByte() || signature[1] != 0x4B.toByte()) {
                temp.delete()
                error("下载内容不是有效 APK（文件格式错误）")
            }
            val info = context.packageManager.getPackageArchiveInfo(temp.absolutePath, PackageManager.GET_META_DATA)
            if (info == null || info.packageName != PACKAGE_NAME || info.longVersionCode.toInt() != update.versionCode) {
                temp.delete()
                error("APK 校验失败，版本或应用包名不匹配")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) error("无法保存更新文件")
        } finally {
            connection.disconnect()
        }

    }

    fun install(context: Context) {
        val target = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "KindlePost-update.apk")
        check(target.exists() && target.length() > 0) { "更新文件不存在" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        check(intent.resolveActivity(context.packageManager) != null) { "系统未找到安装程序" }
        context.startActivity(intent)
    }
}
