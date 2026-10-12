package com.fujiimport.app

import android.app.Application
import com.fujiimport.app.data.repository.FujiRepository

class FujiApplication : Application() {
    lateinit var repository: FujiRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = FujiRepository(this)
        repository.start()
    }
}
