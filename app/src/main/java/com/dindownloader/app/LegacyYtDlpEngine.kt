package com.dindownloader.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException

/**
 * Restored legacy engine from the original Downloader 1.x implementation.
 * Only used when explicitly selected; the default native Kotlin engine remains untouched.
 * Uses the bundled yt-dlp extractor and FFmpeg for joining audio + video when necessary.
 */
object LegacyYtDlpEngine {
    private val mediaExtensions = setOf(
        "mp4", "mkv", "webm", "mov", "mp3", "m4a", "aac", "opus", "ogg", "ts"
    )

    @Synchronized
    private fun prepare(context: Context, ffmpeg: Boolean) {
        YoutubeDL.getInstance().init(context.applicationContext)
        if (ffmpeg) FFmpeg.getInstance().init(context.applicationContext)
    }

    fun inspect(context: Context, url: String, quality: Int): MediaInfo {
        prepare(context, false)
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("-f", formatSelector(quality))
        val jar = SiteSessionCookies.makeTempCookies(context, url)
        if (jar != null) request.addOption("--cookies", jar.absolutePath)
        val metadata = try {
            YoutubeDL.getInstance().getInfo(request)
        } finally {
            jar?.delete()
        }
        val bytes = metadata.requestedFormats.orEmpty().sumOf { fmt ->
            fmt.fileSize.takeIf { it > 0 }
                ?: fmt.fileSizeApproximate.takeIf { it > 0 }
                ?: 0L
        }.takeIf { it > 0 }
            ?: metadata.fileSize.takeIf { it > 0 }
            ?: metadata.fileSizeApproximate.takeIf { it > 0 }
            ?: 0L
        return MediaInfo(
            originalUrl = url, mediaUrl = url,
            title = metadata.title?.takeIf(String::isNotBlank) ?: "Видео",
            thumbnail = metadata.thumbnail?.takeIf(String::isNotBlank),
            estimatedBytes = bytes,
            kind = "legacy"
        )
    }

    internal fun formatSelector(quality: Int): String =
        if (quality <= 0) "bv*+ba/b"
        else "bv*[height<=$quality]+ba/b[height<=$quality]/b"

    fun interrupt(jobId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(jobId)
        } catch (_: Exception) {
            // The process may still be initializing.
        }
    }

    fun transfer(
        context: Context,
        job: DownloadJob,
        directory: File,
        progress: (downloaded: Long, total: Long, percent: Int, eta: Long) -> Unit
    ): List<TransferResult> {
        prepare(context, true)
        directory.mkdirs()
        val request = YoutubeDLRequest(job.url)
        request.addOption("--no-mtime")
        request.addOption("--trim-filenames", 110)
        request.addOption("--newline")
        request.addOption("--progress-template",
            "download:DOWNLOAD_STATS:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s")
        if (!job.playlist) request.addOption("--no-playlist")
        request.addOption("-o", File(directory, "%(title)s [%(id)s].%(ext)s").absolutePath)

        if (job.format == "audio") {
            request.addOption("-f", "bestaudio/best")
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
        } else {
            request.addOption("-f", formatSelector(job.quality))
            request.addOption("--merge-output-format", "mp4")
        }

        var completedBytes = 0L
        var lastPart = 0L
        var completedTotals = 0L
        var lastPartTotal = 0L
        val jar = SiteSessionCookies.makeTempCookies(context, job.url)
        if (jar != null) request.addOption("--cookies", jar.absolutePath)
        try {
            YoutubeDL.getInstance().execute(request, job.id) { percentage: Float, eta: Long, line: String ->
            val values = if ("DOWNLOAD_STATS:" in line)
                line.substringAfter("DOWNLOAD_STATS:").trim().split(':')
            else emptyList()
            if (values.size >= 3) {
                val nowPart = values[0].trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val totalPart = (values[1].trim().toLongOrNull()
                    ?: values[2].trim().toLongOrNull()
                    ?: 0L).coerceAtLeast(0L)
                if (nowPart < lastPart && lastPart > 0L) {
                    completedBytes += lastPart
                    completedTotals += lastPartTotal
                    lastPartTotal = 0L
                }
                lastPart = nowPart
                lastPartTotal = maxOf(lastPartTotal, totalPart)
            }
            val downloaded = completedBytes + lastPart
            val total = completedTotals + lastPartTotal
            val p = if (percentage.isFinite() && percentage >= 0) {
                percentage.toInt().coerceIn(0, 100)
            } else if (total > 0L) {
                (downloaded * 100.0 / total).toInt().coerceIn(0, 100)
            } else 0
            progress(downloaded, total, p, eta)
            }
        } finally {
            jar?.delete()
        }

        val files = directory.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in mediaExtensions && it.length() > 0L }
            .toList()
        if (files.isEmpty()) throw IOException("yt-dlp завершился без готового видео или аудиофайла")
        return files.map { file ->
            TransferResult(file, when (file.extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "opus" -> "audio/opus"
                "ogg" -> "audio/ogg"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "ts" -> "video/mp2t"
                else -> "video/mp4"
            })
        }
    }
}
