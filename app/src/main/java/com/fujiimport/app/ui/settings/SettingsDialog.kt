package com.fujiimport.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.fujiimport.app.BuildConfig
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
    LaunchedEffect(Unit) { size = repository.images.diskSize() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("设置与诊断", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Fuji Import ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                        Text("JPEG、设备支持的 HEIF 保存至 Pictures/FujiImport。\nRAF 与无法原生识别的 HEIF 保存至 Download/FujiImport。\n仅复制原文件；RAW 是否出现在相册中取决于系统支持。")
                        HorizontalDivider()
                        Text("图片缓存：${size?.let { formatBytes(it) } ?: "正在统计"}")
                        Button(onClick = {
                            scope.launch {
                                busy = true
                                try { repository.images.clear(); size = repository.images.diskSize(); message = "缓存已清理" }
                                catch (_: Exception) { message = "缓存清理失败，可稍后重试" }
                                finally { busy = false }
                            }
                        }, enabled = !busy && !importing) { Text("清理缩略图与预览缓存") }
                        HorizontalDivider()
                        Text("相机与 USB", style = MaterialTheme.typography.titleMedium)
                        Text(connection.message)
                        Text(connection.diagnostics, style = MaterialTheme.typography.bodySmall)
                        connection.storages.forEach { storage ->
                            Text(storage.label + "\n容量：${storage.capacity?.let { formatBytes(it) } ?: "未知"}；空闲：${storage.freeBytes?.let { formatBytes(it) } ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("每个“存储”对应相机报告的可访问存储单元，物理卡槽对应关系需要实机确认。\n若连接失败：先关闭系统或其他相机导入应用，再重新插拔；仅在没有 PTP/MTP 接口时检查相机 USB 连接模式。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val file = withContext(Dispatchers.IO) { repository.diagnostics.export(context, connection.diagnostics) }
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(Intent.createChooser(intent, "导出脱敏诊断"))
                                } catch (_: Exception) { message = "无法导出诊断日志，请稍后重试" }
                            }
                        }) { Text("导出脱敏诊断日志") }
                        Text("诊断日志只包含系统版本、USB 接口描述符和错误类别，不包含照片、文件名、序列号和本地文件位置。", style = MaterialTheme.typography.bodySmall)
                        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        HorizontalDivider()
                        Text("最近导入记录（最多显示 200 条）", style = MaterialTheme.typography.titleMedium)
                        if (records.isEmpty()) Text("暂无导入记录")
                        records.forEach { record -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(record.originalName, style = MaterialTheme.typography.titleSmall)
                            Text("${resultLabel(record.result)} · ${formatBytes(record.size)} · ${dateTimeLabel(record.importedAt)}", style = MaterialTheme.typography.bodySmall)
                            record.capturedAt?.let { Text("原始拍摄时间：${dateTimeLabel(it)}", style = MaterialTheme.typography.bodySmall) }
                            record.localUri?.let { uri -> TextButton(onClick = {
                                try {
                                    val mime = PhotoFormat.fromFileName(record.originalName)?.mime ?: "application/octet-stream"
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri.toUri(), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                } catch (_: Exception) { message = "文件不可访问或没有可打开此格式的应用；可在 FujiImport 公共目录中查看" }
                            }) { Text("打开已导入文件") } }
                            record.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            HorizontalDivider()
                        } }
                    }
                }
            }
        }
    }
}

private fun resultLabel(result: ImportResult): String = when (result) {
    ImportResult.SUCCESS -> "成功"; ImportResult.FAILED -> "失败"; ImportResult.SKIPPED -> "跳过重复"; ImportResult.CANCELLED -> "已取消"
}
