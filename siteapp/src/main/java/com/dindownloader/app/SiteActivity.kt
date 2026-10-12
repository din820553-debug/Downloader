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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    private val found = LinkedHashMap<String, OpenVideoStream>()
    private val worker = Executors.newSingleThreadExecutor()
    private val busyUpdate = AtomicBoolean(false)
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
        val updateButton = button("Обновить движок загрузки", "#223654") {
            AlertDialog.Builder(this)
                .setTitle("Обновление экстрактора")
                .setMessage("Загрузить обновление yt-dlp из официального репозитория? " +
                    "Это изменяет исполняемый компонент приложения. Действие добровольное.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Обновить") { _, _ ->
                    if (!busyUpdate.compareAndSet(false, true)) return@setPositiveButton
                    updateButtonStart()
                }.show()
        }
        root.addView(updateButton, LinearLayout.LayoutParams(-1, dp(44)))

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

    private fun updateButtonStart() {
        status.text = "Обновляем движок по твоему запросу…"
        worker.execute {
            val failure = runCatching {
                LegacyYtDlpEngine.updateExtractor(applicationContext)
            }.exceptionOrNull()
            runOnUiThread {
                busyUpdate.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = if (failure == null)
                    "Экстрактор обновлён. Можешь повторить скачивание."
                else "Не удалось обновить: " + failure.localizedMessage.orEmpty().take(200)
            }
        }
    }

    private fun rememberMedia(url: String, quality: Int = 0, label: String = "") {
        if (!OpenVideoStreams.isMediaRequest(url)) return
        synchronized(found) {
            if (found.size < 150 && !found.containsKey(url))
                found[url] = OpenVideoStream(url, quality, label)
        }
    }

    private fun scanMedia() {
        status.text = "Ищем варианты качества в загруженном плеере…"
        browser.evaluateJavascript("""
            (function(){
              var out=[], seen={};
              function add(url,quality,label){
                try {
                  if (!url || typeof url !== "string" || url.slice(0,5)==="blob:") return;
                  url=new URL(url,location.href).href;
                  if (url.slice(0,8)!=="https://") return;
                  if (seen[url]) return;
                  seen[url]=true;
                  out.push({url:url,quality:parseInt(quality,10)||0,label:label||""});
                } catch(e){}
              }
              function definitions(config){
                if(!config || typeof config !== "object") return;
                var values=config.mediaDefinitions;
                if(Array.isArray(values)) values.forEach(function(e){
                    if(e && e.videoUrl) add(e.videoUrl,e.quality,"Плеер");
                });
              }
              try {
                Object.keys(window).filter(function(k){return /^flashvars_[0-9]+$/.test(k);})
                   .slice(0,10).forEach(function(k){definitions(window[k]);});
              } catch(e){}
              try {
                document.querySelectorAll("video,source").forEach(function(el){
                  add(el.currentSrc,el.getAttribute("height"),"Видео");
                  add(el.src,el.getAttribute("height"),"Видео");
                });
              }catch(e){}
              try {
                document.querySelectorAll("script:not([src])").forEach(function(el){
                  var txt=el.textContent||"";
                  if(txt.length>1500000) return;
                  var re=/["']videoUrl["']\s*:\s*["']((?:\\.|[^"'\\])+)["']/g;
                  var match, count=0;
                  while((match=re.exec(txt)) && count++<80){
                     var raw=match[1].replace(/\\\//g,"/").replace(/\\u002[fF]/g,"/");
                     var tail=txt.slice(match.index,match.index+150);
                     var qual=tail.match(/["']quality["']\s*:\s*["']?(\d+)/);
                     add(raw,qual?qual[1]:0,"Скрипт");
                  }
                });
              } catch(e){}
              try{
                performance.getEntriesByType("resource").forEach(function(r){
                  if(r.name && /\.(mp4|m3u8|webm|mpd)(?:$|\?)/i.test(r.name))
                    add(r.name,0,"Запрос");
                });
              }catch(e){}
              return JSON.stringify(out.slice(0,150));
            })();
        """.trimIndent()) { encoded ->
            val page = videoPage() ?: return@evaluateJavascript
            val discovered = try {
                val raw = JSONTokener(encoded).nextValue() as? String ?: "[]"
                OpenVideoStreams.fromBrowserResult(raw, page)
            } catch (_: Exception) { emptyList() }
            synchronized(found) {
                for (stream in discovered) {
                    if (found.size < 150 && !found.containsKey(stream.url))
                        found[stream.url] = stream
                }
            }
            val endpoints = synchronized(found) {
                found.values.filter { OpenVideoStreams.isQualityEndpoint(it.url) }
                    .take(4)
            }
            if (endpoints.isEmpty()) {
                showStreams(page)
            } else {
                status.text = "Получаем доступные варианты качества…"
                worker.execute {
                    val variants = mutableListOf<OpenVideoStream>()
                    for (source in endpoints) {
                        try {
                            val json = NativeEngine.text(source.url, page, 750_000)
                            variants.addAll(OpenVideoStreams.fromQualityApi(json, source.url))
                        } catch (_: Exception) { /* Keep already discovered streams. */ }
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        synchronized(found) {
                            for (stream in variants)
                                if (found.size < 180) found[stream.url] = stream
                        }
                        showStreams(page)
                    }
                }
            }
        }
    }

    private fun showStreams(page: String) {
        val streams = synchronized(found) {
            found.values.filterNot { OpenVideoStreams.isQualityEndpoint(it.url) }
                .sortedWith(compareByDescending<OpenVideoStream> { it.quality })
        }
        if (streams.isEmpty()) {
            status.text = "Прямые ссылки не найдены. Попробуй запустить видео или обновить движок."
            return
        }
        status.text = "Найдено открытых потоков: " + streams.size
        val rows = streams.map {
            val format = when {
                it.url.contains(".m3u8", true) -> "HLS"
                it.url.contains(".mpd", true) -> "DASH"
                it.url.contains(".mp4", true) -> "MP4"
                else -> "Медиа"
            }
            (if (it.quality > 0) it.quality.toString() + "p" else "Качество неизвестно") +
                " · " + format + " · " + (Uri.parse(it.url).host ?: "источник")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Выбери качество")
            .setItems(rows) { _, selected ->
                val stream = streams[selected]
                AlertDialog.Builder(this).setTitle("Способ скачивания")
                    .setItems(arrayOf("yt-dlp + FFmpeg (рекомендуется)",
                        "Прямая загрузка Kotlin")) { _, option ->
                        val selectedEngine = if (option == 0) "legacy" else "native"
                        val item = DownloadStore.add(page, browser.title ?: "Видео",
                            "auto", stream.quality, false, directMediaUrl = stream.url,
                            engine = selectedEngine)
                        jobId = item.id
                        status.text = "Начинаем скачивание · " + rows[selected]
                        startDownloads()
                    }.setNegativeButton("Отмена", null).show()
            }.setNegativeButton("Отмена", null).show()
    }

    @Deprecated("WebView back navigation")
    override fun onBackPressed() {
        if (browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        browser.stopLoading()
        browser.destroy()
        worker.shutdownNow()
        super.onDestroy()
    }
}
