package com.dindownloader.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dindownloader.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var ui: ActivityMainBinding
    private val worker = Executors.newSingleThreadExecutor()
    private var previewLink: String? = null
    private var previewTitle: String? = null
    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        DownloadStore.init(applicationContext)
        DownloadStore.changes.observe(this) { render(it) }
        ui.pasteButton.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip
            val raw = if (clip != null && clip.itemCount > 0)
                clip.getItemAt(0).coerceToText(this).toString() else ""
            ui.urlInput.setText(findLink(raw) ?: raw)
        }
        ui.infoButton.setOnClickListener { preview() }
        ui.startButton.setOnClickListener { enqueue() }
        ui.formatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                ui.qualityBox.visibility = if (position == 0) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        readShared(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShared(intent)
    }

    private fun findLink(text: String): String? =
        Regex("https?://[^\\s]+").find(text)?.value?.trimEnd('.', ',', ')', ']')

    private fun readShared(intent: Intent?) {
        val shared = if (intent?.action == Intent.ACTION_SEND)
            intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val link = shared?.let(::findLink) ?: return
        ui.urlInput.setText(link)
        previewLink = null
        previewTitle = null
        ui.infoCard.visibility = View.GONE
    }

    private fun url(): String? {
        val input = ui.urlInput.text.toString().trim()
        val link = findLink(input) ?: input
        val parsed = Uri.parse(link)
        if ((parsed.scheme != "https" && parsed.scheme != "http") ||
            parsed.host.isNullOrBlank() || link.contains(' ')
        ) {
            ui.urlInput.error = "Укажи ссылку http(s)"
            return null
        }
        return link
    }

    private fun preview() {
        val link = url() ?: return
        ui.infoCard.visibility = View.VISIBLE
        ui.infoTitle.text = "Проверяем ссылку…"
        ui.infoDetails.text = "Подключаемся к источнику"
        ui.infoButton.isEnabled = false
        worker.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                val title = YoutubeDL.getInstance().getInfo(link).title ?: "Видео"
                runOnUiThread {
                    if (!isDestroyed) {
                        previewLink = link
                        previewTitle = title
                        ui.infoTitle.text = title
                        ui.infoDetails.text = "Источник: ${Uri.parse(link).host}"
                        ui.infoButton.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        ui.infoTitle.text = "Не удалось получить информацию"
                        ui.infoDetails.text = e.localizedMessage?.take(160) ?: "Неизвестная ошибка"
                        ui.infoButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun enqueue() {
        val link = url() ?: return
        val format = when (ui.formatSpinner.selectedItemPosition) {
            1 -> "mp3"
            2 -> "m4a"
            else -> "mp4"
        }
        val quality = listOf(0, 1080, 720, 480, 360)
            .getOrElse(ui.qualitySpinner.selectedItemPosition) { 0 }
        val title = if (previewLink == link) previewTitle ?: "Видео"
            else Uri.parse(link).host ?: "Видео"
        DownloadStore.add(link, title, format, quality, ui.playlistSwitch.isChecked)
        startQueue()
        Toast.makeText(this, "Добавлено в очередь", Toast.LENGTH_SHORT).show()
    }

    private fun startQueue() {
        ContextCompat.startForegroundService(
            this, Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_START)
        )
    }

    private fun action(job: DownloadJob) {
        when (job.status) {
            JobStatus.RUNNING, JobStatus.QUEUED -> startService(
                Intent(this, DownloadService::class.java)
                    .setAction(DownloadService.ACTION_CANCEL)
                    .putExtra(DownloadService.EXTRA_JOB_ID, job.id)
            )
            JobStatus.FAILED, JobStatus.CANCELLED -> {
                DownloadStore.add(job.url, job.title, job.format, job.quality, job.playlist)
                startQueue()
            }
            JobStatus.COMPLETED -> {
                val uri = job.resultUri?.let(Uri::parse) ?: return
                val mime = contentResolver.getType(uri) ?: "video/*"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {
                    Toast.makeText(this, "Файл в папке Загрузки/Downloader", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun render(jobs: List<DownloadJob>) {
        ui.historyContainer.removeAllViews()
        ui.emptyView.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
        ui.countText.text = "${jobs.size} задач"
        for (job in jobs) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_row)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            }
            box.addView(TextView(this).apply {
                text = job.title
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, 1)
                maxLines = 2
            })
            val text = when (job.status) {
                JobStatus.QUEUED -> "В очереди"
                JobStatus.RUNNING -> "Скачивание: ${job.progress}%"
                JobStatus.COMPLETED -> "Готово: ${job.savedCount} файлов"
                JobStatus.CANCELLED -> "Отменено"
                JobStatus.FAILED -> "Ошибка: ${job.error ?: "нет описания"}"
            }
            box.addView(TextView(this).apply {
                this.text = "${job.format.uppercase()} · $text"
                textSize = 12f
                setTextColor(Color.parseColor("#A8C1F4"))
                setPadding(0, dp(8), 0, dp(6))
            })
            if (job.status == JobStatus.RUNNING) {
                box.addView(android.widget.ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = job.progress == 0
                    progress = job.progress
                    layoutParams = LinearLayout.LayoutParams(-1, dp(6))
                })
            }
            box.addView(MaterialButton(this).apply {
                text = when (job.status) {
                    JobStatus.QUEUED, JobStatus.RUNNING -> "Отменить"
                    JobStatus.COMPLETED -> "Открыть"
                    else -> "Повторить"
                }
                isAllCaps = false
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#2D5196"))
                cornerRadius = dp(12)
                setOnClickListener { action(job) }
            })
            ui.historyContainer.addView(box)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
