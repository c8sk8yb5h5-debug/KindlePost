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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
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
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            MaterialTheme {
                var sender by remember { mutableStateOf(prefs.getString("sender", "") ?: "") }
                var authCode by remember { mutableStateOf(prefs.getString("auth", "") ?: "") }
                var recipient by remember { mutableStateOf(prefs.getString("recipient", "") ?: "") }
                var photos by remember { mutableStateOf(listOf<Uri>()) }
                var compressedBatch by remember { mutableStateOf<CompressedBatch?>(null) }
                var status by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var sizes by remember { mutableStateOf("") }
                val currentVersion = remember { packageManager.getPackageInfo(packageName, 0).versionName ?: "未知" }
                var updateStatus by remember { mutableStateOf("") }
                var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
                var updateBusy by remember { mutableStateOf(false) }
                var settingsOpen by remember { mutableStateOf(false) }
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)
                ) { selected ->
                    photos = selected.take(MAX_PHOTOS)
                    compressedBatch = null
                    sizes = ""
                    if (photos.isEmpty()) {
                        status = ""
                    } else {
                        busy = true
                        status = "正在压缩照片…"
                        val selectedPhotos = photos
                        lifecycleScope.launch {
                            try {
                                val batch = withContext(Dispatchers.IO) { compressPhotos(this@MainActivity, selectedPhotos) }
                                compressedBatch = batch
                                sizes = "原图 ${formatBytes(batch.originalBytes)} · 压缩后 ${formatBytes(batch.compressedBytes)}"
                                status = "已压缩 ${batch.photos.size} 张照片"
                            } catch (e: Exception) {
                                compressedBatch = null
                                status = e.message ?: "照片压缩失败"
                            } finally { busy = false }
                        }
                    }
                }

                val installPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    if (packageManager.canRequestPackageInstalls()) {
                        try { UpdateManager.install(this@MainActivity) }
                        catch (e: Exception) { updateStatus = "无法启动安装程序" }
                    } else updateStatus = "未获得安装权限"
                }

                Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFFFFEFC)).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("KindlePost", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light, letterSpacing = (-1.1).sp))
                        IconButton(onClick = { settingsOpen = true }, modifier = Modifier.size(48.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) { SettingsGlyph() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy, modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(32.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { PhotoGlyph(); Spacer(Modifier.width(10.dp)); Text(if (photos.isEmpty()) "选择照片" else "添加照片", fontSize = 17.sp) }
                        if (photos.isNotEmpty()) OutlinedButton(onClick = { photos = emptyList(); compressedBatch = null; sizes = ""; status = "" }, enabled = !busy) { Text("清空") }
                    }
                    if (photos.isNotEmpty()) {
                        Text("已选 ${photos.size}/${MAX_PHOTOS} 张", style = MaterialTheme.typography.titleSmall)
                        LazyColumn(Modifier.weight(1f, fill = true)) {
                            itemsIndexed(photos) { index, uri -> Text("${index + 1}. ${uri.lastPathSegment ?: "照片"}", maxLines = 1, modifier = Modifier.padding(vertical = 3.dp)) }
                        }
                    } else Spacer(Modifier.weight(1f))
                    if (sizes.isNotBlank()) Text(sizes, style = MaterialTheme.typography.bodySmall)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && compressedBatch != null && sender.isNotBlank() && authCode.isNotBlank() && recipient.isNotBlank(), onClick = {
                        busy = true
                        status = "正在发送…"
                        lifecycleScope.launch {
                            try {
                                val batch = compressedBatch ?: error("请先选择照片")
                                withContext(Dispatchers.IO) { sendCompressedPhotos(sender.trim(), authCode.trim(), recipient.trim(), batch.photos) }
                                status = "发送成功 · ${batch.photos.size} 张"
                                photos = emptyList()
                                compressedBatch = null
                                sizes = ""
                            } catch (e: Exception) { status = "发送失败：${e.message ?: "请检查邮箱设置"}" }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(32.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, disabledContainerColor = androidx.compose.ui.graphics.Color(0xFFE4E2E6), disabledContentColor = androidx.compose.ui.graphics.Color(0xFF99969E))) { Text(if (busy) "发送中…" else "发送 ${photos.size} 张照片", fontSize = 17.sp) }
                }
                if (settingsOpen) {
                    AlertDialog(
                        onDismissRequest = { settingsOpen = false },
                        title = { Text("设置") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(sender, { sender = it }, label = { Text("QQ 发件邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(authCode, { authCode = it }, label = { Text("QQ 邮箱授权码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(recipient, { recipient = it }, label = { Text("Kindle 接收邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                HorizontalDivider()
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Text("当前版本 $currentVersion", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    OutlinedButton(enabled = !updateBusy, onClick = {
                                        updateBusy = true
                                        updateStatus = "正在检查…"
                                        lifecycleScope.launch {
                                            try {
                                                val update = withContext(Dispatchers.IO) { UpdateManager.check(this@MainActivity) }
                                                if (update == null) {
                                                    updateStatus = "已是最新版本"
                                                } else {
                                                    updateStatus = "正在下载 ${update.versionName}…"
                                                    withContext(Dispatchers.IO) { UpdateManager.download(this@MainActivity, update) }
                                                    updateStatus = "正在启动安装…"
                                                    if (!packageManager.canRequestPackageInstalls()) {
                                                        installPermissionLauncher.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                                                    } else {
                                                        UpdateManager.install(this@MainActivity)
                                                    }
                                                }
                                            } catch (e: Exception) { updateStatus = e.message ?: "更新失败" }
                                            finally { updateBusy = false }
                                        }
                                    }, modifier = Modifier.weight(1f)) { Text(if (updateBusy) "检查中…" else "检查更新") }
                                }
                                if (updateStatus.isNotBlank()) Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                prefs.edit().putString("sender", sender.trim()).putString("auth", authCode.trim()).putString("recipient", recipient.trim()).apply()
                                sender = sender.trim()
                                authCode = authCode.trim()
                                recipient = recipient.trim()
                                settingsOpen = false
                            }) { Text("保存") }
                        },
                        dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text("取消") } }
                    )
                }
            }
        }
    }
}

