package dev.huenotes

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Getting attachments out of the app: to other apps through the share sheet, or into the phone's gallery / music folder. */
object AttachmentExport {
    const val AUTHORITY = "dev.huenotes.files"
    private const val STAGE_MAX_AGE_MS = 24L * 3600 * 1000

    /** A readable file name such as "Weekend trip - Photo - 2026-10-14 15.30.jpg" (receiving apps show this name). */
    fun friendlyName(a: Attachment, noteTitle: String): String {
        val kind = when (a.kind) { AttachmentKind.AUDIO -> "Voice note"; AttachmentKind.DRAWING -> "Drawing"; AttachmentKind.IMAGE -> "Photo" }
        val title = noteTitle.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().take(40).trim()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(a.createdAt))
        return listOfNotNull(title.ifBlank { null }, kind, stamp).joinToString(" - ") + "." + a.kind.ext
    }

    /** Copies the file under its readable name where the share provider can hand it out. Null if the file isn't on this device. */
    fun stage(c: Context, store: NoteStore, a: Attachment, noteTitle: String): Uri? {
        val src = store.storage.file(a)
        if (!src.exists()) return null
        sweep(c)
        val dir = File(c.cacheDir, "share/${System.nanoTime()}").apply { mkdirs() }
        val dst = File(dir, friendlyName(a, noteTitle))
        src.copyTo(dst, overwrite = true)
        return FileProvider.getUriForFile(c, AUTHORITY, dst)
    }

    /** Removes staged copies older than a day. */
    fun sweep(c: Context, now: Long = System.currentTimeMillis()) {
        File(c.cacheDir, "share").listFiles()?.forEach { if (now - it.lastModified() > STAGE_MAX_AGE_MS) it.deleteRecursively() }
    }

    /** One file → ACTION_SEND; several → ACTION_SEND_MULTIPLE. [text] rides along as the caption where the app supports it. */
    fun shareIntent(files: List<Pair<Uri, String>>, text: String?, subject: String?): Intent {
        require(files.isNotEmpty())
        val i = if (files.size == 1) Intent(Intent.ACTION_SEND).setType(files[0].second).putExtra(Intent.EXTRA_STREAM, files[0].first)
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType(commonType(files.map { it.second }))
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.first }))
        if (!text.isNullOrBlank()) i.putExtra(Intent.EXTRA_TEXT, text)
        if (!subject.isNullOrBlank()) i.putExtra(Intent.EXTRA_SUBJECT, subject)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(null, files[0].first).also { c -> files.drop(1).forEach { c.addItem(ClipData.Item(it.first)) } }
        return i
    }

    private fun commonType(types: List<String>) = when {
        types.toSet().size == 1 -> types[0]
        types.all { it.startsWith("image/") } -> "image/*"
        else -> "*/*"
    }

    /** Folder shown to the user after a save. */
    fun folderFor(kind: AttachmentKind) = if (kind == AttachmentKind.AUDIO) "Music/HueNotes" else "Pictures/HueNotes"

    /**
     * Saves into the phone's shared media folders using MediaStore (no storage permission needed on Android 10+).
     * Returns the folder name. Older Android versions use the "create document" picker instead (see [copyToUri]).
     */
    fun saveToPhone(c: Context, src: File, name: String, kind: AttachmentKind): String {
        check(Build.VERSION.SDK_INT >= 29) { "MediaStore saving needs Android 10+" }
        val folder = folderFor(kind)
        val collection = if (kind == AttachmentKind.AUDIO) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = c.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("Can't create the file")
        try {
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw IOException("Can't write the file", e) }
        return folder
    }

    /** Writes [src] to a location the person picked (Storage Access Framework). */
    fun copyToUri(c: Context, src: File, to: Uri) {
        c.contentResolver.openOutputStream(to, "wt")!!.use { copy(src, it) }
    }

    internal fun copy(src: File, out: java.io.OutputStream) { src.inputStream().use { it.copyTo(out) } }
}
