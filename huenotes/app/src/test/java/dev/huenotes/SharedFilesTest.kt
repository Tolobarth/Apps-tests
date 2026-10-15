package dev.huenotes

import android.app.Application
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** "Share → Save to HueNotes" with photos, PDFs and other files, and how such files travel through sync, backup and export. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SharedFilesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dirs = ArrayList<File>()
    private val color = Note.COLORS[3]

    private fun device(name: String = "d"): NoteStore {
        val d = Files.createTempDirectory("sf-$name").toFile().also { dirs += it }
        return NoteStore(app, "sf-$name-${System.nanoTime()}.db", AttachmentStorage(File(d, "att")))
    }
    @After fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private val pdfBytes = "%PDF-1.4\n% not a real document, but kept byte for byte\n".toByteArray()
    private fun incoming(name: String, mime: String, bytes: ByteArray, size: Long = bytes.size.toLong()) =
        SharedFiles.Incoming(name, mime, size) { bytes.inputStream() }
    private fun unreadable(name: String, mime: String) = SharedFiles.Incoming(name, mime, -1) { throw IOException("revoked") }
    private fun jpeg(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    // ---- receiving -----------------------------------------------------------------------------

    @Test fun aPdfWithTextBecomesOneNoteWithTheFileKeptAsIs() {
        val s = device()
        val r = SharedFiles.receive(app, s, "Please sign by Friday", "Contract", color, listOf(incoming("Contract v2.pdf", "application/pdf", pdfBytes)))
        val n = s.get(r.note!!.id)!!
        assertEquals("Contract", n.title); assertEquals("Please sign by Friday", n.body); assertEquals(color, n.color)
        val a = s.attachmentsFor(n.uid).single()
        assertEquals(AttachmentKind.FILE, a.kind); assertEquals("Contract v2.pdf", a.name); assertEquals("application/pdf", a.mimeType)
        assertEquals("pdf", a.ext); assertTrue(a.fileName.endsWith(".pdf"))
        assertArrayEquals(pdfBytes, s.storage.file(a).readBytes())
        assertEquals(1, r.added); assertEquals(0, r.failed)
    }

    @Test fun aLoneFileNamesTheNote() {
        val s = device()
        val r = SharedFiles.receive(app, s, null, null, color, listOf(incoming("Boarding pass.pdf", "application/pdf", pdfBytes)))
        assertEquals("Boarding pass", r.note!!.title); assertEquals("", r.note!!.body)
    }

    @Test fun photosAreScaledLikeCameraPhotosAndDoNotNameTheNote() {
        val s = device()
        val r = SharedFiles.receive(app, s, null, null, color, listOf(incoming("IMG_20261014.jpg", "image/jpeg", jpeg(3000, 1500))))
        assertEquals("", r.note!!.title)
        val a = s.attachmentsFor(r.note!!.uid).single()
        assertEquals(AttachmentKind.IMAGE, a.kind)
        val b = android.graphics.BitmapFactory.decodeFile(s.storage.file(a).path)
        assertEquals(AttachmentFiles.MAX_EDGE, b.width)
    }

    @Test fun picturesAndroidCannotDecodeAreKeptAsFiles() {
        val s = device()
        val raw = "RAW sensor data".toByteArray()
        val r = SharedFiles.receive(app, s, null, null, color, listOf(incoming("DSC0001.dng", "image/x-adobe-dng", raw)))
        val a = s.attachmentsFor(r.note!!.uid).single()
        assertEquals(AttachmentKind.FILE, a.kind); assertArrayEquals(raw, s.storage.file(a).readBytes())
        assertEquals("only the scaled photo or the original stays, never both", 1, s.storage.dir.listFiles()!!.size)
    }

    @Test fun gifsKeepTheirAnimation() = assertEquals(AttachmentKind.FILE, SharedFiles.kindFor("image/gif"))

    @Test fun severalFilesLandInOneNoteInOrder() {
        val s = device()
        val r = SharedFiles.receive(app, s, null, null, color, listOf(
            incoming("a.jpg", "image/jpeg", jpeg(100, 100)), incoming("b.pdf", "application/pdf", pdfBytes), incoming("c.docx", "application/octet-stream", byteArrayOf(9))))
        assertEquals(3, r.added); assertEquals("", r.note!!.title)
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.FILE, AttachmentKind.FILE), s.attachmentsFor(r.note!!.uid).map { it.kind })
        assertEquals(1, s.list(false).size)
    }

    @Test fun tooLargeOrUnreadableFilesAreSkippedAndCounted() {
        val s = device()
        val r = SharedFiles.receive(app, s, null, null, color, listOf(
            incoming("film.mp4", "video/mp4", byteArrayOf(1), size = SharedFiles.MAX_FILE + 1),
            unreadable("gone.pdf", "application/pdf"),
            incoming("ok.pdf", "application/pdf", pdfBytes)))
        assertEquals(1, r.added); assertEquals(2, r.failed)
        assertEquals(listOf("ok.pdf"), s.attachmentsFor(r.note!!.uid).map { it.name })
        assertEquals("no half-copied files left behind", 1, s.storage.dir.listFiles()!!.size)
    }

    @Test fun aFileThatGrowsPastTheLimitWhileCopyingIsDropped() {
        val s = device()
        val big = SharedFiles.Incoming("x.bin", "application/octet-stream", -1) { java.io.ByteArrayInputStream(ByteArray((SharedFiles.MAX_FILE + 1).toInt())) }
        val r = SharedFiles.receive(app, s, null, null, color, listOf(big))
        assertNull(r.note); assertEquals(1, r.failed)
        assertTrue(s.list(false).isEmpty()); assertTrue(s.storage.dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun nothingSavedMeansNoEmptyNote() {
        val s = device()
        val r = SharedFiles.receive(app, s, null, null, color, listOf(unreadable("a.pdf", "application/pdf")))
        assertNull(r.note); assertTrue(s.list(false).isEmpty()); assertTrue(s.tombstones().isEmpty())
    }

    @Test fun textStillWorksAsBefore() {
        val s = device()
        val r = SharedFiles.receive(app, s, "Look https://example.com/a", "Example", color, emptyList())
        assertEquals("https://example.com/a\nLook", r.note!!.body); assertEquals("Example", r.note!!.title)
        assertNull(SharedFiles.receive(app, s, "  ", null, color, emptyList()).note)
    }

    @Test fun aSharedTextFileBecomesTheNoteItself() {
        val s = device()
        val md = "# Packing\n- [ ] passport\n- [x] charger\n".toByteArray()
        val r = SharedFiles.receive(app, s, null, null, color, listOf(incoming("trip.md", "application/octet-stream", md)))
        val n = r.note!!
        assertEquals("Packing", n.title); assertTrue(n.checklist); assertEquals(color, n.color)
        assertTrue(s.attachmentsFor(n.uid).isEmpty())
    }

    // ---- intents and names ---------------------------------------------------------------------

    @Test fun onlyOtherAppsContentAddressesAreAccepted() {
        val ok = Uri.parse("content://com.android.providers.media.documents/document/1")
        val single = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, ok)
        assertEquals(listOf(ok), SharedFiles.streams(single))
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(
            ok, ok, Uri.parse("file:///data/data/dev.huenotes/databases/huenotes.db"), Uri.parse("content://dev.huenotes.files/share/1/x.jpg")))
        assertEquals("file:// and our own files are refused, repeats dropped", listOf(ok), SharedFiles.streams(many))
        assertTrue(SharedFiles.streams(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, ok)).isEmpty())
    }

    @Test fun namesAreMadeSafe() {
        assertEquals("passwd", SharedFiles.cleanName("../../etc/passwd"))
        assertEquals("a_b.pdf", SharedFiles.cleanName("a:b.pdf"))
        assertEquals("file", SharedFiles.cleanName("  ..  "))
        val long = SharedFiles.cleanName("x".repeat(300) + ".pdf")
        assertEquals(100, long.length); assertTrue(long.endsWith(".pdf"))
        assertEquals("bin", Attachment.extOf("noext")); assertEquals("bin", Attachment.extOf("a.p/df")); assertEquals("docx", Attachment.extOf("A.DOCX"))
    }

    @Test fun describeUsesTheSendersNameAndTheIntentTypeAsFallback() {
        val uri = Uri.parse("content://com.example.files/doc/42/Invoice%20March.pdf")
        shadowOf(app.contentResolver).registerInputStream(uri, pdfBytes.inputStream())
        val f = SharedFiles.describe(app.contentResolver, uri, "application/pdf")
        assertEquals("Invoice March.pdf", f.name); assertEquals("application/pdf", f.mime)
        assertArrayEquals(pdfBytes, f.open().readBytes())
    }

    @Test fun shareScreenSavesTheFileAndCloses() {
        val uri = Uri.parse("content://com.example.files/doc/7/Ticket.pdf")
        shadowOf(app.contentResolver).registerInputStream(uri, pdfBytes.inputStream())
        val store = NoteStore(app)
        val before = store.list(false).size
        val i = Intent(app, ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        val ctl = Robolectric.buildActivity(ShareReceiverActivity::class.java, i).create()
        val deadline = System.currentTimeMillis() + 10_000
        while (!ctl.get().isFinishing && System.currentTimeMillis() < deadline) { Thread.sleep(20); ShadowLooper.idleMainLooper() }
        assertTrue(ctl.get().isFinishing)
        val n = store.list(false).also { assertEquals(before + 1, it.size) }.first { it.title == "Ticket" }
        val a = store.attachmentsFor(n.uid).single()
        assertEquals("Ticket.pdf", a.name); assertArrayEquals(pdfBytes, store.storage.file(a).readBytes())
    }

    // ---- sync, backup, export ------------------------------------------------------------------

    private fun seedFile(s: NoteStore): Attachment {
        val id = s.save(Note(title = "Docs")); val uid = s.get(id)!!.uid
        val a = Attachment(noteUid = uid, kind = AttachmentKind.FILE, name = "Lease [final].pdf", mime = "application/pdf")
        s.storage.file(a).writeBytes(pdfBytes); s.addAttachment(a)
        return a
    }

    @Test fun filesSyncWithTheirNameAndRaiseTheFormatOnlyWhenPresent() {
        val plain = Snapshot(listOf(Note(uid = "n1")), emptyMap(), mapOf("n1" to listOf(Attachment(noteUid = "n1", kind = AttachmentKind.IMAGE))))
        assertEquals("older devices keep syncing until a file is added", 2, JSONObject(SyncDoc.toJson(plain)).getInt("format"))
        val f = Attachment(noteUid = "n1", kind = AttachmentKind.FILE, name = "a.pdf", mime = "application/pdf")
        val json = SyncDoc.toJson(plain.copy(attachments = mapOf("n1" to listOf(f))))
        assertEquals(3, JSONObject(json).getInt("format"))
        assertEquals(f, SyncDoc.fromJson(json).attachments["n1"]!!.single())
    }

    @Test fun twoDevicesShareAReceivedFile() {
        val a = device("A"); val b = device("B")
        val cloud = object : SyncRemote {
            var file: String? = null; val blobs = HashMap<String, ByteArray>(); var mimes = HashMap<String, String>()
            override fun download() = file
            override fun upload(json: String) { file = json }
            override fun listBlobs() = blobs.keys.map { BlobInfo(it, 0) }
            override fun downloadBlob(name: String) = blobs[name]
            override fun uploadBlob(name: String, bytes: ByteArray, mime: String) { blobs[name] = bytes; mimes[name] = mime }
            override fun deleteBlob(name: String) { blobs.remove(name) }
        }
        val att = seedFile(a)
        SyncRunner.sync(a, cloud); SyncRunner.sync(b, cloud)
        val got = b.attachmentsFor(att.noteUid).single()
        assertEquals(att, got); assertArrayEquals(pdfBytes, b.storage.file(got).readBytes())
        assertEquals("application/pdf", cloud.mimes[att.blobName])
    }

    @Test fun zipBackupRestoresFiles() {
        val a = device("A"); val att = seedFile(a)
        val z = ByteArrayOutputStream().also { BackupZip.write(a, it) }.toByteArray()
        val b = device("B")
        assertEquals(1, BackupZip.read(app, b, z.inputStream(), File(dirs[0], "tmp")))
        val got = b.attachmentsFor(att.noteUid).single()
        assertEquals("Lease [final].pdf", got.name); assertArrayEquals(pdfBytes, b.storage.file(got).readBytes())
    }

    @Test fun markdownLinksTheFileByName() {
        val a = device(); val att = seedFile(a)
        val md = MarkdownExport.markdown(a.list(false).single(), listOf(att))
        assertTrue(md, md.contains("[Lease \\[final\\].pdf](attachments/${att.uid}.pdf)"))
    }

    @Test fun sharingOrSavingAFileKeepsItsOwnName() {
        val f = Attachment(noteUid = "n", kind = AttachmentKind.FILE, name = "Report.xlsx", mime = "application/vnd.ms-excel")
        assertEquals("Report.xlsx", AttachmentExport.friendlyName(f, "My note"))
        assertEquals("Download/HueNotes", AttachmentExport.folderFor(AttachmentKind.FILE))
    }

    /** Rendering itself needs a device (Robolectric has no PDF engine); here: a broken PDF just gets no preview. */
    @Test fun brokenPdfsGetNoPreviewInsteadOfACrash() {
        val dir = Files.createTempDirectory("pdf").toFile().also { dirs += it }
        assertNull(AttachmentFiles.pdfPage(File(dir, "x.pdf").also { it.writeText("nope") }, 400))
        assertNull(AttachmentFiles.pdfPage(File(dir, "missing.pdf"), 400))
        val storage = AttachmentStorage(dir)
        assertNull(AttachmentFiles.preview(storage, Attachment(noteUid = "n", kind = AttachmentKind.FILE, name = "a.docx"), 400))
    }

    @Test fun upgradingFromVersion6KeepsAttachments() {
        val name = "sf-upgrade-${System.nanoTime()}.db"
        val path = app.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            NoteStore(app, "unused-${System.nanoTime()}.db").onCreate(db)          // current schema…
            db.execSQL("DROP TABLE notes"); db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, checklist INTEGER NOT NULL, color INTEGER NOT NULL, archived INTEGER NOT NULL, reminder_at INTEGER NOT NULL, modified_at INTEGER NOT NULL, created_at INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NOT NULL DEFAULT 0, uid TEXT NOT NULL DEFAULT '', sync_at INTEGER NOT NULL DEFAULT 0)")   // …notes as at v6 (labels came in v8)
            db.execSQL("CREATE UNIQUE INDEX notes_uid ON notes(uid)")
            db.execSQL("DROP TABLE attachments")                                   // …with the v6 attachments table
            db.execSQL("CREATE TABLE attachments(uid TEXT PRIMARY KEY, note_uid TEXT NOT NULL, kind INTEGER NOT NULL, duration INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO attachments VALUES('a1','n1',2,1500,7)")
            db.version = 6
        }
        val s = NoteStore(app, name, AttachmentStorage(Files.createTempDirectory("up").toFile().also { dirs += it }))
        assertEquals(listOf(Attachment("a1", "n1", AttachmentKind.AUDIO, 1500, 7)), s.attachmentsFor("n1"))
        val f = Attachment(noteUid = "n1", kind = AttachmentKind.FILE, name = "x.pdf", mime = "application/pdf", createdAt = 8)
        s.addAttachment(f); assertEquals(f, s.attachmentsFor("n1").last())
    }
}
