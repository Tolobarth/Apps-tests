package dev.huenotes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Daily full backup (zip) into a folder the user picked, through the system file picker. Keeps the newest [KEEP]. */
object AutoBackup {
    const val KEEP = 7
    private const val WORK = "huenotes-auto-backup"
    private val NAME = Regex("huenotes-\\d{8}-\\d{6}.*\\.zip")

    fun enabled(c: Context) = Prefs.backupFolder(c).isNotEmpty()

    fun apply(c: Context) = runCatching {
        val wm = WorkManager.getInstance(c)
        if (enabled(c)) wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        else wm.cancelUniqueWork(WORK)
    }

    fun displayName(c: Context, tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        c.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: tree.lastPathSegment.orEmpty()

    /** Writes one backup now. Throws if the folder is gone or not writable. */
    fun run(c: Context, store: NoteStore, now: Long = System.currentTimeMillis()) {
        val tree = Uri.parse(Prefs.backupFolder(c).ifEmpty { throw IOException("No folder chosen") })
        val res = c.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "huenotes-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".zip"
        val doc = DocumentsContract.createDocument(res, parent, "application/zip", name) ?: throw IOException("Can’t create a file in the chosen folder")
        try { res.openOutputStream(doc)!!.use { BackupZip.write(store, it) } }
        catch (e: Exception) { runCatching { DocumentsContract.deleteDocument(res, doc) }; throw e }
        prune(c, tree)
        Prefs.setLastBackup(c, now)
    }

    private fun prune(c: Context, tree: Uri) {
        val res = c.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val old = res.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { q ->
            generateSequence { if (q.moveToNext()) q.getString(0) to q.getString(1) else null }.filter { NAME.matches(it.second.orEmpty()) }.toList()
        }.orEmpty().sortedByDescending { it.second }.drop(KEEP)
        old.forEach { runCatching { DocumentsContract.deleteDocument(res, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) } }
    }

    /** Runs a backup and records the outcome in the settings status line. */
    fun runAndRecord(c: Context): Result<Unit> = runCatching { run(c, NoteStore(c)) }
        .onSuccess { Prefs.setBackupError(c, "") }
        .onFailure { Prefs.setBackupError(c, Loc.ctx(c).getString(if (it is SecurityException || it is IOException || it is IllegalArgumentException) R.string.backup_err_folder else R.string.backup_err, it.message ?: "")) }
}

class BackupWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        if (!AutoBackup.enabled(applicationContext)) return Result.success()
        AutoBackup.runAndRecord(applicationContext)
        return Result.success()
    }
}
