package com.vibereading.app

import android.app.Application
import com.vibereading.app.data.local.AppDatabase

class VibeReadingApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
