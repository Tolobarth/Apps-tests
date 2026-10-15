package dev.huenotes

import android.Manifest
import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Translating notes: what goes to the translator, how the result fits back, and the editor's flow (with a fake engine). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** "Translates" by upper-casing; remembers what it was asked. */
    private class Fake(var detected: String? = "fr", val have: MutableSet<String> = mutableSetOf()) : Translate.Engine {
        val asked = ArrayList<String>(); var downloads = 0; var fail = false
        override fun languages() = listOf("de", "en", "es", "fr", "pt")
        override fun detect(text: String, done: (String?) -> Unit) = done(detected)
        override fun downloaded(done: (Set<String>) -> Unit) = done(have.toSet())
        var downloadError: String? = null
        override fun download(from: String, to: String, done: (String?) -> Unit) {
            downloads++; if (downloadError == null) { have += from; have += to }; done(downloadError)
        }
        override fun translate(from: String, to: String, pieces: List<String>, done: (List<String>?) -> Unit) {
            asked += pieces; done(if (fail) null else pieces.map { it.uppercase() })
        }
        override fun delete(lang: String, done: (Boolean) -> Unit) { have -= lang; done(true) }
    }

    private lateinit var fake: Fake

    @Before fun setUp() {
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        fake = Fake(); Translate.engine = fake
    }
    @After fun tearDown() { Translate.engine = Translate.MlKit }

    // ---- shape of a note -----------------------------------------------------------------------

    @Test fun onlyWordsGoToTheTranslator() {
        val text = "Voir https://exemple.fr/a?b=1 demain\n\n  sous-point \nfin"
        val p = Translate.pieces(text)
        assertEquals(listOf("Voir ", " demain", "sous-point", "fin"), p.filter { it.translate }.map { it.text })
        assertEquals(text, Translate.assemble(p, p.filter { it.translate }.map { it.text }))
    }

    @Test fun translationKeepsLinksLinesAndChecklistTicks() {
        val n = Note(title = "Courses", body = "[ ] pain\n  [x] lait demi-écrémé\n  [ ] beurre\n[x] voir www.marche.fr", checklist = true)
        var out: List<String>? = null
        Translate.translateTexts("fr", "en", Translate.inputs(n)) { out = it }
        val t = Translate.applyTo(n, out!!)
        assertEquals("COURSES", t.title)
        assertEquals("[ ] PAIN\n  [x] LAIT DEMI-ÉCRÉMÉ\n  [ ] BEURRE\n[x] VOIR www.marche.fr", t.body)
        assertFalse("links are never sent", fake.asked.any { "marche" in it })

        val plain = Note(title = "", body = "Lien : https://a.fr\n\nMerci")
        Translate.translateTexts("fr", "en", Translate.inputs(plain)) { out = it }
        assertEquals("LIEN : https://a.fr\n\nMERCI", Translate.applyTo(plain, out!!).body)
    }

    @Test fun targetsStartWithTheAppLanguageAndRecentOnes() {
        val t = Translate.targets(listOf("de", "en", "es", "fr", "pt"), source = "fr", app = "en", recent = listOf("pt", "en"))
        assertEquals(listOf("en", "pt"), t.take(2)); assertFalse("fr" in t); assertEquals(4, t.size)
        Prefs.addTranslateRecent(app, "de"); Prefs.addTranslateRecent(app, "es"); Prefs.addTranslateRecent(app, "de")
        assertEquals(listOf("de", "es"), Prefs.translateRecent(app))
    }

    // ---- the editor ----------------------------------------------------------------------------

    private fun open(n: Note): Pair<org.robolectric.android.controller.ActivityController<EditorActivity>, Long> {
        val id = NoteStore(app).save(n)
        return Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup() to id
    }
    private fun menuTranslate(a: EditorActivity) { a.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(5, 0); ShadowLooper.idleMainLooper() }
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun pickFirst() { val lv = dialog().listView; lv.performItemClick(lv.adapter.getView(0, null, lv), 0, 0); ShadowLooper.idleMainLooper() }
    private fun press(which: Int) { dialog().getButton(which).performClick(); ShadowLooper.idleMainLooper() }

    @Test fun firstTranslationDownloadsPacksThenAddsTheResultBelow() {
        val (c, id) = open(Note(title = "Bonjour", body = "Une note"))
        menuTranslate(c.get())
        pickFirst()                                         // target list starts with the app language (English)
        assertEquals("asks before downloading", 0, fake.downloads)
        press(AlertDialog.BUTTON_POSITIVE)                  // Download
        assertEquals(1, fake.downloads)
        press(AlertDialog.BUTTON_POSITIVE)                  // Add below
        assertEquals("Une note\n\nBONJOUR\nUNE NOTE", c.get().findViewById<EditText>(R.id.bodyEdit).text.toString())
        c.pause()
        assertEquals("Bonjour", NoteStore(app).get(id)!!.title)
        assertEquals(listOf("en"), Prefs.translateRecent(app))
    }

    @Test fun replacingKeepsTheOriginalInVersionHistory() {
        fake.have += setOf("fr", "en")
        val (c, id) = open(Note(title = "Bonjour", body = "Une note"))
        menuTranslate(c.get()); pickFirst()
        press(AlertDialog.BUTTON_NEGATIVE)                  // Replace
        c.pause()
        val s = NoteStore(app); val n = s.get(id)!!
        assertEquals("BONJOUR", n.title); assertEquals("UNE NOTE", n.body)
        assertEquals("Une note", s.versions(n.uid).first().body)
    }

    @Test fun anUnknownLanguageIsAskedFor() {
        fake.detected = null; fake.have += setOf("fr", "en")
        val (c, _) = open(Note(title = "", body = "ok"))
        menuTranslate(c.get())
        pickFirst()                                         // source: English (app language first)
        pickFirst()                                         // target: first by name (French)
        assertTrue(dialog().isShowing); assertEquals(listOf("ok"), fake.asked)
    }

    @Test fun aFailedTranslationChangesNothing() {
        fake.have += setOf("fr", "en"); fake.fail = true
        val (c, _) = open(Note(title = "T", body = "Une note"))
        menuTranslate(c.get()); pickFirst()
        assertEquals("Une note", c.get().findViewById<EditText>(R.id.bodyEdit).text.toString())
    }

    @Test fun aFailedDownloadSaysWhyAndOffersARetry() {
        fake.downloadError = "No network"
        val (c, _) = open(Note(title = "Bonjour", body = "Une note"))
        menuTranslate(c.get()); pickFirst()
        press(AlertDialog.BUTTON_POSITIVE)                  // Download → fails
        val d = dialog()
        assertTrue(d.findViewById<android.widget.TextView>(android.R.id.message)!!.text.contains("No network"))
        fake.downloadError = null
        press(AlertDialog.BUTTON_POSITIVE)                  // Try again → works, translation carries on
        assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).text == app.getString(R.string.translate_add_below))
    }

    @Test fun aPackThatWentMissingIsFetchedBeforeTranslating() {
        fake.have += setOf("fr", "en")
        val (c, _) = open(Note(title = "Bonjour", body = "Une note"))
        menuTranslate(c.get()); pickFirst()
        assertEquals("downloadModelIfNeeded runs before every translation", 1, fake.downloads)
    }

    @Test fun settingsListsAndDeletesLanguagePacks() {
        fake.have += setOf("fr", "en")
        val c = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val list = c.get().findViewById<android.view.ViewGroup>(R.id.list)
        val row = (0 until list.childCount).map { list.getChildAt(it) }
            .first { it.findViewById<android.widget.TextView>(R.id.title)?.text == app.getString(R.string.settings_translate_packs) }
        row.performClick(); ShadowLooper.idleMainLooper()
        pickFirst()                                          // English (sorted by name)
        press(AlertDialog.BUTTON_POSITIVE)
        assertEquals(setOf("fr"), fake.have)
    }

    @Test fun voiceNotesShowATranscribeButtonWhenThePhoneCanDoIt() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val s = NoteStore(app); val id = s.save(Note(title = "V")); val uid = s.get(id)!!.uid
        s.addAttachment(Attachment(noteUid = uid, kind = AttachmentKind.AUDIO, durationMs = 3000).also { s.storage.file(it).writeBytes(byteArrayOf(1)) })
        val c = Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup()
        assertEquals(View.VISIBLE, c.get().findViewById<View>(R.id.transcribe).visibility)
        ShadowSpeechRecognizer.reset()
    }
}
