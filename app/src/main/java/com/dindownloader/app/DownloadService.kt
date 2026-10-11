package com.dindownloader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DownloadService : Service() {
    companion object {
        const val ACTION_START = "com.dindownloader.app.START"
        const val ACTION_PAUSE = "com.dindownloader.app.PAUSE"
        const val ACTION_CANCEL = "com.dindownloader.app.CANCEL"
        const val ACTION_REMOVE = "com.dindownloader.app.REMOVE"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL = "downloader_v2"
        private const val NOTIFICATION_ID = 17
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var currentJob: String? = null
    @Volatile private var active: NativeTransfer? = null
    @Volatile private var interrupted: String? = null
    @Volatile private var removing: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadStore.init(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Загрузки видео", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_PAUSE || action == ACTION_CANCEL || action == ACTION_REMOVE) {
            intent.getStringExtra(EXTRA_JOB_ID)?.let { id ->
                if (currentJob == id) {
                    interrupted = id
                    if (action == ACTION_REMOVE) removing = id
                    active?.interrupt()
                } else {
                    if (action == ACTION_REMOVE) {
                        workspace(id).deleteRecursively()
                        DownloadStore.remove(id)
                    } else {
                        DownloadStore.update(id) {
                            if (it.status == JobStatus.QUEUED || it.status == JobStatus.PAUSED)
                                it.copy(status = if (action == ACTION_PAUSE) JobStatus.PAUSED
                                    else JobStatus.CANCELLED)
                            else it
                        }
                        if (action == ACTION_CANCEL) workspace(id).deleteRecursively()
                    }
                }
            }
            if (!running.get() && currentJob == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Ожидание очереди…", 0, true))
        executor.execute { drain() }
        return START_NOT_STICKY
    }

    private fun workspace(id: String): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "working/$id")
    }

    private fun drain() {
        if (!running.compareAndSet(false, true)) return
        try {
            while (true) {
                val item = DownloadStore.queued() ?: break
                download(item)
            }
        } finally {
            currentJob = null
            running.set(false)
            if (DownloadStore.queued() != null) executor.execute { drain() }
            else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun ensureActive(id: String) {
        if (interrupted == id || Thread.currentThread().isInterrupted)
            throw CancellationException("Загрузка остановлена")
    }

    private fun download(job: DownloadJob) {
        currentJob = job.id
        if (DownloadStore.get(job.id)?.status != JobStatus.QUEUED) {
            currentJob = null
            return
        }
        interrupted = null
        removing = null
        active = NativeTransfer()
        val directory = workspace(job.id).apply { mkdirs() }
        var savedUri: Uri? = null
        var complete = false
        var lastTick = 0L
        DownloadStore.update(job.id) { it.copy(status = JobStatus.RUNNING, error = null) }
        try {
            ensureActive(job.id)
            notifyProgress("Проверяем видео…", 0, true)
            val info = if (!job.directMediaUrl.isNullOrBlank()) {
                NativeEngine.probe(job.directMediaUrl, job.quality, job.url)
                    .copy(originalUrl = job.url, referer = job.url,
                        title = job.title.takeIf { it != "Видео" } ?: NativeEngine.fileTitle(job.url))
            } else NativeEngine.probe(job.url, job.quality)
            ensureActive(job.id)
            if (job.format == "audio" && !info.mime.startsWith("audio/")) {
                throw IOException("Ссылка не содержит отдельную аудиодорожку. Перекодирование в MP3 без дополнительных библиотек недоступно")
            }
            DownloadStore.update(job.id) {
                it.copy(title = info.title,
                    thumbnailUrl = info.thumbnail ?: it.thumbnailUrl,
                    totalBytes = info.estimatedBytes.takeIf { v -> v > 0 } ?: it.totalBytes)
            }
            val worker = active ?: throw CancellationException()
            val transfer = worker.transfer(info, job.quality, directory) { size, total, percent ->
                if (interrupted != job.id) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTick > 450 || percent >= 100) {
                        lastTick = now
                        DownloadStore.update(job.id) {
                            it.copy(downloadedBytes = size,
                                totalBytes = if (total > 0) total else it.totalBytes,
                                progress = percent)
                        }
                        notifyProgress("Скачано " + SizeText.format(size), percent, total == 0L)
                    }
                }
            }
            ensureActive(job.id)
            notifyProgress("Сохраняем в папку сайта…", 100, true)
            savedUri = store(job.url, info.title, transfer)
            ensureActive(job.id)
            val bytes = transfer.file.length()
            DownloadStore.update(job.id) {
                it.copy(status = JobStatus.COMPLETED, progress = 100,
                    downloadedBytes = bytes, totalBytes = bytes,
                    savedCount = 1, resultUri = savedUri.toString(),
                    savedUris = listOf(savedUri.toString()), error = null)
            }
            complete = true
        } catch (e: Exception) {
            val stopping = interrupted == job.id || e is CancellationException
            DownloadStore.update(job.id) {
                it.copy(status = if (stopping) JobStatus.PAUSED else JobStatus.FAILED,
                    error = if (stopping) null else e.localizedMessage?.take(250)
                        ?: "Неизвестная ошибка")
            }
        } finally {
            active = null
            if (savedUri != null && !complete) {
                try { contentResolver.delete(savedUri, null, null) } catch (_: Exception) {}
            }
            if (complete || removing == job.id) directory.deleteRecursively()
            if (removing == job.id) DownloadStore.remove(job.id)
            currentJob = null
            interrupted = null
            removing = null
        }
    }

    private fun store(originalUrl: String, title: String, result: TransferResult): Uri {
        val ext = when (result.mime) {
            "video/mp2t" -> "ts"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/aac" -> "aac"
            else -> "mp4"
        }
        val baseName = title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim().take(100).ifBlank { "video" }
            .removeSuffix(".$ext")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, result.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, SiteFolders.relativePath(originalUrl))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Не удалось создать файл в папке Загрузки")
        try {
            val output = contentResolver.openOutputStream(uri)
                ?: throw IOException("Нет доступа к созданному файлу")
            output.use { out -> result.file.inputStream().use { it.copyTo(out, 131072) } }
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun notification(message: String, progress: Int, unknown: Boolean): Notification {
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloader 2.0").setContentText(message)
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(activity)
            .setProgress(100, progress, unknown).build()
    }

    private fun notifyProgress(message: String, progress: Int, unknown: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, progress, unknown))
    }

    override fun onDestroy() {
        active?.interrupt()
        executor.shutdownNow()
        super.onDestroy()
    }
}
