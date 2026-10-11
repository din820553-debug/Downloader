package com.dindownloader.app

import java.util.Locale

object SizeText {
    fun format(bytes: Long): String {
        if (bytes < 0) return "0 Б"
        val value = bytes.toDouble()
        return when {
            bytes >= 1024L * 1024L * 1024L ->
                String.format(Locale.forLanguageTag("ru-RU"), "%.2f ГБ",
                    value / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L ->
                String.format(Locale.forLanguageTag("ru-RU"), "%.1f МБ",
                    value / (1024.0 * 1024.0))
            bytes >= 1024L ->
                String.format(Locale.forLanguageTag("ru-RU"), "%.1f КБ", value / 1024.0)
            else -> "$bytes Б"
        }
    }
}
