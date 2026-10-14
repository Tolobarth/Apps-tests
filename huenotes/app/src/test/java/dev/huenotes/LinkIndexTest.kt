package dev.huenotes

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LinkIndexTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { app.deleteDatabase("huenotes.db"); Lock.clear(app); app.getSharedPreferences("settings", 0).edit().clear().commit() }

    // ---- finding links in text -----------------------------------------------------------------

    @Test fun findsEveryLinkInOrderWithoutRepeats() {
        val text = "Read https://a.com/one and then http://b.org/two?x=1&y=2, also https://a.com/one again."
        assertEquals(listOf("https://a.com/one", "http://b.org/two?x=1&y=2"), Links.allUrls(text))
    }

    @Test fun trailingPunctuationAndBracketsAreNotPartOfTheLink() {
        assertEquals(listOf("https://a.com/x"), Links.allUrls("(see https://a.com/x)."))
        assertEquals(listOf("https://a.com/x"), Links.allUrls("\"https://a.com/x\", right?"))
        assertEquals(listOf("https://a.com/x"), Links.allUrls("<https://a.com/x>"))
        assertEquals("a bracket that belongs to the address stays", listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), Links.allUrls("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), Links.allUrls("(see https://en.wikipedia.org/wiki/Foo_(bar))"))
    }

    @Test fun wwwAddressesGetHttpsSoTheyCanBeOpened() {
        assertEquals(listOf("https://www.example.com/page"), Links.allUrls("go to www.example.com/page now"))
        assertEquals(listOf("https://www.example.com", "https://example.com/x"), Links.allUrls("www.example.com or https://example.com/x"))
    }

    @Test fun thingsThatLookLikeLinksButAreNotAreIgnored() {
        listOf("file.txt and e.g. this", "mail me at a@b.com", "ftp://example.com/x", "https://", "http://localhost-only", "version 1.2.3 is out", "")
            .forEach { assertEquals("'$it'", emptyList<String>(), Links.allUrls(it)) }
    }

    @Test fun linksWithQueriesFragmentsAndOddCharactersCount() {
        assertEquals(listOf("https://a.com/p?q=1#top"), Links.allUrls("https://a.com/p?q=1#top"))
        assertEquals(listOf("https://a.com/a|b[1]"), Links.allUrls("https://a.com/a|b[1]"))
        assertEquals(listOf("https://example.com/été"), Links.allUrls("https://example.com/été"))
    }

    @Test fun findsLinksInsideChecklistLines() {
        assertEquals(listOf("https://a.com/x", "https://b.com"), Links.allUrls("[ ] read https://a.com/x\n  [x] https://b.com"))
    }

    // ---- the index over notes ------------------------------------------------------------------

    private fun note(title: String, body: String, color: Int = 0, id: Long = 0) = Note(id = id, title = title, body = body, color = Note.COLORS[color])

    @Test fun everyLinkOfEveryNoteIsListedWithItsNote() {
        val a = note("Trip", "Book https://hotel.com/r1 and www.train.com", id = 1)
        val b = note("", "https://news.com/story\nnice read", id = 2)
        val c = note("No links", "just words", id = 3)
        val e = LinkIndex.entries(listOf(a, b, c))
        assertEquals(listOf("https://hotel.com/r1", "https://www.train.com", "https://news.com/story"), e.map { it.url })
        assertEquals(listOf(1L, 1L, 2L), e.map { it.note.id })
    }

    @Test fun linksInTitlesAndListsCountToo() {
        val e = LinkIndex.entries(listOf(note("https://t.com/title", "[ ] https://l.com/item", id = 1)))
        assertEquals(listOf("https://t.com/title", "https://l.com/item"), e.map { it.url })
    }

    @Test fun theSameLinkInTwoNotesAppearsInBoth() {
        val e = LinkIndex.entries(listOf(note("A", "https://x.com/y", id = 1), note("B", "see https://x.com/y", id = 2)))
        assertEquals(listOf(1L, 2L), e.map { it.note.id })
    }

    @Test fun linkNotesAreLabelledByTheirTitleAndOthersBySite() {
        val pure = LinkIndex.entries(listOf(note("Great article", "https://www.nytimes.com/a/b.html\nread later", id = 1))).single()
        assertTrue(pure.isLinkNote); assertEquals("Great article", pure.label); assertEquals("", pure.sourceName)
        assertEquals("nytimes.com/a/b.html", pure.shown); assertEquals("nytimes.com", pure.domain)
        val inText = LinkIndex.entries(listOf(note("Groceries", "milk\nrecipe: https://food.com/pie", id = 2))).single()
        assertFalse(inText.isLinkNote); assertEquals("food.com", inText.label); assertEquals("Groceries", inText.sourceName)
        val untitled = LinkIndex.entries(listOf(note("", "idea: https://x.org", id = 3))).single()
        assertEquals("idea: https://x.org", untitled.sourceName)
    }

    @Test fun searchMatchesAddressSiteLabelAndNoteName() {
        val e = LinkIndex.entries(listOf(note("Recipes", "https://food.com/pie", id = 1), note("Work", "https://docs.example.org/plan", id = 2)))
        assertEquals(2, LinkIndex.filter(e, "").size); assertEquals(2, LinkIndex.filter(e, "   ").size)
        assertEquals(listOf(1L), LinkIndex.filter(e, "PIE").map { it.note.id })
        assertEquals(listOf(2L), LinkIndex.filter(e, "example").map { it.note.id })
        assertEquals(listOf(1L), LinkIndex.filter(e, "recipes").map { it.note.id })
        assertTrue(LinkIndex.filter(e, "zzz").isEmpty())
    }

    // ---- on the real screen --------------------------------------------------------------------

    private fun screen(): ListActivity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
    private fun find(v: View, cls: Class<*>): View? {
        if (cls.isInstance(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), cls)?.let { return it }
        return null
    }
    private fun chip(a: ListActivity) = find(a.window.decorView, Chip::class.java) as Chip
    private fun recycler(a: ListActivity) = a.findViewById<RecyclerView>(R.id.recycler)

    private fun seed() {
        val s = NoteStore(app)
        s.save(Note(title = "Trip", body = "Hotel: https://hotel.com/r1\nTrain: www.train.com", color = Note.COLORS[4]))
        s.save(Note(title = "Reading", body = "https://news.com/story", color = Note.COLORS[1]))
        s.save(Note(title = "Plain", body = "no links here", color = Note.COLORS[0]))
        s.save(Note(title = "Old", body = "https://archived.com/x", color = Note.COLORS[1], archived = true))
        val trashed = s.save(Note(title = "Gone", body = "https://trashed.com/x")); s.trash(trashed)
    }

    @Test fun theChipCountsEveryLinkOfTheCurrentTab() {
        seed(); val a = screen()
        assertEquals("Links · 3", chip(a).text.toString())
    }

    @Test fun tappingTheChipShowsAllLinksAndNothingElse() {
        seed(); val a = screen(); chip(a).performClick()
        val adapter = recycler(a).adapter as LinkAdapter
        assertEquals(listOf("https://hotel.com/r1", "https://www.train.com", "https://news.com/story").toSet(), adapter.entries.map { it.url }.toSet())
        assertEquals("archived and trashed notes' links stay out of this tab", 3, adapter.itemCount)
        assertTrue("tapping again goes back to the notes", chip(a).let { it.performClick(); recycler(a).adapter is NoteAdapter })
    }

    @Test fun aNewLinkAppearsWithoutAnyManualStep() {
        seed(); val c = Robolectric.buildActivity(ListActivity::class.java).setup(); val a = c.get(); chip(a).performClick()
        NoteStore(app).save(Note(title = "Fresh", body = "check out https://fresh.com/page today"))
        c.pause().resume()
        assertEquals(4, (recycler(a).adapter as LinkAdapter).itemCount); assertEquals("Links · 4", chip(a).text.toString())
    }

    @Test fun removingALinkFromANoteRemovesItFromTheSection() {
        seed(); val c = Robolectric.buildActivity(ListActivity::class.java).setup(); val a = c.get(); chip(a).performClick()
        val s = NoteStore(app); val n = s.list(false).first { it.title == "Reading" }
        s.save(n.copy(body = "no link any more"))
        c.pause().resume()
        assertEquals(2, (recycler(a).adapter as LinkAdapter).itemCount)
    }

    @Test fun theColourFilterAndSearchNarrowTheLinks() {
        seed(); val a = screen(); chip(a).performClick()
        a.findViewById<TextView>(R.id.search).text = "news"
        assertEquals(1, (recycler(a).adapter as LinkAdapter).itemCount)
        a.findViewById<TextView>(R.id.search).text = ""
        assertEquals(3, (recycler(a).adapter as LinkAdapter).itemCount)
    }

    @Test fun anEmptySectionExplainsItself() {
        val a = screen(); chip(a).performClick()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.empty).visibility)
        assertEquals(a.getString(R.string.empty_links_title), a.findViewById<TextView>(R.id.emptyTitle).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.emptyBody).text.contains("automatically"))
    }

    @Test fun searchWithNoMatchSaysNoMatchesNotNoLinks() {
        seed(); val a = screen(); chip(a).performClick(); a.findViewById<TextView>(R.id.search).text = "zzzz"
        assertEquals(a.getString(R.string.empty_search_title), a.findViewById<TextView>(R.id.emptyTitle).text.toString())
    }

    @Test fun tappingARowOpensTheLinkInTheBrowser() {
        seed(); val a = screen(); chip(a).performClick()
        val rv = recycler(a); rv.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)); rv.layout(0, 0, 1000, 2000)
        rv.getChildAt(0).performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action); assertTrue(started.dataString!!.startsWith("http"))
    }

    @Test fun rowsShowTheSiteTheAddressAndTheNote() {
        seed(); val a = screen(); chip(a).performClick()
        val rv = recycler(a); rv.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)); rv.layout(0, 0, 1000, 2000)
        val texts = (0 until rv.childCount).map { rv.getChildAt(it) }.map { Triple(it.findViewById<TextView>(R.id.title).text.toString(), it.findViewById<TextView>(R.id.url).text.toString(), it.findViewById<TextView>(R.id.source).text.toString()) }
        assertTrue(texts.toString(), texts.any { it.first == "hotel.com" && it.second == "hotel.com/r1" && it.third == "in Trip" })
        assertTrue(texts.toString(), texts.any { it.first == "Reading" && it.second == "news.com/story" && it.third.isEmpty() })
    }

    @Test fun copyAndShareAreWired() {
        val a = screen()
        a.copyLink("https://a.com/x")
        assertEquals("https://a.com/x", (app.getSystemService(ClipboardManager::class.java)).primaryClip!!.getItemAt(0).text.toString())
        a.shareLink("https://a.com/x")
        val sent = shadowOf(a).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("https://a.com/x", sent.getStringExtra(Intent.EXTRA_TEXT)); assertEquals("text/plain", sent.type)
    }

    @Test fun theArchiveTabHasItsOwnLinks() {
        seed(); val a = screen()
        a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_archive
        assertEquals("Links · 1", chip(a).text.toString())
    }
}
