package dev.huenotes

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The line under the title: words for text notes, items (and how many are done) for lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetaLineTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { app.deleteDatabase("huenotes.db"); Lock.clear(app); app.getSharedPreferences("settings", 0).edit().clear().commit() }

    private fun open(note: Note): EditorActivity {
        val id = NoteStore(app).save(note)
        return Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup().get()
    }

    private fun meta(a: EditorActivity) = a.findViewById<TextView>(R.id.meta).text.toString()

    /** The tick box of list row [i] (not any chip, which is also technically a CheckBox). */
    private fun tick(a: EditorActivity, i: Int) = a.findViewById<ViewGroup>(R.id.checklist).getChildAt(i).findViewById<CheckBox>(R.id.check)

    private val days = Note(title = "My list", body = "[ ] Lundi\n[ ] Mardi\n[ ] Mercredi", checklist = true)

    @Test fun aListIsCountedInItemsNotWords() {
        val m = meta(open(days))
        assertTrue(m, m.endsWith("3 items")); assertFalse(m, m.contains("word")); assertFalse("nothing is done yet", m.contains("done"))
    }

    @Test fun doneItemsAreShown() {
        val m = meta(open(days.copy(body = "[x] Lundi\n[ ] Mardi\n[x] Mercredi")))
        assertTrue(m, m.endsWith("3 items  ·  2 done"))
    }

    @Test fun oneItemIsSingular() {
        assertTrue(meta(open(days.copy(body = "[ ] Lundi"))).endsWith("1 item"))
    }

    @Test fun theLineFollowsTicksAndEdits() {
        val a = open(days)
        tick(a, 1).performClick()
        assertTrue(meta(a), meta(a).endsWith("3 items  ·  1 done"))
        a.findViewById<ViewGroup>(R.id.checklist).getChildAt(0).findViewById<EditText>(R.id.text).setText("")
        assertTrue("an emptied row is not an item", meta(a).endsWith("2 items  ·  1 done"))
    }

    @Test fun addingAndRemovingRowsUpdatesTheCount() {
        val a = open(days)
        val list = a.findViewById<ViewGroup>(R.id.checklist)
        list.getChildAt(2).findViewById<View>(R.id.remove).performClick()
        assertTrue(meta(a), meta(a).endsWith("2 items"))
    }

    @Test fun textNotesStillCountWords() {
        val m = meta(open(Note(title = "t", body = "one two three four")))
        assertTrue(m, m.endsWith("4 words"))
        assertTrue(meta(open(Note(title = "t", body = "solo"))).endsWith("1 word"))
    }

    @Test @Config(qualifiers = "fr")
    fun frenchUsesItsOwnWords() {
        val m = meta(open(days.copy(body = "[x] Lundi\n[ ] Mardi\n[ ] Mercredi")))
        assertTrue(m, m.endsWith("3 éléments  ·  1 fait"))
    }

    @Test fun switchingAListToTextCountsWordsAgain() {
        val a = open(days)
        a.findViewById<View>(R.id.checklistButton).performClick()
        assertTrue(meta(a), meta(a).endsWith("3 words"))   // "Lundi Mardi Mercredi" as plain lines
    }
}
