package dev.huenotes

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import java.io.IOException
import java.io.InputStream

/**
 * "Share → Save to HueNotes" with photos, PDFs and any other file. Everything shared at once becomes one note: shared text is
 * its body, photos are stored like camera photos (scaled down), other files are kept byte for byte under their own name.
 */
object SharedFiles {
    /** Largest file kept as is: sync holds a whole file in memory, and a note is no place for a film. */
    const val MAX_FILE = 25L * 1024 * 1024
    private const val MAX_TEXT_NOTE = 1L * 1024 * 1024

    class Incoming(val name: String, val mime: String, val size: Long, val open: () -> InputStream)

    /** [note] is null when nothing could be saved. [failed] counts files that were too large or unreadable. */
    class Result(val note: Note?, val added: Int, val failed: Int)

    /**
     * The content:// addresses an ACTION_SEND / ACTION_SEND_MULTIPLE intent carries. file:// addresses are refused (they could
     * name this app's own private files), and so are addresses served by this app.
     */
    fun streams(i: Intent): List<Uri> {
        val raw = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
            else -> emptyList()
        }
        return raw.filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority?.startsWith("dev.huenotes") == false }.distinct()
    }

    /** Name, type and size as the sending app reports them; [typeHint] is the intent's type, used when the app gives none. */
    fun describe(r: ContentResolver, uri: Uri, typeHint: String?): Incoming {
        var name: String? = null
        var size = -1L
        runCatching {
            r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        val rawName = name ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        val mime = runCatching { r.getType(uri) }.getOrNull()?.takeIf { '/' in it && '*' !in it }
            ?: typeHint?.takeIf { '/' in it && '*' !in it }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(rawName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return Incoming(cleanName(rawName), mime.lowercase(), size) { r.openInputStream(uri) ?: throw IOException("Can't open $uri") }
    }

    /** A name that is safe and readable as a file name: no folders, no control characters, at most 100 characters. */
    fun cleanName(raw: String): String {
        val s = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trim('.').trim()
        if (s.isEmpty()) return "file"
        if (s.length <= 100) return s
        val ext = s.substringAfterLast('.', "").takeIf { it.length in 1..8 }
        return if (ext == null) s.take(100) else s.dropLast(ext.length + 1).take(99 - ext.length).trimEnd() + "." + ext
    }

    /** Photos are stored like camera photos; animated GIFs, SVGs and everything else are kept untouched. */
    fun kindFor(mime: String) =
        if (mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml") AttachmentKind.IMAGE else AttachmentKind.FILE

    private fun isTextFile(f: Incoming) =
        f.mime == "text/plain" || f.mime == "text/markdown" || f.mime == "text/x-markdown" ||
            f.name.endsWith(".txt", true) || f.name.endsWith(".md", true)

    /** Saves what was shared as one note. Blocking (copies files): call off the main thread. */
    fun receive(c: Context, store: NoteStore, text: String?, subject: String?, color: Int, files: List<Incoming>): Result {
        // A single shared .txt / .md file reads better as the note itself than as an attachment, like Import does.
        if (files.size == 1 && text.isNullOrBlank() && isTextFile(files[0]) && files[0].size <= MAX_TEXT_NOTE) {
            val content = runCatching { files[0].open().use { readLimited(it, MAX_TEXT_NOTE) } }.getOrNull()
            val n = content?.let { Importers.fromText(files[0].name, it).note.copy(color = color, modifiedAt = System.currentTimeMillis()) }
            if (n != null && !n.isEmpty) return Result(n.copy(id = store.save(n)), 0, 0)
        }
        val subj = subject?.trim().orEmpty()
        val single = files.singleOrNull()
        val fallbackTitle = subj.ifEmpty { if (single != null && kindFor(single.mime) == AttachmentKind.FILE) single.name.substringBeforeLast('.').trim() else "" }
        val fromText = ShareIntake.fromSharedText(text, subject, color)
        val note = fromText ?: Note(title = fallbackTitle, color = color)
        val added = files.mapNotNull { keep(c, store.storage, note.uid, it) }
        val failed = files.size - added.size
        if (fromText == null && added.isEmpty()) return Result(null, 0, failed)      // a title alone is not worth a note
        val saved = note.copy(id = store.save(note))
        added.forEach { store.addAttachment(it) }
        return Result(saved, added.size, failed)
    }

    /** Copies one file into the app's storage. Null (and nothing left behind) when it is too large or can't be read. */
    internal fun keep(c: Context, storage: AttachmentStorage, noteUid: String, f: Incoming): Attachment? {
        if (kindFor(f.mime) == AttachmentKind.IMAGE) {
            val a = Attachment(noteUid = noteUid, kind = AttachmentKind.IMAGE)
            try { AttachmentFiles.importImage(f.open, storage.file(a)); return a }
            catch (e: Exception) { storage.file(a).delete() }      // not decodable here (e.g. a RAW photo): keep it as a file
        }
        if (f.size > MAX_FILE) return null
        val a = Attachment(noteUid = noteUid, kind = AttachmentKind.FILE, name = f.name, mime = f.mime)
        return try {
            f.open().use { BackupZip.copyLimited(it, storage.file(a), MAX_FILE, MAX_FILE) }
            a
        } catch (e: Exception) { storage.file(a).delete(); null }
    }

    private fun readLimited(input: InputStream, max: Long): String {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024)
        while (true) {
            val r = input.read(buf); if (r < 0) break
            out.write(buf, 0, r); if (out.size() > max) throw IOException("Too large for a note")
        }
        return out.toString("UTF-8")
    }
}
