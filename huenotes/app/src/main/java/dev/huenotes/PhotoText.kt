package dev.huenotes

import android.content.Context
import android.graphics.Bitmap
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * "Search text in photos": the text in photos, pictures and PDFs is read on the phone (ML Kit text recognition through Google
 * Play services) and kept next to the attachment, so search finds it. Off until the person turns it on; the recognition
 * model is downloaded once, after asking. The text read is local only (each phone reads its own copies).
 */
object PhotoText {
    /** What HueNotes needs from the recogniser; tests swap in a fake. Callbacks arrive on the main thread. */
    interface Engine {
        fun isInstalled(c: Context, done: (Boolean) -> Unit)
        /** Downloads the model; [progress] gets 0-100 when known, [done] null or why it failed. */
        fun install(c: Context, progress: (Int) -> Unit, done: (String?) -> Unit)
        /** The text in [bmp]. Blocking: worker threads only. */
        fun read(bmp: Bitmap): String
    }

    var engine: Engine = MlKit

    private const val MAX_EDGE = 2048
    private const val MAX_PDF_PAGES = 3
    private const val MAX_TEXT = 20_000

    /** The text in one photo or PDF ("" when there is none or it can't be opened). Blocking. */
    fun readAttachment(store: NoteStore, a: Attachment): String {
        val f = store.storage.file(a)
        if (!f.exists()) throw IllegalStateException("file not here yet")       // not synced yet: try again later
        val parts = ArrayList<String>()
        if (a.kind == AttachmentKind.FILE && a.mimeType == "application/pdf") {
            runCatching { AttachmentFiles.pdfPages(f, MAX_EDGE, MAX_PDF_PAGES) { parts += engine.read(it) } }
        } else AttachmentFiles.decodeLarge(f, MAX_EDGE)?.let { bmp -> try { parts += engine.read(bmp) } finally { bmp.recycle() } }
        return parts.joinToString("\n").trim().take(MAX_TEXT)
    }

    /**
     * Reads up to [max] photos not read yet. Returns how many were read; stops early when [stop] says so. Photos whose file
     * isn't on the phone yet go into [later] and are left for another time. Blocking.
     */
    fun readPending(store: NoteStore, max: Int = 50, later: MutableSet<String> = HashSet(), stop: () -> Boolean = { false }): Int {
        var n = 0
        for (a in store.photosToRead(max, later)) {
            if (stop()) break
            val text = try { readAttachment(store, a) } catch (e: IllegalStateException) { later += a.uid; continue } catch (e: Exception) { "" }
            store.setPhotoText(a.uid, text); n++
        }
        return n
    }

