package com.dindownloader.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONTokener
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * User-controlled WebView fallback for pages that reveal their media only after JS runs.
 * Does not inspect private content or bypass protected streams. Only records media URLs
 * used by this WebView while the screen is open.
 */
class BrowserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PAGE = "page"
        const val EXTRA_MEDIA = "media"
    }

    private val found = LinkedHashSet<String>()
    private lateinit var web: WebView
    private lateinit var info: TextView
    private lateinit var choose: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_PAGE) ?: ""
        if (!start.startsWith("https://") && !start.startsWith("http://")) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 32))
        }
        info = TextView(this).apply {
            text = "Открой видео и нажми воспроизведение. Перехвачено потоков: 0"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        root.addView(info)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        choose = MaterialButton(this).apply {
            text = "Использовать поток"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { chooseStream() }
        }
        row.addView(choose, LinearLayout.LayoutParams(0, dp(50), 2f))
        row.addView(MaterialButton(this).apply {
            text = "Найти"
            isAllCaps = false
            setOnClickListener { scanDocument() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        val close = MaterialButton(this).apply {
            text = "Закрыть"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        row.addView(close, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(row)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "http" && scheme != "https"
                }
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    maybeRecord(request.url.toString())
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    scanDocument()
                }
                override fun onLoadResource(view: WebView, url: String) {
                    maybeRecord(url)
                    super.onLoadResource(view, url)
                }
            }
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(web)
        setContentView(root)
        web.loadUrl(start)
    }

    private fun scanDocument() {
        if (!::web.isInitialized) return
        web.evaluateJavascript("""
            (function() {
              var urls = [];
              document.querySelectorAll('video,source').forEach(function(el) {
                if (el.src && el.src.indexOf('blob:') !== 0) urls.push(el.src);
              });
              try {
                performance.getEntriesByType('resource').forEach(function(e) {
                  if (e.name) urls.push(e.name);
                });
              } catch(e) {}
              return JSON.stringify(urls);
            })();
        """.trimIndent()) { raw ->
            try {
                val decoded = JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                val links = JSONArray(decoded)
                for (i in 0 until links.length()) {
                    links.optString(i).takeIf(String::isNotBlank)?.let(::maybeRecord)
                }
            } catch (_: Exception) {}
        }
    }

    private fun maybeRecord(url: String) {
        val lower = url.lowercase()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val segment = android.net.Uri.parse(url).path.orEmpty().lowercase()
        if (!segment.endsWith(".mp4") && !segment.endsWith(".m3u8") &&
            !segment.endsWith(".webm") && !segment.endsWith(".m4a") &&
            !segment.endsWith(".mp3") && !segment.endsWith(".mov") &&
            !lower.contains("manifest.m3u8") &&
            !lower.contains("playlist.m3u8")) return
        synchronized(found) {
            if (found.size < 80) found.add(url)
        }
        runOnUiThread {
            if (!isDestroyed) {
                info.text = "Найдено медиассылок: " + synchronized(found) { found.size } +
                    ". Выбери поток после запуска видео."
                choose.isEnabled = found.isNotEmpty()
            }
        }
    }

    private fun chooseStream() {
        val links = synchronized(found) { found.toList().asReversed() }
        if (links.isEmpty()) return
        val labels = links.map { link ->
            val uri = android.net.Uri.parse(link)
            (uri.host ?: "Медиа") + " · " + (uri.path?.takeLast(75) ?: "файл")
        }
        AlertDialog.Builder(this)
            .setTitle("Выбрать обнаруженный поток")
            .setItems(labels.toTypedArray()) { _, selected ->
                val originalPage = web.url ?: intent.getStringExtra(EXTRA_PAGE)
                setResult(Activity.RESULT_OK, Intent()
                    .putExtra(EXTRA_PAGE, originalPage)
                    .putExtra(EXTRA_MEDIA, links[selected]))
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density + .5f).toInt()

    @Deprecated("Legacy Activity callback; Android WebView maintains back navigation.")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }
}
