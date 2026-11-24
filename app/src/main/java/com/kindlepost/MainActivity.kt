package com.kindlepost

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

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
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(20)
                ) { photos = it; status = if (it.isEmpty()) "未选择照片" else "已选择 ${it.size} 张照片" }

                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("KindlePost", style = MaterialTheme.typography.headlineMedium)
                    Text("照片邮差", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(sender, { sender = it }, label = { Text("QQ 发件邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(authCode, { authCode = it }, label = { Text("QQ 邮箱授权码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(recipient, { recipient = it }, label = { Text("Kindle 接收邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        prefs.edit().putString("sender", sender.trim()).putString("auth", authCode.trim())
                            .putString("recipient", recipient.trim()).apply()
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (photos.isEmpty()) "选择照片" else "重新选择照片") }
                    if (photos.isNotEmpty()) {
                        Text("已选 ${photos.size} 张照片")
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(photos) { Text(it.lastPathSegment ?: "照片", maxLines = 1) }
                        }
                    }
                    if (sizes.isNotBlank()) Text(sizes, style = MaterialTheme.typography.bodySmall)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !busy && photos.isNotEmpty() && sender.isNotBlank() && authCode.isNotBlank() && recipient.isNotBlank(),
                        onClick = {
                            prefs.edit().putString("sender", sender.trim()).putString("auth", authCode.trim())
                                .putString("recipient", recipient.trim()).apply()
                            busy = true; status = "正在压缩并发送…"
                            lifecycleScope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { sendPhotos(this@MainActivity, photos, sender.trim(), authCode.trim(), recipient.trim()) }
                                    sizes = result.first
                                    status = "发送成功"
                                } catch (e: Exception) {
                                    status = "发送失败：${e.message ?: "请检查邮箱设置"}"
                                } finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "发送中…" else "发送到 Kindle") }
                }
            }
        }
    }
}

private fun sendPhotos(context: Context, uris: List<Uri>, sender: String, auth: String, recipient: String): Pair<String, Unit> {
    val attachments = mutableListOf<Pair<ByteArray, String>>()
    var before = 0L
    var after = 0L
    uris.forEachIndexed { index, uri ->
        val original = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取照片")
        before += original.size
        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size) ?: error("照片格式不支持")
        val scaled = if (bitmap.width > 2400 || bitmap.height > 2400) {
            val ratio = minOf(2400f / bitmap.width, 2400f / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else bitmap
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 86, output)
        val bytes = output.toByteArray()
        after += bytes.size
        attachments += bytes to "KindlePhoto_${index + 1}.jpg"
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
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
        subject = "Kindle 照片"
        val multipart: Multipart = MimeMultipart()
        val intro = MimeBodyPart().apply { setText("已发送 ${attachments.size} 张照片。") }
        multipart.addBodyPart(intro)
        attachments.forEach { (bytes, name) ->
            val part = MimeBodyPart()
            part.setContent(bytes, "image/jpeg")
            part.fileName = name
            multipart.addBodyPart(part)
        }
        setContent(multipart)
    }
    Transport.send(message)
    return "原图 ${formatBytes(before)} → 压缩后 ${formatBytes(after)}" to Unit
}

private fun formatBytes(value: Long): String =
    if (value < 1024) "${value} B" else String.format("%.1f MB", value / 1024f / 1024f)
