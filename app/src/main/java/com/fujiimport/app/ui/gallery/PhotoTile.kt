package com.fujiimport.app.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fujiimport.app.data.cache.PhotoImages
import com.fujiimport.app.domain.Photo
import kotlinx.coroutines.CancellationException

private data class ThumbnailState(val bitmap: Bitmap? = null, val loading: Boolean = true)

@Composable
fun PhotoTile(photo: Photo, images: PhotoImages, selected: Boolean, imported: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    var retry by remember(photo.id) { mutableIntStateOf(0) }
    val thumbnail by produceState(ThumbnailState(), photo.cacheKey, retry) {
        value = ThumbnailState()
        try { value = ThumbnailState(images.thumbnail(photo), loading = false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = ThumbnailState(loading = false) }
    }
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
        .semantics { contentDescription = "${photo.name}，${photo.format.label}" + if (selected) "，已选中" else "" }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        thumbnail.bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (thumbnail.loading) CircularProgressIndicator()
                else {
                    Text("预览不可用", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { retry++ }) { Text("重试") }
                }
            }
        Text(photo.format.label, Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .62f)).padding(horizontal = 6.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        if (imported) Text("已导入", Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = .62f)).padding(horizontal = 6.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .24f)))
            Text("✓", Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary).padding(8.dp), color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
