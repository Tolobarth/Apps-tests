package dev.huenotes

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * If HueNotes closes because of a bug, the error is kept on the phone (never sent anywhere) and the next start offers to
 * share it. The report holds the app and Android versions, the phone model and the technical error, not notes.
 */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last-crash.txt")

    @Volatile private var app: Context? = null
    @Volatile private var installed = false

    /** Once per process; a later call only updates the context (tests create many Application objects). */
    fun install(c: Context) {
        app = c.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            app?.let { a -> runCatching { file(a).writeText(report(a, t, e)) } }
            previous?.uncaughtException(t, e)
        }
    }

    internal fun report(c: Context, t: Thread, e: Throwable): String {
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        return "HueNotes $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " · thread ${t.name}\n\n" + trace.take(12_000)
    }

    /** The report from the last crash, if it hasn't been dealt with. */
    fun pending(c: Context): String? = file(c).takeIf { it.exists() }?.readText()

    fun clear(c: Context) { file(c).delete() }

    fun shareIntent(text: String): Intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "HueNotes error report").putExtra(Intent.EXTRA_TEXT, text)
}
