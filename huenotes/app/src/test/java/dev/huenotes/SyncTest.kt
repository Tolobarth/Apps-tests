package dev.huenotes

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun n(uid: String, title: String, at: Long, deletedAt: Long = 0) = Note(uid = uid, title = title, updatedAt = at, modifiedAt = at, deletedAt = deletedAt)
    private fun snap(vararg notes: Note, tombs: Map<String, Long> = emptyMap()) = Snapshot(notes.toList(), tombs)

    // ---- pure merge rules ---------------------------------------------------------------------

    @Test fun newerEditWinsOnEitherSide() {
        val r = SyncEngine.merge(snap(n("a", "local", 100)), snap(n("a", "remote", 200)), 1000)
        assertEquals(listOf("remote"), r.upserts.map { it.title }); assertFalse(r.needUpload)
        val r2 = SyncEngine.merge(snap(n("a", "local", 300)), snap(n("a", "remote", 200)), 1000)
        assertTrue(r2.upserts.isEmpty()); assertTrue(r2.needUpload)
    }

    @Test fun unknownNotesAreCopiedBothWays() {
        val r = SyncEngine.merge(snap(n("l", "L", 1)), snap(n("r", "R", 1)), 1000)
        assertEquals(listOf("R"), r.upserts.map { it.title }); assertTrue(r.needUpload)
        assertEquals(setOf("l", "r"), r.merged.notes.map { it.uid }.toSet())
    }

    @Test fun tombstoneBeatsOlderEditButNotNewerOne() {
        val gone = SyncEngine.merge(snap(n("a", "x", 100)), snap(tombs = mapOf("a" to 150)), 1000)
        assertEquals(setOf("a"), gone.deleteUids); assertTrue(gone.merged.notes.isEmpty())
        val revived = SyncEngine.merge(snap(n("a", "edited later", 300)), snap(tombs = mapOf("a" to 150)), 1000)
        assertTrue(revived.deleteUids.isEmpty()); assertEquals(1, revived.merged.notes.size); assertTrue(revived.needUpload)
        val remoteOnly = SyncEngine.merge(snap(tombs = mapOf("a" to 150)), snap(n("a", "x", 100)), 1000)
        assertTrue("a note we never had stays out", remoteOnly.merged.notes.isEmpty() && remoteOnly.upserts.isEmpty())
    }

    @Test fun mergeIsIdempotentAndPrunesOldTombstones() {
        val a = snap(n("a", "x", 100), tombs = mapOf("t" to 500))
        val once = SyncEngine.merge(a, a, 1000)
        assertTrue(once.upserts.isEmpty()); assertTrue(once.deleteUids.isEmpty()); assertFalse(once.needUpload)
        val old = SyncEngine.merge(snap(tombs = mapOf("old" to 1)), snap(tombs = mapOf("old" to 1)), SyncEngine.TOMBSTONE_KEEP_MS + 10)
        assertTrue(old.merged.tombstones.isEmpty()); assertTrue("pruned locally, so remote must be rewritten", old.needUpload)
    }

    @Test fun wireFormatRoundTrips() {
        val s = snap(Note(uid = "u", title = "t", body = "[x] a", checklist = true, pinned = true, archived = true, reminderAt = 5, deletedAt = 9, updatedAt = 77, createdAt = 3, modifiedAt = 4, color = Note.COLORS[2]), tombs = mapOf("z" to 8))
        assertEquals(s, SyncDoc.fromJson(SyncDoc.toJson(s)))
        assertThrows(IllegalArgumentException::class.java) { SyncDoc.fromJson("""{"app":"x","format":1,"notes":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { SyncDoc.fromJson("""{"app":"huenotes","format":99,"notes":[]}""") }
    }

    // ---- two devices through a shared fake remote ---------------------------------------------

    private class MemoryRemote : SyncRemote {
        var file: String? = null
        override fun download() = file
        override fun upload(json: String) { file = json }
    }

    private fun tick() = Thread.sleep(4)

    @Test fun twoDevicesConverge() {
        app.deleteDatabase("A.db"); app.deleteDatabase("B.db")
        val a = NoteStore(app, "A.db"); val b = NoteStore(app, "B.db"); val cloud = MemoryRemote()
        fun syncA() = SyncRunner.sync(a, cloud)
        fun syncB() = SyncRunner.sync(b, cloud)

        val id = a.save(Note(title = "Shopping", body = "milk")); syncA(); tick()
        syncB()
        assertEquals("B receives the note", listOf("Shopping"), b.list(false).map { it.title })

        val onB = b.list(false).single(); tick()
        b.save(onB.copy(title = "Shopping list")); syncB(); tick()
        syncA()
        assertEquals("A sees B's edit", "Shopping list", a.get(id)!!.title)

        tick(); a.setPinned(id, true); syncA(); tick(); syncB()
        assertTrue("pin syncs", b.list(false).single().pinned)

        tick(); a.trash(id); syncA(); tick(); syncB()
        assertTrue("trash syncs", b.list(false).isEmpty()); assertEquals(1, b.trashed().size)

        tick(); b.delete(b.trashed().single().id); syncB(); tick(); syncA()
        assertNull("permanent delete reaches A", a.get(id)); assertTrue(a.trashed().isEmpty())
        tick(); syncB(); syncA()   // nothing left to do, and nothing resurrects
        assertTrue(a.allForSync().isEmpty() && b.allForSync().isEmpty())
    }

    @Test fun simultaneousEditsConvergeToTheLaterOne() {
        app.deleteDatabase("A.db"); app.deleteDatabase("B.db")
        val a = NoteStore(app, "A.db"); val b = NoteStore(app, "B.db"); val cloud = MemoryRemote()
        a.save(Note(title = "base")); SyncRunner.sync(a, cloud); tick(); SyncRunner.sync(b, cloud); tick()
        a.save(a.list(false).single().copy(title = "from A")); tick()
        b.save(b.list(false).single().copy(title = "from B"))      // later edit
        SyncRunner.sync(a, cloud); SyncRunner.sync(b, cloud); SyncRunner.sync(a, cloud)
        assertEquals("from B", a.list(false).single().title); assertEquals("from B", b.list(false).single().title)
    }

    @Test fun editMadeDuringSyncIsNotOverwritten() {
        app.deleteDatabase("A.db"); val a = NoteStore(app, "A.db")
        val id = a.save(Note(title = "v1")); val uid = a.get(id)!!.uid
        val stale = a.allForSync().associate { it.uid to it.updatedAt }
        tick(); a.save(a.get(id)!!.copy(title = "edited meanwhile")); tick()
        a.applySync(listOf(Note(uid = uid, title = "remote", updatedAt = System.currentTimeMillis() + 1000)), emptyList(), emptyMap(), stale)
        assertEquals("edited meanwhile", a.get(id)!!.title)
    }

    // ---- migration -----------------------------------------------------------------------------

    @Test fun upgradesVersion2DatabaseGivingNotesIdentity() {
        app.deleteDatabase("huenotes.db")
        val f = app.getDatabasePath("huenotes.db"); f.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("""CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, checklist INTEGER NOT NULL,
                color INTEGER NOT NULL, archived INTEGER NOT NULL, reminder_at INTEGER NOT NULL, modified_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("INSERT INTO notes(title,body,checklist,color,archived,reminder_at,modified_at,created_at) VALUES('one','',0,-1,0,0,10,5),('two','',0,-1,0,0,20,6)")
            db.version = 2
        }
        val all = NoteStore(app).allForSync()
        assertEquals(2, all.size); assertEquals("uids must be unique and non-blank", 2, all.map { it.uid }.toSet().size)
        assertTrue(all.all { it.uid.isNotBlank() }); assertEquals(listOf(10L, 20L), all.map { it.updatedAt })
    }

    // ---- Drive client against a local fake of the three endpoints it uses --------------------

    private class FakeDrive {
        var stored: String? = null; var created = 0; var patched = 0; var lastCreateMeta = ""
        val auth = mutableListOf<String?>(); val violations = mutableListOf<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    auth += request.getHeader("Authorization")
                    val path = request.path!!.substringBefore('?'); val query = request.path!!.substringAfter('?', "")
                    val body = request.body.readUtf8()
                    return when {
                        path == "/drive/v3/files" && request.method == "GET" -> {
                            if (!query.contains("spaces=appDataFolder") || !query.contains("huenotes-sync.json")) violations += "bad list query: $query"
                            ok(if (stored == null) """{"files":[]}""" else """{"files":[{"id":"F1"}]}""")
                        }
                        path == "/drive/v3/files/F1" && query.contains("alt=media") -> ok(stored!!)
                        path == "/upload/drive/v3/files" && request.method == "POST" -> {
                            if (request.getHeader("Content-Type")?.startsWith("multipart/related; boundary=") != true) violations += "bad create content-type"
                            val parts = body.split(Regex("--huenotes\\d+")).filter { it.contains("Content-Type") }
                            lastCreateMeta = parts[0].substringAfter("\r\n\r\n").trim(); stored = parts[1].substringAfter("\r\n\r\n").trim(); created++
                            ok("""{"id":"F1"}""")
                        }
                        path == "/upload/drive/v3/files/F1" && request.getHeader("X-HTTP-Method-Override") == "PATCH" -> { stored = body; patched++; ok("""{"id":"F1"}""") }
                        else -> MockResponse().setResponseCode(404).setBody("""{"error":"unexpected ${request.method} ${request.path}"}""")
                    }
                }
            }
            start()
        }
        private fun ok(text: String) = MockResponse().setResponseCode(200).setBody(text)
        val url: String get() = server.url("/").toString().trimEnd('/')
        fun stop() = server.shutdown()
    }

    private var fake: FakeDrive? = null
    @After fun stopFake() { fake?.stop() }

    @Test fun driveRemoteCreatesThenUpdatesTheHiddenFile() {
        val f = FakeDrive().also { fake = it }; val r = DriveRemote("tok123", f.url)
        assertNull(r.download())
        r.upload("""{"v":1}""")
        assertEquals(1, f.created); assertTrue(f.lastCreateMeta.contains("appDataFolder")); assertTrue(f.lastCreateMeta.contains("huenotes-sync.json"))
        assertEquals("""{"v":1}""", r.download())
        r.upload("""{"v":2}"""); assertEquals(1, f.patched); assertEquals(1, f.created)
        assertEquals("""{"v":2}""", r.download())
        assertTrue(f.auth.all { it == "Bearer tok123" }); assertTrue(f.violations.toString(), f.violations.isEmpty())
    }

    @Test fun driveErrorsSurfaceAsExceptions() {
        val f = FakeDrive().also { fake = it }
        val bad = object : SyncRemote { override fun download() = throw java.io.IOException("Google Drive error 401"); override fun upload(json: String) = Unit }
        app.deleteDatabase("A.db")
        assertThrows(java.io.IOException::class.java) { SyncRunner.sync(NoteStore(app, "A.db"), bad) }
        assertNotNull(f)
    }

    @Test fun fullRoundTripThroughDriveClient() {
        app.deleteDatabase("A.db"); app.deleteDatabase("B.db")
        val f = FakeDrive().also { fake = it }
        val a = NoteStore(app, "A.db"); val b = NoteStore(app, "B.db")
        a.save(Note(title = "via drive", body = "[x] ok", checklist = true))
        SyncRunner.sync(a, DriveRemote("t", f.url)); SyncRunner.sync(b, DriveRemote("t", f.url))
        assertEquals("via drive", b.list(false).single().title); assertTrue(b.list(false).single().checklist)
    }
}
