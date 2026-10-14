package com.fujiimport.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.fujiimport.app.BuildConfig
import com.fujiimport.app.R
import com.fujiimport.app.data.repository.FujiRepository
import com.fujiimport.app.domain.ConnectionState
import com.fujiimport.app.domain.ImportRecord
import com.fujiimport.app.domain.ImportResult
import com.fujiimport.app.domain.PhotoFormat
import com.fujiimport.app.util.dateTimeLabel
import com.fujiimport.app.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsDialog(repository: FujiRepository, connection: ConnectionState, records: List<ImportRecord>, importing: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { size = repository.images.diskSize() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.92f), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("设置", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), "关闭设置") }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(48.dp).clip(MaterialTheme.shapes.small))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Fuji Import", style = MaterialTheme.typography.titleMedium)
                                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                        SettingsSection("保存位置") {
                            listOf("照片" to "Pictures/FujiImport", "RAW / 其他 HEIF" to "Download/FujiImport").forEach { (label, path) ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(path, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("缓存", style = MaterialTheme.typography.titleSmall)
                                    Text(size?.let { formatBytes(it) } ?: "统计中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        busy = true
                                        try { repository.images.clear(); size = repository.images.diskSize(); message = "缓存已清理" }
                                        catch (_: Exception) { message = "清理失败，请重试" }
                                        finally { busy = false }
                                    }
                                }, enabled = !busy && !importing) { Text(if (busy) "清理中…" else "清理") }
                            }
                        }
                        SettingsSection("相机") {
                            connection.model?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            Text(connection.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            connection.storages.forEach { storage ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(storage.label, style = MaterialTheme.typography.bodyMedium)
                                    Text("${storage.capacity?.let { formatBytes(it) } ?: "未知"} · 可用 ${storage.freeBytes?.let { formatBytes(it) } ?: "未知"}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showDiagnostics = !showDiagnostics }) { Text(if (showDiagnostics) "收起诊断" else "USB 诊断") }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            val file = withContext(Dispatchers.IO) { repository.diagnostics.export(context, connection.diagnostics) }
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            context.startActivity(Intent.createChooser(intent, "导出日志"))
                                        } catch (_: Exception) { message = "日志导出失败，请重试" }
                                    }
                                }) { Text("导出日志") }
                            }
                            if (showDiagnostics) Text(connection.diagnostics, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        SettingsSection("导入记录") {
                            if (records.isEmpty()) Text("暂无记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            records.forEach { record -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(record.originalName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    record.localUri?.let { uri -> TextButton(onClick = {
                                        try {
                                            val mime = PhotoFormat.fromFileName(record.originalName)?.mime ?: "application/octet-stream"
                                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri.toUri(), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                        } catch (_: Exception) { message = "无法打开文件" }
                                    }) { Text("打开") } }
                                }
                                Text("${resultLabel(record.result)} · ${formatBytes(record.size)} · ${dateTimeLabel(record.importedAt)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                record.capturedAt?.let { Text("拍摄 ${dateTimeLabel(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                record.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

private fun resultLabel(result: ImportResult): String = when (result) {
    ImportResult.SUCCESS -> "成功"; ImportResult.FAILED -> "失败"; ImportResult.SKIPPED -> "跳过重复"; ImportResult.CANCELLED -> "已取消"
}
