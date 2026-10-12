package com.dindownloader.app

import android.net.Uri
import android.text.Html
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class MediaInfo(
    val originalUrl: String,
    val mediaUrl: String,
    val title: String,
    val thumbnail: String? = null,
    val estimatedBytes: Long = 0L,
    val kind: String = "file",
    val referer: String? = null,
    val mime: String = "video/mp4"
)

data class SegmentInfo(val url: String, val init: Boolean = false)
data class PlaylistInfo(val segments: List<SegmentInfo>, val format: String)

object NativeEngine {
    fun http(url: String, method: String = "GET", referer: String? = null,
             range: String? = null): HttpURLConnection {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Только HTTP(S)-ссылки" }
        val connection = (URL(url).openConnection() as HttpURLConnection)
        connection.connectTimeout = 14000
        connection.readTimeout = 25000
        connection.instanceFollowRedirects = true
        connection.requestMethod = method
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (!referer.isNullOrBlank()) connection.setRequestProperty("Referer", referer)
        if (!range.isNullOrBlank()) connection.setRequestProperty("Range", range)
        try {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Cookie", it)
            }
        } catch (_: Exception) {}
        return connection
    }

    fun text(url: String, referer: String? = null, maxBytes: Int = 2_500_000): String {
        val c = http(url, referer = referer)
        try {
            if (c.responseCode !in 200..299) errorHttp(c)
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val data = ByteArray(32768)
                while (true) {
                    val size = input.read(data)
                    if (size < 0) break
                    if (out.size() + size > maxBytes) throw IOException("Ответ слишком большой")
                    out.write(data, 0, size)
                }
            }
            return out.toString("UTF-8")
        } finally { c.disconnect() }
    }

    fun errorHttp(c: HttpURLConnection): Nothing {
        val code = c.responseCode
        val error = when(code) {
            401 -> "Нужен вход в аккаунт"
            403 -> "Доступ запрещён (403). Попробуй войти на сайт через встроенный браузер"
            404 -> "Видео или файл не найден (404)"
            429 -> "Сайт ограничил число запросов (429)"
            else -> "Ошибка HTTP $code"
        }
        throw IOException(error)
    }

    fun absolute(base: String, path: String): String =
        URL(URL(base), decode(path)).toString()

    fun decode(value: String): String {
        val result = value.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("&#38;", "&")
        return result.trim().trim('"', '\'', ' ')
    }

    private fun cleanTitle(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim().take(180)

    private fun readHead(url: String, referer: String?): Pair<String, Long> {
        // Some video CDNs reject HEAD but accept GET with a byte-range request.
        val head = http(url, "HEAD", referer)
        try {
            if (head.responseCode in 200..299) {
                val mime = (head.contentType ?: "").lowercase(Locale.ROOT)
                if (mime.isNotBlank()) return mime to head.contentLengthLong.coerceAtLeast(0L)
            }
        } finally { head.disconnect() }
        val get = http(url, "GET", referer, "bytes=0-0")
        try {
            if (get.responseCode !in 200..299) errorHttp(get)
            val mime = (get.contentType ?: "").lowercase(Locale.ROOT)
            val total = Regex("""/(\d+)""")
                .find(get.getHeaderField("Content-Range").orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull()
                ?: get.contentLengthLong.coerceAtLeast(0L)
            return mime to total
        } finally { get.disconnect() }
    }

    fun probe(url: String, quality: Int = 0, referer: String? = null): MediaInfo {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Нужна HTTP(S)-ссылка" }
        if (TwitchResolver.identify(url) != null) {
            return TwitchResolver.resolve(url, quality)
                ?: throw IOException("Не удалось найти медиапоток Twitch")
        }
        val ext = Uri.parse(url).lastPathSegment?.substringBefore('?')?.substringAfterLast('.')?.lowercase() ?: ""
        val (mime, length) = try { readHead(url, referer) } catch (_: Exception) { "" to 0L }
        val hls = ext == "m3u8" || "mpegurl" in mime
        if (hls) {
            return MediaInfo(url, url, fileTitle(url), kind = "hls", mime = "application/x-mpegURL")
        }
        if ((ext in setOf("mp4", "webm", "m4v", "mov", "mp3", "m4a", "aac", "ts", "mkv")
            && !mime.contains("text/html") && !mime.contains("json"))
            || mime.startsWith("video/") || mime.startsWith("audio/")) {
            return MediaInfo(url, url, fileTitle(url), estimatedBytes = length,
                mime = if (mime.startsWith("video/") || mime.startsWith("audio/")) mime
                       else typeFor(ext))
        }
        val page = text(url, referer)
        if (page.trimStart().startsWith("#EXTM3U")) {
            return MediaInfo(url, url, fileTitle(url), kind = "hls", mime = "application/x-mpegURL")
        }
        val title = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.get(1)?.let(::cleanTitle) ?: fileTitle(url)
        val picture = findMeta(page, "og:image")?.let { absolute(url, it) }
        val candidates = extractCandidates(url, page, quality)
        if (candidates.isEmpty()) {
            throw IOException("Прямая ссылка на медиа не найдена. Сайт может требовать вход, JavaScript или защищать поток")
        }
        for (link in candidates.take(15)) {
            try {
                val (kindMime, bytes) = readHead(link, url)
                val fileExt = Uri.parse(link).path?.substringAfterLast('.')?.lowercase() ?: ""
                if (kindMime.contains("text/html")) continue
                if ("mpegurl" in kindMime || fileExt == "m3u8") {
                    return MediaInfo(url, link, title, picture, kind = "hls",
                        referer = url, mime = "application/x-mpegURL")
                }
                if (kindMime.startsWith("video/") || kindMime.startsWith("audio/") ||
                    fileExt in setOf("mp4", "m4v", "webm", "m4a", "mp3", "mov")) {
                    return MediaInfo(url, link, title, picture, bytes,
                        referer = url, mime = if (kindMime.startsWith("video/") ||
                            kindMime.startsWith("audio/")) kindMime else typeFor(fileExt))
                }
                // Some media endpoints do not support HEAD. Keep only obvious media extensions.
                if (fileExt in setOf("mp4", "m4v", "m3u8", "webm", "m4a", "mp3")) {
                    return MediaInfo(url, link, title, picture, bytes,
                        kind = if (fileExt == "m3u8") "hls" else "file",
                        referer = url, mime = typeFor(fileExt))
                }
                // Endpoints may return a JSON list of alternate quality URLs.
                if ("json" in kindMime && link != url) {
                    val nested = text(link, url, 250_000)
                    val nestedLinks = extractCandidates(link, nested, quality)
                    for (candidate in nestedLinks.take(6)) {
                        if (candidate == link) continue
                        val cMime = readHead(candidate, url)
                        if (cMime.first.startsWith("video/") || "mpegurl" in cMime.first ||
                            candidate.contains(".mp4") || candidate.contains(".m3u8")) {
                            return MediaInfo(url, candidate, title, picture, cMime.second,
                                kind = if ("mpegurl" in cMime.first || candidate.contains(".m3u8")) "hls" else "file",
                                referer = url, mime = if ("mpegurl" in cMime.first) "application/x-mpegURL"
                                    else "video/mp4")
                        }
                    }
                }
            } catch (_: IOException) { }
        }
        throw IOException("Сайт не предоставил доступный видеопоток. Открой страницу во встроенном браузере и повтори")
    }

    fun fileTitle(url: String): String =
        Uri.parse(url).lastPathSegment?.substringBefore('?')?.take(100)?.ifBlank { null } ?: "Видео"

    fun typeFor(ext: String): String = when(ext) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }

    fun findMeta(html: String, name: String): String? {
        val escaped = Regex.escape(name)
        val tags = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
        for (match in tags) {
            val tag = match.value
            if (!Regex("""(?:property|name)\s*=\s*["']""" + escaped + """["']""",
                    RegexOption.IGNORE_CASE).containsMatchIn(tag)) continue
            val content = Regex("""content\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            if (!content.isNullOrBlank()) return decode(content)
        }
        return null
    }

    fun extractCandidates(base: String, html: String, quality: Int = 0): List<String> {
        val found = LinkedHashSet<String>()
        fun add(value: String) {
            val link = decode(value)
            if (link.isBlank() || link.startsWith("data:") || link.startsWith("blob:")) return
            try {
                val absolute = absolute(base, link)
                if (absolute.startsWith("https://") || absolute.startsWith("http://")) found.add(absolute)
            } catch (_: Exception) {}
        }
        // Recognize ordinary player JSON definitions and choose requested quality first.
        val definitions = Regex("""["']?mediaDefinitions["']?\s*[:=]\s*(\[[\s\S]{1,100000}?\])""")
            .find(html)?.groupValues?.getOrNull(1)
        if (definitions != null) {
            try {
                val array = JSONArray(definitions)
                val options = mutableListOf<Pair<Int, String>>()
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val location = entry.optString("videoUrl")
                    if (location.isNotBlank()) {
                        options.add(entry.optString("quality").toIntOrNull().let { it ?: 0 } to location)
                    }
                }
                val valid = options.filter { quality == 0 || it.first <= quality || it.first == 0 }
                    .ifEmpty { options }
                valid.sortedByDescending { it.first }.forEach { add(it.second) }
            } catch (_: Exception) {}
        }
        for (tag in listOf("og:video:secure_url","og:video:url","og:video","twitter:player:stream")) {
            findMeta(html, tag)?.let(::add)
        }
        val patterns = listOf(
            Regex("""<(?:source|video)\b[^>]*\bsrc\s*=\s*["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:videoUrl|video_url|videoSrc|hlsUrl|hls_url|streamUrl|contentUrl|file|src)["']\s*:\s*["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|source)\s*:\s*["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:https?:)?\\?/\\?/[^"' ]+\.(?:m3u8|mp4|webm|m4a)(?:\?[^"' <]*)?""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(html).take(50)) {
                val candidate = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                add(candidate)
            }
        }
        // Player JSON-LD <script type="application/ld+json"> sometimes holds direct contentUrl.
        for (m in Regex("""<script[^>]*ld\+json[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .findAll(html).take(6)) {
            try {
                val o = JSONObject(m.groupValues[1])
                o.optString("contentUrl").takeIf(String::isNotBlank)?.let(::add)
            } catch (_: Exception) {}
        }
        return found.toList()
    }

    fun playlist(info: MediaInfo, quality: Int, depth: Int = 0): PlaylistInfo {
        if (depth > 4) throw IOException("Слишком много перенаправлений HLS")
        val data = text(info.mediaUrl, info.referer, 3_000_000)
        if (!data.trimStart().startsWith("#EXTM3U")) throw IOException("Сервер не прислал HLS-плейлист")
        val lines = data.lines().map(String::trim).filter(String::isNotBlank)
        val variants = mutableListOf<Triple<Int,Int,String>>()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = lines[i].substringAfter(':')
                val h = Regex("RESOLUTION=\\d+x(\\d+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bw = Regex("BANDWIDTH=(\\d+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val path = lines.drop(i+1).firstOrNull { !it.startsWith("#") } ?: continue
                variants.add(Triple(h,bw,absolute(info.mediaUrl,path)))
            }
        }
        if (variants.isNotEmpty()) {
            if (lines.any { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") && it.contains("URI=") }) {
                throw IOException("Этот HLS-поток использует отдельное аудио. Объединение дорожек пока не поддерживается")
            }
            val suitable = variants.filter { quality == 0 || it.first == 0 || it.first <= quality }
                .ifEmpty { variants }
            val chosen = suitable.maxWithOrNull(compareBy<Triple<Int,Int,String>> { it.first }
                .thenBy { it.second })!!
            return playlist(info.copy(mediaUrl = chosen.third),quality,depth+1)
        }
        if (lines.any { it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE") }) {
            throw IOException("Этот HLS-поток зашифрован; версия 2.0 поддерживает только открытые медиапотоки")
        }
        if (lines.any { it.startsWith("#EXT-X-DRM") || it.contains("SAMPLE-AES") }) {
            throw IOException("Защищённый поток не поддерживается")
        }
        if (lines.none { it.startsWith("#EXT-X-ENDLIST") }) {
            throw IOException("Прямые эфиры пока не поддерживаются; нужна запись VOD")
        }
        val segments = mutableListOf<SegmentInfo>()
        var fmp4 = false
        for (line in lines) {
            if (line.startsWith("#EXT-X-MAP:")) {
                val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                if (uri != null) {
                    segments.add(SegmentInfo(absolute(info.mediaUrl,uri), true))
                    fmp4 = true
                }
            } else if (!line.startsWith("#")) {
                segments.add(SegmentInfo(absolute(info.mediaUrl,line)))
            }
        }
        if (segments.isEmpty()) throw IOException("HLS-плейлист без сегментов")
        if (lines.any { it.startsWith("#EXT-X-BYTERANGE:") }) {
            throw IOException("HLS с byte-range сегментами пока не поддерживается")
        }
        return PlaylistInfo(segments, if (fmp4) "mp4" else "ts")
    }
}
