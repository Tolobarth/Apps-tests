package dev.huenotes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()

    @Before fun setUp() { app.deleteDatabase("huenotes.db"); app.getSharedPreferences("settings", 0).edit().clear().commit(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun normalizeAcceptsWebAddressesAndAddsHttps() {
        assertEquals("https://example.com", Links.normalize("https://example.com"))
        assertEquals("https://example.com/path?q=1", Links.normalize("  example.com/path?q=1 "))
        assertEquals("HTTP://Example.com", Links.normalize("HTTP://Example.com"))
        assertEquals("http://localhost:8080/x", Links.normalize("http://localhost:8080/x"))
        assertEquals("https://example.com:8080/x", Links.normalize("example.com:8080/x"))
    }

    @Test fun normalizeRejectsEverythingElse() {
        listOf("", "   ", "hello", "two words.com", "ftp://example.com", "javascript:alert(1)", "https://", "mailto:a@b.com", "file:///etc/passwd")
            .forEach { assertNull("'$it' must be rejected", Links.normalize(it)) }
    }

    @Test fun firstUrlIgnoresSurroundingPunctuation() {
        assertEquals("https://a.com/x", Links.firstUrl("see https://a.com/x, thanks"))
        assertEquals("https://a.com/b", Links.firstUrl("(https://a.com/b)."))
        assertEquals("https://a.com/q?x=1&y=2", Links.firstUrl("go to https://a.com/q?x=1&y=2!"))
        assertNull(Links.firstUrl("no link here")); assertNull(Links.firstUrl("http://"))
    }

    @Test fun linkNotesAreRecognisedByTheirFirstLine() {
        assertEquals("https://a.com", Links.linkOf("https://a.com"))
        assertEquals("https://a.com", Links.linkOf("\n  https://a.com  \nmy words"))
        assertNull("text before the address makes it an ordinary note", Links.linkOf("read https://a.com"))
        assertNull(Links.linkOf("https://a.com and more")); assertNull(Links.linkOf(""))
        assertNull("checklists are never link notes", Links.linkOf(Note(body = "[ ] https://a.com", checklist = true)))
        assertTrue(Links.isLinkNote(Note(body = "https://a.com\nnotes")))
        assertEquals("my words\nsecond line", Links.restOf("https://a.com\n\nmy words\nsecond line"))
        assertEquals("", Links.restOf("https://a.com"))
    }

    @Test fun domainDropsWww() {
        assertEquals("example.com", Links.domain("https://www.example.com/a/b")); assertEquals("blog.example.org", Links.domain("http://blog.example.org"))
    }

    @Test fun titlesAreParsedAndCleaned() {
        assertEquals("Hello & welcome", Links.parseTitle("<html><head><TITLE>\n  Hello &amp;   welcome \n</TITLE></head>"))
        assertEquals("It’s «ok»", Links.parseTitle("<title>It&#8217;s &#xAB;ok&#xBB;</title>"))
        assertEquals("a < b", Links.parseTitle("<title lang=en>a &lt; b</title>"))
        assertNull(Links.parseTitle("<html>no title</html>")); assertNull(Links.parseTitle("<title>   </title>"))
        assertEquals(120, Links.parseTitle("<title>${"x".repeat(500)}</title>")!!.length)
    }

    private fun page(body: String, type: String = "text/html; charset=utf-8", code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", type).setBody(body)

    @Test fun fetchTitleReadsHtmlPagesOnly() {
        server.enqueue(page("<html><title>Great article</title><body>…</body></html>"))
        assertEquals("Great article", Links.fetchTitle(server.url("/a").toString()))
        server.enqueue(page("<title>nope</title>", type = "image/png")); assertNull(Links.fetchTitle(server.url("/img").toString()))
        server.enqueue(page("<title>gone</title>", code = 404)); assertNull(Links.fetchTitle(server.url("/missing").toString()))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)); assertNull(Links.fetchTitle(server.url("/down").toString()))
    }

    @Test fun fetchTitleHonoursTheDeclaredCharset() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=ISO-8859-1").setBody(Buffer().write("<title>Café</title>".toByteArray(Charsets.ISO_8859_1))))
        assertEquals("Café", Links.fetchTitle(server.url("/").toString()))
    }

    @Test fun fetchTitleOnlyReadsTheStartOfBigPages() {
        server.enqueue(page("<title>Early</title>" + "x".repeat(2_000_000)))
        assertEquals("Early", Links.fetchTitle(server.url("/").toString()))
    }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(25) }
        return cond()
    }

    @Test fun backgroundFetchFillsAnEmptyTitleButNeverOverwritesOne() {
        val store = NoteStore(app)
        val blank = store.save(Note(body = server.url("/p").toString())); val named = store.save(Note(title = "Mine", body = server.url("/p").toString()))
        server.enqueue(page("<title>Fetched</title>")); server.enqueue(page("<title>Fetched</title>"))
        Links.fetchTitleAsync(app, blank, server.url("/p").toString()); Links.fetchTitleAsync(app, named, server.url("/p").toString())
        assertTrue("blank title gets filled", waitFor { store.get(blank)!!.title == "Fetched" })
        waitFor(500) { false }
        assertEquals("a title you wrote is never replaced", "Mine", store.get(named)!!.title)
    }

    @Test fun backgroundFetchRespectsTheSetting() {
        Prefs.setFetchLinkTitles(app, false)
        val store = NoteStore(app); val id = store.save(Note(body = server.url("/p").toString()))
        server.enqueue(page("<title>Fetched</title>"))
        Links.fetchTitleAsync(app, id, server.url("/p").toString())
        waitFor(600) { false }
        assertEquals("", store.get(id)!!.title); assertEquals("the site is not contacted when the setting is off", 0, server.requestCount)
    }

    // ---- Share → HueNotes -----------------------------------------------------------------------

    @Test fun sharedLinkBecomesALinkNote() {
        val n = ShareIntake.fromSharedText("https://example.com/article", "Great article", 7)!!
        assertEquals("Great article", n.title); assertEquals("https://example.com/article", n.body); assertEquals(7, n.color); assertTrue(Links.isLinkNote(n))
    }

    @Test fun textAroundASharedLinkIsKeptBelowIt() {
        val n = ShareIntake.fromSharedText("Look at this: https://example.com/x  it is great", null, 0)!!
        assertEquals("https://example.com/x\nLook at this: it is great", n.body); assertTrue(Links.isLinkNote(n)); assertEquals("", n.title)
    }

    @Test fun aSubjectThatIsTheLinkItselfIsNotATitle() {
        assertEquals("", ShareIntake.fromSharedText("https://example.com", "https://example.com", 0)!!.title)
    }

    @Test fun plainSharedTextBecomesATextNoteAndEmptyShareIsIgnored() {
        val n = ShareIntake.fromSharedText("  buy milk  ", "Reminder", 0)!!
        assertEquals("buy milk", n.body); assertEquals("Reminder", n.title); assertFalse(Links.isLinkNote(n))
        assertNull(ShareIntake.fromSharedText("   ", "x", 0)); assertNull(ShareIntake.fromSharedText(null, null, 0))
    }

    @Test fun titleIsOnlyFilledWhenBlank() {
        val store = NoteStore(app)
        val a = store.save(Note(body = "https://a.com")); val b = store.save(Note(title = "Keep", body = "https://b.com"))
        store.setTitleIfBlank(a, "New"); store.setTitleIfBlank(b, "New")
        assertEquals("New", store.get(a)!!.title); assertEquals("Keep", store.get(b)!!.title)
    }
}
