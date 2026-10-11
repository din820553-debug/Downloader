package com.dindownloader.app

import com.yausername.youtubedl_android.mapper.VideoInfo

object DownloadFormats {
    fun selector(format: String, quality: Int): String =
        if (format == "mp3" || format == "m4a") "bestaudio/best"
        else if (quality <= 0) "bv*+ba/b"
        else "bv*[height<=${quality}]+ba/b[height<=${quality}]/b"

    fun estimate(info: VideoInfo): Long {
        val selected = info.requestedFormats.orEmpty()
        if (selected.isNotEmpty()) {
            val sizes = selected.map { format ->
                format.fileSize.takeIf { it > 0 }
                    ?: format.fileSizeApproximate.takeIf { it > 0 }
                    ?: 0L
            }
            if (sizes.all { it > 0 }) return sizes.sum()
        }
        return info.fileSize.takeIf { it > 0 }
            ?: info.fileSizeApproximate.takeIf { it > 0 }
            ?: 0L
    }
}
