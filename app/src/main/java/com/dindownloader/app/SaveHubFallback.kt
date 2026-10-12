package com.dindownloader.app

import android.net.Uri

/**
 * Explicit, opt-in handoff to a public third-party web downloader.
 * Pass only the canonical public watch URL; never pass a WebView session,
 * cookies, auth tokens, headers or an arbitrary page query string.
 *
 * No private or undocumented service APIs are used.
 */
object SaveHubFallback {
    const val WEBSITE = "https://savehub.cc/ru/"

    /** Null for unsupported links. Whitelists the original public video host. */
    fun publicVideoUrl(input: String): String? {
        val uri = runCatching { Uri.parse(input.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != "pornhub.com") return null

        val path = uri.path.orEmpty().lowercase()
        val key = when {
            path == "/view_video.php" || path == "/video/show" ->
                uri.getQueryParameter("viewkey")
            path.startsWith("/embed/") ->
                uri.pathSegments.getOrNull(1)
            else -> null
        } ?: return null

        // The key is a public video ID, NOT a login token.
        if (!key.matches(Regex("[A-Za-z0-9]{5,64}"))) return null
        return "https://www.pornhub.com/view_video.php?viewkey=$key"
    }
}
