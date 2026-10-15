package dev.huenotes

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
 * Besides Kotlin/Java crashes (caught here), Android 11+ keeps the reason for freezes (ANR) and native crashes, which are
 * read back on the next start; [doing] labels the current action so the report says what was going on.
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

    @Volatile private var action = "-"

    /** Remembers what HueNotes is doing (e.g. "transcribe"), for the report and Android's exit record. Short, no note content. */
    fun doing(c: Context, what: String) {
        action = what
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            c.getSystemService(ActivityManager::class.java).setProcessStateSummary(what.take(64).toByteArray())
        }
    }

    private fun header(c: Context): String {
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
        return "HueNotes $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n"
    }

    internal fun report(c: Context, t: Thread, e: Throwable): String {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        return header(c) + stamp(System.currentTimeMillis()) + " · crash · doing: $action · thread ${t.name}\n\n" + trace.take(12_000)
    }

    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    /** The report from the last crash, freeze or native crash, if it hasn't been dealt with. */
    fun pending(c: Context): String? = file(c).takeIf { it.exists() }?.readText() ?: exitReport(c)

    /** Marks the report as dealt with (shared or dismissed). */
    fun clear(c: Context) {
        file(c).delete()
        prefs(c).edit().putLong("exitSeen", System.currentTimeMillis()).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("crash", Context.MODE_PRIVATE)

    /** Android 11+: the last freeze or native crash (newer than the last report dealt with, at most 3 days old). */
    internal fun exitReport(c: Context, now: Long = System.currentTimeMillis()): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val seen = maxOf(prefs(c).getLong("exitSeen", 0), now - 3L * 24 * 3600 * 1000)
        val exits = runCatching { c.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(c.packageName, 0, 10) }.getOrNull() ?: return null
        val e = exits.filter { it.timestamp > seen }
            .firstOrNull { it.reason == ApplicationExitInfo.REASON_ANR || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE } ?: return null
        val kind = if (e.reason == ApplicationExitInfo.REASON_ANR) "freeze (ANR)" else "native crash"
        val doingThen = e.processStateSummary?.let { String(it) } ?: "-"
        val trace = runCatching { e.traceInputStream?.use { it.readBytes() } }.getOrNull()
        val detail = when {
            trace == null -> ""
            e.reason == ApplicationExitInfo.REASON_ANR -> mainThread(String(trace))
            else -> readable(trace)          // a tombstone in binary form: keep the readable parts (libraries, functions)
        }
        return header(c) + stamp(e.timestamp) + " · $kind · doing: $doingThen\n" + (e.description ?: "") + "\n\n" + detail.take(12_000)
    }

    /** From an ANR dump: the main thread's stack first (that's what froze), then the start of the rest. */
    internal fun mainThread(dump: String): String {
        val start = dump.indexOf("\"main\"")
        if (start < 0) return dump.take(8000)
        val end = dump.indexOf("\n\n", start).let { if (it < 0) dump.length else it }
        return dump.substring(start, end) + "\n\n" + dump.take(4000)
    }

    /** Printable runs of 6+ characters from binary data, one per line. */
    internal fun readable(bytes: ByteArray): String {
        val out = StringBuilder(); val run = StringBuilder()
        fun flush() { if (run.length >= 6) out.append(run).append('\n'); run.setLength(0) }
        for (b in bytes) { val ch = b.toInt() and 0xFF; if (ch in 32..126) run.append(ch.toChar()) else flush() }
        flush()
        return out.toString()
    }

    fun shareIntent(text: String): Intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "HueNotes error report").putExtra(Intent.EXTRA_TEXT, text)
}
