package com.fujiimport.app.data.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import com.fujiimport.app.data.mtp.MtpCamera
import com.fujiimport.app.domain.DuplicatePolicy
import com.fujiimport.app.domain.FileNames
import com.fujiimport.app.domain.ImportResult
import com.fujiimport.app.domain.ImportStore
import com.fujiimport.app.domain.PendingImport
import com.fujiimport.app.domain.Photo
import com.fujiimport.app.domain.PhotoFormat
import com.fujiimport.app.domain.StorageFullException
import com.fujiimport.app.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class MediaStoreWriter(
    private val context: Context,
    private val camera: MtpCamera,
    private val history: ImportHistory,
    private val diagnostics: Diagnostics,
    private val onRecorded: (Photo, ImportResult) -> Unit,
) : ImportStore {
    private val resolver = context.contentResolver
    private val occupied = mutableMapOf<String, MutableSet<String>>()

    override suspend fun findDuplicate(photo: Photo): String? {
        val record = history.successful(photo.sourceKey) ?: return null
        return if (DuplicatePolicy.confirmed(photo, record, actualSize(record.localUri))) record.localUri else null
    }

    suspend fun actualSize(uri: String?): Long? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null
        try {
            resolver.openFileDescriptor(uri.toUri(), "r")?.use { it.statSize.takeIf { size -> size >= 0 } }
        } catch (_: Exception) { null }
    }

    override suspend fun create(photo: Photo): PendingImport = withContext(Dispatchers.IO) {
        if (photo.size <= 0) throw IOException("invalid_size")
        val space = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        if (space < photo.size + 16L * 1024 * 1024) throw StorageFullException()
        val pictures = photo.format == PhotoFormat.JPEG ||
            (photo.format == PhotoFormat.HEIF && ImageDecoder.isMimeTypeSupported(photo.format.mime))
        val collection = if (pictures) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val directory = if (pictures) "${Environment.DIRECTORY_PICTURES}/FujiImport/" else "${Environment.DIRECTORY_DOWNLOADS}/FujiImport/"
        val names = occupied.getOrPut(directory) { namesAt(collection, directory).toMutableSet() }
        val name = FileNames.available(photo.name, names)
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, photo.format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (pictures) photo.capturedAt?.let { put(MediaStore.Images.ImageColumns.DATE_TAKEN, it) }
        }) ?: throw IOException("media_insert")
        try {
            history.addPending(uri.toString())
            val actualName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else name
            } ?: name
            names.add(actualName)
            PendingImport(uri.toString(), actualName)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    override suspend fun copy(photo: Photo, target: PendingImport, onBytes: (Long) -> Unit) = withContext(Dispatchers.IO) {
        try {
            resolver.openFileDescriptor(target.uri.toUri(), "rw")?.use { descriptor ->
                camera.transfer(photo, descriptor, onBytes)
                Os.fsync(descriptor.fileDescriptor)
                if (descriptor.statSize != photo.size) throw IOException("size_mismatch")
            } ?: throw IOException("media_open")
        } catch (error: Exception) {
            diagnostics.event("original_transfer_failed", error)
            if (generateSequence<Throwable>(error) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOSPC }) {
                throw StorageFullException()
            }
            throw error
        }
    }

    override suspend fun publish(photo: Photo, target: PendingImport): Unit = withContext(Dispatchers.IO) {
        if (actualSize(target.uri) != photo.size) throw IOException("size_mismatch")
        if (resolver.update(target.uri.toUri(), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1) {
            throw IOException("media_publish")
        }
        // A published file survives even if journal maintenance fails.
        try { history.removePending(target.uri) } catch (error: Exception) { diagnostics.event("pending_journal_update_failed", error) }
        Unit
    }

    override suspend fun discard(target: PendingImport): Unit = withContext(Dispatchers.IO) {
        val uri = target.uri.toUri()
        try {
            val pending = resolver.query(includePending(uri), arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
                if (it.moveToFirst()) it.getInt(0) == 1 else false
            } ?: false
            // Only incomplete local destination files are removed. Camera objects are never modified.
            if (pending && resolver.delete(uri, null, null) != 1) throw IOException("pending_delete")
            history.removePending(target.uri)
        } catch (error: Exception) {
            diagnostics.event("pending_cleanup_failed", error)
            // Keep the journal entry so the next launch can retry cleanup.
        }
        Unit
    }

    override suspend fun record(photo: Photo, result: ImportResult, uri: String?, message: String?) {
        history.add(photo, result, uri, message)
        onRecorded(photo, result)
    }

    suspend fun recoverPending() = withContext(Dispatchers.IO) {
        for (uri in history.pending()) discard(PendingImport(uri, ""))
        // Recover the small insert-to-journal crash window too. Own pending rows are visible without media permissions.
        for ((collection, path) in listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/FujiImport/",
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download/FujiImport/",
        )) {
            val abandoned = resolver.query(includePending(collection), arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(path), null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
            }.orEmpty()
            for (uri in abandoned) discard(PendingImport(uri.toString(), ""))
        }
        diagnostics.event("pending_recovery_complete")
    }

    fun resetNameIndex() { occupied.clear() }

    @Suppress("DEPRECATION")
    private fun includePending(uri: Uri): Uri = MediaStore.setIncludePending(uri)

    private fun namesAt(collection: Uri, directory: String): Set<String> = resolver.query(collection,
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(directory), null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.orEmpty()
}
