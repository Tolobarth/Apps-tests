package dev.huenotes

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NoteStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "huenotes.db", null, 2) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
               body TEXT NOT NULL, checklist INTEGER NOT NULL, color INTEGER NOT NULL,
               archived INTEGER NOT NULL, reminder_at INTEGER NOT NULL, modified_at INTEGER NOT NULL,
               created_at INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0,
               deleted_at INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE notes SET created_at = modified_at")
        }
    }

    private fun Cursor.toNote() = Note(
        getLong(0), getString(1), getString(2), getInt(3) == 1, getInt(4), getInt(5) == 1,
        getLong(6), getLong(7), getLong(8), getInt(9) == 1, getLong(10)
    )

    private fun query(where: String, args: Array<String>, order: String): List<Note> =
        readableDatabase.rawQuery("SELECT $COLS FROM notes WHERE $where ORDER BY $order", args)
            .use { c -> generateSequence { if (c.moveToNext()) c.toNote() else null }.toList() }

    /** Live notes (not in the trash), newest edit first; sorting for display is done by [Sorter]. */
    fun list(archived: Boolean, query: String = ""): List<Note> {
        val like = "%${query.trim()}%"
        return query("archived=? AND deleted_at=0 AND (title LIKE ? OR body LIKE ?)",
            arrayOf(if (archived) "1" else "0", like, like), "modified_at DESC")
    }

    fun trashed(): List<Note> = query("deleted_at>0", emptyArray(), "deleted_at DESC")

    fun get(id: Long): Note? = query("id=?", arrayOf(id.toString()), "id").firstOrNull()

    fun reminderBetween(from: Long, to: Long): List<Note> =
        query("reminder_at>=? AND reminder_at<? AND archived=0 AND deleted_at=0", arrayOf(from.toString(), to.toString()), "reminder_at")

    fun upcomingReminders(now: Long): List<Note> =
        query("reminder_at>? AND archived=0 AND deleted_at=0", arrayOf(now.toString()), "reminder_at")

    /** Inserts when id==0, otherwise updates. [touch] stamps modified_at with now (imports keep the original). */
    fun save(n: Note, touch: Boolean = true): Long {
        val v = ContentValues().apply {
            put("title", n.title); put("body", n.body); put("checklist", if (n.checklist) 1 else 0)
            put("color", n.color); put("archived", if (n.archived) 1 else 0); put("pinned", if (n.pinned) 1 else 0)
            put("reminder_at", n.reminderAt); put("modified_at", if (touch) System.currentTimeMillis() else n.modifiedAt)
        }
        val id = if (n.id == 0L) {
            v.put("created_at", n.createdAt)
            writableDatabase.insert("notes", null, v)
        } else { writableDatabase.update("notes", v, "id=?", arrayOf(n.id.toString())); n.id }
        notifyChanged()
        return id
    }

    private fun flag(id: Long, column: String, value: Long) {
        writableDatabase.update("notes", ContentValues().apply { put(column, value) }, "id=?", arrayOf(id.toString()))
        notifyChanged()
    }

    fun setArchived(id: Long, v: Boolean) = flag(id, "archived", if (v) 1 else 0)
    fun setPinned(id: Long, v: Boolean) = flag(id, "pinned", if (v) 1 else 0)
    fun trash(id: Long) = flag(id, "deleted_at", System.currentTimeMillis())
    fun restore(id: Long) = flag(id, "deleted_at", 0)

    /** Permanently removes trashed notes deleted before [olderThan]. Returns how many. */
    fun purgeTrash(olderThan: Long): Int =
        writableDatabase.delete("notes", "deleted_at>0 AND deleted_at<?", arrayOf(olderThan.toString())).also { if (it > 0) notifyChanged() }

    fun emptyTrash() { writableDatabase.delete("notes", "deleted_at>0", null); notifyChanged() }

    /** Permanent delete of one note. */
    fun delete(id: Long) { writableDatabase.delete("notes", "id=?", arrayOf(id.toString())); notifyChanged() }

    private fun notifyChanged() = Widgets.refresh(appContext)

    companion object {
        const val TRASH_RETENTION_MS = 30L * 24 * 3600 * 1000
        private const val COLS = "id,title,body,checklist,color,archived,reminder_at,modified_at,created_at,pinned,deleted_at"
    }
}
