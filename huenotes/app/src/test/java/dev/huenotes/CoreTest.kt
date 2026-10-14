package dev.huenotes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: NoteStore

    @Before fun setUp() {
        app.deleteDatabase("huenotes.db")
        Lock.clear(app)
        store = NoteStore(app)
    }

    @Test fun saveListSearchArchive() {
        val a = store.save(Note(title = "Groceries", body = "milk"))
        store.save(Note(title = "Work", body = "ship it"))
        assertEquals(2, store.list(false).size)
        assertEquals(listOf("Groceries"), store.list(false, "MILK").map { it.title })
        store.save(store.get(a)!!.copy(archived = true))
        assertEquals(1, store.list(false).size)
        assertEquals(listOf("Groceries"), store.list(true).map { it.title })
        store.delete(a)
        assertNull(store.get(a))
    }

    @Test fun reminderDayRange() {
        store.save(Note(title = "in", reminderAt = 1_000))
        store.save(Note(title = "out", reminderAt = 5_000))
        store.save(Note(title = "archived", reminderAt = 1_500, archived = true))
        assertEquals(listOf("in"), store.reminderBetween(0, 2_000).map { it.title })
    }

    @Test fun passwordLock() {
        assertFalse(Lock.isSet(app))
        Lock.set(app, "hunter22")
        assertTrue(Lock.isSet(app))
        assertTrue(Lock.verify(app, "hunter22"))
        assertFalse(Lock.verify(app, "hunter23"))
        assertFalse(Lock.verify(app, ""))
        val raw = app.getSharedPreferences("lock", 0).all.toString()
        assertFalse("plaintext password must not be stored", raw.contains("hunter22"))
        Lock.clear(app)
        assertFalse(Lock.isSet(app))
    }

    @Test fun lockRelocksAfterLeaving() {
        Lock.set(app, "hunter22")
        Lock.markUnlocked(); Lock.markLeft()
        assertFalse(Lock.needsUnlock(app))
    }

    @Test fun widgetsDoNotLeakWhenLocked() {
        store.save(Note(title = "secret plan"))
        Lock.set(app, "hunter22")
        val rv = Widgets.build(app, today = false)
        val v = rv.apply(app, android.widget.FrameLayout(app))
        val texts = (0 until 4).joinToString("|") { v.findViewById<android.widget.TextView>(listOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3)[it]).text }
        assertTrue(texts.startsWith("Locked"))
        assertFalse(texts.contains("secret"))
    }

    @Test fun backupRoundTrip() {
        val src = listOf(
            Note(title = "A", body = "[x] done\n[ ] todo", checklist = true, color = Note.COLORS[3], reminderAt = 42),
            Note(title = "B", body = "line", archived = true),
        )
        val back = Backup.fromJson(Backup.toJson(src))
        assertEquals(src.map { it.copy(id = 0) }, back)
    }

    @Test fun backupRejectsForeignAndNewerFiles() {
        assertThrows(IllegalArgumentException::class.java) { Backup.fromJson("""{"app":"other","version":1,"notes":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { Backup.fromJson("""{"app":"huenotes","version":99,"notes":[]}""") }
    }
}
