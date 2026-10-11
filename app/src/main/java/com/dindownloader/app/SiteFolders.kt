package com.dindownloader.app

import android.net.Uri
import java.net.IDN
import java.util.Locale

object SiteFolders {
    fun forUrl(source: String): String {
        val rawHost = Uri.parse(source).host ?: return "other"
        val ascii = try { IDN.toASCII(rawHost.lowercase(Locale.ROOT)) } catch (_: Exception) { rawHost }
        return ascii.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9.-]"), "_")
            .trim('.', '_', '-').take(64).ifEmpty { "other" }
    }
    fun relativePath(source: String): String =
        "Download/Downloader/" + forUrl(source)
}
