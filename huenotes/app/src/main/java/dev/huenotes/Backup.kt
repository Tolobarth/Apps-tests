package dev.huenotes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON backup of all live notes (including archived). Format version 3 adds per-note attachment lists (the files travel in a
 * zip, see [BackupZip]); version 4 is written only when a note has a received file (PDF, document…). Older files still import.
 */
object Backup {
    private const val VERSION = 4

    fun toJson(notes: List<Note>, attachments: Map<String, List<Attachment>> = emptyMap()): String = JSONObject()
        .put("app", "huenotes").put("version", if (AttachmentJson.hasFiles(attachments)) VERSION else 3)
        .put("notes", JSONArray(notes.map {
            JSONObject().put("title", it.title).put("body", it.body).put("checklist", it.checklist)
                .put("color", it.color).put("archived", it.archived).put("pinned", it.pinned)
                .put("reminderAt", it.reminderAt).put("modifiedAt", it.modifiedAt).put("createdAt", it.createdAt).put("uid", it.uid)
                .apply {
                    val a = attachments[it.uid].orEmpty()
                    if (a.isNotEmpty()) put("attachments", JSONArray(a.map { x -> AttachmentJson.toJson(x) }))
                }
        })).toString(2)

    private val SAFE_UID = Regex("[A-Za-z0-9-]{8,64}")

    /** note uid to its attachments. Entries whose uid isn't a plain id are dropped: the uid becomes a file name. */
    fun attachmentsFromJson(text: String): Map<String, List<Attachment>> {
        val arr = JSONObject(text).optJSONArray("notes") ?: return emptyMap()
        val out = LinkedHashMap<String, List<Attachment>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val nu = o.optString("uid"); val at = o.optJSONArray("attachments") ?: continue
            val list = (0 until at.length()).map { at.getJSONObject(it) }.filter { SAFE_UID.matches(it.optString("uid")) }.map { AttachmentJson.fromJson(it, nu) }
            if (list.isNotEmpty()) out[nu] = list
        }
        return out
    }

    fun fromJson(text: String): List<Note> {
        val root = JSONObject(text)
        require(root.optString("app") == "huenotes") { "Not a HueNotes backup" }
        require(root.optInt("version") <= VERSION) { "Backup is from a newer version" }
        val arr = root.getJSONArray("notes")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val modified = o.optLong("modifiedAt", System.currentTimeMillis())
            Note(
                title = o.optString("title"), body = o.optString("body"), checklist = o.optBoolean("checklist"),
                color = o.optInt("color", Note.COLORS[0]), archived = o.optBoolean("archived"),
                pinned = o.optBoolean("pinned"), reminderAt = o.optLong("reminderAt"),
                modifiedAt = modified, createdAt = o.optLong("createdAt", modified),
                uid = o.optString("uid").ifBlank { java.util.UUID.randomUUID().toString() }
            )
        }
    }

    /** Writes the full backup (notes and their files) as a zip. */
    fun export(c: Context, store: NoteStore, to: Uri) {
        c.contentResolver.openOutputStream(to, "wt")!!.use { BackupZip.write(store, it) }
    }

    /** Reads a zip or a plain JSON backup. */
    fun import(c: Context, store: NoteStore, from: Uri): Int {
        val input = java.io.BufferedInputStream(c.contentResolver.openInputStream(from)!!)
        input.use {
            it.mark(4); val head = ByteArray(2); val n = it.read(head); it.reset()
            return if (n == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) BackupZip.read(c, store, it, java.io.File(c.cacheDir, "restore-${System.nanoTime()}"))
            else importText(c, store, it.readBytes().toString(Charsets.UTF_8))
        }
    }

    fun importText(c: Context, store: NoteStore, text: String): Int = importNotes(c, store, text).size

    /** Adds notes you don't already have (same uid, or same title, body and creation time). Returns the ones added. */
    fun importNotes(c: Context, store: NoteStore, text: String): List<Note> {
        val incoming = fromJson(text).filterNot { it.isEmpty }
        val existing = store.list(false) + store.list(true)
        val uids = existing.map { it.uid }.toHashSet()
        val have = existing.map { Triple(it.title, it.body, it.createdAt) }.toHashSet()
        val added = ArrayList<Note>()
        incoming.forEach { n ->
            if (n.uid !in uids && have.add(Triple(n.title, n.body, n.createdAt))) {
                val saved = n.copy(id = store.save(n, touch = false))
                Reminders.sync(c, saved)
                added += saved
            }
        }
        return added
    }
}
