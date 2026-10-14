package dev.huenotes

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: NoteStore
    private val oldZone = TimeZone.getDefault()
    private val at = 1_791_991_800_000L   // 2026-10-14 15:30 UTC

    @Before fun setUp() {
        // FileProvider caches its folders in a static map; each test has its own temp folders, so start clean.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        app.deleteDatabase("exp.db"); File(app.cacheDir, "share").deleteRecursively()
        store = NoteStore(app, "exp.db", AttachmentStorage(File(app.cacheDir, "exp-att").also { it.deleteRecursively() }))
    }
    @After fun tearDown() { TimeZone.setDefault(oldZone) }

    private fun attach(kind: AttachmentKind, bytes: ByteArray, title: String = "Trip"): Pair<Note, Attachment> {
        val id = store.save(Note(title = title)); val note = store.get(id)!!
        val a = Attachment(noteUid = note.uid, kind = kind, createdAt = at)
        store.storage.file(a).writeBytes(bytes); store.addAttachment(a)
        return note to a
    }

    @Test fun fileNamesAreReadableAndSafe() {
        val photo = Attachment(noteUid = "n", kind = AttachmentKind.IMAGE, createdAt = at)
        assertEquals("Weekend trip - Photo - 2026-10-14 15.30.jpg", AttachmentExport.friendlyName(photo, "Weekend trip"))
        assertEquals("Voice note - 2026-10-14 15.30.m4a", AttachmentExport.friendlyName(photo.copy(kind = AttachmentKind.AUDIO), "  "))
        assertEquals("a_b_c_d - Drawing - 2026-10-14 15.30.png", AttachmentExport.friendlyName(photo.copy(kind = AttachmentKind.DRAWING), "a/b:c*d"))
        val long = AttachmentExport.friendlyName(photo, "x".repeat(200)); assertTrue(long.length < 90); assertTrue(long.endsWith(".jpg"))
        assertFalse(AttachmentExport.friendlyName(photo, "bad\nname\t?").any { it in "\n\t?\\/:*\"<>|" })
    }

    @Test fun stagedCopyHasTheFriendlyNameAndIsServedByTheProvider() {
        val bytes = ByteArray(1000) { (it * 5).toByte() }
        val (note, a) = attach(AttachmentKind.IMAGE, bytes)
        val uri = AttachmentExport.stage(app, store, a, note.title)!!
        assertEquals(AttachmentExport.AUTHORITY, uri.authority)
        assertTrue("receiving apps see a readable name", Uri.decode(uri.lastPathSegment!!).endsWith("Trip - Photo - 2026-10-14 15.30.jpg"))
        // What a receiving app asks the provider for: the display name and size (opening the stream itself trips a Robolectric/JDK 21 quirk).
        app.contentResolver.query(uri, null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Trip - Photo - 2026-10-14 15.30.jpg", c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), c.getLong(c.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)))
        }
        val staged = File(app.cacheDir, "share").walkTopDown().single { it.isFile }
        assertArrayEquals(bytes, staged.readBytes())
        assertTrue("the stored original is untouched", store.storage.file(a).exists())
    }

    @Test fun missingFilesCannotBeStaged() {
        val (note, a) = attach(AttachmentKind.AUDIO, byteArrayOf(1)); store.storage.file(a).delete()
        assertNull(AttachmentExport.stage(app, store, a, note.title))
    }

    @Test fun oldStagedCopiesAreSweptButFreshOnesStay() {
        val old = File(app.cacheDir, "share/old").apply { mkdirs() }; File(old, "x.jpg").writeBytes(byteArrayOf(1))
        val fresh = File(app.cacheDir, "share/fresh").apply { mkdirs() }
        old.setLastModified(System.currentTimeMillis() - 48L * 3600 * 1000)
        AttachmentExport.sweep(app)
        assertFalse(old.exists()); assertTrue(fresh.exists())
    }

    @Test fun oneFileIsSentWithItsTypeCaptionAndPermission() {
        val (note, a) = attach(AttachmentKind.IMAGE, byteArrayOf(1, 2, 3))
        val uri = AttachmentExport.stage(app, store, a, note.title)!!
        val i = AttachmentExport.shareIntent(listOf(uri to a.kind.mime), "my caption", "Trip")
        assertEquals(Intent.ACTION_SEND, i.action); assertEquals("image/jpeg", i.type)
        assertEquals(uri, i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)); assertEquals("my caption", i.getStringExtra(Intent.EXTRA_TEXT)); assertEquals("Trip", i.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0); assertEquals(1, i.clipData!!.itemCount)
    }

    @Test fun severalFilesUseTheMostSpecificCommonType() {
        val u1 = Uri.parse("content://x/1"); val u2 = Uri.parse("content://x/2")
        val twoImages = AttachmentExport.shareIntent(listOf(u1 to "image/jpeg", u2 to "image/png"), null, null)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, twoImages.action); assertEquals("image/*", twoImages.type)
        assertEquals(listOf(u1, u2), twoImages.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)); assertEquals(2, twoImages.clipData!!.itemCount)
        assertNull("no caption extra when there's no text", twoImages.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("*/*", AttachmentExport.shareIntent(listOf(u1 to "image/jpeg", u2 to "audio/mp4"), null, null).type)
        assertEquals("image/jpeg", AttachmentExport.shareIntent(listOf(u1 to "image/jpeg", u2 to "image/jpeg"), null, null).type)
    }

    private val screen = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).get()   // real code runs from an Activity

    private fun sharedIntent(): Intent {
        val chooser = shadowOf(screen).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!
    }

    @Test fun sharingANoteIncludesItsAttachments() {
        val (note, a) = attach(AttachmentKind.IMAGE, byteArrayOf(9, 9))
        store.save(note.copy(body = "come along!"))
        shareNote(screen, store.get(note.id)!!, store)
        val sent = sharedIntent()
        assertEquals(Intent.ACTION_SEND, sent.action); assertEquals("image/jpeg", sent.type); assertEquals("come along!", sent.getStringExtra(Intent.EXTRA_TEXT))
        assertNotNull(sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)); assertNotNull(a)
    }

    @Test fun sharingANoteWithoutAttachmentsStaysPlainText() {
        val id = store.save(Note(title = "T", body = "hello")); shareNote(screen, store.get(id)!!, store)
        val sent = sharedIntent(); assertEquals("text/plain", sent.type); assertEquals("hello", sent.getStringExtra(Intent.EXTRA_TEXT)); assertNull(sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    @Test fun sharingAChecklistUsesTickBoxes() {
        val id = store.save(Note(title = "L", body = "[x] a\n[ ] b", checklist = true)); shareNote(screen, store.get(id)!!, store)
        assertEquals("☑ a\n☐ b", sharedIntent().getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun photosAndDrawingsGoToPicturesAndVoiceNotesToMusic() {
        assertEquals("Pictures/HueNotes", AttachmentExport.folderFor(AttachmentKind.IMAGE)); assertEquals("Pictures/HueNotes", AttachmentExport.folderFor(AttachmentKind.DRAWING))
        assertEquals("Music/HueNotes", AttachmentExport.folderFor(AttachmentKind.AUDIO))
    }

    @Test fun savingCopiesEveryByte() {
        val bytes = ByteArray(300_000) { (it * 31).toByte() }; val (_, a) = attach(AttachmentKind.AUDIO, bytes)
        val out = java.io.ByteArrayOutputStream()
        AttachmentExport.copy(store.storage.file(a), out)
        assertArrayEquals(bytes, out.toByteArray())
    }
}
