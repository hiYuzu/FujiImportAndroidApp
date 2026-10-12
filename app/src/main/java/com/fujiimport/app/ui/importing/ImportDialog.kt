package com.fujiimport.app.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.fujiimport.app.domain.ImportPhase
import com.fujiimport.app.domain.ImportProgress
import com.fujiimport.app.util.formatBytes

@Composable
fun ImportDialog(progress: ImportProgress, cancel: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(when (progress.phase) {
        ImportPhase.RUNNING -> "正在导入原文件"
        ImportPhase.CANCELLING -> "正在取消导入"
        ImportPhase.FINISHED -> "导入完成"
        ImportPhase.CANCELLED -> "导入已取消"
    }) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${progress.processed} / ${progress.total}", style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            progress.currentName?.let { Text(it) }
            if (progress.fileBytes != null && progress.fileSize != null) Text("${formatBytes(progress.fileBytes)} / ${formatBytes(progress.fileSize)}")
            Text("成功 ${progress.succeeded} · 失败 ${progress.failed} · 跳过 ${progress.skipped}" + if (progress.cancelled > 0) " · 未完成 ${progress.cancelled}" else "")
            if (progress.phase == ImportPhase.CANCELLING) Text("正在清理未完成文件。系统原生传输可能需要等待当前调用返回。", style = MaterialTheme.typography.bodySmall)
            progress.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!progress.isRunning) Text("JPEG/支持的 HEIF：Pictures/FujiImport\nRAF：Download/FujiImport，可在文件管理器查看", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(if (progress.isRunning) "后台继续" else "关闭") } },
        dismissButton = { if (progress.phase == ImportPhase.RUNNING) TextButton(onClick = cancel) { Text("取消导入") } })
}
