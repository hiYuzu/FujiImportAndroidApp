package com.fujiimport.app.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fujiimport.app.R
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
    Box(Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .semantics { contentDescription = "${photo.name}，${photo.format.label}" + (if (selected) "，已选中" else "") + (if (imported) "，已导入" else "") }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        thumbnail.bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (thumbnail.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else {
                    Icon(painterResource(R.drawable.ic_photo), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { retry++ }) { Text("重试") }
                }
            }
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .12f))
                .border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small))
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Text(photo.format.label, Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = .64f), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        if (imported) Text("已导入", Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = .64f), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
