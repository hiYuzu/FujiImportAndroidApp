package com.fujiimport.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fujiimport.app.R
import com.fujiimport.app.data.cache.PhotoImages
import com.fujiimport.app.data.cache.PreviewData
import com.fujiimport.app.domain.Photo
import com.fujiimport.app.ui.PreviewSession
import com.fujiimport.app.util.dateTimeLabel
import com.fujiimport.app.util.formatBytes
import kotlinx.coroutines.CancellationException

private data class PreviewLoad(val data: PreviewData = PreviewData(null, null), val loading: Boolean = true)

@Composable
fun PreviewDialog(session: PreviewSession, selected: Set<String>, images: PhotoImages, importing: Boolean,
    onDismiss: () -> Unit, onSelect: (Photo) -> Unit, onImport: (Photo) -> Unit) {
    val pager = rememberPagerState(initialPage = session.initialIndex) { session.photos.size }
    val current = session.photos[pager.currentPage]
    var toolbar by remember { mutableStateOf(true) }
    var zoomed by remember(current.id) { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var metadata by remember(current.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { session.photos[it].id }) { index ->
                val photo = session.photos[index]
                val isCurrent = index == pager.currentPage
                PreviewPhoto(photo, images, isCurrent, showNote = toolbar, onTap = { toolbar = !toolbar },
                    onZoomed = { if (isCurrent) zoomed = it }, onMetadata = { if (isCurrent) metadata = it })
            }
            if (toolbar) Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .72f)).padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), "关闭预览", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(current.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pager.currentPage + 1} / ${session.photos.size}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { showInfo = true }) { Icon(painterResource(R.drawable.ic_info), "照片信息", tint = Color.White) }
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .72f)).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onSelect(current) }) {
                        if (current.id in selected) {
                            Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (current.id in selected) "已选" else "选择", color = Color.White)
                    }
                    Button(onClick = { onImport(current) }, enabled = !importing) { Text("导入") }
                }
            }
        }
        if (showInfo) PhotoInformation(current, metadata) { showInfo = false }
    }
}

@Composable
private fun PreviewPhoto(photo: Photo, images: PhotoImages, active: Boolean, showNote: Boolean, onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit, onMetadata: (Map<String, String>) -> Unit) {
    var retry by remember(photo.id) { mutableIntStateOf(0) }
    val load by produceState(PreviewLoad(), photo.id, retry, active) {
        value = PreviewLoad()
        try {
            val thumb = images.thumbnail(photo)
            value = PreviewLoad(PreviewData(thumb, null), loading = active)
            if (active) value = PreviewLoad(images.preview(photo), loading = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = PreviewLoad(PreviewData(value.data.bitmap, "预览不可用", retryAllowed = true), loading = false) }
    }
    LaunchedEffect(load.data.metadata, active) { if (active) onMetadata(load.data.metadata) }
    Box(Modifier.fillMaxSize()) {
        val bitmap = load.data.bitmap
        if (bitmap != null) ZoomablePhoto(bitmap, photo.id, onTap, onZoomed)
        else if (!load.loading) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("预览不可用", color = Color.White)
            TextButton(onClick = { retry++ }) { Text("重试", color = Color.White) }
        }
        if (load.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White, strokeWidth = 2.dp)
        if (showNote && bitmap != null && load.data.note != null && !load.loading) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp, start = 16.dp, end = 16.dp)
            .background(Color.Black.copy(alpha = .7f), MaterialTheme.shapes.medium).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(load.data.note!!, color = Color.White, style = MaterialTheme.typography.bodySmall)
            if (load.data.retryAllowed) TextButton(onClick = { retry++ }) { Text("重试", color = Color.White) }
        }
    }
}

@Composable
private fun PhotoInformation(photo: Photo, exif: Map<String, String>, dismiss: () -> Unit) {
    val info = buildMap {
        put("文件名", photo.name); put("格式", photo.format.label); put("大小", formatBytes(photo.size))
        photo.capturedAt?.let { put("拍摄时间", dateTimeLabel(it)) }
        if (photo.width != null && photo.height != null) put("分辨率", "${photo.width} × ${photo.height}")
        photo.cameraModel?.let { put("相机", it) }
        putAll(exif)
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("照片信息") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            info.forEach { (label, value) -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            } }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}
