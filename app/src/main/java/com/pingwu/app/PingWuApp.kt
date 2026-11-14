package com.pingwu.app

import android.app.Application
import com.pingwu.app.ui.theme.ThemePrefs

class PingWuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.init(this)
    }
}
