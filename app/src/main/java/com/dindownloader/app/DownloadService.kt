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
        const val ACTION_PAUSE = "com.dindownloader.app.PAUSE"
        const val ACTION_REMOVE = "com.dindownloader.app.REMOVE"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL_ID = "downloader_progress"
        private const val NOTIFICATION_ID = 17
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var currentJob: String? = null
    @Volatile private var cancelJob: String? = null
    @Volatile private var pauseJob: String? = null
    @Volatile private var removeJob: String? = null
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
        if (intent?.action == ACTION_REMOVE) {
            intent.getStringExtra(EXTRA_JOB_ID)?.let { id ->
                if (currentJob == id) {
                    removeJob = id
                    cancel(id)
                } else {
                    cleanupWorkingDirectory(id)
                    DownloadStore.remove(id)
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            intent.getStringExtra(EXTRA_JOB_ID)?.let(::pause)
            return START_NOT_STICKY
        }
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

    private fun cleanupWorkingDirectory(id: String) {
        val base = getExternalFilesDir(null) ?: filesDir
        File(base, "working/$id").deleteRecursively()
    }

    private fun pause(id: String) {
        if (currentJob == id) {
            pauseJob = id
            try {
                YoutubeDL.getInstance().destroyProcessById(id)
            } catch (_: Exception) {}
        } else {
            DownloadStore.update(id) {
                if (it.status == JobStatus.QUEUED) it.copy(status = JobStatus.PAUSED) else it
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
        if (DownloadStore.get(job.id)?.status != JobStatus.QUEUED) return
        currentJob = job.id
        cancelJob = null
        pauseJob = null
        val base = getExternalFilesDir(null) ?: filesDir
        val directory = File(base, "working/${job.id}")
        directory.mkdirs()
        val savedUriList = mutableListOf<Uri>()
        var succeeded = false
        DownloadStore.update(job.id) { it.copy(status = JobStatus.RUNNING, progress = 0, error = null) }
        try {
            notifyProgress("Подготовка · ${job.title.take(35)}", 0, true)
            YtDlpEngine.prepare(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            if (!job.playlist && (job.thumbnailUrl.isNullOrBlank() || job.totalBytes <= 0L)) {
                try {
                    val metadata = YoutubeDLRequest(job.url)
                    metadata.addOption("--no-playlist")
                    metadata.addOption("-f", DownloadFormats.selector(job.format, job.quality))
                    val info = YoutubeDL.getInstance().getInfo(metadata)
                    DownloadStore.update(job.id) {
                        it.copy(
                            title = info.title ?: it.title,
                            thumbnailUrl = info.thumbnail ?: it.thumbnailUrl,
                            totalBytes = DownloadFormats.estimate(info).takeIf { size -> size > 0L }
                                ?: it.totalBytes
                        )
                    }
                } catch (_: Exception) {
                    // A metadata request may fail even for a downloadable media URL.
                }
            }
            if (cancelJob == job.id) throw InterruptedException("Отменено")
            if (pauseJob == job.id) throw InterruptedException("Приостановлено")

            val request = createRequest(job, directory, false)

            var finishedBytes = 0L
            var lastPartBytes = 0L
            var completedPartTotals = 0L
            var partTotal = 0L
            val reportProgress: (Float, Long, String) -> Unit = { progress, eta, line ->
                val parsed = if (line.contains("DOWNLOAD_STATS:")) {
                    line.substringAfter("DOWNLOAD_STATS:").trim().split(':')
                } else emptyList()
                var downloaded = finishedBytes + lastPartBytes
                var total = completedPartTotals + partTotal
                if (parsed.size >= 3) {
                    val partBytes = parsed[0].trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    val knownTotal = (parsed[1].trim().toLongOrNull()
                        ?: parsed[2].trim().toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    if (partBytes < lastPartBytes && lastPartBytes > 0) {
                        finishedBytes += lastPartBytes
                        completedPartTotals += partTotal
                        partTotal = 0L
                    }
                    lastPartBytes = partBytes
                    partTotal = maxOf(partTotal, knownTotal)
                    downloaded = finishedBytes + partBytes
                    total = completedPartTotals + partTotal
                }
                if (cancelJob != job.id) {
                    val pct = if (progress >= 0) progress.toInt().coerceIn(0, 100)
                        else if (total > 0) (downloaded * 100.0 / total).toInt().coerceIn(0, 100)
                        else 0
                    val now = SystemClock.elapsedRealtime()
                    val shouldSave = now - lastNotify >= 900
                    DownloadStore.update(job.id, save = shouldSave) {
                        it.copy(
                            progress = pct, etaSeconds = eta,
                            downloadedBytes = maxOf(it.downloadedBytes, downloaded),
                            totalBytes = if (total > 0) maxOf(total, downloaded) else it.totalBytes
                        )
                    }
                    if (shouldSave) {
                        lastNotify = now
                        notifyProgress("${job.title.take(23)} · ${SizeText.format(downloaded)}", pct, total == 0L)
                    }
                }
            }
            try {
                YoutubeDL.getInstance().execute(request, job.id, reportProgress)
            } catch (first: Exception) {
                if (pauseJob == job.id || cancelJob == job.id) throw first
                // Retry using standard browser headers and an alternative media transport.
                // If that also fails, surface both error messages.
                notifyProgress("Пробуем другой способ загрузки…", 0, true)
                try {
                    YoutubeDL.getInstance().execute(
                        createRequest(job, directory, true), job.id, reportProgress
                    )
                } catch (second: Exception) {
                    if (pauseJob == job.id || cancelJob == job.id) throw second
                    throw IllegalStateException(
                        "Обычный: ${first.localizedMessage?.take(100)}; " +
                        "другой способ: ${second.localizedMessage?.take(100)}", second
                    )
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
                savedUriList.add(uri)
                saved++
            }
            DownloadStore.update(job.id) {
                it.copy(
                    status = JobStatus.COMPLETED, progress = 100,
                    savedCount = saved, resultUri = first?.toString(),
                    savedUris = savedUriList.map(Uri::toString),
                    downloadedBytes = finished.sumOf(File::length),
                    totalBytes = finished.sumOf(File::length),
                    error = null
                )
            }
            succeeded = true
        } catch (error: Exception) {
            if (pauseJob == job.id) {
                DownloadStore.update(job.id) {
                    it.copy(status = JobStatus.PAUSED, error = null)
                }
            } else if (cancelJob == job.id || error is InterruptedException) {
                DownloadStore.update(job.id) { it.copy(status = JobStatus.CANCELLED, error = null) }
            } else {
                val message = error.localizedMessage?.take(180) ?: "Неизвестная ошибка"
                DownloadStore.update(job.id) {
                    it.copy(status = JobStatus.FAILED, error = message)
                }
            }
        } finally {
            if (!succeeded || removeJob == job.id) {
                savedUriList.forEach { uri ->
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            }
            if (pauseJob != job.id) directory.deleteRecursively()
            if (removeJob == job.id) {
                DownloadStore.remove(job.id)
                removeJob = null
            }
            currentJob = null
            cancelJob = null
            pauseJob = null
        }
    }

    private fun createRequest(job: DownloadJob, directory: File, fallback: Boolean): YoutubeDLRequest {
        val request = YoutubeDLRequest(job.url)
        request.addOption("--continue")
        request.addOption("--part")
        request.addOption("--no-mtime")
        request.addOption("--trim-filenames", 110)
        request.addOption("--newline")
        request.addOption("--progress-template",
            "download:DOWNLOAD_STATS:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s")
        if (!job.playlist) request.addOption("--no-playlist")
        request.addOption("-o", File(directory, "%(title)s [%(id)s].%(ext)s").absolutePath)
        if (fallback) {
            // Some websites require ordinary browser headers or an ffmpeg HLS transport.
            // This does not bypass DRM, authentication or geographic restrictions.
            request.addOption("--user-agent",
                "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            request.addOption("--add-header", "Referer:${job.url}")
            request.addOption("--downloader", "m3u8:ffmpeg")
            request.addOption("--no-check-formats")
        }
        if (job.format == "mp3" || job.format == "m4a") {
            request.addOption("-f", if (fallback) "ba/b" else "bestaudio/best")
            request.addOption("-x")
            request.addOption("--audio-format", job.format)
            if (job.format == "mp3") request.addOption("--audio-quality", "0")
        } else {
            request.addOption("-f", if (fallback) "b[ext=mp4]/b/bv*+ba"
                else DownloadFormats.selector(job.format, job.quality))
            request.addOption("--merge-output-format", "mp4")
            request.addOption("--recode-video", "mp4")
        }
        return request
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
