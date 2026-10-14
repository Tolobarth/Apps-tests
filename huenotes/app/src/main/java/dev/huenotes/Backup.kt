package dev.huenotes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** JSON backup of all notes (including archived). Format version 1. Import adds notes; it never overwrites. */
object Backup {
    private const val VERSION = 1

    fun toJson(notes: List<Note>): String = JSONObject()
        .put("app", "huenotes").put("version", VERSION)
        .put("notes", JSONArray(notes.map {
            JSONObject().put("title", it.title).put("body", it.body).put("checklist", it.checklist)
                .put("color", it.color).put("archived", it.archived)
                .put("reminderAt", it.reminderAt).put("modifiedAt", it.modifiedAt)
        })).toString(2)

    fun fromJson(text: String): List<Note> {
        val root = JSONObject(text)
        require(root.optString("app") == "huenotes") { "Not a HueNotes backup" }
        require(root.optInt("version") <= VERSION) { "Backup is from a newer version" }
        val arr = root.getJSONArray("notes")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Note(
                title = o.optString("title"), body = o.optString("body"), checklist = o.optBoolean("checklist"),
                color = o.optInt("color", Note.COLORS[0]), archived = o.optBoolean("archived"),
                reminderAt = o.optLong("reminderAt"), modifiedAt = o.optLong("modifiedAt", System.currentTimeMillis())
            )
        }
    }

    fun export(c: Context, store: NoteStore, to: Uri) {
        val all = store.list(false) + store.list(true)
        c.contentResolver.openOutputStream(to, "wt")!!.use { it.write(toJson(all).toByteArray()) }
    }

    /** Returns how many notes were imported. Parses fully before writing anything. */
    fun import(c: Context, store: NoteStore, from: Uri): Int {
        val text = c.contentResolver.openInputStream(from)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val notes = fromJson(text).filterNot { it.isEmpty }
        notes.forEach { n -> Reminders.schedule(c, n.copy(id = store.save(n))) }
        return notes.size
    }
}
