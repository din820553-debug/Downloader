package com.dindownloader.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Keep yt-dlp fresher than the version bundled with the APK.
 * Failure to check GitHub should never prevent an offline-bundled extractor from running.
 */
object YtDlpEngine {
    @Synchronized
    fun prepare(context: Context) {
        val app = context.applicationContext
        val engine = YoutubeDL.getInstance()
        engine.init(app)
        val prefs = app.getSharedPreferences("engine_update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_attempt", 0L)
        if (now - last > 24L * 60 * 60 * 1000) {
            prefs.edit().putLong("last_attempt", now).apply()
            try {
                engine.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            } catch (_: Exception) {
                // Network and GitHub rate limits must not break all downloads.
            }
        }
    }
}
