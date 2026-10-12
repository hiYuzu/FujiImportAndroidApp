package com.fujiimport.app.ui.preview

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@Composable
fun ZoomablePhoto(bitmap: Bitmap, photoId: String, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    var scale by remember(photoId) { mutableFloatStateOf(1f) }
    var offset by remember(photoId) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val tapCallback by rememberUpdatedState(onTap)
    val zoomCallback by rememberUpdatedState(onZoomed)
    fun constrain(candidate: Offset): Offset {
        val fit = minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val maxX = ((bitmap.width * fit * scale - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((bitmap.height * fit * scale - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }
    Image(bitmap.asImageBitmap(), contentDescription = "照片预览", contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
            .pointerInput(photoId) {
                detectTapGestures(onTap = { tapCallback() }, onDoubleTap = {
                    scale = if (scale > 1.01f) 1f else 3f
                    offset = Offset.Zero
                    zoomCallback(scale > 1.01f)
                })
            }
            .pointerInput(photoId, viewport, bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale <= 1.01f) Offset.Zero else constrain(offset + pan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            zoomCallback(scale > 1.01f)
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
}
