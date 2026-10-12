package com.fujiimport.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only destination operations are writable. The camera is exposed through a read-only adapter. */
interface ImportStore {
    suspend fun findDuplicate(photo: Photo): String?
    suspend fun create(photo: Photo): PendingImport
    suspend fun copy(photo: Photo, target: PendingImport, onBytes: (Long) -> Unit)
    suspend fun publish(photo: Photo, target: PendingImport)
    suspend fun discard(target: PendingImport)
    suspend fun record(photo: Photo, result: ImportResult, uri: String?, message: String?)
}

class ImportEngine(
    private val store: ImportStore,
    private val onProgress: (ImportProgress) -> Unit,
    private val onRecordFailure: () -> Unit = {},
) {
    suspend fun execute(photos: List<Photo>): ImportProgress {
        var progress = ImportProgress(total = photos.size)
        onProgress(progress)
        var stopReason: String? = null
        var pending: PendingImport? = null
        var current: Photo? = null
        try {
            for (photo in photos) {
                currentCoroutineContext().ensureActive()
                current = photo
                progress = progress.copy(currentName = photo.name, fileBytes = null, fileSize = photo.size)
                onProgress(progress)
                try {
                    val duplicate = store.findDuplicate(photo)
                    if (duplicate != null) {
                        withContext(NonCancellable) {
                            saveRecord(photo, ImportResult.SKIPPED, duplicate, "本地原文件已存在")
                            progress = progress.copy(skipped = progress.skipped + 1, processed = progress.processed + 1)
                            current = null
                        }
                    } else {
                        stopReason?.let { throw java.io.IOException(it) }
                        val target = withContext(NonCancellable) { store.create(photo).also { pending = it } }
                        currentCoroutineContext().ensureActive()
                        store.copy(photo, target) { bytes ->
                            progress = progress.copy(fileBytes = bytes)
                            onProgress(progress)
                        }
                        currentCoroutineContext().ensureActive()
                        // The final commit is atomic with respect to cancellation and its success count.
                        withContext(NonCancellable) {
                            store.publish(photo, target)
                            pending = null
                            saveRecord(photo, ImportResult.SUCCESS, target.uri, null)
                            progress = progress.copy(succeeded = progress.succeeded + 1, processed = progress.processed + 1)
                            current = null
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (error is CameraUnavailableException || error is StorageFullException) {
                        stopReason = error.message
                    }
                    val message = when (error) {
                        is CameraUnavailableException -> "相机已断开"
                        is StorageFullException -> "存储空间不足"
                        else -> stopReason ?: "读取或写入失败，请重试；详见诊断"
                    }
                    withContext(NonCancellable) {
                        pending?.let { store.discard(it) }
                        pending = null
                        saveRecord(photo, ImportResult.FAILED, null, message)
                        progress = progress.copy(
                            failed = progress.failed + 1, processed = progress.processed + 1,
                            errors = (progress.errors + "${photo.name}：$message").takeLast(20),
                        )
                        current = null
                    }
                }
                progress = progress.copy(fileBytes = null)
                onProgress(progress)
            }
            progress = progress.copy(phase = ImportPhase.FINISHED, currentName = null)
            onProgress(progress)
            return progress
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                pending?.let { store.discard(it) }
                current?.let { saveRecord(it, ImportResult.CANCELLED, null, "导入已取消") }
            }
            onProgress(progress.copy(
                phase = ImportPhase.CANCELLED,
                cancelled = progress.total - progress.processed,
                currentName = null,
                fileBytes = null,
            ))
            throw cancelled
        }
    }

    private suspend fun saveRecord(photo: Photo, result: ImportResult, uri: String?, message: String?) {
        try {
            store.record(photo, result, uri, message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onRecordFailure()
        }
    }
}
