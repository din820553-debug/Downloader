package com.dindownloader.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Extract open media locations from player definitions or JSON quality endpoints. */
data class OpenVideoStream(
    val url: String,
    val quality: Int = 0,
    val label: String = ""
)

object OpenVideoStreams {
    private fun add(list: MutableList<OpenVideoStream>, base: String, raw: String,
                    quality: Int = 0, label: String = "") {
        val url = NativeEngine.decode(raw)
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return
        val absolute = runCatching { NativeEngine.absolute(base, url) }.getOrNull() ?: return
        val uri = Uri.parse(absolute)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return
        if (list.none { it.url == absolute }) {
            list.add(OpenVideoStream(absolute, quality.coerceAtLeast(0), label))
        }
    }

    fun isMediaRequest(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val path = uri.path.orEmpty().lowercase()
        return listOf(".mp4", ".m3u8", ".webm", ".m4v", ".mpd", ".mov")
            .any { path.endsWith(it) } ||
            path.contains("/video/get_media")
    }

    /** Browsing a homepage cannot be mistaken for opening a video page. */
    fun isVideoPage(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return false
        val path = uri.path.orEmpty().lowercase()
        return isMediaRequest(raw) ||
            (path.contains("view_video") && !uri.getQueryParameter("viewkey").isNullOrBlank()) ||
            (path.contains("/video/show") && !uri.getQueryParameter("viewkey").isNullOrBlank()) ||
            path.startsWith("/embed/") && path.length > 7 ||
            path.startsWith("/watch/") && path.length > 7 ||
            path.startsWith("/videos/") && path.length > 8
    }

    fun isQualityEndpoint(raw: String): Boolean =
        Uri.parse(raw).path.orEmpty().contains("/video/get_media")

    /** Expects page-side metadata: [{url,quality,label},...]. */
    fun fromBrowserResult(json: String, pageUrl: String): List<OpenVideoStream> {
        val result = mutableListOf<OpenVideoStream>()
        val arr = JSONArray(json)
        for (i in 0 until minOf(arr.length(), 200)) {
            val row = arr.optJSONObject(i)
            if (row != null) {
                add(result, pageUrl, row.optString("url"), row.optInt("quality"),
                    row.optString("label"))
            } else {
                add(result, pageUrl, arr.optString(i))
            }
        }
        return result.sortedWith(compareByDescending<OpenVideoStream> { it.quality })
    }

    /** Quality API returns either [{videoUrl,quality}] or {mediaDefinitions:[...]}. */
    fun fromQualityApi(json: String, requestUrl: String): List<OpenVideoStream> {
        val raw = json.trim()
        val arr = if (raw.startsWith("[")) JSONArray(raw)
        else {
            val obj = JSONObject(raw)
            obj.optJSONArray("mediaDefinitions")
                ?: obj.optJSONArray("formats") ?: JSONArray().put(obj)
        }
        val result = mutableListOf<OpenVideoStream>()
        for (i in 0 until minOf(arr.length(), 100)) {
            val row = arr.optJSONObject(i) ?: continue
            val url = row.optString("videoUrl").ifBlank {
                row.optString("url").ifBlank { row.optString("file") }
            }
            val quality = row.optInt("quality",
                row.optString("quality").filter(Char::isDigit).toIntOrNull() ?: 0)
            add(result, requestUrl, url, quality, "API")
        }
        return result.sortedWith(compareByDescending<OpenVideoStream> { it.quality })
    }
}
