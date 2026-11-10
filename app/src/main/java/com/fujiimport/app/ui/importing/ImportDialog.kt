package com.fujiimport.app.ui.importing

import android.content.Intent
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fujiimport.app.domain.ImportPhase
import com.fujiimport.app.domain.ImportProgress
import com.fujiimport.app.util.formatBytes

@Composable
fun ImportDialog(progress: ImportProgress, cancel: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var openError by remember { mutableStateOf<String?>(null) }
    val locations by produceState<Map<String, String>>(emptyMap(), progress.isRunning, progress.recentFiles) {
        if (!progress.isRunning) value = withContext(Dispatchers.IO) {
            progress.recentFiles.associate { file ->
                val path = try {
                    context.contentResolver.query(file.uri.toUri(), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) "${it.getString(0).orEmpty()}${it.getString(1).orEmpty()}" else null
                    }
                } catch (_: Exception) { null }
                file.uri to (path ?: "保存位置暂不可用")
            }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(progress.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${progress.processed} / ${progress.total}", style = MaterialTheme.typography.titleMedium)
            if (progress.isRunning && progress.fileBytes == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            progress.currentName?.takeIf { progress.phase == ImportPhase.RUNNING }?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
            if (progress.fileBytes != null && progress.fileSize != null) Text("${formatBytes(progress.fileBytes)} / ${formatBytes(progress.fileSize)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val counts = listOf("已保存" to progress.succeeded, "失败" to progress.failed, "重复跳过" to progress.skipped) +
                    if (progress.cancelled > 0) listOf("未完成" to progress.cancelled) else emptyList()
                counts.forEach { (label, count) -> Column {
                    Text("$count", style = MaterialTheme.typography.titleLarge,
                        color = if (label == "失败" && count > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            if (progress.cleaning) Text("清理中…", style = MaterialTheme.typography.bodySmall)
            progress.stopReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            progress.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!progress.isRunning && progress.recentFiles.isNotEmpty()) {
                Text("最近文件", style = MaterialTheme.typography.titleSmall)
                progress.recentFiles.forEach { file ->
                    Column {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium)
                        Text(locations[file.uri] ?: "读取保存位置中…", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            openError = null
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(file.uri.toUri(), file.mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (_: Exception) { openError = "无法打开文件" }
                        }) { Text("打开") }
                    }
                }
                openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(if (progress.isRunning) "后台继续" else "关闭") } },
        dismissButton = { if (progress.phase == ImportPhase.RUNNING) TextButton(onClick = cancel) { Text("取消导入") } })
}
