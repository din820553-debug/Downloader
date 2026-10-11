package com.dindownloader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DownloadService : Service() {
    companion object {
        const val ACTION_START = "com.dindownloader.app.START"
        const val ACTION_CANCEL = "com.dindownloader.app.CANCEL"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL_ID = "downloader_progress"
        private const val NOTIFICATION_ID = 17
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var currentJob: String? = null
    @Volatile private var cancelJob: String? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadStore.init(applicationContext)
        val channel = NotificationChannel(
            CHANNEL_ID, "Загрузки видео", NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Прогресс скачивания видео и аудио"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_JOB_ID)
            if (id != null) cancel(id)
            return START_NOT_STICKY
        }

        // startForeground must happen immediately after startForegroundService.
        startForeground(NOTIFICATION_ID, notification("Ожидание загрузки", 0, true))
        executor.execute { drainQueue() }
        return START_NOT_STICKY
    }

    private fun drainQueue() {
        if (!running.compareAndSet(false, true)) return
        try {
            while (true) {
                val next = DownloadStore.queued() ?: break
                download(next)
            }
        } finally {
            currentJob = null
            running.set(false)
            if (DownloadStore.queued() != null) {
                executor.execute { drainQueue() }
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancel(id: String) {
        if (currentJob == id) {
            cancelJob = id
            try {
                YoutubeDL.getInstance().destroyProcessById(id)
            } catch (_: Exception) {
                // The process may not have launched yet; cancelJob is still checked.
            }
        } else {
            DownloadStore.update(id) {
                if (it.status == JobStatus.QUEUED) it.copy(status = JobStatus.CANCELLED) else it
            }
        }
    }

    private fun download(job: DownloadJob) {
        currentJob = job.id
        cancelJob = null
        val base = getExternalFilesDir(null) ?: filesDir
        val directory = File(base, "working/${job.id}")
        directory.mkdirs()
        DownloadStore.update(job.id) { it.copy(status = JobStatus.RUNNING, progress = 0, error = null) }
        try {
            notifyProgress("Подготовка · ${job.title.take(35)}", 0, true)
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            if (cancelJob == job.id) throw InterruptedException("Отменено")

            val request = YoutubeDLRequest(job.url)
            request.addOption("--no-mtime")
            request.addOption("--trim-filenames", 110)
            request.addOption("--newline")
            if (!job.playlist) request.addOption("--no-playlist")
            request.addOption("-o", File(directory, "%(title)s [%(id)s].%(ext)s").absolutePath)

            if (job.format == "mp3" || job.format == "m4a") {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", job.format)
                if (job.format == "mp3") request.addOption("--audio-quality", "0")
            } else {
                val selector = if (job.quality == 0) {
                    "bv*+ba/b"
                } else {
                    "bv*[height<=${job.quality}]+ba/b[height<=${job.quality}]/b"
                }
                request.addOption("-f", selector)
                request.addOption("--merge-output-format", "mp4")
                request.addOption("--recode-video", "mp4")
            }

            YoutubeDL.getInstance().execute(request, job.id) { progress: Float, eta: Long, _: String ->
                val value = progress.toInt().coerceIn(0, 100)
                if (cancelJob != job.id) {
                    DownloadStore.update(job.id, save = false) {
                        it.copy(progress = value, etaSeconds = eta)
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotify >= 900) {
                        lastNotify = now
                        notifyProgress("${job.title.take(36)} · ${value}%", value, false)
                    }
                }
            }

            if (cancelJob == job.id) throw InterruptedException("Отменено")
            notifyProgress("Сохранение файлов…", 100, true)

            val allowed = setOf("mp4", "mkv", "webm", "mov", "mp3", "m4a", "opus", "ogg")
            val finished = directory.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in allowed }
                .toList()
            if (finished.isEmpty()) throw IllegalStateException("Готовые медиафайлы не найдены")

            var first: Uri? = null
            var saved = 0
            for (file in finished) {
                if (cancelJob == job.id) throw InterruptedException("Отменено")
                val uri = saveToDownloads(file)
                if (first == null) first = uri
                saved++
            }
            DownloadStore.update(job.id) {
                it.copy(
                    status = JobStatus.COMPLETED, progress = 100,
                    savedCount = saved, resultUri = first?.toString(), error = null
                )
            }
        } catch (error: Exception) {
            if (cancelJob == job.id || error is InterruptedException) {
                DownloadStore.update(job.id) { it.copy(status = JobStatus.CANCELLED, error = null) }
            } else {
                val message = error.localizedMessage?.take(180) ?: "Неизвестная ошибка"
                DownloadStore.update(job.id) {
                    it.copy(status = JobStatus.FAILED, error = message)
                }
            }
        } finally {
            directory.deleteRecursively()
            currentJob = null
            cancelJob = null
        }
    }

    private fun saveToDownloads(file: File): Uri {
        val mime = when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
        val cleanName = file.name
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .take(180)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Downloader")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл в Загрузках")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть файл для записи")
            out.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun notification(message: String, progress: Int, indeterminate: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloader")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setProgress(100, progress, indeterminate)
            .build()
    }

    private fun notifyProgress(message: String, progress: Int, indeterminate: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, progress, indeterminate))
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
