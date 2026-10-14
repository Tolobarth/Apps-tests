package dev.huenotes

import android.app.Application

class HueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
        Prefs.applyLanguage(this)
    }
}
