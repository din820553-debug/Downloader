package com.dindownloader.app

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

data class TransferResult(val file: File, val mime: String)

class NativeTransfer {
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun interrupt() {
        cancelled = true
        active?.disconnect()
    }

    private fun check() {
        if (cancelled) throw CancellationException("Приостановлено")
    }

    fun transfer(
        media: MediaInfo, quality: Int, folder: File,
        progress: (Long, Long, Int) -> Unit
    ): TransferResult {
        folder.mkdirs()
        check()
        return if (media.kind == "hls") hls(media, quality, folder, progress)
            else direct(media, folder, progress)
    }

    private fun direct(
        media: MediaInfo, folder: File,
        progress: (Long, Long, Int) -> Unit
    ): TransferResult {
        val partial = File(folder, "media.part")
        val meta = File(folder, "media.resume.json")
        val saved = try { JSONObject(meta.readText()) } catch (_: Exception) { JSONObject() }
        if (saved.optString("source") != media.originalUrl ||
            (saved.has("mediaUrl") && saved.optString("mediaUrl") != media.mediaUrl)) {
            partial.delete()
            meta.delete()
        }
        var offset = partial.length()
        var c = NativeEngine.http(media.mediaUrl, referer = media.referer,
            range = if (offset > 0) "bytes=$offset-" else null)
        active = c
        try {
            if (offset > 0 && saved.optString("etag").isNotBlank()) {
                c.setRequestProperty("If-Range", saved.getString("etag"))
            }
            var response = c.responseCode
            if (response == 416) {
                val previousSize = saved.optLong("total")
                if (previousSize > 0 && previousSize == offset) {
                    progress(offset, previousSize, 100)
                    return TransferResult(partial, media.mime)
                }
                c.disconnect()
                partial.delete()
                meta.delete()
                offset = 0
                c = NativeEngine.http(media.mediaUrl, referer = media.referer)
                active = c
                response = c.responseCode
            }
            if (response !in 200..299) NativeEngine.errorHttp(c)
            if (c.contentType?.contains("text/html", ignoreCase = true) == true) {
                throw IOException("Сайт прислал HTML вместо видео. Повтори через встроенный браузер")
            }
            if (response == 206) {
                val first = Regex("""bytes (\d+)-""").find(
                    c.getHeaderField("Content-Range").orEmpty()
                )?.groupValues?.get(1)?.toLongOrNull()
                if (first != offset) throw IOException("Некорректный ответ сервера на докачку")
            } else if (offset > 0L) {
                // No range support: restart rather than join unrelated bytes.
                offset = 0L
            }
            val total = if (response == 206) {
                Regex("""/(\d+)""").find(c.getHeaderField("Content-Range").orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull()
                    ?: c.contentLengthLong.takeIf { it > 0 }?.plus(offset) ?: 0L
            } else c.contentLengthLong.coerceAtLeast(0L)
            val tag = c.getHeaderField("ETag")
            JSONObject().apply {
                put("source", media.originalUrl)
                put("mediaUrl", media.mediaUrl)
                put("total", total)
                if (!tag.isNullOrBlank()) put("etag", tag)
            }.let { meta.writeText(it.toString()) }
            var current = offset
            var last = 0L
            progress(current, total, if (total > 0) (current * 100 / total).toInt().coerceIn(0,100) else 0)
            c.inputStream.use { input ->
                FileOutputStream(partial, offset > 0).buffered(65536).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        check()
                        val bytes = input.read(buffer)
                        if (bytes == -1) break
                        output.write(buffer, 0, bytes)
                        current += bytes
                        val now = SystemClock.elapsedRealtime()
                        if (now - last > 350) {
                            progress(current, total,
                                if (total > 0) (current * 100 / total).toInt().coerceIn(0,100) else 0)
                            last = now
                        }
                    }
                }
            }
            check()
            if (total > 0 && current != total) throw IOException("Получено $current из $total байт")
            progress(current, if (total > 0) total else current, 100)
            meta.delete()
            return TransferResult(partial, media.mime)
        } finally {
            active = null
            c.disconnect()
        }
    }

    private fun hls(
        media: MediaInfo, quality: Int, folder: File,
        progress: (Long, Long, Int) -> Unit
    ): TransferResult {
        val playlist = NativeEngine.playlist(media, quality)
        val chunks = File(folder, "chunks")
        val signature = playlist.segments.joinToString("\n") { it.url }.hashCode().toString()
        val manifestState = File(folder, "playlist.fingerprint")
        if (manifestState.exists() && manifestState.readText() != signature) chunks.deleteRecursively()
        chunks.mkdirs()
        manifestState.writeText(signature)
        fun file(i: Int): File = File(chunks, i.toString().padStart(7, '0') + ".seg")
        var bytes = playlist.segments.indices.sumOf { i -> file(i).takeIf { it.exists() }?.length() ?: 0L }
        val count = playlist.segments.size
        for ((i, segment) in playlist.segments.withIndex()) {
            check()
            val output = file(i)
            if (output.length() > 0L) {
                progress(bytes, 0, ((i + 1) * 100 / count))
                continue
            }
            val temporary = File(chunks, i.toString().padStart(7, '0') + ".tmp")
            temporary.delete()
            val conn = NativeEngine.http(segment.url, referer = media.referer ?: media.originalUrl)
            active = conn
            try {
                if (conn.responseCode !in 200..299) NativeEngine.errorHttp(conn)
                if (conn.contentType?.contains("text/html", ignoreCase = true) == true) {
                    throw IOException("Сайт вернул страницу вместо видеофрагмента. Открой видео во встроенном браузере")
                }
                val segmentLength = conn.contentLengthLong.coerceAtLeast(0L)
                var segmentDownloaded = 0L
                var lastReport = 0L
                conn.inputStream.use { stream ->
                    FileOutputStream(temporary).buffered(65536).use { dest ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            check()
                            val read = stream.read(buffer)
                            if (read < 0) break
                            dest.write(buffer, 0, read)
                            segmentDownloaded += read
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReport >= 300L || lastReport == 0L) {
                                lastReport = now
                                val percentWithin = if (segmentLength > 0) {
                                    (segmentDownloaded.toDouble() / segmentLength).coerceIn(0.0, 0.999)
                                } else 0.0
                                val currentPercent = ((i + percentWithin) * 100.0 / count)
                                    .toInt().coerceIn(0, 99)
                                progress(bytes + segmentDownloaded, 0, currentPercent)
                            }
                        }
                    }
                }
                check()
                if (temporary.length() == 0L || !temporary.renameTo(output)) {
                    throw IOException("Не удалось записать HLS-фрагмент")
                }
                bytes += output.length()
                progress(bytes, 0, ((i+1)*100/count))
            } finally {
                active = null
                conn.disconnect()
                temporary.delete()
            }
        }
        check()
        val result = File(folder, if (playlist.format == "mp4") "video.mp4" else "video.ts")
        FileOutputStream(result).buffered(65536).use { dest ->
            for (i in playlist.segments.indices) {
                check()
                file(i).inputStream().buffered().use { it.copyTo(dest) }
            }
        }
        check()
        chunks.deleteRecursively()
        manifestState.delete()
        progress(result.length(), result.length(), 100)
        return TransferResult(result,
            if (playlist.format == "mp4") "video/mp4" else "video/mp2t")
    }
}
