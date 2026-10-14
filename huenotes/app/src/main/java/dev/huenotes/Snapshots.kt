package dev.huenotes

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One automatic JSON copy a day in the app's private storage; the last [KEEP] are kept. Uninstalling removes them. */
object Snapshots {
    private const val KEEP = 7
    private const val DAY = 24L * 3600 * 1000

    private fun dir(c: Context) = File(c.filesDir, "snapshots").apply { mkdirs() }

    fun list(c: Context): List<File> =
        dir(c).listFiles { f -> f.extension == "json" }?.sortedByDescending { it.name } ?: emptyList()

    fun maybeRun(c: Context, store: NoteStore, now: Long = System.currentTimeMillis()): Boolean {
        if (!Prefs.autoSnapshots(c) || now - Prefs.lastSnapshot(c) < DAY) return false
        Prefs.setLastSnapshot(c, now)
        val notes = store.list(false) + store.list(true)
        if (notes.isEmpty()) return false
        val name = "huenotes-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".json"
        File(dir(c), name).writeText(Backup.toJson(notes))
        list(c).drop(KEEP).forEach { it.delete() }
        return true
    }
}
