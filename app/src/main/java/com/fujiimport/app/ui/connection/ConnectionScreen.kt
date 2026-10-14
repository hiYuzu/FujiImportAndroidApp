package com.fujiimport.app.ui.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
fun ConnectionScreen(state: ConnectionState, modifier: Modifier, retry: () -> Unit) {
    val busy = state.phase in setOf(ConnectionPhase.DETECTING, ConnectionPhase.PERMISSION, ConnectionPhase.CONNECTING)
    Column(modifier.padding(32.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(104.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(when (state.phase) {
            ConnectionPhase.DISCONNECTED -> "未连接相机"
            ConnectionPhase.DETACHED -> "相机已断开"
            ConnectionPhase.DENIED -> "需要 USB 授权"
            ConnectionPhase.FAILED -> "连接失败"
            ConnectionPhase.PERMISSION -> "等待授权"
            ConnectionPhase.DETECTING -> "识别中"
            ConnectionPhase.CONNECTING -> "连接中"
            ConnectionPhase.CONNECTED -> "已连接"
        }, style = MaterialTheme.typography.headlineSmall)
        if (state.phase == ConnectionPhase.FAILED || state.phase == ConnectionPhase.PERMISSION) {
            Text(state.message, Modifier.widthIn(max = 360.dp), textAlign = TextAlign.Center,
                color = if (state.phase == ConnectionPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        else Button(onClick = retry, modifier = Modifier.widthIn(max = 200.dp).fillMaxWidth()) {
            Text(if (state.phase == ConnectionPhase.DENIED) "授权" else "重新检测")
        }
    }
}
