package dev.huenotes

import android.app.Application

class HueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Prefs.applyTheme(this)
        Prefs.applyLanguage(this)
        SyncScheduler.apply(this)
        AutoBackup.apply(this)
    }
}
