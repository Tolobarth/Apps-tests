package dev.huenotes

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NoteStore(context: Context, name: String = "huenotes.db") : SQLiteOpenHelper(context.applicationContext, name, null, 3) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
               body TEXT NOT NULL, checklist INTEGER NOT NULL, color INTEGER NOT NULL,
               archived INTEGER NOT NULL, reminder_at INTEGER NOT NULL, modified_at INTEGER NOT NULL,
               created_at INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0,
               deleted_at INTEGER NOT NULL DEFAULT 0, uid TEXT NOT NULL DEFAULT '', sync_at INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE UNIQUE INDEX notes_uid ON notes(uid)")
        db.execSQL("CREATE TABLE tombstones(uid TEXT PRIMARY KEY, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE notes SET created_at = modified_at")
        }
        if (o < 3) {
            db.execSQL("ALTER TABLE notes ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN sync_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE notes SET uid = lower(hex(randomblob(16))), sync_at = modified_at")
            db.execSQL("CREATE UNIQUE INDEX notes_uid ON notes(uid)")
            db.execSQL("CREATE TABLE tombstones(uid TEXT PRIMARY KEY, at INTEGER NOT NULL)")
        }
    }

    private fun Cursor.toNote() = Note(
        getLong(0), getString(1), getString(2), getInt(3) == 1, getInt(4), getInt(5) == 1,
        getLong(6), getLong(7), getLong(8), getInt(9) == 1, getLong(10), getString(11), getLong(12)
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
            put("sync_at", System.currentTimeMillis())
        }
        val id = if (n.id == 0L) {
            v.put("created_at", n.createdAt); v.put("uid", n.uid)
            writableDatabase.insert("notes", null, v)
        } else { writableDatabase.update("notes", v, "id=?", arrayOf(n.id.toString())); n.id }
        notifyChanged()
        return id
    }

    private fun flag(id: Long, column: String, value: Long) {
        writableDatabase.update("notes", ContentValues().apply { put(column, value); put("sync_at", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString()))
        notifyChanged()
    }

    fun setArchived(id: Long, v: Boolean) = flag(id, "archived", if (v) 1 else 0)
    fun setPinned(id: Long, v: Boolean) = flag(id, "pinned", if (v) 1 else 0)
    fun trash(id: Long) = flag(id, "deleted_at", System.currentTimeMillis())
    fun restore(id: Long) = flag(id, "deleted_at", 0)

    /** Permanently removes trashed notes deleted before [olderThan]. Returns how many. */
    fun purgeTrash(olderThan: Long): Int = removeWhere("deleted_at>0 AND deleted_at<?", arrayOf(olderThan.toString()))

    fun emptyTrash() { removeWhere("deleted_at>0", emptyArray()) }

    /** Permanent delete of one note. */
    fun delete(id: Long) { removeWhere("id=?", arrayOf(id.toString())) }

    /** Deletes rows and leaves tombstones so synced devices delete them too. */
    private fun removeWhere(where: String, args: Array<String>): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.rawQuery("SELECT uid FROM notes WHERE $where", args).use { c ->
                while (c.moveToNext()) db.execSQL("INSERT OR REPLACE INTO tombstones(uid, at) VALUES(?, ?)", arrayOf(c.getString(0), now))
            }
            n = db.delete("notes", where, args)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (n > 0) notifyChanged()
        return n
    }

    // ---- sync support ------------------------------------------------------------------------

    /** Every row, including archived and trashed ones. */
    fun allForSync(): List<Note> = query("1=1", emptyArray(), "id")

    fun tombstones(): Map<String, Long> = readableDatabase.rawQuery("SELECT uid, at FROM tombstones", null)
        .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getLong(1) else null }.toMap() }

    class Applied(val upserted: List<Note>, val removedIds: List<Long>)

    /**
     * Applies a merge result in one transaction. [baseline] maps uid to the updatedAt the merge saw locally; a note edited
     * since then is left alone and picked up by the next sync.
     */
    fun applySync(upserts: List<Note>, deleteUids: Collection<String>, tombs: Map<String, Long>, baseline: Map<String, Long>): Applied {
        val db = writableDatabase
        val upserted = ArrayList<Note>(); val removed = ArrayList<Long>()
        db.beginTransaction()
        try {
            for (n in upserts) {
                val cur = query("uid=?", arrayOf(n.uid), "id").firstOrNull()
                if (cur != null && cur.updatedAt != baseline[n.uid]) continue
                val v = ContentValues().apply {
                    put("title", n.title); put("body", n.body); put("checklist", if (n.checklist) 1 else 0)
                    put("color", n.color); put("archived", if (n.archived) 1 else 0); put("pinned", if (n.pinned) 1 else 0)
                    put("reminder_at", n.reminderAt); put("modified_at", n.modifiedAt); put("created_at", n.createdAt)
                    put("deleted_at", n.deletedAt); put("sync_at", n.updatedAt); put("uid", n.uid)
                }
                val id = if (cur == null) db.insert("notes", null, v) else { db.update("notes", v, "uid=?", arrayOf(n.uid)); cur.id }
                upserted += n.copy(id = id)
            }
            for (uid in deleteUids) {
                val cur = query("uid=?", arrayOf(uid), "id").firstOrNull() ?: continue
                if (cur.updatedAt != baseline[uid]) continue
                db.delete("notes", "uid=?", arrayOf(uid)); removed += cur.id
            }
            for ((uid, at) in tombs) db.execSQL("INSERT OR REPLACE INTO tombstones(uid, at) VALUES(?, MAX(?, COALESCE((SELECT at FROM tombstones WHERE uid=?), 0)))", arrayOf(uid, at, uid))
            db.execSQL("DELETE FROM tombstones WHERE at < ?", arrayOf(System.currentTimeMillis() - SyncEngine.TOMBSTONE_KEEP_MS))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        notifyChanged()
        return Applied(upserted, removed)
    }

    private fun notifyChanged() = Widgets.refresh(appContext)

    companion object {
        const val TRASH_RETENTION_MS = 30L * 24 * 3600 * 1000
        private const val COLS = "id,title,body,checklist,color,archived,reminder_at,modified_at,created_at,pinned,deleted_at,uid,sync_at"
    }
}
