package com.kindlepost

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import java.io.File
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

private const val MAX_PHOTOS = 20
private const val MAX_TOTAL_ATTACHMENT_BYTES = 18L * 1024L * 1024L
private const val MAX_IMAGE_EDGE = 2400

class MainActivity : ComponentActivity() {
    private val prefs by lazy {
        val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(this, "mail_settings", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var sender by remember { mutableStateOf(prefs.getString("sender", "") ?: "") }
                var authCode by remember { mutableStateOf(prefs.getString("auth", "") ?: "") }
                var recipient by remember { mutableStateOf(prefs.getString("recipient", "") ?: "") }
                var photos by remember { mutableStateOf(listOf<Uri>()) }
                var status by remember { mutableStateOf("选择照片后发送到 Kindle") }
                var busy by remember { mutableStateOf(false) }
                var sizes by remember { mutableStateOf("") }
                var updateStatus by remember { mutableStateOf("") }
                var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
                var updateBusy by remember { mutableStateOf(false) }
                var downloadedUpdate by remember { mutableStateOf(File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "KindlePost-update.apk").exists()) }
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)
                ) { selected ->
                    photos = selected.take(MAX_PHOTOS)
                    status = if (photos.isEmpty()) "未选择照片" else "已选择 ${photos.size} 张照片"
                    sizes = ""
                }

                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("KindlePost", style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(enabled = !updateBusy, onClick = {
                            updateBusy = true
                            updateStatus = "正在检查更新…" 
                            lifecycleScope.launch {
                                try {
                                    val update = withContext(Dispatchers.IO) { UpdateManager.check(this@MainActivity) }
                                    availableUpdate = update
                                    updateStatus = if (update == null) "已是最新版本" else "发现新版本 ${update.versionName}"
                                } catch (e: Exception) { updateStatus = "检查失败：${e.message ?: "网络错误"}" }
                                finally { updateBusy = false }
                            }
                        }) { Text(if (updateBusy) "检查中…" else "检查更新") }
                        if (availableUpdate != null) Button(onClick = {
                            try {
                                UpdateManager.download(this@MainActivity, availableUpdate!!)
                                updateStatus = "已开始下载，下载完成后重新打开应用即可安装"
                                availableUpdate = null
                            } catch (e: Exception) { updateStatus = "下载失败：${e.message}" }
                        }) { Text("下载更新") }
                    }
                    if (updateStatus.isNotBlank()) Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                    if (downloadedUpdate) OutlinedButton(onClick = { try { UpdateManager.install(this@MainActivity) } catch (e: Exception) { updateStatus = "安装失败：${e.message}" } }, modifier = Modifier.fillMaxWidth()) { Text("安装已下载更新") }
                    OutlinedTextField(sender, { sender = it }, label = { Text("QQ 发件邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(authCode, { authCode = it }, label = { Text("QQ 邮箱授权码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(recipient, { recipient = it }, label = { Text("Kindle 接收邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (photos.isEmpty()) "选择照片" else "重新选择") }
                        if (photos.isNotEmpty()) {
                            OutlinedButton(onClick = { photos = emptyList(); sizes = ""; status = "已清空" }, enabled = !busy) { Text("清空") }
                        }
                    }
                    if (photos.isNotEmpty()) {
                        Text("已选 ${photos.size}/${MAX_PHOTOS} 张", style = MaterialTheme.typography.titleSmall)
                        LazyColumn(Modifier.weight(1f, fill = true)) {
                            itemsIndexed(photos) { index, uri ->
                                Text("${index + 1}. ${uri.lastPathSegment ?: "照片"}", maxLines = 1, modifier = Modifier.padding(vertical = 3.dp))
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (sizes.isNotBlank()) Text(sizes, style = MaterialTheme.typography.bodySmall)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !busy && photos.isNotEmpty() && sender.isNotBlank() && authCode.isNotBlank() && recipient.isNotBlank(),
                        onClick = {
                            prefs.edit().putString("sender", sender.trim()).putString("auth", authCode.trim())
                                .putString("recipient", recipient.trim()).apply()
                            busy = true
                            status = "正在压缩照片…"
                            lifecycleScope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        prepareAndSend(this@MainActivity, photos, sender.trim(), authCode.trim(), recipient.trim())
                                    }
                                    sizes = result
                                    status = "发送成功 · ${photos.size} 张"
                                    photos = emptyList()
                                } catch (e: Exception) {
                                    status = "发送失败：${e.message ?: "请检查邮箱设置"}"
                                } finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "发送中…" else "发送 ${photos.size} 张照片") }
                }
            }
        }
    }
}

private data class CompressedPhoto(val bytes: ByteArray, val name: String)

private fun prepareAndSend(context: Context, uris: List<Uri>, sender: String, auth: String, recipient: String): String {
    require(uris.size <= MAX_PHOTOS) { "一次最多发送 $MAX_PHOTOS 张照片" }
    val attachments = mutableListOf<CompressedPhoto>()
    var before = 0L
    var after = 0L

    uris.forEachIndexed { index, uri ->
        val originalSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (originalSize > 0) before += originalSize

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("无法读取第 ${index + 1} 张照片")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "第 ${index + 1} 张照片格式不支持" }

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("第 ${index + 1} 张照片解码失败")

        val oriented = applyExifOrientation(context, uri, bitmap)
        val output = ByteArrayOutputStream()
        var quality = 86
        do {
            output.reset()
            oriented.compress(Bitmap.CompressFormat.JPEG, quality, output)
            if (output.size() > 4 * 1024 * 1024 && quality > 72) quality -= 5 else break
        } while (true)
        val bytes = output.toByteArray()
        oriented.recycle()
        if (oriented !== bitmap) bitmap.recycle()

        require(after + bytes.size <= MAX_TOTAL_ATTACHMENT_BYTES) {
            "压缩后附件超过 18 MB，请减少照片数量后分批发送"
        }
        after += bytes.size
        attachments += CompressedPhoto(bytes, "KindlePhoto_${(index + 1).toString().padStart(2, '0')}.jpg")
    }

    val props = Properties().apply {
        put("mail.smtp.host", "smtp.qq.com")
        put("mail.smtp.port", "465")
        put("mail.smtp.auth", "true")
        put("mail.smtp.ssl.enable", "true")
        put("mail.smtp.connectiontimeout", "20000")
        put("mail.smtp.timeout", "30000")
        put("mail.smtp.writetimeout", "30000")
    }
    val session = Session.getInstance(props, object : javax.mail.Authenticator() {
        override fun getPasswordAuthentication() = javax.mail.PasswordAuthentication(sender, auth)
    })
    val message = MimeMessage(session).apply {
        setFrom(InternetAddress(sender))
        setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
        subject = "Kindle 照片（${attachments.size} 张）"
        val multipart: Multipart = MimeMultipart()
        multipart.addBodyPart(MimeBodyPart().apply { setText("已发送 ${attachments.size} 张照片。") })
        attachments.forEach { photo ->
            val part = MimeBodyPart()
            part.dataHandler = DataHandler(ByteArrayDataSource(photo.bytes, "image/jpeg"))
            part.fileName = photo.name
            multipart.addBodyPart(part)
        }
        setContent(multipart)
    }
    Transport.send(message)
    return "原图约 ${formatBytes(before)} → 压缩后 ${formatBytes(after)}"
}

private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

private fun applyExifOrientation(context: Context, uri: Uri, source: Bitmap): Bitmap {
    val orientation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(270f) }
    }
    return if (matrix.isIdentity) source else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun formatBytes(value: Long): String =
    if (value < 1024) "${value} B" else String.format("%.1f MB", value / 1024f / 1024f)
