package com.fujiimport.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.fujiimport.app.ui.FujiImportApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as FujiApplication).repository.attachUi()
        enableEdgeToEdge()
        lifecycleScope.launch {
            (application as FujiApplication).repository.progress.collect { progress ->
                if (progress?.isRunning == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        setContent { FujiImportApp() }
    }

    override fun onResume() {
        super.onResume()
        (application as FujiApplication).repository.camera.checkAttachment()
    }

    override fun onDestroy() {
        if (isFinishing) (application as FujiApplication).repository.detachUi()
        super.onDestroy()
    }
}
