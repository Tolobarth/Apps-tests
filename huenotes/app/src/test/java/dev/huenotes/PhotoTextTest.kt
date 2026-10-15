package dev.huenotes

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.FileOutputStream
import java.nio.file.Files

/** Search text in photos (with a fake recogniser: ML Kit needs a real phone) and the type chips. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhotoTextTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: NoteStore

    private class Fake(var installed: Boolean = true, var text: String = "RECEIPT Café du Port total 42,50 EUR") : PhotoText.Engine {
        var reads = 0; var installs = 0; var fail = false
        override fun isInstalled(c: Context, done: (Boolean) -> Unit) = done(installed)
        override fun install(c: Context, progress: (Int) -> Unit, done: (String?) -> Unit) { installs++; progress(50); installed = true; done(null) }
        override fun read(bmp: Bitmap): String { reads++; if (fail) throw RuntimeException("engine"); return text }
    }
    private lateinit var fake: Fake

    @Before fun setUp() {
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        store = NoteStore(app); fake = Fake(); PhotoText.engine = fake
    }
    @After fun tearDown() { PhotoText.engine = PhotoText.MlKit }

    private fun photoNote(title: String, kind: AttachmentKind = AttachmentKind.IMAGE, mime: String = "", withFile: Boolean = true): Pair<Note, Attachment> {
        val id = store.save(Note(title = title)); val n = store.get(id)!!
        val a = Attachment(noteUid = n.uid, kind = kind, name = if (kind == AttachmentKind.FILE) "f" else "", mime = mime)
        if (withFile) FileOutputStream(store.storage.file(a)).use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        store.addAttachment(a)
        return n to a
    }

    // ---- reading and storing -------------------------------------------------------------------

    @Test fun photosPicturesAndPdfsAreReadButNotVoiceOrDrawings() {
        val (_, photo) = photoNote("p")
        val (_, png) = photoNote("q", AttachmentKind.FILE, "image/png")
        val (_, pdf) = photoNote("r", AttachmentKind.FILE, "application/pdf")
        photoNote("s", AttachmentKind.AUDIO); photoNote("t", AttachmentKind.DRAWING); photoNote("u", AttachmentKind.FILE, "application/zip")
        assertEquals(setOf(photo.uid, png.uid, pdf.uid), store.photosToRead(10).map { it.uid }.toSet())
        assertEquals(0 to 3, store.photoTextProgress())
    }

    @Test fun readTextMakesTheNoteFindableWithAnExcerpt() {
        val (n, _) = photoNote("Lunch")
        photoNote("Other")
        assertEquals(2, PhotoText.readPending(store))
        assertEquals("each photo is read once", 0, PhotoText.readPending(store))
        assertEquals(2 to 2, store.photoTextProgress())
        assertTrue(store.list(false, "café du port").any { it.uid == n.uid })
        assertTrue(store.photoTextMatches("42,50")[n.uid]!!.contains("42,50 EUR"))
        assertTrue(store.photoTextMatches("café")[n.uid]!!.contains("Café du Port"))
    }

    @Test fun photosNotOnThePhoneYetWaitAndBrokenOnesAreNotRetried() {
        val (_, missing) = photoNote("m", withFile = false)
        val (_, ok) = photoNote("o")
        val later = HashSet<String>()
        assertEquals(1, PhotoText.readPending(store, later = later))
        assertEquals(setOf(missing.uid), later)
        assertEquals(listOf(missing.uid), store.photosToRead(10).map { it.uid })
        fake.fail = true
        FileOutputStream(store.storage.file(missing)).use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(1, PhotoText.readPending(store))
        assertTrue("a failure is stored as no text", store.photosToRead(10).isEmpty())
        assertNotNull(ok)
    }

    @Test fun excerptsCentreOnTheMatch() {
        assertEquals("…dolor sit amet…", PhotoText.excerpt("lorem ipsum dolor sit amet consectetur", "sit", 6))
        assertEquals("short", PhotoText.excerpt("short", "short"))
    }

    @Test fun syncKeepsTextAlreadyRead() {
        val (n, a) = photoNote("p"); PhotoText.readPending(store)
        val again = store.get(store.list(false).single().id)!!.copy(title = "p2", updatedAt = System.currentTimeMillis() + 10)
        store.applySync(listOf(again), emptyList(), emptyMap(), mapOf(n.uid to store.get(store.list(false).single().id)!!.updatedAt),
            mapOf(n.uid to listOf(a)))
        assertTrue(store.photosToRead(10).isEmpty())
        assertTrue(store.list(false, "café").isNotEmpty())
    }

    @Test fun upgradingFromVersion8AddsTheColumn() {
        val name = "ocr-up-${System.nanoTime()}.db"
        val path = app.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            NoteStore(app, "unused-${System.nanoTime()}.db").onCreate(db)
            db.execSQL("DROP TABLE attachments")
            db.execSQL("CREATE TABLE attachments(uid TEXT PRIMARY KEY, note_uid TEXT NOT NULL, kind INTEGER NOT NULL, duration INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, name TEXT NOT NULL DEFAULT '', mime TEXT NOT NULL DEFAULT '')")
            db.execSQL("INSERT INTO attachments(uid, note_uid, kind, created_at) VALUES('a1','n1',0,1)")
            db.version = 8
        }
        val s = NoteStore(app, name, AttachmentStorage(Files.createTempDirectory("ocr").toFile()))
        assertEquals(listOf("a1"), s.photosToRead(10).map { it.uid })
    }

    // ---- type chips ------------------------------------------------------------------------------

    @Test fun typesMatchWhatTheNoteHolds() {
        val img = Attachment(noteUid = "n", kind = AttachmentKind.IMAGE)
        val voice = Attachment(noteUid = "n", kind = AttachmentKind.FILE, name = "a.ogg", mime = "audio/ogg")
        assertTrue(NoteType.PHOTOS.matches(Note(), listOf(img))); assertFalse(NoteType.PHOTOS.matches(Note(), listOf(voice)))
        assertTrue(NoteType.VOICE.matches(Note(), listOf(voice))); assertTrue(NoteType.FILES.matches(Note(), listOf(voice)))
        assertTrue(NoteType.LISTS.matches(Note(checklist = true), emptyList()))
        val f = SavedFilter("Receipts", query = "eur", type = NoteType.PHOTOS)
        assertEquals(listOf(f), SavedFilter.listFromJson(SavedFilter.listToJson(listOf(f))))
        assertTrue(f.matches(Note(title = "EUR"), listOf(img))); assertFalse(f.matches(Note(title = "EUR"), emptyList()))
    }

    // ---- the list --------------------------------------------------------------------------------

    private fun chips(a: android.app.Activity) = a.findViewById<ViewGroup>(R.id.filterRow).let { r -> (0 until r.childCount).map { r.getChildAt(it) }.filterIsInstance<Chip>() }
    private fun titles(a: android.app.Activity): List<String> {
        val rv = a.findViewById<RecyclerView>(R.id.recycler)
        return (0 until rv.adapter!!.itemCount).mapNotNull { (rv.adapter as? NoteAdapter)?.noteAt(it)?.title }
    }
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog

    @Test fun thePhotosChipShowsOnlyNotesWithPictures() {
        photoNote("With photo"); store.save(Note(title = "Text only"))
        val c = Robolectric.buildActivity(ListActivity::class.java).setup()
        chips(c.get()).first { it.text == app.getString(R.string.type_photos) }.performClick()
        assertEquals(listOf("With photo"), titles(c.get()))
    }

    @Test fun searchingOffersPhotoTextThenFindsAndQuotesIt() {
        fake.installed = false
        photoNote("Lunch"); store.save(Note(title = "Dinner"))
        val c = Robolectric.buildActivity(ListActivity::class.java).setup()
        c.get().findViewById<android.widget.EditText>(R.id.search).setText("café")
        assertTrue(titles(c.get()).isEmpty())
        chips(c.get()).first { it.text == app.getString(R.string.photo_text_offer) }.performClick(); ShadowLooper.idleMainLooper()
        assertEquals("asks before downloading", 0, fake.installs)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(1, fake.installs); assertTrue(Prefs.photoText(app))
        PhotoText.readPending(store)                    // what the background worker does
        c.get().findViewById<android.widget.EditText>(R.id.search).setText("café ")
        c.get().findViewById<android.widget.EditText>(R.id.search).setText("café")
        assertEquals(listOf("Lunch"), titles(c.get()))
        val rv = c.get().findViewById<RecyclerView>(R.id.recycler)
        rv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)); rv.layout(0, 0, 1080, 1920)
        val found = (0 until rv.childCount).map { rv.getChildAt(it).findViewById<TextView>(R.id.found) }.firstOrNull { it != null && it.visibility == View.VISIBLE }
        assertNotNull(found); assertTrue(found!!.text.contains("Café du Port"))
    }
}
