package com.dindownloader.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File
import java.net.IDN

/**
 * WebView login session bridge for the standalone web downloader.
 * No credentials are collected or copied. Cookies are scoped to the configured
 * website and exported only to a temporary private file, deleted after extraction.
 */
object SiteSessionCookies {
    private const val PREFS = "site_session"

    fun configuredHost(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("host", null)?.takeIf(String::isNotBlank)

    internal fun normalized(host: String): String =
        IDN.toASCII(host.trim().lowercase())
            .removePrefix("www.").removePrefix("m.").removePrefix("mobile.")

    fun allows(context: Context, url: String): Boolean {
        val parsed = Uri.parse(url)
        if (parsed.scheme != "https") return false
        val allowed = configuredHost(context) ?: return false
        val hostname = parsed.host?.let(::normalized) ?: return false
        return hostname == allowed || hostname.endsWith(".$allowed")
    }

    fun configure(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return false
        val hostname = uri.host?.let(::normalized)?.takeIf(String::isNotBlank)
            ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("host", null)
        if (existing != null && existing != hostname) return false
        prefs.edit().putString("host", hostname).apply()
        return true
    }

    fun isSiteApp(context: Context): Boolean =
        context.packageName == "com.dindownloader.sitevideo"

    fun makeTempCookies(context: Context, pageUrl: String): File? {
        if (!isSiteApp(context) || !allows(context, pageUrl)) return null
        val url = Uri.parse(pageUrl)
        val host = url.host ?: return null
        val raw = try { CookieManager.getInstance().getCookie(pageUrl).orEmpty() }
            catch (_: Exception) { "" }
        if (raw.isBlank()) return null

        val data = mutableListOf("# Netscape HTTP Cookie File")
        val domain = "." + normalized(host)
        val expiry = System.currentTimeMillis() / 1000L + 60L * 60L
        for (part in raw.split(';')) {
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            val name = part.substring(0, separator).trim()
            val value = part.substring(separator + 1).trim()
            if (!name.matches(Regex("[a-zA-Z0-9!#$%&'*+.^_\u0060|~-]{1,150}"))) continue
            if (value.any { it == '\n' || it == '\r' || it == '\t' }) continue
            data.add(listOf(domain, "TRUE", "/", "TRUE",
                expiry.toString(), name, value).joinToString("\t"))
        }
        if (data.size <= 1) return null
        val jar = File.createTempFile("site-session-", ".cookies", context.cacheDir)
        jar.setReadable(false, false)
        jar.setWritable(false, false)
        jar.setReadable(true, true)
        jar.setWritable(true, true)
        try {
            jar.writeText(data.joinToString("\n") + "\n")
            return jar
        } catch (e: Exception) {
            jar.delete()
            throw e
        }
    }
}
