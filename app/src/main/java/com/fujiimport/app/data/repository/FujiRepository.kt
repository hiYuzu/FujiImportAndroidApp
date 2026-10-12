package com.fujiimport.app.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fujiimport.app.data.cache.PhotoImages
import com.fujiimport.app.data.media.ImportHistory
import com.fujiimport.app.data.media.MediaStoreWriter
import com.fujiimport.app.data.mtp.MtpCamera
import com.fujiimport.app.domain.ConnectionPhase
import com.fujiimport.app.domain.ImportEngine
import com.fujiimport.app.domain.ImportPhase
import com.fujiimport.app.domain.ImportProgress
import com.fujiimport.app.domain.ImportRecord
import com.fujiimport.app.domain.ImportResult
import com.fujiimport.app.domain.Photo
import com.fujiimport.app.service.ImportService
import com.fujiimport.app.util.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotoListState(
    val photos: List<Photo> = emptyList(),
    val scanning: Boolean = false,
    val unreadable: Int = 0,
    val message: String? = null,
)

class FujiRepository(private val context: Context) {
    val diagnostics = Diagnostics()
    val camera = MtpCamera(context, diagnostics)
    val preferences = UserPreferences(context)
    private val history = ImportHistory(context)
    private val mutableImportedKeys = MutableStateFlow<Set<String>>(emptySet())
    val importedKeys = mutableImportedKeys.asStateFlow()
    private val writer = MediaStoreWriter(context, camera, history, diagnostics) { photo, result ->
        if (result == ImportResult.SUCCESS || result == ImportResult.SKIPPED) mutableImportedKeys.update { it + photo.sourceKey }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutablePhotos = MutableStateFlow(PhotoListState())
    val photos = mutablePhotos.asStateFlow()
    private val mutableRecords = MutableStateFlow<List<ImportRecord>>(emptyList())
    val records = mutableRecords.asStateFlow()
    private val mutableProgress = MutableStateFlow<ImportProgress?>(null)
    val progress = mutableProgress.asStateFlow()
    private val importing = MutableStateFlow(false)
    private val ready = CompletableDeferred<Unit>()
    val images = PhotoImages(context, camera, diagnostics, { photos.value.photos }) {
        ready.await()
        importing.first { !it }
    }
    private var scanJob: Job? = null
    private var importJob: Job? = null
    private var markerJob: Job? = null
    private var queued: List<Photo> = emptyList()
    @Volatile private var cancelRequested = false
    @Volatile private var scanGeneration = 0
    private var rescanAfterImport = false
    private var uiAttached = false

    fun attachUi() { uiAttached = true; camera.start() }
    fun detachUi() { uiAttached = false; releaseCameraIfUiClosed() }
    fun releaseCameraIfUiClosed() {
        if (!uiAttached && progress.value?.isRunning != true) {
            rescanAfterImport = false
            camera.stop()
        }
    }

    fun start() {
        scope.launch {
            try {
                images.initialize()
                writer.recoverPending()
                mutableRecords.value = history.latest()
                ready.complete(Unit)
                refreshImportMarkers()
            } catch (error: Exception) {
                diagnostics.event("local_initialization_failed", error)
                ready.completeExceptionally(error)
                mutablePhotos.value = PhotoListState(message = "本地存储初始化失败，请检查平板空间后重启应用")
            }
        }
        scope.launch {
            history.changes.collectLatest {
                try { mutableRecords.value = history.latest() }
                catch (error: Exception) { if (error is CancellationException) throw error; diagnostics.event("history_read_failed", error) }
            }
        }
        scope.launch {
            var lastSession: String? = null
            camera.state.collect { state ->
                if (state.sessionId == lastSession) return@collect
                lastSession = state.sessionId
                scanGeneration++
                scanJob?.cancel()
                mutablePhotos.value = PhotoListState()
                if (state.phase == ConnectionPhase.CONNECTED) {
                    if (progress.value?.isRunning == true) rescanAfterImport = true else refresh()
                }
                else if (progress.value?.isRunning == true) cancelImport()
            }
        }
        camera.start()
    }

    fun refresh() {
        if (camera.state.value.sessionId == null || progress.value?.isRunning == true) return
        val generation = ++scanGeneration
        refreshImportMarkers()
        val previous = scanJob
        previous?.cancel()
        mutablePhotos.value = mutablePhotos.value.copy(scanning = true, message = null, unreadable = 0)
        scanJob = scope.launch {
            previous?.join()
            try {
                ready.await()
                val discovered = LinkedHashMap<String, Photo>()
                val unreadable = camera.scan { batch ->
                    if (generation == scanGeneration) {
                        batch.forEach { discovered[it.id] = it }
                        mutablePhotos.update { current ->
                            if (generation == scanGeneration) PhotoListState(discovered.values.toList(), scanning = true) else current
                        }
                    }
                }
                if (generation == scanGeneration) mutablePhotos.value = PhotoListState(discovered.values.toList(), unreadable = unreadable)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                diagnostics.event("scan_failed", error)
                if (generation == scanGeneration) mutablePhotos.value = mutablePhotos.value.copy(message = "照片扫描未完成，可刷新重试或查看诊断")
            } finally {
                if (generation == scanGeneration) mutablePhotos.value = mutablePhotos.value.copy(scanning = false)
            }
        }
    }

    fun startImport(photos: List<Photo>) {
        if (photos.isEmpty() || progress.value?.isRunning == true || importJob?.isActive == true) return
        if (photos.any { it.sessionId != camera.state.value.sessionId }) return
        queued = photos.distinctBy { it.id }
        cancelRequested = false
        mutableProgress.value = ImportProgress(queued.size)
        importing.value = true
        markerJob?.cancel()
        try {
            ContextCompat.startForegroundService(context, Intent(context, ImportService::class.java).setAction(ImportService.START))
        } catch (error: Exception) {
            diagnostics.event("import_service_start_failed", error)
            failQueuedImport()
        }
    }

    fun failQueuedImport() {
        val total = progress.value?.total ?: queued.size
        mutableProgress.value = ImportProgress(total, failed = total, processed = total,
            phase = ImportPhase.FINISHED, errors = listOf("无法启动导入任务，请确认相机已连接并保持应用在前台后重试"))
        queued = emptyList()
        importing.value = false
    }

    fun runQueuedImport() {
        if (queued.isEmpty() || importJob?.isActive == true) return
        val queue = queued
        queued = emptyList()
        importJob = scope.launch {
            try {
                ready.await()
                rescanAfterImport = rescanAfterImport || photos.value.scanning
                scanJob?.cancelAndJoin()
                writer.resetNameIndex()
                ImportEngine(writer, { value ->
                    mutableProgress.value = if (cancelRequested && value.phase == ImportPhase.RUNNING) value.copy(phase = ImportPhase.CANCELLING) else value
                }, { diagnostics.event("import_record_failed") }).execute(queue)
            } catch (cancelled: CancellationException) {
                if (progress.value?.isRunning == true) mutableProgress.value = progress.value?.let {
                    it.copy(phase = ImportPhase.CANCELLED, cancelled = it.total - it.processed, currentName = null, fileBytes = null)
                }
                throw cancelled
            } catch (error: Exception) {
                diagnostics.event("import_task_failed", error)
                mutableProgress.value = progress.value?.let {
                    it.copy(phase = ImportPhase.FINISHED, failed = it.failed + it.total - it.processed,
                        processed = it.total, currentName = null, errors = it.errors + "导入任务失败，请检查本地存储和诊断")
                }
            } finally {
                importing.value = false
                withContext(NonCancellable) {
                    try { mutableRecords.value = history.latest() } catch (error: Exception) { diagnostics.event("history_read_failed", error) }
                    if (uiAttached && rescanAfterImport && camera.state.value.phase == ConnectionPhase.CONNECTED) {
                        rescanAfterImport = false
                        refresh()
                    }
                    releaseCameraIfUiClosed()
                }
            }
        }
        if (cancelRequested) importJob?.cancel()
    }

    fun cancelImport() {
        if (progress.value?.isRunning != true) return
        cancelRequested = true
        mutableProgress.value = progress.value?.copy(phase = ImportPhase.CANCELLING)
        importJob?.cancel()
    }

    private fun refreshImportMarkers() {
        markerJob?.cancel()
        markerJob = scope.launch {
            try {
                ready.await()
                val valid = history.successfulRecords().filter { writer.actualSize(it.localUri) == it.size }.mapTo(HashSet()) { it.sourceKey }
                mutableImportedKeys.value = valid
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { diagnostics.event("import_markers_failed", error) }
        }
    }
}
