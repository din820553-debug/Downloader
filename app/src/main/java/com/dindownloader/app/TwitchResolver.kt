package com.dindownloader.app

import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import java.io.IOException
import java.net.URLEncoder

/** Native Twitch clip and VOD URL resolver. Web GraphQL is undocumented and may change. */
object TwitchResolver {
    private const val CLIENT = "ue6666qo983tsx6so1t0vnawi233wa"
    private const val CLIP_HASH = "2db6a3b20eabf510bd3cf465ae2408834b59eb6b8af89ca73ab1486cacecfb63"
    private const val CLIP_HASH_BACKUP = "36b89d2507fce29e5ca551df756d27c1cfe079e2609642b4390aa4c35796eb11"

    data class Target(val clip: String? = null, val vod: String? = null)

    fun isTwitchUrl(url: String): Boolean {
        val parsed = Uri.parse(url)
        val host = parsed.host?.lowercase()
            ?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("go.")
            ?: return false
        return parsed.scheme in listOf("http", "https") &&
            host in setOf("twitch.tv", "clips.twitch.tv", "player.twitch.tv")
    }

    fun identify(url: String): Target? {
        if (!isTwitchUrl(url)) return null
        val parsed = Uri.parse(url)
        val host = parsed.host!!.lowercase()
            .removePrefix("www.").removePrefix("m.").removePrefix("go.")
        val parts = parsed.pathSegments.filter(String::isNotBlank)

        if (host == "player.twitch.tv") {
            val vod = parsed.getQueryParameter("video")
                ?.removePrefix("v")
                ?.takeIf { it.matches(Regex("[0-9]{5,20}")) }
            return vod?.let { Target(vod = it) }
        }
        if (host == "clips.twitch.tv") {
            val slug = if (parts.firstOrNull() == "embed")
                parsed.getQueryParameter("clip").orEmpty()
            else parts.lastOrNull().orEmpty()
            return slug.takeIf { it.matches(Regex("[A-Za-z0-9_-]{5,140}")) }
                ?.let { Target(clip = it) }
        }

        val clipIndex = parts.indexOf("clip")
        if (clipIndex >= 0) {
            val slug = parts.getOrNull(clipIndex + 1)
            if (slug != null && slug.matches(Regex("[A-Za-z0-9_-]{5,140}")))
                return Target(clip = slug)
        }
        // Both current and legacy Twitch VOD links.
        // /videos/123, /channel/v/123, /channel/video/123, /channel/videos/123
        val vodIndex = parts.indexOfFirst { it in setOf("videos", "video", "v") }
        if (vodIndex >= 0) {
            val id = parts.getOrNull(vodIndex + 1)
            if (id != null && id.matches(Regex("[0-9]{5,20}"))) return Target(vod = id)
        }
        return null
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun gql(request: JSONObject): JSONObject {
        val conn = NativeEngine.http("https://gql.twitch.tv/gql", "POST",
            "https://www.twitch.tv/")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Client-ID", CLIENT)
            conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            val payload = if (request.has("extensions")) JSONArray().put(request).toString()
                else request.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) NativeEngine.errorHttp(conn)
            val raw = JSONTokener(conn.inputStream.bufferedReader().use { it.readText() }).nextValue()
            val json = when (raw) {
                is JSONArray -> raw.optJSONObject(0)
                is JSONObject -> raw
                else -> null
            } ?: throw IOException("Twitch прислал неожиданный ответ")
            if (json.has("errors")) {
                val description = json.optJSONArray("errors")?.optJSONObject(0)
                    ?.optString("message").orEmpty()
                throw IOException("Twitch: " + description)
            }
            return json.optJSONObject("data") ?: throw IOException("Twitch не вернул данные")
        } finally {
            conn.disconnect()
        }
    }

    internal fun clipRequest(slug: String, hash: String = CLIP_HASH) = JSONObject()
        .put("operationName", "ShareClipRenderStatus")
        .put("variables", JSONObject().put("slug", slug))
        .put("extensions", JSONObject().put("persistedQuery",
            JSONObject().put("version", 1).put("sha256Hash", hash)))

    internal fun clipFromData(original: String, data: JSONObject, quality: Int): MediaInfo {
        val clip = data.optJSONObject("clip") ?: throw IOException("Клип Twitch недоступен")
        // Current Twitch GraphQL stores video qualities in clip.assets[0], not at clip root.
        val assets = clip.optJSONArray("assets")
        val links = mutableListOf<Pair<Int, String>>()
        if (assets != null) {
            for (assetIndex in 0 until assets.length()) {
                val formats = assets.optJSONObject(assetIndex)?.optJSONArray("videoQualities") ?: continue
                for (index in 0 until formats.length()) {
                    val item = formats.optJSONObject(index) ?: continue
                    val url = item.optString("sourceURL")
                    if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                    val height = item.optString("quality").filter(Char::isDigit).toIntOrNull() ?: 0
                    links.add(height to url)
                }
            }
        }
        if (links.isEmpty()) {
            val formats = clip.optJSONArray("videoQualities")
            if (formats != null) {
                for (index in 0 until formats.length()) {
                    val item = formats.optJSONObject(index) ?: continue
                    val url = item.optString("sourceURL")
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        links.add((item.optString("quality").filter(Char::isDigit).toIntOrNull() ?: 0) to url)
                    }
                }
            }
        }
        val chosen = links.filter { quality == 0 || it.first <= quality }.ifEmpty { links }
            .maxByOrNull { it.first } ?: throw IOException("Нет доступного MP4 клипа")
        val token = clip.optJSONObject("playbackAccessToken")
        val signature = token?.optString("signature").orEmpty()
        val value = token?.optString("value").orEmpty()
        var url = chosen.second
        if (signature.isNotBlank() && value.isNotBlank() &&
            !url.contains("sig=") && !url.contains("token=")) {
            url += (if (url.contains("?")) "&" else "?") +
                "sig=" + encode(signature) + "&token=" + encode(value)
        }
        return MediaInfo(original, url,
            clip.optString("title").ifBlank { "Twitch clip" },
            thumbnail = clip.optString("thumbnailURL").takeIf { it.startsWith("http") },
            referer = "https://clips.twitch.tv/", mime = "video/mp4")
    }

    internal fun clipDirectRequest(slug: String): JSONObject {
        val safe = slug.replace(Regex("[^A-Za-z0-9_-]"), "")
        val query = """{
            clip(slug: "$safe") {
                title
                thumbnailURL
                playbackAccessToken { signature value }
                assets { videoQualities { quality sourceURL } }
            }
        }""".trimIndent()
        return JSONObject().put("query", query)
    }

    private fun clip(url: String, slug: String, quality: Int): MediaInfo {
        var failure: Exception? = null
        for (hash in listOf(CLIP_HASH, CLIP_HASH_BACKUP)) {
            try {
                return clipFromData(url, gql(clipRequest(slug, hash)), quality)
            } catch (e: Exception) { failure = e }
        }
        try {
            return clipFromData(url, gql(clipDirectRequest(slug)), quality)
        } catch (e: Exception) {
            throw IOException("Не удалось получить клип Twitch: " + e.localizedMessage, e)
        }
    }

    internal fun vodRequest(id: String): JSONObject {
        val query = """{
          videoPlaybackAccessToken(
            id: "$id",
            params: {platform: "web", playerBackend: "mediaplayer", playerType: "site"}
          ) {
            value
            signature
          }
        }""".trimIndent()
        return JSONObject().put("query", query)
    }

    internal fun vodFromData(original: String, id: String, data: JSONObject): MediaInfo {
        val auth = data.optJSONObject("videoPlaybackAccessToken")
            ?: throw IOException("Twitch не предоставил доступ к записи")
        val sig = auth.optString("signature")
        val token = auth.optString("value")
        if (sig.isBlank() || token.isBlank()) throw IOException("Нет токена просмотра Twitch")
        val hls = "https://usher.ttvnw.net/vod/" + id + ".m3u8" +
            "?allow_source=true&allow_audio_only=true&player=twitchweb" +
            "&playlist_include_framerate=true&p=7452391&platform=web" +
            "&sig=" + encode(sig) + "&token=" + encode(token)
        return MediaInfo(original, hls, "Twitch VOD " + id, kind = "hls",
            referer = "https://www.twitch.tv/", mime = "application/x-mpegURL")
    }

    fun resolve(original: String, quality: Int): MediaInfo? {
        val target = identify(original) ?: return null
        return when {
            target.clip != null -> clip(original, target.clip, quality)
            target.vod != null -> vodFromData(original, target.vod, gql(vodRequest(target.vod)))
            else -> null
        }
    }
}
