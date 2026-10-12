package com.dindownloader.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONTokener

/**
 * Standalone single-site browser. Passwords remain inside the actual site's
 * WebView login form; the application never reads form input or logs cookies.
 */
class SiteActivity : AppCompatActivity() {
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var session: TextView
    private val found = LinkedHashSet<String>()
    private var jobId: String? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun message(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun button(t: String, color: String, click: () -> Unit) =
        MaterialButton(this).apply {
            text = t
            isAllCaps = false
            cornerRadius = dp(12)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
            setOnClickListener { click() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        DownloadStore.init(applicationContext)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 32))
            setPadding(dp(12), dp(16), dp(12), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "VideoSpace"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Отдельный браузер для видео · вход и загрузки"
            textSize = 12f
            setTextColor(Color.LTGRAY)
        })
        address = EditText(this).apply {
            hint = "Вставь HTTPS-ссылку на страницу видео"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(getSharedPreferences("site_session", MODE_PRIVATE).getString("lastPage", ""))
        }
        root.addView(address, LinearLayout.LayoutParams(-1, dp(56)))
        val navigation = LinearLayout(this)
        navigation.addView(button("Открыть", "#3057A4") { openPage() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        navigation.addView(button("Войти на сайте", "#294463") { loginPage() },
            LinearLayout.LayoutParams(0, dp(48), 1.3f))
        root.addView(navigation)
        val actions = LinearLayout(this)
        actions.addView(button("Скачать видео", "#426CE6") { downloadPage() },
            LinearLayout.LayoutParams(0, dp(48), 1.3f))
        actions.addView(button("Найти поток", "#294463") { scanMedia() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions)

        session = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), dp(5), 0, dp(4))
        }
        root.addView(session)
        status = TextView(this).apply {
            text = "Открой видео. При необходимости войди в аккаунт на сайте."
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        root.addView(status)
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme != "https") {
                        if (request.isForMainFrame) runOnUiThread { message("Только защищённые HTTPS-страницы") }
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    if (SiteSessionCookies.allows(this@SiteActivity, url)) {
                        address.setText(url)
                        getSharedPreferences("site_session", MODE_PRIVATE).edit()
                            .putString("lastPage", url).apply()
                    }
                    updateSession()
                }
                override fun onLoadResource(view: WebView, url: String) {
                    rememberMedia(url)
                    super.onLoadResource(view, url)
                }
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    rememberMedia(request.url.toString())
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateSession()
        DownloadStore.changes.observe(this) { jobs ->
            val job = jobs.firstOrNull { it.id == jobId } ?: return@observe
            val amount = SizeText.format(job.downloadedBytes)
            status.text = when (job.status) {
                JobStatus.QUEUED -> "В очереди · yt-dlp + FFmpeg"
                JobStatus.RUNNING -> "Загружено $amount · " + job.progress + "%"
                JobStatus.COMPLETED -> "Готово · $amount · папка Загрузки / Downloader"
                JobStatus.FAILED -> "Ошибка: " + job.error.orEmpty().take(240)
                JobStatus.PAUSED -> "Пауза · $amount"
                JobStatus.CANCELLED -> "Отменено"
            }
        }
        getSharedPreferences("site_session", MODE_PRIVATE)
            .getString("lastPage", null)?.takeIf { SiteSessionCookies.allows(this, it) }
            ?.let(browser::loadUrl)
        if (intent?.action == Intent.ACTION_SEND) {
            val txt = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Regex("https://[^\\s]+").find(txt)?.value?.let { address.setText(it); openPage() }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 171)
    }

    private fun updateSession() {
        val host = SiteSessionCookies.configuredHost(this)
        val url = browser.url?.takeIf { SiteSessionCookies.allows(this, it) }
            ?: host?.let { "https://$it/" }
        val hasCookies = if (url == null) false else try {
            !CookieManager.getInstance().getCookie(url).isNullOrBlank()
        } catch (_: Exception) { false }
        session.text = if (host == null) "Сайт ещё не выбран" else
            "Сайт: $host · cookies: " +
                (if (hasCookies) "есть (вход проверь на странице)" else "нет")
    }

    private fun openPage() {
        val link = address.text.toString().trim()
        val uri = Uri.parse(link)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
            message("Вставь полную HTTPS-ссылку")
            return
        }
        if (!SiteSessionCookies.configure(this, link) ||
            !SiteSessionCookies.allows(this, link)) {
            message("Приложение уже привязано к другому сайту")
            return
        }
        getSharedPreferences("site_session", MODE_PRIVATE).edit()
            .putString("lastPage", link).apply()
        status.text = "Открываем видео…"
        browser.loadUrl(link)
        updateSession()
    }

    private fun loginPage() {
        val host = SiteSessionCookies.configuredHost(this)
        if (host == null) {
            message("Сначала открой ссылку нужного сайта")
            return
        }
        status.text = "Войди на сайте обычным способом. Приложение не запрашивает пароль."
        browser.loadUrl("https://$host/")
    }

    private fun videoPage(): String? {
        val url = browser.url?.takeIf { SiteSessionCookies.allows(this, it) }
            ?: address.text.toString().trim().takeIf { SiteSessionCookies.allows(this, it) }
        if (url == null) message("Открой страницу ролика на выбранном сайте")
        return url
    }

    private fun downloadPage() {
        val page = videoPage() ?: return
        val title = browser.title?.takeIf(String::isNotBlank)?.take(100) ?: "Видео"
        val job = DownloadStore.add(page, title, "auto", 0, false, engine = "legacy")
        jobId = job.id
        status.text = "Добавлено в очередь: $title"
        startDownloads()
    }

    private fun startDownloads() {
        ContextCompat.startForegroundService(this,
            Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_START))
    }

    private fun rememberMedia(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme != "https") return
        val path = uri.path.orEmpty().lowercase()
        if (path.endsWith(".mp4") || path.endsWith(".m3u8") ||
            path.endsWith(".webm") || path.endsWith(".m4v")) {
            synchronized(found) { if (found.size < 100) found.add(url) }
        }
    }

    private fun scanMedia() {
        browser.evaluateJavascript("""
            (function() {
              var found=[];
              document.querySelectorAll('video,source').forEach(function(el) {
                [el.src,el.currentSrc].forEach(function(url) {
                  if(url && url.indexOf('blob:') !== 0) found.push(url);
                });
              });
              try { performance.getEntriesByType('resource').forEach(function(r) {
                if(r.name) found.push(r.name);
              }); } catch(e) {}
              return JSON.stringify(found.slice(-120));
            })();
        """.trimIndent()) { result ->
            try {
                val str = JSONTokener(result).nextValue() as? String ?: return@evaluateJavascript
                val arr = JSONArray(str)
                for (i in 0 until arr.length()) rememberMedia(arr.optString(i))
            } catch (_: Exception) {}
            val links = synchronized(found) { found.toList() }
            if (links.isEmpty()) {
                message("Включи видео кнопкой Play или нажми «Скачать видео»")
                return@evaluateJavascript
            }
            AlertDialog.Builder(this).setTitle("Найденные открытые потоки")
                .setItems(links.map { (Uri.parse(it).host ?: "Медиа") + " · " + it.takeLast(54) }
                    .toTypedArray()) { _, chosen ->
                    val page = videoPage() ?: return@setItems
                    val job = DownloadStore.add(page, browser.title ?: "Видео",
                        "auto", 0, false, directMediaUrl = links[chosen], engine = "native")
                    jobId = job.id
                    startDownloads()
                }.setNegativeButton("Отмена", null).show()
        }
    }

    @Deprecated("WebView back navigation")
    override fun onBackPressed() {
        if (browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        browser.stopLoading()
        browser.destroy()
        super.onDestroy()
    }
}
