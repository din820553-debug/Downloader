package com.dindownloader.app

import android.content.Context
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class JobStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

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
    val error: String? = null,
    val thumbnailUrl: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val savedUris: List<String> = emptyList()
)

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
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val original = runCatching {
                    JobStatus.valueOf(obj.optString("status", "FAILED"))
                }.getOrDefault(JobStatus.FAILED)
                val status = if (original == JobStatus.RUNNING) JobStatus.PAUSED else original
                val uris = mutableListOf<String>()
                val saved = obj.optJSONArray("savedUris")
                if (saved != null) {
                    for (j in 0 until saved.length()) saved.optString(j).takeIf(String::isNotBlank)?.let(uris::add)
                } else {
                    obj.optString("resultUri").takeIf(String::isNotBlank)?.let(uris::add)
                }
                records.add(
                    DownloadJob(
                        id = obj.getString("id"),
                        url = obj.getString("url"),
                        title = obj.optString("title", "Видео"),
                        format = obj.optString("format", "mp4"),
                        quality = obj.optInt("quality", 0),
                        playlist = obj.optBoolean("playlist"),
                        status = status,
                        progress = if (status == JobStatus.COMPLETED) 100 else obj.optInt("progress"),
                        savedCount = obj.optInt("savedCount"),
                        resultUri = obj.optString("resultUri").takeIf(String::isNotBlank),
                        error = if (original == JobStatus.RUNNING) null
                            else obj.optString("error").takeIf(String::isNotBlank),
                        thumbnailUrl = obj.optString("thumbnailUrl").takeIf(String::isNotBlank),
                        downloadedBytes = obj.optLong("downloadedBytes", 0L),
                        totalBytes = obj.optLong("totalBytes", 0L),
                        savedUris = uris
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
    fun add(
        url: String, title: String, format: String, quality: Int,
        playlist: Boolean, thumbnailUrl: String? = null, totalBytes: Long = 0L
    ): DownloadJob {
        val job = DownloadJob(UUID.randomUUID().toString(), url, title, format, quality, playlist,
            thumbnailUrl = thumbnailUrl, totalBytes = totalBytes)
        records.add(0, job)
        while (records.size > 60) records.removeAt(records.lastIndex)
        persist()
        emit()
        return job
    }

    @Synchronized
    fun queued(): DownloadJob? = records.lastOrNull { it.status == JobStatus.QUEUED }

    @Synchronized
    fun resume(id: String): Boolean {
        val index = records.indexOfFirst { it.id == id }
        if (index < 0 || records[index].status != JobStatus.PAUSED) return false
        records[index] = records[index].copy(status = JobStatus.QUEUED, error = null)
        persist()
        emit()
        return true
    }

    @Synchronized
    fun get(id: String): DownloadJob? = records.firstOrNull { it.id == id }

    @Synchronized
    fun remove(id: String): Boolean {
        val result = records.removeAll { it.id == id }
        if (result) {
            persist()
            emit()
        }
        return result
    }

    @Synchronized
    fun update(id: String, save: Boolean = true, modify: (DownloadJob) -> DownloadJob) {
        val index = records.indexOfFirst { it.id == id }
        if (index == -1) return
        records[index] = modify(records[index])
        if (save) persist()
        emit()
    }

    private fun emit() = changes.postValue(records.toList())

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
            data.put("thumbnailUrl", job.thumbnailUrl ?: "")
            data.put("downloadedBytes", job.downloadedBytes)
            data.put("totalBytes", job.totalBytes)
            data.put("savedUris", JSONArray(job.savedUris))
            array.put(data)
        }
        app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
            .edit().putString("jobs", array.toString()).apply()
    }
}
