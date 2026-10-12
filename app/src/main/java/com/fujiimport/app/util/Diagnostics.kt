package com.fujiimport.app.util

import android.content.Context
import android.os.Build
import com.fujiimport.app.BuildConfig
import java.io.File
import java.time.Instant
import java.util.ArrayDeque

/** Whitelisted events only: no file names, serials, photos, URIs, or exception messages. */
class Diagnostics {
    private val events = ArrayDeque<String>()

    @Synchronized
    fun event(category: String, error: Throwable? = null) {
        val type = error?.javaClass?.simpleName?.replace(Regex("[^A-Za-z0-9_]"), "")
        events.addLast("${Instant.now()} $category${type?.let { " [$it]" } ?: ""}")
        while (events.size > 250) events.removeFirst()
    }

    @Synchronized
    fun export(context: Context, usbDescription: String): File {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        return File(directory, "fuji-import-diagnostics.txt").apply {
            writeText(buildString {
                appendLine("Fuji Import ${BuildConfig.VERSION_NAME}")
                appendLine("Android API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("USB 描述符（不包含序列号）：")
                appendLine(usbDescription)
                appendLine("事件（不包含文件名、照片、URI 或异常正文）：")
                events.forEach { appendLine(it) }
            })
        }
    }
}
