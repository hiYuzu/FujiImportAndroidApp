package com.fujiimport.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(Locale.CHINA, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.CHINA, bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(Locale.CHINA, bytes / (1024.0 * 1024 * 1024))
}

fun dateLabel(millis: Long?): String = millis?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))
} ?: "拍摄日期未知"

fun dateTimeLabel(millis: Long?): String = millis?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA))
} ?: "未知"
