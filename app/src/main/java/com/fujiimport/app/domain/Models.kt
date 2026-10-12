package com.fujiimport.app.domain

import java.security.MessageDigest
import java.util.Locale

enum class PhotoFormat(val label: String, val mime: String) {
    JPEG("JPG", "image/jpeg"), RAF("RAW", "image/x-fuji-raf"), HEIF("HEIF", "image/heif");

    companion object {
        fun fromFileName(name: String): PhotoFormat? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> JPEG
            "raf" -> RAF
            "heif", "heic", "hif" -> HEIF
            else -> null
        }
    }
}

data class Photo(
    val sessionId: String,
    val cameraKey: String?,
    val cameraModel: String?,
    val storageId: Int,
    val handle: Int,
    val parentHandle: Int,
    val directoryKey: String?,
    val name: String,
    val format: PhotoFormat,
    val size: Long,
    val capturedAt: Long?,
    val modifiedAt: Long?,
    val width: Int?,
    val height: Int?,
    val thumbnailSize: Long,
) {
    val id: String get() = "$sessionId:$storageId:$handle"
    val sourceKey: String get() = sha256(
        listOf(if (cameraKey != null && directoryKey != null) cameraKey else sessionId,
            storageId, directoryKey ?: parentHandle, name, size, capturedAt).joinToString("\u0000")
    )
    val cacheKey: String get() = sha256("$sessionId:$sourceKey:$handle")
}

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class CameraStorage(val id: Int, val label: String, val capacity: Long?, val freeBytes: Long?)

enum class ConnectionPhase {
    DISCONNECTED, DETECTING, PERMISSION, CONNECTING, CONNECTED, DENIED, FAILED, DETACHED
}

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val message: String = "未连接相机",
    val model: String? = null,
    val sessionId: String? = null,
    val storages: List<CameraStorage> = emptyList(),
    val diagnostics: String = "尚未检测 USB 设备",
)

enum class ImportResult { SUCCESS, FAILED, SKIPPED, CANCELLED }

data class ImportRecord(
    val sourceKey: String,
    val originalName: String,
    val size: Long,
    val capturedAt: Long?,
    val importedAt: Long,
    val result: ImportResult,
    val localUri: String?,
    val message: String?,
)

object DuplicatePolicy {
    fun confirmed(photo: Photo, record: ImportRecord, actualSize: Long?): Boolean =
        photo.cameraKey != null && photo.directoryKey != null && photo.capturedAt != null && photo.size > 0 &&
            record.result in setOf(ImportResult.SUCCESS, ImportResult.SKIPPED) &&
            record.localUri != null && record.sourceKey == photo.sourceKey &&
            record.originalName == photo.name && record.capturedAt == photo.capturedAt &&
            record.size == photo.size && actualSize == photo.size
}

object FileNames {
    fun safe(original: String): String {
        val base = original.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").trim().trim('.')
        if (base.isEmpty()) return "照片"
        if (base.length <= 200) return base
        val extension = base.substringAfterLast('.', "").take(12)
        return if (extension.isEmpty()) base.take(200) else base.take(187) + "." + extension
    }

    fun available(original: String, occupied: Set<String>): String {
        val safe = safe(original)
        val names = occupied.map { it.lowercase(Locale.ROOT) }.toHashSet()
        if (safe.lowercase(Locale.ROOT) !in names) return safe
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val extension = if (dot > 0) safe.substring(dot) else ""
        var suffix = 1
        while ("$stem ($suffix)$extension".lowercase(Locale.ROOT) in names) suffix++
        return "$stem ($suffix)$extension"
    }
}

enum class ImportPhase { RUNNING, CANCELLING, FINISHED, CANCELLED }

data class ImportProgress(
    val total: Int,
    val processed: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val cancelled: Int = 0,
    val currentName: String? = null,
    val fileBytes: Long? = null,
    val fileSize: Long? = null,
    val phase: ImportPhase = ImportPhase.RUNNING,
    val errors: List<String> = emptyList(),
) {
    val isRunning: Boolean get() = phase == ImportPhase.RUNNING || phase == ImportPhase.CANCELLING
    val fraction: Float get() {
        if (total == 0) return 1f
        val current = if (fileBytes != null && fileSize != null && fileSize > 0) {
            fileBytes.toDouble().div(fileSize).coerceIn(0.0, 1.0)
        } else 0.0
        return ((processed + current) / total).toFloat().coerceIn(0f, 1f)
    }
}

data class PendingImport(val uri: String, val displayName: String)

class CameraUnavailableException : java.io.IOException("相机连接已失效，请重新连接")
class StorageFullException : java.io.IOException("平板存储空间不足")