private data class CompressedPhoto(val bytes: ByteArray, val name: String)
private data class CompressedBatch(val photos: List<CompressedPhoto>, val originalBytes: Long, val compressedBytes: Long)

private fun compressPhotos(context: Context, uris: List<Uri>): CompressedBatch {
    require(uris.size <= MAX_PHOTOS) { "一次最多选择 $MAX_PHOTOS 张照片" }
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
        require(after + bytes.size <= MAX_TOTAL_ATTACHMENT_BYTES) { "压缩后附件超过 18 MB，请减少照片数量后分批选择" }
        after += bytes.size
        attachments += CompressedPhoto(bytes, "KindlePhoto_${(index + 1).toString().padStart(2, '0')}.jpg")
    }
    return CompressedBatch(attachments, before, after)
}

private fun sendCompressedPhotos(sender: String, auth: String, recipient: String, attachments: List<CompressedPhoto>) {
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


@Composable
private fun SettingsGlyph() {
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.32f
        drawCircle(color, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()))
        drawCircle(color, size.minDimension * 0.09f, c)
        repeat(8) { i ->
            val a = Math.toRadians(i * 45.0)
            val p1 = androidx.compose.ui.geometry.Offset(c.x + kotlin.math.cos(a).toFloat() * (r + 1.dp.toPx()), c.y + kotlin.math.sin(a).toFloat() * (r + 1.dp.toPx()))
            val p2 = androidx.compose.ui.geometry.Offset(c.x + kotlin.math.cos(a).toFloat() * (r + 4.dp.toPx()), c.y + kotlin.math.sin(a).toFloat() * (r + 4.dp.toPx()))
            drawLine(color, p1, p2, 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun PhotoGlyph() {
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val sw = 1.7.dp.toPx()
        drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .16f, size.height * .76f)
            lineTo(size.width * .43f, size.height * .48f)
            lineTo(size.width * .59f, size.height * .64f)
            lineTo(size.width * .75f, size.height * .42f)
            lineTo(size.width * .88f, size.height * .58f)
        }
        drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(color, 1.4.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * .34f, size.height * .28f))
    }
}
