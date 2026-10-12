package com.fujiimport.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fujiimport.app.domain.ConnectionPhase
import com.fujiimport.app.ui.connection.ConnectionScreen
import com.fujiimport.app.ui.gallery.PhotoTile
import com.fujiimport.app.ui.importing.ImportDialog
import com.fujiimport.app.ui.preview.PreviewDialog
import com.fujiimport.app.ui.settings.SettingsDialog

@Composable
fun FujiImportApp(viewModel: GalleryViewModel = viewModel()) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9ED6BC), background = Color(0xFF111512), surface = Color(0xFF171C18))
        else lightColorScheme(primary = Color(0xFF28684C), background = Color(0xFFF8FAF6), surface = Color(0xFFF8FAF6))
    MaterialTheme(colorScheme = colors) {
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
        var showMenu by remember { mutableStateOf(false) }
        BackHandler(enabled = selection.active && preview == null && !showSettings && !showImport) { viewModel.clearSelection() }

        Scaffold(
            topBar = {
                Surface {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (selection.active) "已选 ${selection.ids.size} 张" else "Fuji Import", style = MaterialTheme.typography.titleLarge)
                                Text(connection.model?.let { "$it · ${connection.message}" } ?: connection.message, style = MaterialTheme.typography.bodySmall)
                            }
                            if (connection.phase == ConnectionPhase.CONNECTED) TextButton(onClick = { viewModel.clearSelection(); repo.refresh() }, enabled = !scan.scanning && progress?.isRunning != true) { Text("刷新") }
                            Box {
                                TextButton(onClick = { showMenu = true }) { Text("更多") }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(text = { Text("设置与诊断") }, onClick = { showMenu = false; viewModel.showSettings.value = true })
                                    if (progress != null) DropdownMenuItem(text = { Text("导入进度与结果") }, onClick = { showMenu = false; viewModel.showImport.value = true })
                                }
                            }
                        }
                        if (connection.phase == ConnectionPhase.CONNECTED) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                                FilterChip(selected = storage == null, onClick = { viewModel.storageFilter.value = null }, label = { Text("全部存储") })
                                connection.storages.forEach { item -> FilterChip(selected = storage == item.id,
                                    onClick = { viewModel.storageFilter.value = item.id }, label = { Text(item.label) }) }
                                TextButton(onClick = viewModel::toggleSort) { Text(if (gallery.ascending) "时间正序 ↑" else "时间倒序 ↓") }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text((if (storage == null) "${gallery.photos.size} 张" else "当前 ${gallery.photos.size} 张 · 共 ${scan.photos.size} 张") +
                                    if (scan.scanning) " · 扫描中" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                if (selection.active) {
                                    TextButton(onClick = viewModel::selectAll) { Text("全选当前") }
                                    TextButton(onClick = viewModel::clearVisibleSelection) { Text("取消全选") }
                                    TextButton(onClick = viewModel::clearSelection) { Text("完成") }
                                }
                            }
                            if (scan.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                            scan.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            if (scan.unreadable > 0) Text("${scan.unreadable} 个对象无法读取，其他照片不受影响", style = MaterialTheme.typography.bodySmall)
                        }
                        if (progress?.isRunning == true) {
                            TextButton(onClick = { viewModel.showImport.value = true }) { Text("正在导入 ${progress!!.processed} / ${progress!!.total} · 查看进度") }
                            LinearProgressIndicator(progress = { progress!!.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            bottomBar = {
                if (selection.active && connection.phase == ConnectionPhase.CONNECTED) Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selection.ids.size} 张", Modifier.weight(1f))
                        Button(onClick = viewModel::importSelected, enabled = selection.ids.isNotEmpty() && progress?.isRunning != true) { Text("导入原文件") }
                    }
                }
            },
        ) { padding ->
            if (connection.phase != ConnectionPhase.CONNECTED) {
                ConnectionScreen(connection, Modifier.fillMaxSize().padding(padding), repo.camera::retry, { viewModel.showSettings.value = true })
            } else if (gallery.photos.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(when { scan.scanning -> "正在读取照片…"; connection.storages.isEmpty() -> "没有可访问的存储，请检查相机 SD 卡"; else -> "当前存储没有可显示的照片" })
                        Text("支持 JPG、RAF 和 HEIF", style = MaterialTheme.typography.bodySmall)
                        if (!scan.scanning) TextButton(onClick = repo::refresh, enabled = progress?.isRunning != true) { Text("刷新照片") }
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), state = gridState,
                    modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    gallery.groups.forEach { group ->
                        item(key = "date:${group.date}", span = { GridItemSpan(maxLineSpan) }) {
                            Text("${group.date} · ${group.photos.size} 张", Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
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
