package dev.huenotes

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Brings in notes from other apps: Google Keep (Takeout), and plain text or Markdown files. */
object Importers {
    /** A note ready to save, plus the picture/audio files it references (by the name they have inside the source). */
    class Parsed(val note: Note, val files: List<Pair<String, AttachmentKind>>)

    private val KEEP_COLORS = mapOf(
        "RED" to 2, "ORANGE" to 1, "YELLOW" to 0, "GREEN" to 3, "TEAL" to 6, "BLUE" to 4, "CERULEAN" to 4,
        "PURPLE" to 5, "PINK" to 7, "BROWN" to 1, "GRAY" to 0, "DEFAULT" to 0,
    )

    /** One Google Keep Takeout note (.json). Returns null for notes in Keep's trash or files that aren't notes. */
    fun fromKeepJson(text: String, now: Long = System.currentTimeMillis()): Parsed? {
        val o = try { JSONObject(text) } catch (_: Exception) { return null }
        if (!o.has("textContent") && !o.has("listContent") && !o.has("title")) return null
        if (o.optBoolean("isTrashed")) return null
        val list = o.optJSONArray("listContent")
        val checklist = list != null && list.length() > 0
        val body = if (checklist) Checklist.serialize((0 until list!!.length()).map { list.getJSONObject(it) }
            .map { Checklist.Item(it.optBoolean("isChecked"), it.optString("text").replace('\n', ' ').trim()) }.filter { it.text.isNotEmpty() })
        else o.optString("textContent")
        val modified = o.optLong("userEditedTimestampUsec", 0) / 1000
        val created = o.optLong("createdTimestampUsec", 0) / 1000
        val files = o.optJSONArray("attachments")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { x ->
                val path = x.optString("filePath"); val mime = x.optString("mimetype")
                when {
                    path.isBlank() -> null
                    mime.startsWith("image/") -> path to AttachmentKind.IMAGE
                    mime.startsWith("audio/") -> path to AttachmentKind.AUDIO
                    else -> null
                }
            }
        }.orEmpty()
        val note = Note(
            title = o.optString("title").trim(), body = body, checklist = checklist,
            color = Note.COLORS[KEEP_COLORS[o.optString("color").uppercase()] ?: 0],
            archived = o.optBoolean("isArchived"), pinned = o.optBoolean("isPinned"),
            modifiedAt = if (modified > 0) modified else now, createdAt = if (created > 0) created else (if (modified > 0) modified else now),
            labels = o.optJSONArray("labels")?.let { a -> Labels.normalize((0 until a.length()).map { a.optJSONObject(it)?.optString("name").orEmpty() }) }.orEmpty(),
        )
        return Parsed(note, files)
    }

    private val TASK = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s+(.*)$""")

    /** A .txt or .md file: a leading "# Heading" becomes the title, otherwise the file name does. All-checkbox files become lists. */
    fun fromText(fileName: String, text: String, now: Long = System.currentTimeMillis()): Parsed {
        val lines = text.replace("\r\n", "\n").lines()
        val firstIdx = lines.indexOfFirst { it.isNotBlank() }
        val first = lines.getOrNull(firstIdx)
        var title = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        var rest = lines
        if (first != null && Regex("""^#{1,6}\s+\S.*""").matches(first)) {
            title = first.trimStart('#').trim(); rest = lines.drop(firstIdx + 1)
        }
        val nonBlank = rest.filter { it.isNotBlank() }
        val tasks = nonBlank.map { TASK.matchEntire(it) }
        val checklist = nonBlank.isNotEmpty() && tasks.all { it != null }
        val body = if (checklist) Checklist.serialize(tasks.map { m ->
            Checklist.Item(m!!.groupValues[2].isNotBlank(), m.groupValues[3].trim(), if (m.groupValues[1].isNotEmpty()) 1 else 0)
        }) else rest.joinToString("\n").trim('\n')
        return Parsed(Note(title = title, body = body, checklist = checklist, modifiedAt = now, createdAt = now), emptyList())
    }

    /**
     * Saves new notes (skipping ones already there: same title and text) and attaches their files from [files],
     * a map of source file name to a readable file. Returns how many notes were added.
     */
    fun save(c: Context, store: NoteStore, parsed: List<Parsed>, files: Map<String, File>): Int {
        val existing = (store.list(false) + store.list(true)).map { it.title to it.body }.toHashSet()
        var added = 0
        for (p in parsed) {
            if (p.note.isEmpty && p.files.isEmpty()) continue
            if (!existing.add(p.note.title to p.note.body)) continue
            val id = store.save(p.note, touch = false)
            val saved = p.note.copy(id = id, uid = store.get(id)!!.uid)
            for ((name, kind) in p.files) {
                val src = files[name.substringAfterLast('/')] ?: continue
                val a = Attachment(noteUid = saved.uid, kind = kind)
                try {
                    if (kind == AttachmentKind.IMAGE) AttachmentFiles.importImage(c.contentResolver, Uri.fromFile(src), store.storage.file(a))
                    else src.copyTo(store.storage.file(a), overwrite = true)
                    store.addAttachment(a)
                } catch (e: Exception) { store.storage.file(a).delete() }
            }
            Reminders.sync(c, saved)
            added++
        }
        return added
    }

    enum class Source { KEEP, TEXT }

    /** Reads the picked files (Takeout zip, loose Keep .json, .txt/.md) and saves what is new. Returns the number of notes added. */
    fun importUris(c: Context, store: NoteStore, uris: List<Uri>, source: Source): Int {
        val tmp = File(c.cacheDir, "import-${System.nanoTime()}").apply { mkdirs() }
        try {
            val parsed = ArrayList<Parsed>(); val files = HashMap<String, File>()
            var budget = BackupZip.MAX_TOTAL
            for (uri in uris) {
                val name = displayName(c, uri)
                val bytes = c.contentResolver.openInputStream(uri)!!.buffered()
                bytes.use { input ->
                    input.mark(4); val head = ByteArray(2); val n = input.read(head); input.reset()
                    val isZip = n == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                    if (isZip) budget -= readZip(input, source, tmp, parsed, files, budget)
                    else {
                        val text = readText(input)
                        if (source == Source.KEEP) fromKeepJson(text)?.let { parsed += it }
                        else parsed += fromText(name, text)
                    }
                }
            }
            return save(c, store, parsed, files)
        } finally { tmp.deleteRecursively() }
    }

    private fun readText(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024); var total = 0L
        while (true) {
            val r = input.read(buf); if (r < 0) break
            total += r; if (total > 16L * 1024 * 1024) throw java.io.IOException("File is too large to import as a note")
            out.write(buf, 0, r)
        }
        return out.toString("UTF-8")
    }

    private fun readZip(input: InputStream, source: Source, tmp: File, parsed: MutableList<Parsed>, files: MutableMap<String, File>, budget: Long): Long {
        var used = 0L
        val z = ZipInputStream(input)
        while (true) {
            val e = z.nextEntry ?: break
            if (e.isDirectory) continue
            val base = e.name.substringAfterLast('/')
            val ext = base.substringAfterLast('.', "").lowercase()
            if (base.isEmpty() || e.name.contains("..")) continue
            when {
                source == Source.KEEP && ext == "json" -> {
                    val dest = File(tmp, "j${parsed.size}-${files.size}"); used += BackupZip.copyLimited(z, dest, 16L * 1024 * 1024, budget - used)
                    fromKeepJson(dest.readText())?.let { parsed += it }; dest.delete()
                }
                source == Source.KEEP && ext in setOf("jpg", "jpeg", "png", "webp", "gif", "3gp", "m4a", "mp3", "aac", "amr") -> {
                    val dest = File(tmp, "f${files.size}"); used += BackupZip.copyLimited(z, dest, BackupZip.MAX_ENTRY, budget - used)
                    files[base] = dest
                }
                source == Source.TEXT && (ext == "txt" || ext == "md" || ext == "markdown") -> {
                    val dest = File(tmp, "t${parsed.size}"); used += BackupZip.copyLimited(z, dest, 16L * 1024 * 1024, budget - used)
                    parsed += fromText(base, dest.readText()); dest.delete()
                }
            }
        }
        return used
    }

    private fun displayName(c: Context, uri: Uri): String =
        runCatching {
            c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
}
