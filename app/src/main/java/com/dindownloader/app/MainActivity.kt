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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dindownloader.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AlertDialog
import coil.load
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var ui: ActivityMainBinding
    private val worker = Executors.newSingleThreadExecutor()
    private var previewLink: String? = null
    private var previewTitle: String? = null
    private var previewThumbnail: String? = null
    private var previewSize = 0L
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
        previewThumbnail = null
        previewSize = 0L
        ui.infoThumbnail.visibility = View.GONE
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
        ui.infoThumbnail.visibility = View.GONE
        worker.execute {
            try {
                YtDlpEngine.prepare(applicationContext)
                val info = YoutubeDL.getInstance().getInfo(link)
                val title = info.title ?: "Видео"
                val thumbnail = info.thumbnail?.takeIf { it.startsWith("https://") }
                val size = info.fileSize.takeIf { it > 0 }
                    ?: info.fileSizeApproximate.takeIf { it > 0 } ?: 0L
                runOnUiThread {
                    if (!isDestroyed && url() == link) {
                        previewLink = link
                        previewTitle = title
                        previewThumbnail = thumbnail
                        previewSize = size
                        ui.infoTitle.text = title
                        ui.infoDetails.text = "Источник: ${Uri.parse(link).host}" +
                            if (size > 0) " · примерно ${SizeText.format(size)}" else ""
                        ui.infoThumbnail.visibility = if (thumbnail == null) View.GONE else View.VISIBLE
                        if (thumbnail != null) ui.infoThumbnail.load(thumbnail) {
                            crossfade(true)
                            placeholder(R.drawable.ic_download)
                            error(R.drawable.ic_download)
                        }
                    }
                    ui.infoButton.isEnabled = true
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
        DownloadStore.add(link, title, format, quality, ui.playlistSwitch.isChecked,
            if (previewLink == link) previewThumbnail else null,
            if (previewLink == link) previewSize else 0L)
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

    private fun removeJob(job: DownloadJob) {
        when (job.status) {
            JobStatus.RUNNING -> {
                AlertDialog.Builder(this)
                    .setTitle("Удалить загрузку?")
                    .setMessage("Текущая загрузка будет остановлена и убрана из списка.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ ->
                        startService(Intent(this, DownloadService::class.java)
                            .setAction(DownloadService.ACTION_REMOVE)
                            .putExtra(DownloadService.EXTRA_JOB_ID, job.id))
                    }.show()
            }
            JobStatus.QUEUED -> DownloadStore.remove(job.id)
            JobStatus.COMPLETED -> {
                val legacyWarning = if (job.savedUris.size < job.savedCount)
                    "\n\nВ старых загрузках сохранена ссылка не на все файлы. Остальные можно удалить вручную."
                else ""
                AlertDialog.Builder(this)
                    .setTitle("Удаление загрузки")
                    .setMessage("Удалить только запись из списка или также скачанные файлы?" + legacyWarning)
                    .setNeutralButton("Отмена", null)
                    .setNegativeButton("Только из списка") { _, _ -> DownloadStore.remove(job.id) }
                    .setPositiveButton("Удалить файлы") { _, _ -> deleteFiles(job) }
                    .show()
            }
            JobStatus.FAILED, JobStatus.CANCELLED -> DownloadStore.remove(job.id)
        }
    }

    private fun deleteFiles(job: DownloadJob) {
        worker.execute {
            var failed = 0
            val uris = job.savedUris.ifEmpty { listOfNotNull(job.resultUri) }
            for (value in uris) {
                try {
                    val deleted = contentResolver.delete(Uri.parse(value), null, null)
                    if (deleted == 0) failed++
                } catch (_: Exception) {
                    failed++
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    if (failed == 0) {
                        DownloadStore.remove(job.id)
                        Toast.makeText(this, "Файлы удалены", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Не удалось удалить $failed файл(ов)", Toast.LENGTH_LONG).show()
                    }
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
                setPadding(dp(14), dp(14), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(66)).apply {
                    marginEnd = dp(12)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_input)
                clipToOutline = true
                setImageResource(R.drawable.ic_download)
                contentDescription = "Превью видео"
                if (!job.thumbnailUrl.isNullOrBlank()) {
                    load(job.thumbnailUrl) {
                        crossfade(true)
                        placeholder(R.drawable.ic_download)
                        error(R.drawable.ic_download)
                    }
                }
            }
            header.addView(image)
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            details.addView(TextView(this).apply {
                text = job.title
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, 1)
                maxLines = 2
            })
            val status = when (job.status) {
                JobStatus.QUEUED -> "В очереди"
                JobStatus.RUNNING -> "Скачивание: ${job.progress}%"
                JobStatus.COMPLETED -> "Готово · ${job.savedCount} файлов"
                JobStatus.CANCELLED -> "Отменено"
                JobStatus.FAILED -> "Ошибка"
            }
            details.addView(TextView(this).apply {
                text = "${job.format.uppercase()} · $status"
                textSize = 12f
                setTextColor(Color.parseColor("#A8C1F4"))
                setPadding(0, dp(6), 0, 0)
            })
            header.addView(details)
            box.addView(header)

            val sizeInfo = when {
                job.status == JobStatus.COMPLETED ->
                    "Размер: ${SizeText.format(job.downloadedBytes)}"
                job.status == JobStatus.RUNNING && job.totalBytes > 0 ->
                    "${SizeText.format(job.downloadedBytes)} / ~${SizeText.format(job.totalBytes)}"
                job.status == JobStatus.RUNNING ->
                    "Загружено: ${SizeText.format(job.downloadedBytes)}"
                job.downloadedBytes > 0 -> "Загружено: ${SizeText.format(job.downloadedBytes)}"
                job.totalBytes > 0 -> "Ожидается: ~${SizeText.format(job.totalBytes)}"
                else -> ""
            }
            if (sizeInfo.isNotBlank()) box.addView(TextView(this).apply {
                text = sizeInfo
                textSize = 13f
                setTextColor(Color.parseColor("#E3ECFF"))
                setPadding(0, dp(9), 0, dp(4))
            })
            if (job.status == JobStatus.RUNNING) {
                box.addView(android.widget.ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = job.progress <= 0
                    progress = job.progress
                    layoutParams = LinearLayout.LayoutParams(-1, dp(7)).apply {
                        topMargin = dp(7)
                        bottomMargin = dp(4)
                    }
                })
            }
            if (job.status == JobStatus.FAILED && !job.error.isNullOrBlank()) {
                box.addView(TextView(this).apply {
                    text = job.error
                    textSize = 11f
                    maxLines = 3
                    setTextColor(Color.parseColor("#F1B6BD"))
                })
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            actions.addView(MaterialButton(this).apply {
                text = when (job.status) {
                    JobStatus.RUNNING, JobStatus.QUEUED -> "Отменить"
                    JobStatus.COMPLETED -> "Открыть"
                    else -> "Повторить"
                }
                isAllCaps = false
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#2D5196"))
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener { action(job) }
            })
            actions.addView(MaterialButton(this).apply {
                text = "Удалить"
                isAllCaps = false
                setTextColor(Color.parseColor("#FFBAC3"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#403042"))
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                setOnClickListener { removeJob(job) }
            })
            box.addView(actions)
            ui.historyContainer.addView(box)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
