package com.dindownloader.app

import android.content.Context
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class JobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
}

data class DownloadJob(
    val id: String,
    val url: String,
    val title: String,
    val format: String,
    val quality: Int,
    val playlist: Boolean,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Int = 0,
    val etaSeconds: Long = -1,
    val savedCount: Int = 0,
    val resultUri: String? = null,
    val error: String? = null
)

/**
 * Small durable download history. Work is run by DownloadService, not the Activity.
 * In-progress jobs are marked interrupted when the Android process has been killed.
 */
object DownloadStore {
    val changes = MutableLiveData<List<DownloadJob>>(emptyList())

    private val records = mutableListOf<DownloadJob>()
    private var initialized = false
    private lateinit var app: Context

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        val raw = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
            .getString("jobs", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                val original = runCatching {
                    JobStatus.valueOf(json.optString("status", "FAILED"))
                }.getOrDefault(JobStatus.FAILED)
                val status = if (original == JobStatus.RUNNING || original == JobStatus.QUEUED) {
                    JobStatus.FAILED
                } else original
                records.add(
                    DownloadJob(
                        id = json.getString("id"),
                        url = json.getString("url"),
                        title = json.optString("title", "Видео"),
                        format = json.optString("format", "mp4"),
                        quality = json.optInt("quality", 0),
                        playlist = json.optBoolean("playlist"),
                        status = status,
                        progress = if (status == JobStatus.COMPLETED) 100 else json.optInt("progress"),
                        savedCount = json.optInt("savedCount"),
                        resultUri = json.optString("resultUri").takeIf { it.isNotBlank() },
                        error = if (original == JobStatus.RUNNING || original == JobStatus.QUEUED) {
                            "Загрузка прервана системой"
                        } else json.optString("error").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (_: Exception) {
            records.clear()
        }
        initialized = true
        emit()
    }

    @Synchronized
    fun add(url: String, title: String, format: String, quality: Int, playlist: Boolean): DownloadJob {
        val job = DownloadJob(UUID.randomUUID().toString(), url, title, format, quality, playlist)
        records.add(0, job)
        while (records.size > 60) records.removeAt(records.lastIndex)
        persist()
        emit()
        return job
    }

    @Synchronized
    fun queued(): DownloadJob? = records.lastOrNull { it.status == JobStatus.QUEUED }

    @Synchronized
    fun update(id: String, save: Boolean = true, modify: (DownloadJob) -> DownloadJob) {
        val position = records.indexOfFirst { it.id == id }
        if (position == -1) return
        records[position] = modify(records[position])
        if (save) persist()
        emit()
    }

    private fun emit() {
        changes.postValue(records.toList())
    }

    private fun persist() {
        val array = JSONArray()
        for (job in records) {
            val data = JSONObject()
            data.put("id", job.id)
            data.put("url", job.url)
            data.put("title", job.title)
            data.put("format", job.format)
            data.put("quality", job.quality)
            data.put("playlist", job.playlist)
            data.put("status", job.status.name)
            data.put("progress", job.progress)
            data.put("savedCount", job.savedCount)
            data.put("resultUri", job.resultUri ?: "")
            data.put("error", job.error ?: "")
            array.put(data)
        }
        app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
            .edit().putString("jobs", array.toString()).apply()
    }
}
