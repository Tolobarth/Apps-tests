package dev.huenotes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** JSON backup of all live notes (including archived). Format version 2; version 1 files still import. */
object Backup {
    private const val VERSION = 2

    fun toJson(notes: List<Note>): String = JSONObject()
        .put("app", "huenotes").put("version", VERSION)
        .put("notes", JSONArray(notes.map {
            JSONObject().put("title", it.title).put("body", it.body).put("checklist", it.checklist)
                .put("color", it.color).put("archived", it.archived).put("pinned", it.pinned)
                .put("reminderAt", it.reminderAt).put("modifiedAt", it.modifiedAt).put("createdAt", it.createdAt).put("uid", it.uid)
        })).toString(2)

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

    fun export(c: Context, store: NoteStore, to: Uri) {
        c.contentResolver.openOutputStream(to, "wt")!!.use { it.write(toJson(store.list(false) + store.list(true)).toByteArray()) }
    }

    fun import(c: Context, store: NoteStore, from: Uri): Int =
        importText(c, store, c.contentResolver.openInputStream(from)!!.use { it.readBytes().toString(Charsets.UTF_8) })

    /** Adds notes you don't already have (same title, body and creation time). Parses fully before writing. */
    fun importText(c: Context, store: NoteStore, text: String): Int {
        val incoming = fromJson(text).filterNot { it.isEmpty }
        val existing = store.list(false) + store.list(true)
        val uids = existing.map { it.uid }.toHashSet()
        val have = existing.map { Triple(it.title, it.body, it.createdAt) }.toHashSet()
        var added = 0
        incoming.forEach { n ->
            if (n.uid !in uids && have.add(Triple(n.title, n.body, n.createdAt))) {
                Reminders.sync(c, n.copy(id = store.save(n, touch = false)))
                added++
            }
        }
        return added
    }
}
