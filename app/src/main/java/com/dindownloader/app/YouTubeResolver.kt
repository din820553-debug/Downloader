package com.dindownloader.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Native YouTube resolver for openly available progressive MP4/WebM video URLs.
 * It deliberately does not return adaptive video-only streams as finished movies.
 * DRM, signatureCipher, n-transform, PO-token and separate A/V muxing are not bypassed.
 */
object YouTubeResolver {
    fun videoId(page: String): String? {
        val uri = Uri.parse(page)
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")
            ?.removePrefix("music.") ?: return null
        val path = uri.pathSegments
        val id = when {
            host == "youtu.be" -> path.firstOrNull()
            host == "youtube.com" || host == "youtube-nocookie.com" -> when {
                path.firstOrNull() == "watch" -> uri.getQueryParameter("v")
                path.firstOrNull() in listOf("shorts", "embed", "live", "v") -> path.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    /** Extract JSON object safely, including nested objects and quoted braces. */
    internal fun jsonObjectFollowing(source: String, marker: String): JSONObject? {
        val at = source.indexOf(marker)
        if (at == -1) return null
        val begin = source.indexOf('{', at + marker.length).takeIf { it >= 0 } ?: return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in begin until minOf(source.length, begin + 4_000_000)) {
            val ch = source[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when(ch) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return runCatching {
                        JSONObject(source.substring(begin,index+1))
                    }.getOrNull()
                }
            }
        }
        return null
    }

    internal fun select(player: JSONObject, original: String, quality: Int): MediaInfo {
        val play = player.optJSONObject("playabilityStatus")
        val status = play?.optString("status")
        if (status != null && status != "OK") {
            throw IOException("YouTube: " + play.optString("reason")
                .ifBlank { "Видео недоступно: $status" })
        }
        val details = player.optJSONObject("videoDetails")
        val title = details?.optString("title").orEmpty().ifBlank { "YouTube видео" }
        val duration = details?.optLong("lengthSeconds", 0L) ?: 0L
        val thumb = details?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")?.let { thumbnails ->
                thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url")
            }
        val formats = player.optJSONObject("streamingData")
            ?: throw IOException("YouTube не предоставил данные воспроизведения")
        val options = formats.optJSONArray("formats") ?: JSONArray()
        val compatible = mutableListOf<Triple<Int, Long, JSONObject>>()
        var hasCiphered = false
        for (i in 0 until options.length()) {
            val item = options.optJSONObject(i) ?: continue
            if (item.has("signatureCipher") || item.has("cipher")) hasCiphered = true
            val link = item.optString("url")
            val mime = item.optString("mimeType")
            if (!link.startsWith("https://") || !mime.startsWith("video/")) continue
            val height = item.optInt("height", 0)
            val bitrate = item.optLong("bitrate", 0L)
            compatible.add(Triple(height, bitrate, item))
        }
        val best = compatible.filter { quality <= 0 || it.first <= quality }
            .ifEmpty { compatible }.maxWithOrNull(
                compareBy<Triple<Int, Long, JSONObject>> { it.first }.thenBy { it.second })
        if (best != null) {
            val selected = best.third
            val mime = selected.optString("mimeType").substringBefore(';')
            return MediaInfo(
                originalUrl = original,
                mediaUrl = selected.getString("url"),
                title = title,
                thumbnail = thumb,
                estimatedBytes = selected.optLong("contentLength", 0L),
                referer = "https://www.youtube.com/",
                mime = mime,
                expectedDurationSeconds = duration
            )
        }
        val hls = formats.optString("hlsManifestUrl")
        if (hls.startsWith("https://")) {
            return MediaInfo(original, hls, title, thumb, kind = "hls",
                referer = "https://www.youtube.com/",
                mime = "application/x-mpegURL", expectedDurationSeconds = duration)
        }
        throw IOException(if (hasCiphered)
            "YouTube защищает адрес видео динамической подписью. Открытый MP4 недоступен"
        else
            "YouTube предоставляет только отдельные видео/аудиодорожки или защищённый поток; нативное объединение пока недоступно")
    }

    fun resolve(page: String, quality: Int): MediaInfo {
        val id = videoId(page) ?: throw IOException("Неизвестная ссылка YouTube")
        val watch = "https://www.youtube.com/watch?v=$id"
        val html = NativeEngine.text(watch, maxBytes = 4_000_000)
        val player = jsonObjectFollowing(html, "ytInitialPlayerResponse")
            ?: jsonObjectFollowing(html, "ytInitialPlayerResponse\\\"")
        if (player != null) {
            try { return select(player, page, quality) } catch (e: IOException) {
                // For logged-out viewers, the web player sometimes omits streaming URLs.
                // An unencrypted Android player API request can offer a regular stream.
            }
        }
        val key = Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.getOrNull(1)
            ?: throw IOException("YouTube не выдал ссылку на видео. Попробуй встроенный браузер")
        val request = JSONObject()
            .put("videoId", id)
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "19.09.37")
                .put("androidSdkVersion", 34)
                .put("hl", "ru")))
            .put("contentCheckOk", true)
        val conn = NativeEngine.http("https://www.youtube.com/youtubei/v1/player?key=$key",
            "POST", "https://www.youtube.com/")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) NativeEngine.errorHttp(conn)
            return select(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }),
                page, quality)
        } catch (e: Exception) {
            throw IOException("YouTube: не удалось получить открытый поток. " +
                (e.localizedMessage ?: "Используй встроенный браузер"), e)
        } finally { conn.disconnect() }
    }
}
