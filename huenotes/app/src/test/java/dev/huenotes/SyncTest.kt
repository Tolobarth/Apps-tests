package dev.huenotes

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun n(uid: String, title: String, at: Long, deletedAt: Long = 0) = Note(uid = uid, title = title, updatedAt = at, modifiedAt = at, deletedAt = deletedAt)
    private fun snap(vararg notes: Note, tombs: Map<String, Long> = emptyMap(), att: Map<String, List<Attachment>> = emptyMap()) = Snapshot(notes.toList(), tombs, att)
    private fun att(note: String, kind: AttachmentKind = AttachmentKind.IMAGE) = Attachment(noteUid = note, kind = kind)

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

    @Test fun winnerBringsItsAttachmentsAlong() {
        val mine = att("a"); val theirs = att("a", AttachmentKind.AUDIO)
        val remoteWins = SyncEngine.merge(snap(n("a", "x", 100), att = mapOf("a" to listOf(mine))), snap(n("a", "x", 200), att = mapOf("a" to listOf(theirs))), 1000)
        assertEquals(listOf(theirs), remoteWins.upsertAttachments["a"]); assertEquals(listOf(theirs), remoteWins.merged.attachments["a"])
        val removedRemotely = SyncEngine.merge(snap(n("a", "x", 100), att = mapOf("a" to listOf(mine))), snap(n("a", "x", 200)), 1000)
        assertEquals("an empty list clears local attachments", emptyList<Attachment>(), removedRemotely.upsertAttachments["a"])
        val localWins = SyncEngine.merge(snap(n("a", "x", 300), att = mapOf("a" to listOf(mine))), snap(n("a", "x", 200), att = mapOf("a" to listOf(theirs))), 1000)
        assertTrue(localWins.upsertAttachments.isEmpty()); assertEquals(listOf(mine), localWins.merged.attachments["a"])
    }

    @Test fun wireFormatRoundTrips() {
        val a = Attachment(uid = "att1", noteUid = "u", kind = AttachmentKind.AUDIO, durationMs = 4200, createdAt = 11)
        val s = snap(Note(uid = "u", title = "t", body = "[x] a", checklist = true, pinned = true, archived = true, reminderAt = 5, deletedAt = 9, updatedAt = 77, createdAt = 3, modifiedAt = 4, color = Note.COLORS[2]),
            tombs = mapOf("z" to 8), att = mapOf("u" to listOf(a)))
        assertEquals(s, SyncDoc.fromJson(SyncDoc.toJson(s)))
        assertThrows(IllegalArgumentException::class.java) { SyncDoc.fromJson("""{"app":"x","format":1,"notes":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { SyncDoc.fromJson("""{"app":"huenotes","format":99,"notes":[]}""") }
    }

    @Test fun format1FilesWithoutAttachmentsStillRead() {
        val old = """{"app":"huenotes","format":1,"notes":[{"uid":"u","title":"t","body":"","checklist":false,"color":-1,"archived":false,"pinned":false,"reminderAt":0,"modifiedAt":1,"createdAt":1,"updatedAt":1,"deletedAt":0}],"tombstones":[]}"""
        val s = SyncDoc.fromJson(old)
        assertEquals(1, s.notes.size); assertTrue(s.attachments.isEmpty())
    }

    // ---- two devices through a shared fake remote ---------------------------------------------

    private class MemoryRemote(val clock: () -> Long = { System.currentTimeMillis() }) : SyncRemote {
        var file: String? = null
        val blobs = LinkedHashMap<String, Pair<ByteArray, Long>>()
        override fun download() = file
        override fun upload(json: String) { file = json }
        override fun listBlobs() = blobs.map { BlobInfo(it.key, it.value.second) }
        override fun downloadBlob(name: String) = blobs[name]?.first
        override fun uploadBlob(name: String, bytes: ByteArray, mime: String) { blobs[name] = bytes to clock() }
        override fun deleteBlob(name: String) { blobs.remove(name) }
    }

    private fun tick() = Thread.sleep(4)

    private fun device(name: String): NoteStore {
        app.deleteDatabase("$name.db")
        val dir = File(app.cacheDir, "att-$name").also { it.deleteRecursively() }
        return NoteStore(app, "$name.db", AttachmentStorage(dir))
    }

    @Test fun twoDevicesConverge() {
        val a = device("A"); val b = device("B"); val cloud = MemoryRemote()
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
        tick(); syncB(); syncA()
        assertTrue(a.allForSync().isEmpty() && b.allForSync().isEmpty())
    }

    @Test fun simultaneousEditsConvergeToTheLaterOne() {
        val a = device("A"); val b = device("B"); val cloud = MemoryRemote()
        a.save(Note(title = "base")); SyncRunner.sync(a, cloud); tick(); SyncRunner.sync(b, cloud); tick()
        a.save(a.list(false).single().copy(title = "from A")); tick()
        b.save(b.list(false).single().copy(title = "from B"))
        SyncRunner.sync(a, cloud); SyncRunner.sync(b, cloud); SyncRunner.sync(a, cloud)
        assertEquals("from B", a.list(false).single().title); assertEquals("from B", b.list(false).single().title)
    }

    @Test fun editMadeDuringSyncIsNotOverwritten() {
        val a = device("A")
        val id = a.save(Note(title = "v1")); val uid = a.get(id)!!.uid
        val stale = a.allForSync().associate { it.uid to it.updatedAt }
        tick(); a.save(a.get(id)!!.copy(title = "edited meanwhile")); tick()
        a.applySync(listOf(Note(uid = uid, title = "remote", updatedAt = System.currentTimeMillis() + 1000)), emptyList(), emptyMap(), stale)
        assertEquals("edited meanwhile", a.get(id)!!.title)
    }

    // ---- attachments across devices -----------------------------------------------------------

    private fun addFile(store: NoteStore, noteUid: String, kind: AttachmentKind, bytes: ByteArray, durationMs: Long = 0): Attachment {
        val a = Attachment(noteUid = noteUid, kind = kind, durationMs = durationMs)
        store.storage.file(a).writeBytes(bytes); store.addAttachment(a)
        return a
    }

    @Test fun attachmentsTravelWithTheNoteAndAreCleanedUp() {
        val a = device("A"); val b = device("B"); val cloud = MemoryRemote()
        val id = a.save(Note(title = "trip")); val uid = a.get(id)!!.uid
        val photoBytes = ByteArray(300) { (it * 7).toByte() }      // binary, not text
        val photo = addFile(a, uid, AttachmentKind.IMAGE, photoBytes)
        val voice = addFile(a, uid, AttachmentKind.AUDIO, ByteArray(40) { it.toByte() }, durationMs = 4000)
        SyncRunner.sync(a, cloud)
        assertEquals(setOf(photo.blobName, voice.blobName), cloud.blobs.keys)

        tick(); val got = SyncRunner.sync(b, cloud)
        assertEquals(0, got.filesPending)
        val onB = b.list(false).single()
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.AUDIO), b.attachmentsFor(onB.uid).map { it.kind })
        assertArrayEquals(photoBytes, b.storage.file(photo).readBytes())
        assertEquals(4000L, b.attachmentsFor(onB.uid).last().durationMs)

        tick(); b.removeAttachment(b.attachmentsFor(onB.uid).first { it.kind == AttachmentKind.IMAGE }); SyncRunner.sync(b, cloud)
        tick(); SyncRunner.sync(a, cloud)
        assertEquals(listOf(AttachmentKind.AUDIO), a.attachmentsFor(uid).map { it.kind })
        assertFalse("removed attachment's file is deleted on the other device", a.storage.file(photo).exists())
        assertTrue("fresh unreferenced blob survives the grace period", photo.blobName in cloud.blobs)
        SyncRunner.sync(a, cloud, System.currentTimeMillis() + SyncRunner.BLOB_GRACE_MS + 1000)
        assertFalse("old unreferenced blob is removed", photo.blobName in cloud.blobs); assertTrue(voice.blobName in cloud.blobs)
    }

    @Test fun deletingANoteForGoodDeletesItsAttachmentFiles() {
        val a = device("A")
        val id = a.save(Note(title = "x")); val photo = addFile(a, a.get(id)!!.uid, AttachmentKind.IMAGE, byteArrayOf(1))
        a.trash(id); assertTrue("trash keeps attachments so restore works", a.storage.file(photo).exists())
        a.delete(id)
        assertFalse(a.storage.file(photo).exists()); assertTrue(a.attachmentsFor(a.allForSync().firstOrNull()?.uid ?: "none").isEmpty())
        assertTrue(a.attachmentsByNote().isEmpty())
    }

    @Test fun filesAreUploadedBeforeTheNotesFile() {
        val a = device("A")
        val id = a.save(Note(title = "x")); addFile(a, a.get(id)!!.uid, AttachmentKind.IMAGE, byteArrayOf(1, 2))
        val failing = object : SyncRemote {
            var docUploaded = false
            override fun download(): String? = null
            override fun upload(json: String) { docUploaded = true }
            override fun listBlobs() = emptyList<BlobInfo>()
            override fun downloadBlob(name: String): ByteArray? = null
            override fun uploadBlob(name: String, bytes: ByteArray, mime: String) { throw java.io.IOException("offline") }
            override fun deleteBlob(name: String) = Unit
        }
        assertThrows(java.io.IOException::class.java) { SyncRunner.sync(a, failing) }
        assertFalse("the notes file must not point at files the cloud lacks", failing.docUploaded)
    }

    @Test fun missingRemoteFileIsReportedNotFatal() {
        val a = device("A"); val b = device("B"); val cloud = MemoryRemote()
        val id = a.save(Note(title = "x")); val att = addFile(a, a.get(id)!!.uid, AttachmentKind.IMAGE, byteArrayOf(9))
        SyncRunner.sync(a, cloud); cloud.blobs.remove(att.blobName)
        tick(); val out = SyncRunner.sync(b, cloud)
        assertEquals(1, out.filesPending); assertEquals(1, b.attachmentsFor(b.list(false).single().uid).size)
    }

    @Test fun orphanedBlobsOlderThanGraceAreSwept() {
        val a = device("A"); val cloud = MemoryRemote()
        cloud.blobs["att-old"] = byteArrayOf(1) to 0L
        cloud.blobs["att-new"] = byteArrayOf(2) to System.currentTimeMillis()
        SyncRunner.sync(a, cloud)
        assertEquals(setOf("att-new"), cloud.blobs.keys)
    }

    // ---- migrations ----------------------------------------------------------------------------

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
        val store = NoteStore(app)
        val all = store.allForSync()
        assertEquals(2, all.size); assertEquals("uids must be unique and non-blank", 2, all.map { it.uid }.toSet().size)
        assertTrue(all.all { it.uid.isNotBlank() }); assertEquals(listOf(10L, 20L), all.map { it.updatedAt })
        store.addAttachment(Attachment(noteUid = all[0].uid, kind = AttachmentKind.IMAGE))
        assertEquals(1, store.attachmentsFor(all[0].uid).size)
    }

    @Test fun upgradesVersion3DatabaseAddingAttachments() {
        app.deleteDatabase("huenotes.db")
        val f = app.getDatabasePath("huenotes.db"); f.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("""CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, checklist INTEGER NOT NULL,
                color INTEGER NOT NULL, archived INTEGER NOT NULL, reminder_at INTEGER NOT NULL, modified_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NOT NULL DEFAULT 0,
                uid TEXT NOT NULL DEFAULT '', sync_at INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("CREATE UNIQUE INDEX notes_uid ON notes(uid)")
            db.execSQL("CREATE TABLE tombstones(uid TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO notes(title,body,checklist,color,archived,reminder_at,modified_at,uid,sync_at) VALUES('keep','',0,-1,0,0,1,'u1',1)")
            db.version = 3
        }
        val store = NoteStore(app)
        assertEquals("keep", store.list(false).single().title)
        assertTrue(store.attachmentsByNote().isEmpty())
    }

    // ---- Drive client against a local fake of the endpoints it uses --------------------------

    private class FakeDrive {
        class F(val name: String, var bytes: ByteArray, val created: String)
        val files = LinkedHashMap<String, F>(); private var next = 1
        val auth = mutableListOf<String?>(); val violations = mutableListOf<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    auth += request.getHeader("Authorization")
                    val url = request.requestUrl!!; val path = url.encodedPath
                    return when {
                        path == "/drive/v3/files" && request.method == "GET" -> {
                            if (url.queryParameter("spaces") != "appDataFolder") violations += "list must use appDataFolder"
                            val q = url.queryParameter("q").orEmpty()
                            val exact = Regex("name='([^']+)'").find(q)?.groupValues?.get(1)
                            val hits = files.filter { (_, f) -> if (exact != null) f.name == exact else f.name.contains("att-") }
                            val arr = JSONArray(hits.map { (id, f) -> JSONObject().put("id", id).put("name", f.name).put("createdTime", f.created) })
                            ok(JSONObject().put("files", arr).toString())
                        }
                        path.startsWith("/drive/v3/files/") && request.method == "GET" && url.queryParameter("alt") == "media" ->
                            files[path.substringAfterLast('/')]?.let { MockResponse().setResponseCode(200).setBody(Buffer().write(it.bytes)) } ?: MockResponse().setResponseCode(404)
                        path.startsWith("/drive/v3/files/") && request.method == "DELETE" -> { files.remove(path.substringAfterLast('/')); MockResponse().setResponseCode(204) }
                        path == "/upload/drive/v3/files" && request.method == "POST" -> {
                            val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
                            val parts = String(request.body.readByteArray(), Charsets.ISO_8859_1).split("--$boundary").filter { it.contains("Content-Type") }
                            val meta = JSONObject(parts[0].substringAfter("\r\n\r\n").trim())
                            if (meta.getJSONArray("parents").getString(0) != "appDataFolder") violations += "must create inside appDataFolder"
                            val data = parts[1].substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
                            val id = "F${next++}"; files[id] = F(meta.getString("name"), data, "2026-10-14T10:20:30.123Z")
                            ok("""{"id":"$id"}""")
                        }
                        path.startsWith("/upload/drive/v3/files/") && request.getHeader("X-HTTP-Method-Override") == "PATCH" -> {
                            files[path.substringAfterLast('/')]?.bytes = request.body.readByteArray(); ok("{}")
                        }
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
        assertEquals(1, f.files.size)
        assertEquals("""{"v":1}""", r.download())
        r.upload("""{"v":2}"""); assertEquals("update, not a second file", 1, f.files.size)
        assertEquals("""{"v":2}""", r.download())
        assertTrue(f.auth.all { it == "Bearer tok123" }); assertTrue(f.violations.toString(), f.violations.isEmpty())
    }

    @Test fun driveRemoteStoresBinaryFiles() {
        val f = FakeDrive().also { fake = it }; val r = DriveRemote("t", f.url)
        val bytes = ByteArray(256) { it.toByte() }
        r.uploadBlob("att-1", bytes, "image/jpeg"); r.uploadBlob("att-1", byteArrayOf(0), "image/jpeg")
        assertEquals("same name is not uploaded twice", 1, f.files.size)
        assertArrayEquals(bytes, r.downloadBlob("att-1")); assertNull(r.downloadBlob("att-missing"))
        val listed = r.listBlobs().single()
        assertEquals("att-1", listed.name); assertEquals(1_791_973_230_123L, listed.createdAt)
        r.deleteBlob("att-1"); assertTrue(r.listBlobs().isEmpty()); assertTrue(f.violations.toString(), f.violations.isEmpty())
    }

    @Test fun driveErrorsSurfaceAsExceptions() {
        val bad = object : SyncRemote {
            override fun download(): String? = throw java.io.IOException("Google Drive error 401")
            override fun upload(json: String) = Unit
            override fun listBlobs() = emptyList<BlobInfo>()
            override fun downloadBlob(name: String): ByteArray? = null
            override fun uploadBlob(name: String, bytes: ByteArray, mime: String) = Unit
            override fun deleteBlob(name: String) = Unit
        }
        assertThrows(java.io.IOException::class.java) { SyncRunner.sync(device("A"), bad) }
        val f = FakeDrive().also { fake = it }
        assertThrows(java.io.IOException::class.java) { DriveRemote("t", f.url + "/nope").download() }
    }

    @Test fun fullRoundTripThroughDriveClientWithAnAttachment() {
        val f = FakeDrive().also { fake = it }
        val a = device("A"); val b = device("B")
        val id = a.save(Note(title = "via drive", body = "[x] ok", checklist = true))
        val photo = addFile(a, a.get(id)!!.uid, AttachmentKind.IMAGE, ByteArray(500) { (it * 3).toByte() })
        SyncRunner.sync(a, DriveRemote("t", f.url)); tick(); SyncRunner.sync(b, DriveRemote("t", f.url))
        val got = b.list(false).single()
        assertEquals("via drive", got.title); assertTrue(got.checklist)
        assertArrayEquals(a.storage.file(photo).readBytes(), b.storage.file(photo).readBytes())
        assertTrue(f.violations.toString(), f.violations.isEmpty())
    }
}
