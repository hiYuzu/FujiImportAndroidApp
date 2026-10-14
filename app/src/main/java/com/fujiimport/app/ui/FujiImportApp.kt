package com.fujiimport.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fujiimport.app.R
import com.fujiimport.app.domain.ConnectionPhase
import com.fujiimport.app.domain.ImportPhase
import com.fujiimport.app.ui.connection.ConnectionScreen
import com.fujiimport.app.ui.gallery.PhotoTile
import com.fujiimport.app.ui.importing.ImportDialog
import com.fujiimport.app.ui.preview.PreviewDialog
import com.fujiimport.app.ui.settings.SettingsDialog

@Composable
fun FujiImportApp(viewModel: GalleryViewModel = viewModel()) {
    FujiTheme {
        val repo = viewModel.repository
        val connection by repo.camera.state.collectAsStateWithLifecycle()
        val scan by repo.photos.collectAsStateWithLifecycle()
        val gallery by viewModel.gallery.collectAsStateWithLifecycle()
        val selection by viewModel.selection.collectAsStateWithLifecycle()
        val storage by viewModel.storageFilter.collectAsStateWithLifecycle()
        val preview by viewModel.preview.collectAsStateWithLifecycle()
        val records by repo.records.collectAsStateWithLifecycle()
        val imported by repo.importedKeys.collectAsStateWithLifecycle()
        val progress by repo.progress.collectAsStateWithLifecycle()
        val showSettings by viewModel.showSettings.collectAsStateWithLifecycle()
        val showImport by viewModel.showImport.collectAsStateWithLifecycle()
        val gridState = rememberLazyGridState()
        BackHandler(enabled = selection.active && preview == null && !showSettings && !showImport) { viewModel.clearSelection() }

        Scaffold(
            topBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (selection.active) "已选 ${selection.ids.size} 张" else "Fuji Import",
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!selection.active && connection.phase == ConnectionPhase.CONNECTED) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                        Text(connection.model ?: "已连接", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (selection.active) {
                                val allSelected = gallery.photos.isNotEmpty() && gallery.photos.all { it.id in selection.ids }
                                TextButton(onClick = { if (allSelected) viewModel.clearVisibleSelection() else viewModel.selectAll() }) {
                                    Text(if (allSelected) "取消全选" else "全选")
                                }
                                IconButton(onClick = viewModel::clearSelection) {
                                    Icon(painterResource(R.drawable.ic_close), "退出选择")
                                }
                            } else {
                                if (connection.phase == ConnectionPhase.CONNECTED) {
                                    TextButton(onClick = { viewModel.selection.value = SelectionState(active = true) }, enabled = gallery.photos.isNotEmpty()) { Text("选择") }
                                    IconButton(onClick = repo::refresh, enabled = !scan.scanning && progress?.isRunning != true) {
                                        Icon(painterResource(R.drawable.ic_refresh), "刷新")
                                    }
                                }
                                IconButton(onClick = { viewModel.showSettings.value = true }) {
                                    Icon(painterResource(R.drawable.ic_settings), "设置")
                                }
                            }
                        }
                        if (connection.phase == ConnectionPhase.CONNECTED) {
                            val count = (if (storage == null) "${gallery.photos.size} 张" else "${gallery.photos.size} 张 · 共 ${scan.photos.size} 张") +
                                if (scan.scanning) " · 扫描中" else ""
                            val storageChips: @Composable (Modifier) -> Unit = { modifier ->
                                FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = storage == null, onClick = { viewModel.storageFilter.value = null }, label = { Text("全部") })
                                    connection.storages.forEach { item ->
                                        FilterChip(selected = storage == item.id, onClick = { viewModel.storageFilter.value = item.id },
                                            modifier = Modifier.widthIn(max = 220.dp), label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                    }
                                }
                            }
                            val sortToggle: @Composable () -> Unit = {
                                TextButton(onClick = viewModel::toggleSort) {
                                    Icon(painterResource(if (gallery.ascending) R.drawable.ic_sort_ascending else R.drawable.ic_sort_descending), null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (gallery.ascending) "最早优先" else "最新优先")
                                }
                            }
                            BoxWithConstraints {
                                if (maxWidth >= 600.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (connection.storages.size > 1) storageChips(Modifier.weight(1f).padding(horizontal = 16.dp))
                                    else Spacer(Modifier.weight(1f))
                                    sortToggle()
                                } else Column {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(count, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        sortToggle()
                                    }
                                    if (connection.storages.size > 1) storageChips(Modifier.fillMaxWidth())
                                }
                            }
                            if (scan.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                            scan.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            if (scan.unreadable > 0) Text("${scan.unreadable} 项无法读取", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        progress?.let { current ->
                            Surface(onClick = { viewModel.showImport.value = true }, shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(painterResource(when {
                                            current.phase == ImportPhase.FINISHED && current.failed == 0 -> R.drawable.ic_check
                                            !current.isRunning && current.failed > 0 -> R.drawable.ic_info
                                            else -> R.drawable.ic_import
                                        }), null, Modifier.size(20.dp), tint = if (!current.isRunning && current.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                        Text(when (current.phase) {
                                            ImportPhase.RUNNING -> "导入中"
                                            ImportPhase.CANCELLING -> "取消中"
                                            ImportPhase.FINISHED -> "导入完成"
                                            ImportPhase.CANCELLED -> "已取消"
                                        }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                        Text(if (!current.isRunning && current.failed > 0) "${current.failed} 项失败" else "${current.processed} / ${current.total}",
                                            style = MaterialTheme.typography.bodySmall, color = if (!current.isRunning && current.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (current.isRunning) LinearProgressIndicator(progress = { current.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (selection.active && connection.phase == ConnectionPhase.CONNECTED) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.CenterEnd) {
                        Button(onClick = viewModel::importSelected, enabled = selection.ids.isNotEmpty() && progress?.isRunning != true,
                            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) { Text("导入") }
                    }
                }
            },
        ) { padding ->
            if (connection.phase != ConnectionPhase.CONNECTED) {
                ConnectionScreen(connection, Modifier.fillMaxSize().padding(padding), repo.camera::retry)
            } else if (gallery.photos.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (scan.scanning) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                        else Icon(painterResource(R.drawable.ic_photo), null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when { scan.scanning -> "读取中"; connection.storages.isEmpty() -> "未发现存储，请检查 SD 卡"; else -> "暂无照片" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!scan.scanning) TextButton(onClick = repo::refresh, enabled = progress?.isRunning != true) { Text("刷新") }
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), state = gridState,
                    modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    gallery.groups.forEach { group ->
                        item(key = "date:${group.date}", span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(group.date, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("${group.photos.size} 张", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(group.photos, key = { it.id }, contentType = { "photo" }) { photo ->
                            PhotoTile(photo, repo.images, photo.id in selection.ids, photo.sourceKey in imported,
                                onClick = { viewModel.open(photo) }, onLongClick = { viewModel.toggle(photo) })
                        }
                    }
                }
            }
        }
        preview?.let { session -> PreviewDialog(session, selection.ids, repo.images, progress?.isRunning == true,
            onDismiss = { viewModel.preview.value = null }, onSelect = viewModel::toggle, onImport = viewModel::importPhoto) }
        if (showSettings) SettingsDialog(repo, connection, records, progress?.isRunning == true, onDismiss = { viewModel.showSettings.value = false })
        if (showImport && progress != null) ImportDialog(progress!!, repo::cancelImport, onDismiss = { viewModel.showImport.value = false })
    }
}
