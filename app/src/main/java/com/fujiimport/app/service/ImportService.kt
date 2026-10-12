package com.fujiimport.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.fujiimport.app.FujiApplication
import com.fujiimport.app.MainActivity
import com.fujiimport.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as FujiApplication).repository
    private val manager get() = getSystemService(NotificationManager::class.java)
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "照片导入", NotificationManager.IMPORTANCE_LOW))
        if (!showNotification("准备导入照片", starting = true)) return
        scope.launch {
            repository.progress.collect { progress ->
                if (progress != null) {
                    if (progress.isRunning) {
                        if (!showNotification("已处理 ${progress.processed} / ${progress.total} 张")) return@collect
                        delay(250)
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            START -> repository.runQueuedImport()
            CANCEL -> repository.cancelImport()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (repository.progress.value?.isRunning == true) repository.cancelImport()
        repository.releaseCameraIfUiClosed()
        scope.cancel()
        super.onDestroy()
    }

    private fun showNotification(text: String, starting: Boolean = false): Boolean = try {
        // Foreground-service notifications use startForeground; ordinary notifications need a separate grant.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (error: Exception) {
        repository.diagnostics.event("foreground_service_failed", error)
        if (repository.progress.value?.isRunning == true) {
            repository.cancelImport()
            if (starting) repository.failQueuedImport()
        }
        stopSelf()
        false
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, ImportService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Fuji Import")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "取消导入", cancel)
            .build()
    }

    companion object {
        const val START = "com.fujiimport.app.START_IMPORT"
        const val CANCEL = "com.fujiimport.app.CANCEL_IMPORT"
        private const val CHANNEL = "photo-import"
        private const val NOTIFICATION_ID = 1
    }
}