    /** A short piece of [text] around the first [query] match, on one line. */
    fun excerpt(text: String, query: String, radius: Int = 40): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val i = flat.indexOf(query.trim(), ignoreCase = true)
        if (i < 0) return flat.take(radius * 2)
        val from = maxOf(0, i - radius); val to = minOf(flat.length, i + query.trim().length + radius)
        return (if (from > 0) "…" else "") + flat.substring(from, to).trim() + (if (to < flat.length) "…" else "")
    }

    /** Reads new photos in the background when the feature is on. Safe to call often. */
    fun schedule(c: Context) {
        if (!Prefs.photoText(c)) return
        runCatching {
            WorkManager.getInstance(c).enqueueUniqueWork("huenotes-photo-text", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PhotoTextWorker>().setInitialDelay(2, TimeUnit.SECONDS).build())
        }
    }

    /** ML Kit text recognition (Latin script) from Google Play services; the model is fetched through ModuleInstall. */
    object MlKit : Engine {
        private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

        override fun isInstalled(c: Context, done: (Boolean) -> Unit) {
            appContext = c.applicationContext
            try {
                ModuleInstall.getClient(c).areModulesAvailable(recognizer)
                    .addOnSuccessListener { done(it.areModulesAvailable()) }.addOnFailureListener { done(false) }
            } catch (e: Exception) { done(false) }
        }

        override fun install(c: Context, progress: (Int) -> Unit, done: (String?) -> Unit) {
            appContext = c.applicationContext
            val client = ModuleInstall.getClient(c)
            var finished = false
            fun end(why: String?) { if (!finished) { finished = true; done(why) } }
            val listener = object : InstallStatusListener {
                override fun onInstallStatusUpdated(u: ModuleInstallStatusUpdate) {
                    u.progressInfo?.let { p -> if (p.totalBytesToDownload > 0) progress((p.bytesDownloaded * 100 / p.totalBytesToDownload).toInt()) }
                    when (u.installState) {
                        ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> { client.unregisterListener(this); end(null) }
                        ModuleInstallStatusUpdate.InstallState.STATE_FAILED -> { client.unregisterListener(this); end("error ${u.errorCode}") }
                        ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> { client.unregisterListener(this); end("cancelled") }
                    }
                }
            }
            try {
                client.installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).setListener(listener).build())
                    .addOnSuccessListener { r -> if (r.areModulesAlreadyInstalled()) end(null) }
                    .addOnFailureListener { end(it.message ?: it.javaClass.simpleName) }
            } catch (e: Exception) { end(e.message ?: e.javaClass.simpleName) }
        }

        override fun read(bmp: Bitmap): String = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS).text

        @Volatile var appContext: Context? = null
    }
}

/** Reads photos in batches until none are left or Android stops the work. */
class PhotoTextWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        if (!Prefs.photoText(applicationContext)) return Result.success()
        PhotoText.MlKit.appContext = applicationContext
        val store = NoteStore(applicationContext)
        CrashLog.doing(applicationContext, "photo-text")
        val later = HashSet<String>()
        while (!isStopped && PhotoText.readPending(store, 20, later) { isStopped } > 0) { }
        return Result.success()
    }
}

/** Turning "search text in photos" on: asks before the one-time download, shows its progress, then starts reading. */
object PhotoTextSetup {
    fun enable(a: android.app.Activity, done: (Boolean) -> Unit) {
        PhotoText.engine.isInstalled(a) { installed ->
            if (a.isFinishing) return@isInstalled
            if (installed) { turnOn(a); done(true); return@isInstalled }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(a).setTitle(R.string.photo_text_title)
                .setMessage(R.string.photo_text_setup_msg)
                .setPositiveButton(R.string.speech_download) { _, _ -> download(a, done) }
                .setNegativeButton(R.string.speech_not_now) { _, _ -> done(false) }
                .setOnCancelListener { done(false) }.show()
        }
    }

    private fun download(a: android.app.Activity, done: (Boolean) -> Unit) {
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(a).apply {
            isIndeterminate = true
            setPadding(Palette.dp(a, 24), Palette.dp(a, 16), Palette.dp(a, 24), Palette.dp(a, 8))
        }
        val wait = com.google.android.material.dialog.MaterialAlertDialogBuilder(a).setTitle(R.string.photo_text_downloading).setView(bar)
            .setNegativeButton(R.string.speech_hide, null).show()
        CrashLog.doing(a, "photo-text-download")
        PhotoText.engine.install(a, { p -> bar.isIndeterminate = false; bar.setProgressCompat(p, true) }) { error ->
            wait.dismiss()
            if (a.isFinishing) return@install
            if (error == null) { turnOn(a); android.widget.Toast.makeText(a, R.string.photo_text_on, android.widget.Toast.LENGTH_LONG).show(); done(true) }
            else {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                    .setMessage(a.getString(R.string.photo_text_failed) + "\n\n(" + error + ")")
                    .setPositiveButton(R.string.translate_retry) { _, _ -> download(a, done) }
                    .setNegativeButton(R.string.btn_cancel) { _, _ -> done(false) }.show()
            }
        }
    }

    private fun turnOn(c: Context) { Prefs.setPhotoText(c, true); PhotoText.schedule(c) }
}
