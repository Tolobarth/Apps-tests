package dev.huenotes

import android.app.Application
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Labels on notes, and saved filters: rules, storage, sync/backup, and the screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: NoteStore

    @Before fun setUp() {
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        store = NoteStore(app)
    }

    // ---- rules ---------------------------------------------------------------------------------

    @Test fun namesAreTidiedAndRepeatsIgnoringCaseDropped() {
        assertEquals(listOf("Work", "Home trips"), Labels.normalize(listOf(" Work ", "work", "Home\n  trips", "", "  ")))
        assertEquals(Labels.MAX_LENGTH, Labels.clean("x".repeat(80))!!.length)
        assertEquals(listOf("A", "b"), Labels.decode(Labels.encode(listOf("A", "b", "a"))))
        assertEquals(listOf("Job", "Home"), Labels.rename(listOf("work", "Home"), "Work", "Job"))
        assertEquals("merges into the label already there", listOf("Home"), Labels.rename(listOf("Work", "Home"), "work", "home"))
        assertEquals(listOf("x"), Labels.rename(listOf("x"), "Work", "Job"))
    }

    // ---- storage -------------------------------------------------------------------------------

    @Test fun labelsAreStoredSearchableAndChangingThemIsNotAnEdit() {
        val id = store.save(Note(title = "Plan", body = "x", labels = listOf("Work")))
        assertEquals(listOf("Work"), store.get(id)!!.labels)
        val before = store.get(id)!!
        Thread.sleep(5)
        store.setLabels(id, listOf("Work", "Urgent"))
        val after = store.get(id)!!
        assertEquals(listOf("Work", "Urgent"), after.labels)
        assertEquals("last edited stays", before.modifiedAt, after.modifiedAt)
        assertTrue("sync notices", after.updatedAt > before.updatedAt)
        assertEquals(listOf(id), store.list(false, "urgent").map { it.id })
    }

    @Test fun renamingAndDeletingALabelReachesEveryNote() {
        val a = store.save(Note(title = "a", labels = listOf("Work", "Home")))
        val b = store.save(Note(title = "b", labels = listOf("work")))
        val c = store.save(Note(title = "c", labels = listOf("Home")))
        assertEquals(2, store.relabel("WORK", "Job"))
        assertEquals(listOf("Job", "Home"), store.get(a)!!.labels); assertEquals(listOf("Job"), store.get(b)!!.labels)
        store.relabel("home", null)
        assertEquals(listOf("Job"), store.get(a)!!.labels); assertTrue(store.get(c)!!.labels.isEmpty())
    }

    @Test fun upgradingFromVersion7AddsLabelsKeepingNotes() {
        val name = "lab-up-${System.nanoTime()}.db"
        val path = app.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            NoteStore(app, "unused-${System.nanoTime()}.db").onCreate(db)
            db.execSQL("DROP TABLE notes"); db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, checklist INTEGER NOT NULL, color INTEGER NOT NULL, archived INTEGER NOT NULL, reminder_at INTEGER NOT NULL, modified_at INTEGER NOT NULL, created_at INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NOT NULL DEFAULT 0, uid TEXT NOT NULL DEFAULT '', sync_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE UNIQUE INDEX notes_uid ON notes(uid)")
            db.execSQL("INSERT INTO notes(title, body, checklist, color, archived, reminder_at, modified_at, uid) VALUES('old','b',0,0,0,0,1,'u1')")
            db.version = 7
        }
        val s = NoteStore(app, name, AttachmentStorage(Files.createTempDirectory("lab").toFile()))
        val n = s.list(false).single()
        assertEquals("old", n.title); assertTrue(n.labels.isEmpty())
        s.setLabels(n.id, listOf("Kept")); assertEquals(listOf("Kept"), s.get(n.id)!!.labels)
    }

    // ---- sync and backup -----------------------------------------------------------------------

    @Test fun syncCarriesLabelsAndOnlyThenRaisesTheFormat() {
        val plain = Snapshot(listOf(Note(uid = "n1")), emptyMap())
        assertEquals(2, JSONObject(SyncDoc.toJson(plain)).getInt("format"))
        val labelled = Snapshot(listOf(Note(uid = "n1", labels = listOf("Work"))), emptyMap())
        val json = SyncDoc.toJson(labelled)
        assertEquals("older devices refuse instead of dropping labels", 4, JSONObject(json).getInt("format"))
        assertEquals(listOf("Work"), SyncDoc.fromJson(json).notes.single().labels)
    }

    @Test fun twoDevicesAgreeOnLabels() {
        fun device(n: String) = NoteStore(app, "lab-$n-${System.nanoTime()}.db", AttachmentStorage(Files.createTempDirectory(n).toFile()))
        val a = device("a"); val b = device("b")
        val cloud = object : SyncRemote {
            var file: String? = null
            override fun download() = file
            override fun upload(json: String) { file = json }
            override fun listBlobs() = emptyList<BlobInfo>()
            override fun downloadBlob(name: String): ByteArray? = null
            override fun uploadBlob(name: String, bytes: ByteArray, mime: String) {}
            override fun deleteBlob(name: String) {}
        }
        val id = a.save(Note(title = "T", labels = listOf("Work")))
        SyncRunner.sync(a, cloud); SyncRunner.sync(b, cloud)
        assertEquals(listOf("Work"), b.list(false).single().labels)
        Thread.sleep(5); b.setLabels(b.list(false).single().id, listOf("Work", "Home"))
        SyncRunner.sync(b, cloud); SyncRunner.sync(a, cloud)
        assertEquals(listOf("Work", "Home"), a.get(id)!!.labels)
    }

    @Test fun backupsAndKeepImportsKeepLabels() {
        val n = Note(title = "T", labels = listOf("Work"))
        val json = Backup.toJson(listOf(n))
        assertEquals(5, JSONObject(json).getInt("version"))
        assertEquals(listOf("Work"), Backup.fromJson(json).single().labels)
        assertEquals(3, JSONObject(Backup.toJson(listOf(Note(title = "x")))).getInt("version"))
        val keep = Importers.fromKeepJson("""{"title":"K","textContent":"t","labels":[{"name":"Recipes"},{"name":"recipes"}]}""")!!
        assertEquals(listOf("Recipes"), keep.note.labels)
        assertTrue(MarkdownExport.markdown(n, emptyList()).contains("Labels: Work"))
    }

    // ---- saved filters ---------------------------------------------------------------------------

    @Test fun savedFiltersMatchAndSurviveARename() {
        val f = SavedFilter("Urgent work", query = "call", color = Note.COLORS[2], label = "Work")
        assertTrue(f.matches(Note(title = "Call Ann", color = Note.COLORS[2], labels = listOf("work"))))
        assertFalse(f.matches(Note(title = "Call Ann", color = Note.COLORS[1], labels = listOf("Work"))))
        assertEquals(listOf(f), SavedFilter.listFromJson(SavedFilter.listToJson(listOf(f))))
        Prefs.setSavedFilters(app, listOf(f, SavedFilter("Only work", label = "Work")))
        Prefs.renameLabel(app, "work", "Job")
        assertEquals(listOf("Job", "Job"), Prefs.savedFilters(app).map { it.label })
        Prefs.renameLabel(app, "Job", null)
        assertEquals("a filter left with nothing is dropped", listOf("Urgent work"), Prefs.savedFilters(app).map { it.name })
    }

    // ---- screens ---------------------------------------------------------------------------------

    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun chips(a: android.app.Activity) = a.findViewById<ViewGroup>(R.id.filterRow).let { r -> (0 until r.childCount).map { r.getChildAt(it) }.filterIsInstance<Chip>() }

    @Test fun theEditorAddsALabelThroughNewLabelAndShowsItAsAChip() {
        val id = store.save(Note(title = "Plan", body = "x"))
        val c = Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup()
        c.get().findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(6, 0); ShadowLooper.idleMainLooper()
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); ShadowLooper.idleMainLooper()     // New label
        (dialog().findViewById<View>(android.R.id.custom) as ViewGroup?)
        val input = generateSequence(listOf<View>(dialog().window!!.decorView)) { level -> level.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().filterIsInstance<EditText>().first()
        input.setText("  Travel  ")
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()       // Done (the new one is ticked)
        assertEquals(listOf("Travel"), NoteStore(app).get(id)!!.labels)
        val group = c.get().findViewById<ViewGroup>(R.id.labelChips)
        assertEquals(View.VISIBLE, group.visibility); assertEquals("Travel", (group.getChildAt(0) as Chip).text.toString())
        (group.getChildAt(0) as Chip).performCloseIconClick()
        assertTrue(NoteStore(app).get(id)!!.labels.isEmpty()); assertEquals(View.GONE, group.visibility)
    }

    @Test fun theListFiltersByLabelAndCanSaveTheFilter() {
        store.save(Note(title = "Report", labels = listOf("Work")))
        store.save(Note(title = "Groceries", labels = listOf("Home")))
        val c = Robolectric.buildActivity(ListActivity::class.java).setup()
        val rv = c.get().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
        fun titles() = (0 until rv.adapter!!.itemCount).mapNotNull { (rv.adapter as NoteAdapter).noteAt(it)?.title }
        assertEquals(setOf("Report", "Groceries"), titles().toSet())
        chips(c.get()).first { it.text == "Work" }.performClick()
        assertEquals(listOf("Report"), titles())
        chips(c.get()).first { it.text == app.getString(R.string.filter_save) }.performClick(); ShadowLooper.idleMainLooper()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()      // suggested name: "Work"
        assertEquals(listOf(SavedFilter("Work", label = "Work")), Prefs.savedFilters(app))
        chips(c.get()).first { it.text == "Work" && it.isChecked && it.chipIcon != null }.performClick()     // label chip off
        assertEquals(2, titles().size)
        chips(c.get()).first().performClick()                                                                // the saved filter
        assertEquals(listOf("Report"), titles())
    }
}
