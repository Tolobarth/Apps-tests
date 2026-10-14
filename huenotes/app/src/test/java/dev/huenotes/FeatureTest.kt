package dev.huenotes

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: NoteStore

    @Before fun setUp() {
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        java.io.File(app.filesDir, "snapshots").deleteRecursively()
        store = NoteStore(app)
    }

    @Test fun trashHidesNotesAndRestoreBringsThemBack() {
        val id = store.save(Note(title = "t", reminderAt = 1_000))
        store.trash(id)
        assertTrue(store.list(false).isEmpty())
        assertEquals(listOf("t"), store.trashed().map { it.title })
        assertTrue("trashed notes must not show on the calendar", store.reminderBetween(0, 2_000).isEmpty())
        store.restore(id)
        assertEquals(1, store.list(false).size)
        assertTrue(store.trashed().isEmpty())
    }

    @Test fun purgeOnlyRemovesOldTrash() {
        val old = store.save(Note(title = "old")); val fresh = store.save(Note(title = "fresh")); val live = store.save(Note(title = "live"))
        store.trash(old); store.trash(fresh)
        assertEquals(2, store.purgeTrash(System.currentTimeMillis() + 1))     // both are older than "now+1"
        assertNull(store.get(old)); assertNull(store.get(fresh)); assertNotNull(store.get(live))
        val again = store.save(Note(title = "again")); store.trash(again)
        assertEquals(0, store.purgeTrash(System.currentTimeMillis() - NoteStore.TRASH_RETENTION_MS))
        assertNotNull(store.get(again))
        store.emptyTrash(); assertNull(store.get(again))
    }

    @Test fun archiveAndPinFlagsDoNotTouchEditTime() {
        val id = store.save(Note(title = "x", modifiedAt = 5))
        val before = store.get(id)!!.modifiedAt
        Thread.sleep(5)
        store.setPinned(id, true); store.setArchived(id, true)
        val n = store.get(id)!!
        assertTrue(n.pinned); assertTrue(n.archived); assertEquals(before, n.modifiedAt)
    }

    private fun n(title: String, mod: Long, created: Long, color: Int, reminder: Long, pinned: Boolean = false) =
        Note(title = title, modifiedAt = mod, createdAt = created, color = Note.COLORS[color], reminderAt = reminder, pinned = pinned)

    @Test fun sortingModes() {
        val a = n("b", 100, 300, 2, 0); val b = n("A", 300, 100, 0, 500); val c = n("c", 200, 200, 1, 400)
        val all = listOf(a, b, c)
        fun order(m: SortMode, l: List<Note> = all) = Sorter.sort(l, m).map { it.title }
        assertEquals(listOf("A", "c", "b"), order(SortMode.MODIFIED))
        assertEquals(listOf("b", "c", "A"), order(SortMode.CREATED))
        assertEquals(listOf("A", "b", "c"), order(SortMode.TITLE))
        assertEquals(listOf("A", "c", "b"), order(SortMode.COLOUR))
        assertEquals(listOf("c", "A", "b"), order(SortMode.REMINDER))
        assertEquals("pinned always first", "b", order(SortMode.MODIFIED, listOf(a.copy(pinned = true), b, c)).first())
    }

    @Test fun upgradesVersion1DatabaseKeepingNotes() {
        app.deleteDatabase("huenotes.db")
        val f = app.getDatabasePath("huenotes.db"); f.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("""CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL,
                checklist INTEGER NOT NULL, color INTEGER NOT NULL, archived INTEGER NOT NULL, reminder_at INTEGER NOT NULL, modified_at INTEGER NOT NULL)""")
            db.execSQL("INSERT INTO notes(title,body,checklist,color,archived,reminder_at,modified_at) VALUES('old','b',0,-1,0,0,1234)")
            db.version = 1
        }
        val old = NoteStore(app).list(false).single()
        assertEquals("old", old.title); assertEquals(1234L, old.createdAt); assertFalse(old.pinned); assertEquals(0L, old.deletedAt)
    }

    @Test fun importSkipsNotesYouAlreadyHaveAndKeepsPinAndDates() {
        val json = Backup.toJson(listOf(Note(title = "A", body = "x", pinned = true, createdAt = 11, modifiedAt = 22), Note(title = "B", body = "y", createdAt = 33, modifiedAt = 44)))
        assertEquals(2, Backup.importText(app, store, json))
        assertEquals("second import adds nothing", 0, Backup.importText(app, store, json))
        assertEquals(2, store.list(false).size)
        val a = store.list(false).first { it.title == "A" }
        assertTrue(a.pinned); assertEquals(22L, a.modifiedAt); assertEquals(11L, a.createdAt)
    }

    @Test fun oldVersion1BackupsStillImport() {
        val v1 = """{"app":"huenotes","version":1,"notes":[{"title":"legacy","body":"b","checklist":false,"color":-1,"archived":false,"reminderAt":0,"modifiedAt":99}]}"""
        assertEquals(1, Backup.importText(app, store, v1))
        assertEquals(99L, store.list(false).single().createdAt)
    }

    @Test fun snapshotsRunDailyAndKeepSeven() {
        store.save(Note(title = "keep me"))
        val day = 24L * 3600 * 1000; val t0 = 1_700_000_000_000
        assertTrue(Snapshots.maybeRun(app, store, t0))
        assertFalse("not again within a day", Snapshots.maybeRun(app, store, t0 + day / 2))
        repeat(9) { assertTrue(Snapshots.maybeRun(app, store, t0 + (it + 1) * day)) }
        assertEquals(7, Snapshots.list(app).size)
        assertTrue(Snapshots.list(app).first().readText().contains("keep me"))
        Prefs.setAutoSnapshots(app, false)
        assertFalse(Snapshots.maybeRun(app, store, t0 + 99 * day))
    }

    @Test fun prefsDefaultsAndRoundTrip() {
        assertEquals(SortMode.MODIFIED, Prefs.sort(app)); assertTrue(Prefs.grid(app)); assertEquals(100, Prefs.widgetOpacity(app))
        assertEquals("", Prefs.language(app)); assertEquals(Note.COLORS[0], Prefs.defaultColor(app))
        Prefs.setSort(app, SortMode.TITLE); Prefs.setFontSize(app, 2); Prefs.setWidgetOpacity(app, 60)
        assertEquals(SortMode.TITLE, Prefs.sort(app)); assertEquals(1.2f, Prefs.fontScale(app), 0.001f); assertEquals(60, Prefs.widgetOpacity(app))
    }

    @Test fun widgetOpacityIsApplied() {
        Prefs.setWidgetOpacity(app, 50)
        val v = Widgets.build(app, today = false).apply(app, android.widget.FrameLayout(app))
        assertEquals(127, v.findViewById<android.widget.ImageView>(R.id.bg).imageAlpha)
    }
}
