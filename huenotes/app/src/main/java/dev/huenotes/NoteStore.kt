package dev.huenotes

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NoteStore(
    context: Context,
    name: String = "huenotes.db",
    val storage: AttachmentStorage = AttachmentStorage.of(context),
) : SQLiteOpenHelper(context.applicationContext, name, null, 8) {
    /** Clock for version spacing; tests replace it. */
    var clock: () -> Long = System::currentTimeMillis
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
               body TEXT NOT NULL, checklist INTEGER NOT NULL, color INTEGER NOT NULL,
               archived INTEGER NOT NULL, reminder_at INTEGER NOT NULL, modified_at INTEGER NOT NULL,
               created_at INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0,
               deleted_at INTEGER NOT NULL DEFAULT 0, uid TEXT NOT NULL DEFAULT '', sync_at INTEGER NOT NULL DEFAULT 0,
               labels TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE UNIQUE INDEX notes_uid ON notes(uid)")
        db.execSQL("CREATE TABLE tombstones(uid TEXT PRIMARY KEY, at INTEGER NOT NULL)")
        createAttachments(db)
        createVersions(db)
        db.execSQL("CREATE TABLE sync_base(uid TEXT PRIMARY KEY, at INTEGER NOT NULL)")
    }

    private fun createVersions(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE note_versions(id INTEGER PRIMARY KEY AUTOINCREMENT, note_uid TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, checklist INTEGER NOT NULL, saved_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX note_versions_uid ON note_versions(note_uid)")
    }

    private fun createAttachments(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE attachments(uid TEXT PRIMARY KEY, note_uid TEXT NOT NULL, kind INTEGER NOT NULL, duration INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, name TEXT NOT NULL DEFAULT '', mime TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX attachments_note ON attachments(note_uid)")
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
        if (o < 4) createAttachments(db)
        if (o < 5) createVersions(db)
        if (o < 6) db.execSQL("CREATE TABLE sync_base(uid TEXT PRIMARY KEY, at INTEGER NOT NULL)")
        if (o < 8) db.execSQL("ALTER TABLE notes ADD COLUMN labels TEXT NOT NULL DEFAULT ''")
        if (o in 4..6) {      // attachments created by v4-v6 lack the file name and type columns
            db.execSQL("ALTER TABLE attachments ADD COLUMN name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE attachments ADD COLUMN mime TEXT NOT NULL DEFAULT ''")
        }
    }

    private fun Cursor.toNote() = Note(
        getLong(0), getString(1), getString(2), getInt(3) == 1, getInt(4), getInt(5) == 1,
        getLong(6), getLong(7), getLong(8), getInt(9) == 1, getLong(10), getString(11), getLong(12), Labels.decode(getString(13))
    )

    private fun query(where: String, args: Array<String>, order: String): List<Note> =
        readableDatabase.rawQuery("SELECT $COLS FROM notes WHERE $where ORDER BY $order", args)
            .use { c -> generateSequence { if (c.moveToNext()) c.toNote() else null }.toList() }

    /** Live notes (not in the trash), newest edit first; sorting for display is done by [Sorter]. */
    fun list(archived: Boolean, query: String = ""): List<Note> {
        val like = "%${query.trim()}%"
        return query("archived=? AND deleted_at=0 AND (title LIKE ? OR body LIKE ? OR labels LIKE ?)",
            arrayOf(if (archived) "1" else "0", like, like, like), "modified_at DESC")
    }

    fun trashed(): List<Note> = query("deleted_at>0", emptyArray(), "deleted_at DESC")

    fun get(id: Long): Note? = query("id=?", arrayOf(id.toString()), "id").firstOrNull()

    fun reminderBetween(from: Long, to: Long): List<Note> =
        query("reminder_at>=? AND reminder_at<? AND archived=0 AND deleted_at=0", arrayOf(from.toString(), to.toString()), "reminder_at")

    fun upcomingReminders(now: Long): List<Note> =
        query("reminder_at>? AND archived=0 AND deleted_at=0", arrayOf(now.toString()), "reminder_at")

    /** Inserts when id==0, otherwise updates. [touch] stamps modified_at with now (imports keep the original). */
    fun save(n: Note, touch: Boolean = true): Long {
        if (n.id != 0L) get(n.id)?.let { if (it.title != n.title || it.body != n.body) snapshot(it, force = false) }
        val v = ContentValues().apply {
            put("title", n.title); put("body", n.body); put("checklist", if (n.checklist) 1 else 0)
            put("color", n.color); put("archived", if (n.archived) 1 else 0); put("pinned", if (n.pinned) 1 else 0)
            put("reminder_at", n.reminderAt); put("modified_at", if (touch) System.currentTimeMillis() else n.modifiedAt)
            put("labels", Labels.encode(n.labels))
            put("sync_at", System.currentTimeMillis())
        }
        val id = if (n.id == 0L) {
            v.put("created_at", n.createdAt); v.put("uid", n.uid)
            writableDatabase.insert("notes", null, v)
        } else { writableDatabase.update("notes", v, "id=?", arrayOf(n.id.toString())); n.id }
        notifyChanged()
        return id
    }

    // ---- sync baseline -----------------------------------------------------------------------

    /** uid to the updatedAt both sides agreed on at the last completed sync (used to spot edits made on two devices). */
    fun syncBase(): Map<String, Long> =
        readableDatabase.rawQuery("SELECT uid, at FROM sync_base", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getLong(1) else null }.toMap()
        }

    fun setSyncBase(base: Map<String, Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM sync_base")
            for ((u, at) in base) db.execSQL("INSERT INTO sync_base(uid, at) VALUES(?,?)", arrayOf(u, at))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---- version history ---------------------------------------------------------------------

    class Version(val id: Long, val title: String, val body: String, val checklist: Boolean, val savedAt: Long)

    /**
     * Keeps [old] (the content about to be replaced) as a version. A burst of edits within [VERSION_SPACING_MS] keeps only the
     * first pre-edit state, unless [force] (used before a sync overwrites local content). Blank and duplicate states are skipped.
     */
    private fun snapshot(old: Note, force: Boolean) {
        if (old.title.isBlank() && old.body.isBlank()) return
        val db = writableDatabase
        val now = clock()
        db.rawQuery("SELECT title, body, saved_at FROM note_versions WHERE note_uid=? ORDER BY id DESC LIMIT 1", arrayOf(old.uid)).use { c ->
            if (c.moveToFirst()) {
                if (c.getString(0) == old.title && c.getString(1) == old.body) return
                if (!force && now - c.getLong(2) < VERSION_SPACING_MS) return
            }
        }
        db.execSQL("INSERT INTO note_versions(note_uid, title, body, checklist, saved_at) VALUES(?,?,?,?,?)",
            arrayOf(old.uid, old.title, old.body, if (old.checklist) 1 else 0, now))
        db.execSQL("DELETE FROM note_versions WHERE note_uid=? AND id NOT IN (SELECT id FROM note_versions WHERE note_uid=? ORDER BY id DESC LIMIT $MAX_VERSIONS)", arrayOf(old.uid, old.uid))
    }

    /** Keeps [n]'s current content as a version right now (before a big change such as replacing it with a translation). */
    fun keepVersion(n: Note) = snapshot(n, force = true)

    /** Earlier versions of a note, newest first. */
    fun versions(noteUid: String): List<Version> =
        readableDatabase.rawQuery("SELECT id, title, body, checklist, saved_at FROM note_versions WHERE note_uid=? ORDER BY id DESC", arrayOf(noteUid)).use { c ->
            generateSequence { if (c.moveToNext()) Version(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getLong(4)) else null }.toList()
        }

    private fun flag(id: Long, column: String, value: Long) {
        writableDatabase.update("notes", ContentValues().apply { put(column, value); put("sync_at", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString()))
        notifyChanged()
    }

    /** Fills in a title only if the note still has none (used for fetched page titles). */
    fun setTitleIfBlank(id: Long, title: String) {
        val n = writableDatabase.update("notes", ContentValues().apply { put("title", title); put("sync_at", System.currentTimeMillis()) }, "id=? AND title=''", arrayOf(id.toString()))
        if (n > 0) notifyChanged()
    }

    fun setArchived(id: Long, v: Boolean) = flag(id, "archived", if (v) 1 else 0)

    /** Changing labels counts as a change for sync, but not as an edit ("last edited" stays). */
    fun setLabels(id: Long, labels: List<String>) {
        writableDatabase.update("notes", ContentValues().apply { put("labels", Labels.encode(labels)); put("sync_at", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString()))
        notifyChanged()
    }

    /** Renames (or, with [to] null, removes) a label on every note that has it, trash included. Returns how many notes changed. */
    fun relabel(from: String, to: String?): Int {
        val affected = query("labels != ''", emptyArray(), "id").filter { Labels.has(it, from) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (n in affected) {
                val next = if (to == null) Labels.remove(n.labels, from) else Labels.rename(n.labels, from, to)
                db.update("notes", ContentValues().apply { put("labels", Labels.encode(next)); put("sync_at", now) }, "id=?", arrayOf(n.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (affected.isNotEmpty()) notifyChanged()
        return affected.size
    }
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
        val doomed = attachmentsWhere("note_uid IN (SELECT uid FROM notes WHERE $where)", args)
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.execSQL("DELETE FROM attachments WHERE note_uid IN (SELECT uid FROM notes WHERE $where)", args)
            db.execSQL("DELETE FROM note_versions WHERE note_uid IN (SELECT uid FROM notes WHERE $where)", args)
            db.rawQuery("SELECT uid FROM notes WHERE $where", args).use { c ->
                while (c.moveToNext()) db.execSQL("INSERT OR REPLACE INTO tombstones(uid, at) VALUES(?, ?)", arrayOf(c.getString(0), now))
            }
            n = db.delete("notes", where, args)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        doomed.forEach { storage.file(it).delete() }
        if (n > 0) notifyChanged()
        return n
    }

    // ---- attachments -------------------------------------------------------------------------

    private fun attachmentsWhere(where: String, args: Array<String>): List<Attachment> =
        readableDatabase.rawQuery("SELECT $ACOLS FROM attachments WHERE $where ORDER BY created_at, rowid", args).use { c ->
            generateSequence {
                if (c.moveToNext()) Attachment(c.getString(0), c.getString(1), AttachmentKind.of(c.getInt(2)), c.getLong(3), c.getLong(4), c.getString(5), c.getString(6)) else null
            }.toList()
        }

    fun attachmentsFor(noteUid: String): List<Attachment> = attachmentsWhere("note_uid=?", arrayOf(noteUid))
    fun attachmentsByNote(): Map<String, List<Attachment>> = attachmentsWhere("1=1", emptyArray()).groupBy { it.noteUid }

    /** Adding or removing an attachment counts as a change to its note, so sync picks it up. */
    fun addAttachment(a: Attachment) {
        insertAttachment(writableDatabase, a)
        touch(a.noteUid)
    }

    private fun insertAttachment(db: SQLiteDatabase, a: Attachment) =
        db.execSQL("INSERT OR REPLACE INTO attachments(uid, note_uid, kind, duration, created_at, name, mime) VALUES(?,?,?,?,?,?,?)",
            arrayOf(a.uid, a.noteUid, a.kind.code, a.durationMs, a.createdAt, a.name, a.mime))

    fun removeAttachment(a: Attachment) {
        writableDatabase.execSQL("DELETE FROM attachments WHERE uid=?", arrayOf(a.uid))
        storage.file(a).delete()
        touch(a.noteUid)
    }

    private fun touch(noteUid: String) {
        writableDatabase.execSQL("UPDATE notes SET sync_at=? WHERE uid=?", arrayOf(System.currentTimeMillis(), noteUid))
        notifyChanged()
    }

    /** Drops attachment rows whose note is gone and files nothing refers to. Call from the main screen, not mid-edit. */
    fun cleanupAttachments(): Int {
        val db = writableDatabase
        val orphans = attachmentsWhere("note_uid NOT IN (SELECT uid FROM notes)", emptyArray())
        orphans.forEach { db.execSQL("DELETE FROM attachments WHERE uid=?", arrayOf(it.uid)) }
        val known = attachmentsWhere("1=1", emptyArray()).map { it.fileName }.toHashSet()
        var removed = orphans.size
        storage.dir.listFiles()?.forEach { if (it.name !in known && it.delete()) removed++ }
        orphans.forEach { storage.file(it).delete() }
        return removed
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
    fun applySync(
        upserts: List<Note>, deleteUids: Collection<String>, tombs: Map<String, Long>, baseline: Map<String, Long>,
        upsertAttachments: Map<String, List<Attachment>> = emptyMap(),
    ): Applied {
        val db = writableDatabase
        val upserted = ArrayList<Note>(); val removed = ArrayList<Long>()
        val staleFiles = ArrayList<Attachment>()
        db.beginTransaction()
        try {
            for (n in upserts) {
                val cur = query("uid=?", arrayOf(n.uid), "id").firstOrNull()
                if (cur != null && cur.updatedAt != baseline[n.uid]) continue
                if (cur != null && (cur.title != n.title || cur.body != n.body)) snapshot(cur, force = true)
                val v = ContentValues().apply {
                    put("title", n.title); put("body", n.body); put("checklist", if (n.checklist) 1 else 0)
                    put("color", n.color); put("archived", if (n.archived) 1 else 0); put("pinned", if (n.pinned) 1 else 0)
                    put("reminder_at", n.reminderAt); put("modified_at", n.modifiedAt); put("created_at", n.createdAt)
                    put("deleted_at", n.deletedAt); put("sync_at", n.updatedAt); put("uid", n.uid); put("labels", Labels.encode(n.labels))
                }
                val id = if (cur == null) db.insert("notes", null, v) else { db.update("notes", v, "uid=?", arrayOf(n.uid)); cur.id }
                upserted += n.copy(id = id)
                upsertAttachments[n.uid]?.let { fresh ->
                    val keep = fresh.map { it.uid }.toSet()
                    attachmentsWhere("note_uid=?", arrayOf(n.uid)).filter { it.uid !in keep }.forEach { staleFiles += it }
                    db.execSQL("DELETE FROM attachments WHERE note_uid=?", arrayOf(n.uid))
                    fresh.forEach { insertAttachment(db, it) }
                }
            }
            for (uid in deleteUids) {
                val cur = query("uid=?", arrayOf(uid), "id").firstOrNull() ?: continue
                if (cur.updatedAt != baseline[uid]) continue
                staleFiles += attachmentsWhere("note_uid=?", arrayOf(uid))
                db.execSQL("DELETE FROM attachments WHERE note_uid=?", arrayOf(uid))
                db.execSQL("DELETE FROM note_versions WHERE note_uid=?", arrayOf(uid))
                db.delete("notes", "uid=?", arrayOf(uid)); removed += cur.id
            }
            for ((uid, at) in tombs) db.execSQL("INSERT OR REPLACE INTO tombstones(uid, at) VALUES(?, MAX(?, COALESCE((SELECT at FROM tombstones WHERE uid=?), 0)))", arrayOf(uid, at, uid))
            db.execSQL("DELETE FROM tombstones WHERE at < ?", arrayOf(System.currentTimeMillis() - SyncEngine.TOMBSTONE_KEEP_MS))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        staleFiles.forEach { storage.file(it).delete() }
        notifyChanged(syncSoon = false)
        return Applied(upserted, removed)
    }

    private fun notifyChanged(syncSoon: Boolean = true) { Widgets.refresh(appContext); if (syncSoon) SyncScheduler.requestSoon(appContext) }

    companion object {
        const val VERSION_SPACING_MS = 5L * 60 * 1000
        const val MAX_VERSIONS = 50
        const val TRASH_RETENTION_MS = 30L * 24 * 3600 * 1000
        private const val ACOLS = "uid,note_uid,kind,duration,created_at,name,mime"
        private const val COLS = "id,title,body,checklist,color,archived,reminder_at,modified_at,created_at,pinned,deleted_at,uid,sync_at,labels"
    }
}
