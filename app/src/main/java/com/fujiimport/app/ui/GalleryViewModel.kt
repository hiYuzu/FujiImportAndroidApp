package com.fujiimport.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fujiimport.app.FujiApplication
import com.fujiimport.app.domain.Photo
import com.fujiimport.app.util.dateLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GalleryGroup(val date: String, val photos: List<Photo>)
data class GalleryState(val photos: List<Photo> = emptyList(), val groups: List<GalleryGroup> = emptyList(), val ascending: Boolean = true)
data class SelectionState(val active: Boolean = false, val ids: Set<String> = emptySet())
data class PreviewSession(val photos: List<Photo>, val initialIndex: Int)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as FujiApplication).repository
    val storageFilter = MutableStateFlow<Int?>(null)
    val selection = MutableStateFlow(SelectionState())
    val preview = MutableStateFlow<PreviewSession?>(null)
    val showSettings = MutableStateFlow(false)
    val showImport = MutableStateFlow(false)

    val gallery = combine(repository.photos, repository.preferences.ascending, storageFilter) { state, ascending, storageId ->
        val comparator = compareBy<Photo> { it.capturedAt ?: Long.MIN_VALUE }.thenBy { it.name }.thenBy { it.storageId }.thenBy { it.handle }
        val photos = state.photos.filter { storageId == null || it.storageId == storageId }
            .sortedWith(if (ascending) comparator else comparator.reversed())
        GalleryState(photos, photos.groupBy { dateLabel(it.capturedAt) }.map { GalleryGroup(it.key, it.value) }, ascending)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryState())

    init {
        viewModelScope.launch {
            var lastSession: String? = null
            repository.camera.state.collect { connection ->
                if (connection.sessionId != lastSession) {
                    lastSession = connection.sessionId
                    selection.value = SelectionState()
                    preview.value = null
                    storageFilter.value = null
                }
            }
        }
        viewModelScope.launch {
            repository.photos.collect { state ->
                if (!state.scanning) {
                    val valid = state.photos.mapTo(HashSet()) { it.id }
                    selection.update { it.copy(ids = it.ids.intersect(valid)) }
                }
            }
        }
    }

    fun open(photo: Photo) {
        if (selection.value.active) toggle(photo)
        else gallery.value.photos.indexOfFirst { it.id == photo.id }.takeIf { it >= 0 }?.let {
            preview.value = PreviewSession(gallery.value.photos, it)
        }
    }

    fun toggle(photo: Photo) { selection.update { it.copy(active = true, ids = if (photo.id in it.ids) it.ids - photo.id else it.ids + photo.id) } }
    fun clearSelection() { selection.value = SelectionState() }
    fun selectAll() { selection.update { it.copy(active = true, ids = it.ids + gallery.value.photos.map { photo -> photo.id }) } }
    fun clearVisibleSelection() { selection.update { it.copy(ids = it.ids - gallery.value.photos.map { photo -> photo.id }.toSet()) } }

    fun importSelected() {
        val photos = repository.photos.value.photos.filter { it.id in selection.value.ids }
        if (photos.isNotEmpty()) { repository.startImport(photos); showImport.value = true }
    }

    fun importPhoto(photo: Photo) {
        preview.value = null
        repository.startImport(listOf(photo))
        showImport.value = true
    }

    fun toggleSort() { viewModelScope.launch { repository.preferences.setAscending(!gallery.value.ascending) } }
}
