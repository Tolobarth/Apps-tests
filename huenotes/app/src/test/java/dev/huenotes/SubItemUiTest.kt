package dev.huenotes

import android.app.Application
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sub-items as the person uses them: the real editor screen, driven with taps and keys. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // real fonts, so "bold" can be checked
@Config(sdk = [34])
class SubItemUiTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: NoteStore

    private val groceries = "[ ] Bakery\n  [x] Sourdough\n  [ ] Croissants\n[ ] Dairy\n  [x] Oat milk\n  [ ] Butter\n[x] Coffee beans"

    @Before fun setUp() { app.deleteDatabase("huenotes.db"); Lock.clear(app); app.getSharedPreferences("settings", 0).edit().clear().commit(); store = NoteStore(app) }

    private class Screen(val c: ActivityController<EditorActivity>, val id: Long) {
        val a: EditorActivity get() = c.get()
        val list: ViewGroup get() = a.findViewById(R.id.checklist)
        val rows get() = (0 until list.childCount).map { list.getChildAt(it) }
        fun text(i: Int) = list.getChildAt(i).findViewById<EditText>(R.id.text)
        fun check(i: Int) = list.getChildAt(i).findViewById<CheckBox>(R.id.check)
        fun texts() = rows.map { it.findViewById<EditText>(R.id.text).text.toString() }
        fun levels() = rows.map { it.tag as Int }
        fun done() = rows.indices.map { check(it).isChecked }
        fun meta() = a.findViewById<TextView>(R.id.meta).text.toString()
        fun indentButton(i: Int) = list.getChildAt(i).findViewById<View>(R.id.indent)
        fun backspace(i: Int) = text(i).dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        fun enter(i: Int) = text(i).onEditorAction(EditorInfo.IME_ACTION_NEXT)
        fun savedBody(): String { c.pause(); return NoteStore(ApplicationProvider.getApplicationContext()).get(id)!!.body }
    }

    private fun open(body: String): Screen {
        val id = store.save(Note(title = "L", body = body, checklist = true))
        return Screen(Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup(), id)
    }

    @Test fun savedSubItemsAppearIndentedUnderTheirParents() {
        val s = open(groceries)
        assertEquals(listOf(0, 1, 1, 0, 1, 1, 0), s.levels())
        assertEquals(listOf("Bakery", "Sourdough", "Croissants", "Dairy", "Oat milk", "Butter", "Coffee beans"), s.texts())
        assertEquals(listOf(false, true, false, false, true, false, true), s.done())
        assertTrue("sub-items show a guide line", s.list.getChildAt(1).findViewById<View>(R.id.guide).visibility == View.VISIBLE)
        assertTrue(s.list.getChildAt(0).findViewById<View>(R.id.guide).visibility == View.GONE)
        assertTrue("sub-items are indented", s.list.getChildAt(1).findViewById<View>(R.id.indentBox).layoutParams.width > 0)
        assertEquals(0, s.list.getChildAt(0).findViewById<View>(R.id.indentBox).layoutParams.width)
        assertTrue("parents are bold", s.text(0).typeface?.isBold == true); assertFalse(s.text(1).typeface?.isBold == true); assertFalse(s.text(6).typeface?.isBold == true)
    }

    @Test fun theLineUnderTheTitleCountsSubItems() {
        assertTrue(open(groceries).meta(), open(groceries).meta().endsWith("7 items  ·  3 done"))
    }

    @Test fun theIndentButtonAppearsOnTheRowBeingTypedInAndWorks() {
        val s = open("[ ] Bakery\n[ ] Croissants")
        assertEquals("hidden until a row is focused", View.GONE, s.indentButton(1).visibility)
        s.text(1).requestFocus()
        assertEquals(View.VISIBLE, s.indentButton(1).visibility); assertEquals("the first row can't be a sub-item", View.GONE, s.indentButton(0).visibility)
        s.indentButton(1).performClick()
        assertEquals(listOf(0, 1), s.levels()); assertEquals("a sub-item always offers 'move out'", View.VISIBLE, s.indentButton(1).visibility)
        assertEquals("[ ] Bakery\n  [ ] Croissants", s.savedBody())
    }

    @Test fun theOutdentButtonBringsASubItemBack() {
        val s = open("[ ] Bakery\n  [ ] Croissants")
        s.indentButton(1).performClick()
        assertEquals(listOf(0, 0), s.levels()); assertEquals("[ ] Bakery\n[ ] Croissants", s.savedBody())
    }

    @Test fun tickingAParentTicksItsSubItemsAndTheLineFollows() {
        val s = open(groceries)
        s.check(0).performClick()
        assertEquals(listOf(true, true, true), s.done().subList(0, 3)); assertTrue(s.meta(), s.meta().endsWith("7 items  ·  5 done"))
        s.check(0).performClick()
        assertEquals(listOf(false, false, false), s.done().subList(0, 3)); assertTrue(s.meta(), s.meta().endsWith("7 items  ·  2 done"))
    }

    @Test fun tickingTheLastOpenSubItemTicksTheParentAndUntickingUnticksIt() {
        val s = open(groceries)
        s.check(2).performClick()
        assertTrue(s.check(0).isChecked); assertTrue(s.check(1).isChecked)
        s.check(1).performClick()
        assertFalse(s.check(0).isChecked); assertTrue(s.check(2).isChecked)
    }

    @Test fun enterOnASubItemAddsAnotherSubItemAndFocusesIt() {
        val s = open(groceries)
        s.enter(1)
        assertEquals(8, s.rows.size); assertEquals(listOf(0, 1, 1, 1, 0), s.levels().subList(0, 5)); assertEquals("", s.texts()[2])
        assertTrue("the new row is ready for typing", s.text(2).hasFocus())
    }

    @Test fun enterOnAParentAddsATopLevelRowAfterItsWholeGroup() {
        val s = open(groceries)
        s.enter(0)
        assertEquals(listOf("Bakery", "Sourdough", "Croissants", "", "Dairy"), s.texts().subList(0, 5)); assertEquals(0, s.levels()[3])
    }

    @Test fun backspaceOnAnEmptySubItemFirstMovesItOutThenDeletesIt() {
        val s = open("[ ] Bakery\n  [ ] Croissants\n  [ ] x")
        s.text(2).setText("")
        assertTrue(s.backspace(2)); assertEquals(listOf(0, 1, 0), s.levels()); assertEquals(3, s.rows.size)
        assertTrue(s.backspace(2)); assertEquals(2, s.rows.size)
    }

    @Test fun backspaceOnATextyRowDoesNothingSpecial() {
        val s = open(groceries); assertFalse(s.backspace(1)); assertEquals(7, s.rows.size)
    }

    @Test fun removingAParentKeepsItsSubItemsAsTopLevelRows() {
        val s = open(groceries)
        s.list.getChildAt(0).findViewById<View>(R.id.remove).performClick()
        assertEquals(listOf("Sourdough", "Croissants", "Dairy", "Oat milk", "Butter", "Coffee beans"), s.texts())
        assertEquals(listOf(0, 0, 0, 1, 1, 0), s.levels())
    }

    @Test fun removingASubItemKeepsTheParentInStep() {
        val s = open("[ ] Bakery\n  [x] Sourdough\n  [ ] Croissants")
        s.list.getChildAt(2).findViewById<View>(R.id.remove).performClick()
        assertTrue("the only sub-item is done, so the parent is too", s.check(0).isChecked)
    }

    @Test fun switchingToTextAndBackKeepsTheStructure() {
        val s = open("[ ] Bakery\n  [x] Sourdough\n[ ] Dairy")
        s.a.findViewById<View>(R.id.checklistButton).performClick()
        assertEquals("Bakery\n  Sourdough\nDairy", s.a.findViewById<EditText>(R.id.bodyEdit).text.toString())
        s.a.findViewById<View>(R.id.checklistButton).performClick()
        assertEquals(listOf(0, 1, 0), s.levels()); assertEquals(listOf("Bakery", "Sourdough", "Dairy"), s.texts())
    }

    @Test fun flatListsWorkExactlyAsBefore() {
        val s = open("[ ] a\n[x] b")
        assertEquals(listOf(0, 0), s.levels()); assertEquals(listOf(false, true), s.done())
        s.enter(0); assertEquals(3, s.rows.size); assertEquals(listOf(0, 0, 0), s.levels())
        assertEquals("[ ] a\n[x] b", s.savedBody())
    }

    @Test fun editsSurviveReopening() {
        val s = open("[ ] Bakery\n[ ] Croissants\n[ ] Dairy")
        s.text(1).requestFocus(); s.indentButton(1).performClick(); s.check(1).performClick()
        val body = s.savedBody()
        assertEquals("[x] Bakery\n  [x] Croissants\n[ ] Dairy", body)
        val again = Screen(Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", s.id)).setup(), s.id)
        assertEquals(listOf(0, 1, 0), again.levels()); assertEquals(listOf(true, true, false), again.done())
    }

    @Test fun sharedTextKeepsTheIndentation() {
        val id = store.save(Note(title = "L", body = "[ ] Bakery\n  [x] Sourdough", checklist = true))
        val screen = Robolectric.buildActivity(android.app.Activity::class.java).get()
        shareNote(screen, store.get(id)!!, store)
        val sent = shadowOf(screen).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("its only sub-item is done, so the parent shows done too", "☑ Bakery\n  ☑ Sourdough", sent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
