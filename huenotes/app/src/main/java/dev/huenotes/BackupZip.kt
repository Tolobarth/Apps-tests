package dev.huenotes

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup: `notes.json` plus `attachments/<uid>.<ext>` for every photo, drawing and recording.
 * Reading never uses an entry's name as a path (files are matched by the names the notes file lists), and sizes are capped.
 */
object BackupZip {
    const val MAX_ENTRY = 300L * 1024 * 1024
    const val MAX_TOTAL = 2L * 1024 * 1024 * 1024
    private const val MAX_JSON = 64L * 1024 * 1024

    fun write(store: NoteStore, out: OutputStream) {
        val notes = store.list(false) + store.list(true)
        val atts = store.attachmentsByNote()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("notes.json")); z.write(Backup.toJson(notes, atts).toByteArray()); z.closeEntry()
            for (a in notes.flatMap { atts[it.uid].orEmpty() }) {
                val f = store.storage.file(a)
                if (!f.exists()) continue
                z.putNextEntry(ZipEntry("attachments/" + a.fileName)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    /** Returns how many notes were added. [tmp] is a scratch folder that is removed afterwards. */
    fun read(c: Context, store: NoteStore, input: InputStream, tmp: File): Int {
        tmp.mkdirs()
        try {
            var json: String? = null
            val files = HashMap<String, File>()
            var total = 0L
            ZipInputStream(input).let { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name == "notes.json") {
                        val dest = File(tmp, "notes.json"); total += copyLimited(z, dest, MAX_JSON, MAX_TOTAL - total)
                        json = dest.readText()
                    } else if (name.startsWith("attachments/") && name.indexOf('/', 12) < 0 && !name.contains("..")) {
                        val dest = File(tmp, "a${files.size}"); total += copyLimited(z, dest, MAX_ENTRY, MAX_TOTAL - total)
                        files[name.removePrefix("attachments/")] = dest
                    }
                }
            }
            val text = json ?: throw IllegalArgumentException("Not a HueNotes backup")
            val added = Backup.importNotes(c, store, text)
            val byNote = Backup.attachmentsFromJson(text)
            for (n in added) for (a in byNote[n.uid].orEmpty()) {
                val src = files[a.fileName] ?: continue
                src.copyTo(store.storage.file(a), overwrite = true)
                store.addAttachment(a)
            }
            return added.size
        } finally { tmp.deleteRecursively() }
    }

    private fun copyLimited(from: InputStream, to: File, maxEntry: Long, maxLeft: Long): Long {
        var n = 0L
        to.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = from.read(buf); if (r < 0) break
                n += r
                if (n > maxEntry || n > maxLeft) throw IOException("Backup file is too large")
                o.write(buf, 0, r)
            }
        }
        return n
    }
}
