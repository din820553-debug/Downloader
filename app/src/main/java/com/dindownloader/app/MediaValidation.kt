package com.dindownloader.app

import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException

/** Reject HTML/JSON and short teaser clips masquerading as completed media. */
object MediaValidation {
    fun recognizable(bytes: ByteArray, mime: String): Boolean {
        if (bytes.isEmpty()) return false
        val start = String(bytes.take(128).toByteArray(), Charsets.ISO_8859_1).trimStart()
        if (start.startsWith("<!") || start.startsWith("<html", true) ||
            start.startsWith("{") || start.startsWith("[") || start.startsWith("#EXTM3U")) {
            return false
        }
        val magic = bytes.take(16).toByteArray()
        val mp4 = magic.size >= 8 && String(magic,4,4,Charsets.US_ASCII) in
            setOf("ftyp","styp","moov","moof")
        val webm = magic.size >= 4 &&
            magic[0] == 0x1a.toByte() && magic[1] == 0x45.toByte() &&
            magic[2] == 0xdf.toByte() && magic[3] == 0xa3.toByte()
        val ts = magic[0] == 0x47.toByte() || start.startsWith("ID3")
        val mp3 = start.startsWith("ID3") || (magic.size >= 2 &&
            (magic[0].toInt() and 255) == 0xff && (magic[1].toInt() and 0xe0) == 0xe0)
        val ogg = start.startsWith("OggS")
        val aac = magic.size >= 2 && (magic[0].toInt() and 255) == 0xff &&
            (magic[1].toInt() and 0xf0) == 0xf0
        return when {
            mime.contains("mp4") || mime.contains("quicktime") -> mp4
            mime.contains("webm") || mime.contains("matroska") -> webm
            mime.contains("mp2t") -> ts
            mime.contains("mpeg") -> mp3 || ts
            mime.contains("aac") -> aac
            mime.contains("ogg") || mime.contains("opus") -> ogg
            else -> mp4 || webm || ts || mp3 || aac || ogg
        }
    }

    fun check(info: MediaInfo, file: File, mime: String) {
        if (!file.exists() || file.length() <= 0L)
            throw IOException("Видео не скачалось: получен пустой файл")
        val header = ByteArray(128)
        val count = file.inputStream().use { it.read(header) }
        if (count <= 0 || !recognizable(header.copyOf(count), mime)) {
            throw IOException(
                "Вместо видео получен другой файл (страница, служебный ответ " +
                "или недопустимый видеоконтейнер). Скачивание не завершено."
            )
        }
        val expected = info.expectedDurationSeconds
        if (expected > 10 && mime.startsWith("video/")) {
            val actual = try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.div(1000L)
                } finally { retriever.release() }
            } catch (_: Exception) { null }
            if (actual != null && actual > 0L && actual.toDouble() < expected * 0.70) {
                throw IOException("Загружено только " + actual + " сек. из ожидаемых " +
                    expected + " сек. Ссылка ведёт на неполную версию видео.")
            }
        }
    }
}
