package com.fujiimport.app.ui.connection

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fujiimport.app.R
import com.fujiimport.app.domain.ConnectionPhase
import com.fujiimport.app.domain.ConnectionState

@Composable
fun ConnectionScreen(state: ConnectionState, modifier: Modifier, retry: () -> Unit, diagnostics: () -> Unit) {
    Column(modifier.padding(32.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Image(painterResource(R.drawable.ic_launcher), contentDescription = null, Modifier.size(88.dp))
        Text("连接相机，导入照片", style = MaterialTheme.typography.headlineSmall)
        Text("使用支持数据传输的 USB-C 数据线连接 X-T5 与平板，\n开启相机，并允许 USB 访问。", textAlign = TextAlign.Center)
        Text(state.message, textAlign = TextAlign.Center, color = if (state.phase == ConnectionPhase.FAILED || state.phase == ConnectionPhase.DENIED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (state.phase in setOf(ConnectionPhase.DETECTING, ConnectionPhase.PERMISSION, ConnectionPhase.CONNECTING)) CircularProgressIndicator()
        else Button(onClick = retry) { Text(if (state.phase == ConnectionPhase.DENIED) "重新授权" else "重新检测") }
        Text("离线运行 · 仅从相机读取 · 保留原始文件", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = diagnostics) { Text("设置与诊断") }
    }
}
