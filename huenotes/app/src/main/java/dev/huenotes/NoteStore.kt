package dev.huenotes

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NoteStore(context: Context) : SQLiteOpenHelper(context, "huenotes.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
               body TEXT NOT NULL, checklist INTEGER NOT NULL, color INTEGER NOT NULL,
               archived INTEGER NOT NULL, reminder_at INTEGER NOT NULL, modified_at INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = Unit

    private fun Cursor.toNote() = Note(
        getLong(0), getString(1), getString(2), getInt(3) == 1, getInt(4), getInt(5) == 1, getLong(6), getLong(7)
    )

    fun list(archived: Boolean, query: String = ""): List<Note> {
        val like = "%${query.trim()}%"
        readableDatabase.rawQuery(
            "SELECT id,title,body,checklist,color,archived,reminder_at,modified_at FROM notes " +
                "WHERE archived=? AND (title LIKE ? OR body LIKE ?) ORDER BY modified_at DESC",
            arrayOf(if (archived) "1" else "0", like, like)
        ).use { c -> return generateSequence { if (c.moveToNext()) c.toNote() else null }.toList() }
    }

    fun get(id: Long): Note? = readableDatabase.rawQuery(
        "SELECT id,title,body,checklist,color,archived,reminder_at,modified_at FROM notes WHERE id=?",
        arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.toNote() else null }

    fun upcomingReminders(now: Long): List<Note> = readableDatabase.rawQuery(
        "SELECT id,title,body,checklist,color,archived,reminder_at,modified_at FROM notes " +
            "WHERE reminder_at>? AND archived=0", arrayOf(now.toString())
    ).use { c -> generateSequence { if (c.moveToNext()) c.toNote() else null }.toList() }

    /** Inserts when id==0, otherwise updates. Returns the row id. */
    fun save(n: Note): Long {
        val v = ContentValues().apply {
            put("title", n.title); put("body", n.body); put("checklist", if (n.checklist) 1 else 0)
            put("color", n.color); put("archived", if (n.archived) 1 else 0)
            put("reminder_at", n.reminderAt); put("modified_at", System.currentTimeMillis())
        }
        return if (n.id == 0L) writableDatabase.insert("notes", null, v)
        else { writableDatabase.update("notes", v, "id=?", arrayOf(n.id.toString())); n.id }
    }

    fun delete(id: Long) { writableDatabase.delete("notes", "id=?", arrayOf(id.toString())) }
}
