package com.fujiimport.app.ui.importing

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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(when (progress.phase) {
        ImportPhase.RUNNING -> "导入中"
        ImportPhase.CANCELLING -> "取消中"
        ImportPhase.FINISHED -> "导入完成"
        ImportPhase.CANCELLED -> "已取消"
    }) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${progress.processed} / ${progress.total}", style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            progress.currentName?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
            if (progress.fileBytes != null && progress.fileSize != null) Text("${formatBytes(progress.fileBytes)} / ${formatBytes(progress.fileSize)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val counts = listOf("成功" to progress.succeeded, "失败" to progress.failed, "跳过" to progress.skipped) +
                    if (progress.cancelled > 0) listOf("未完成" to progress.cancelled) else emptyList()
                counts.forEach { (label, count) -> Column {
                    Text("$count", style = MaterialTheme.typography.titleLarge,
                        color = if (label == "失败" && count > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            if (progress.phase == ImportPhase.CANCELLING) Text("清理中…", style = MaterialTheme.typography.bodySmall)
            progress.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(if (progress.isRunning) "后台继续" else "关闭") } },
        dismissButton = { if (progress.phase == ImportPhase.RUNNING) TextButton(onClick = cancel) { Text("取消导入") } })
}
