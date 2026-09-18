package cloud.huyenvu.tv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

object RecorderState {
    val isRecording = MutableStateFlow(false)
    val lastMessage = MutableStateFlow<String?>(null)
}

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "cloud.huyenvu.tv.START_RECORDING"
        const val ACTION_STOP = "cloud.huyenvu.tv.STOP_RECORDING"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_NAME = "channel_name"
        private const val CHANNEL_ID = "tv_recording"
        private const val NOTIFICATION_ID = 301
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().build()
    @Volatile private var active = false
    private var recordingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Ghi chương trình TV", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!active) startRecording(
                    intent.getStringExtra(EXTRA_URL).orEmpty(),
                    intent.getStringExtra(EXTRA_NAME).orEmpty()
                )
            }
            ACTION_STOP -> finishRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(url: String, channelName: String) {
        if (url.isBlank()) return
        active = true
        RecorderState.isRecording.value = true
        startForeground(NOTIFICATION_ID, notification(channelName, "Đang chuẩn bị bản ghi…"))
        recordingJob = scope.launch {
            val target = createTarget(channelName)
            try {
                target.output.use { output ->
                    recordHls(url, output) { count ->
                        if (count % 8 == 0) updateNotification(channelName, "Đang ghi • $count đoạn")
                    }
                }
                target.finish(true)
                RecorderState.lastMessage.value = "Đã lưu bản ghi: ${target.fileName}"
                updateNotification(channelName, "Đã lưu ${target.fileName}")
            } catch (error: Throwable) {
                target.finish(false)
                RecorderState.lastMessage.value = error.message ?: "Không thể ghi luồng này"
                updateNotification(channelName, "Ghi thất bại: luồng không tương thích")
            } finally {
                active = false
                RecorderState.isRecording.value = false
                delay(2500)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun finishRecording() {
        active = false
        updateNotification("TV Dr Vũ", "Đang hoàn tất và lưu bản ghi…")
    }

    private suspend fun recordHls(initialUrl: String, output: OutputStream, progress: (Int) -> Unit) {
        var playlistUrl = initialUrl
        var text = getText(playlistUrl)
        if (text.contains("#EXT-X-STREAM-INF")) {
            playlistUrl = selectBestVariant(playlistUrl, text)
            text = getText(playlistUrl)
        }
        require(text.contains("#EXTM3U")) { "Nguồn không phải HLS" }
        require(!text.contains("#EXT-X-KEY")) { "Luồng mã hóa chưa được hỗ trợ" }
        require(!text.contains("#EXT-X-MAP")) { "Định dạng fMP4 chưa được hỗ trợ" }

        val seen = LinkedHashSet<String>()
        var count = 0
        while (active && currentCoroutineContext().isActive) {
            text = getText(playlistUrl)
            val segments = text.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { resolveUrl(playlistUrl, it) }.toList()
            for (segment in segments) {
                if (!active || !currentCoroutineContext().isActive) break
                if (seen.add(segment)) {
                    getBytes(segment).let { output.write(it) }
                    output.flush()
                    count++
                    progress(count)
                }
            }
            if (text.contains("#EXT-X-ENDLIST")) break
            if (seen.size > 3000) {
                val keep = seen.takeLast(1000)
                seen.clear(); seen.addAll(keep)
            }
            delay(1800)
        }
    }

    private fun selectBestVariant(base: String, playlist: String): String {
        data class Variant(val bandwidth: Long, val url: String)
        val lines = playlist.lines()
        val variants = lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            Variant(bandwidth, resolveUrl(base, next.trim()))
        }
        return variants.maxByOrNull { it.bandwidth }?.url ?: base
    }

    private fun getText(url: String): String = request(url).toString(Charsets.UTF_8)
    private fun getBytes(url: String): ByteArray = request(url)
    private fun request(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", "TV-Dr-Vu-Android/1.1").build()
        return client.newCall(req).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body?.bytes() ?: error("Dữ liệu trống")
        }
    }

    private fun resolveUrl(base: String, value: String) = URI(base).resolve(value).toString()

    private data class Target(
        val fileName: String,
        val output: OutputStream,
        val finish: (Boolean) -> Unit
    )

    private fun createTarget(channelName: String): Target {
        val safeName = channelName.replace(Regex("[^A-Za-z0-9À-ỹ _-]"), "").trim().take(48).ifBlank { "TV" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "$safeName-$stamp.ts"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TV Dr Vũ")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Không tạo được tệp ghi")
            val output = contentResolver.openOutputStream(uri, "w") ?: error("Không mở được tệp ghi")
            return Target(fileName, output) { success ->
                if (success) {
                    values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else contentResolver.delete(uri, null, null)
            }
        }
        val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val file = File(directory, fileName)
        return Target(fileName, file.outputStream()) { success -> if (!success) file.delete() }
    }

    private fun notification(name: String, status: String): android.app.Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Đang ghi • $name")
            .setContentText(status)
            .setOngoing(active)
            .addAction(android.R.drawable.ic_media_pause, "Dừng và lưu", pendingStop)
            .build()
    }

    private fun updateNotification(name: String, status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(name, status))
    }

    override fun onDestroy() {
        active = false
        recordingJob?.cancel()
        scope.cancel()
        RecorderState.isRecording.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
