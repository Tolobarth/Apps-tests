package dev.huenotes

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object Prefs {
    const val SCREEN_NOTES = 0
    const val SCREEN_CALENDAR = 1

    private fun p(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 0 = follow system, 1 = light, 2 = dark. */
    fun theme(c: Context) = p(c).getInt("theme", 0)
    fun setTheme(c: Context, v: Int) { p(c).edit().putInt("theme", v).apply(); applyTheme(c) }
    fun applyTheme(c: Context) = AppCompatDelegate.setDefaultNightMode(
        when (theme(c)) { 1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
    )

    /** BCP-47 tag, empty = follow system. */
    fun language(c: Context) = p(c).getString("lang", "") ?: ""
    fun setLanguage(c: Context, tag: String) { p(c).edit().putString("lang", tag).apply(); applyLanguage(c) }
    fun applyLanguage(c: Context) {
        try {
            val want = LocaleListCompat.forLanguageTags(language(c))
            if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != want.toLanguageTags())
                AppCompatDelegate.setApplicationLocales(want)
        } catch (_: Exception) { /* locale service unavailable: fall back to system language */ }
    }

    fun defaultColor(c: Context) = p(c).getInt("color", Note.COLORS[0])
    fun setDefaultColor(c: Context, v: Int) = p(c).edit().putInt("color", v).apply()

    /** 0 small, 1 medium, 2 large. */
    fun fontSize(c: Context) = p(c).getInt("font", 1)
    fun setFontSize(c: Context, v: Int) = p(c).edit().putInt("font", v).apply()
    fun fontScale(c: Context) = when (fontSize(c)) { 0 -> 0.9f; 2 -> 1.2f; else -> 1f }

    fun defaultScreen(c: Context) = p(c).getInt("screen", SCREEN_NOTES)
    fun setDefaultScreen(c: Context, v: Int) = p(c).edit().putInt("screen", v).apply()

    fun sort(c: Context): SortMode = try { SortMode.valueOf(p(c).getString("sort", null) ?: "") } catch (_: Exception) { SortMode.MODIFIED }
    fun setSort(c: Context, v: SortMode) = p(c).edit().putString("sort", v.name).apply()

    fun grid(c: Context) = p(c).getBoolean("grid", true)
    fun setGrid(c: Context, v: Boolean) = p(c).edit().putBoolean("grid", v).apply()

    fun suggestions(c: Context) = p(c).getBoolean("suggest", true)
    fun setSuggestions(c: Context, v: Boolean) = p(c).edit().putBoolean("suggest", v).apply()

    /** 0 = follow system, otherwise a java.util.Calendar day constant. */
    fun firstDay(c: Context) = p(c).getInt("firstDay", 0)
    fun setFirstDay(c: Context, v: Int) = p(c).edit().putInt("firstDay", v).apply()

    fun widgetOpacity(c: Context) = p(c).getInt("wopacity", 100)
    fun setWidgetOpacity(c: Context, v: Int) = p(c).edit().putInt("wopacity", v).apply()

    fun syncEmail(c: Context) = p(c).getString("syncEmail", "") ?: ""
    fun setSyncEmail(c: Context, v: String) = p(c).edit().putString("syncEmail", v).apply()
    fun autoSync(c: Context) = p(c).getBoolean("autoSync", true)
    fun setAutoSync(c: Context, v: Boolean) = p(c).edit().putBoolean("autoSync", v).apply()
    fun syncError(c: Context) = p(c).getString("syncError", "").orEmpty()
    fun setSyncError(c: Context, v: String) = p(c).edit().putString("syncError", v).apply()
    fun lastSync(c: Context) = p(c).getLong("lastSync", 0)
    fun setLastSync(c: Context, v: Long) = p(c).edit().putLong("lastSync", v).apply()

    fun fetchLinkTitles(c: Context) = p(c).getBoolean("linkTitles", true)
    fun setFetchLinkTitles(c: Context, v: Boolean) = p(c).edit().putBoolean("linkTitles", v).apply()

    /** Off by default: online recognition sends the voice to the phone's speech service. */
    fun onlineSpeech(c: Context) = p(c).getBoolean("onlineSpeech", false)
    fun setOnlineSpeech(c: Context, v: Boolean) = p(c).edit().putBoolean("onlineSpeech", v).apply()

    /** Translation targets picked recently, newest first. */
    fun translateRecent(c: Context): List<String> = p(c).getString("translateRecent", "").orEmpty().split(',').filter { it.isNotBlank() }
    fun addTranslateRecent(c: Context, lang: String) =
        p(c).edit().putString("translateRecent", (listOf(lang) + translateRecent(c)).distinct().take(3).joinToString(",")).apply()

    fun backupFolder(c: Context) = p(c).getString("backupFolder", "").orEmpty()
    fun backupFolderName(c: Context) = p(c).getString("backupFolderName", "").orEmpty()
    fun setBackupFolder(c: Context, uri: String, name: String) = p(c).edit().putString("backupFolder", uri).putString("backupFolderName", name).apply()
    fun lastBackup(c: Context) = p(c).getLong("lastBackup", 0)
    fun setLastBackup(c: Context, v: Long) = p(c).edit().putLong("lastBackup", v).apply()
    fun backupError(c: Context) = p(c).getString("backupError", "").orEmpty()
    fun setBackupError(c: Context, v: String) = p(c).edit().putString("backupError", v).apply()
    fun autoSnapshots(c: Context) = p(c).getBoolean("snapshots", true)
    fun setAutoSnapshots(c: Context, v: Boolean) = p(c).edit().putBoolean("snapshots", v).apply()
    fun lastSnapshot(c: Context) = p(c).getLong("lastSnapshot", 0)
    fun setLastSnapshot(c: Context, v: Long) = p(c).edit().putLong("lastSnapshot", v).apply()
}

/** Strings for contexts without an Activity (widgets, notifications) must follow the in-app language too. */
object Loc {
    fun ctx(c: Context): Context {
        val tag = Prefs.language(c)
        if (tag.isEmpty()) return c
        val cfg = Configuration(c.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(tag))
        return c.createConfigurationContext(cfg)
    }
}
