package dev.huenotes

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZipBackupTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dirs = ArrayList<File>()
    private fun device(name: String): NoteStore {
        val d = Files.createTempDirectory("zb-$name").toFile().also { dirs += it }
        return NoteStore(app, "zb-$name-${System.nanoTime()}.db", AttachmentStorage(File(d, "att")))
    }
    @After fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun seed(s: NoteStore): Attachment {
        val id = s.save(Note(title = "Trip", body = "photos", createdAt = 5)); val uid = s.get(id)!!.uid
        val a = Attachment(noteUid = uid, kind = AttachmentKind.IMAGE)
        s.storage.file(a).writeBytes(byteArrayOf(1, 2, 3, 4)); s.addAttachment(a)
        return a
    }

    private fun zip(store: NoteStore) = ByteArrayOutputStream().also { BackupZip.write(store, it) }.toByteArray()

    @Test fun roundTripRestoresNotesAndFiles() {
        val a = device("a"); val att = seed(a)
        val b = device("b")
        assertEquals(1, BackupZip.read(app, b, zip(a).inputStream(), File(dirs[0], "tmp")))
        val restored = b.list(false).single()
        assertEquals("Trip", restored.title)
        val list = b.attachmentsFor(restored.uid)
        assertEquals(listOf(att.uid), list.map { it.uid })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), b.storage.file(list[0]).readBytes())
    }

    @Test fun restoringTwiceDoesNotDuplicate() {
        val a = device("a"); seed(a); val b = device("b"); val z = zip(a)
        BackupZip.read(app, b, z.inputStream(), File(dirs[0], "t1"))
        assertEquals(0, BackupZip.read(app, b, z.inputStream(), File(dirs[0], "t2")))
        assertEquals(1, b.list(false).size)
    }

    @Test fun plainJsonBackupsStillImportThroughTheSniffingEntryPoint() {
        val a = device("a"); seed(a); val b = device("b")
        val f = File(dirs[0], "old.json").also { it.writeText(Backup.toJson(a.list(false))) }
        assertEquals(1, Backup.import(app, b, Uri.fromFile(f)))
        val z = File(dirs[0], "new.zip").also { it.writeBytes(zip(a)) }
        val c = device("c")
        assertEquals(1, Backup.import(app, c, Uri.fromFile(z)))
        assertEquals(1, c.attachmentsFor(c.list(false).single().uid).size)
    }

    @Test fun hostileZipsCannotWriteOutsideOrSmuggleNames() {
        val evilUid = "../../evil"
        val json = """{"app":"huenotes","version":3,"notes":[{"title":"X","body":"y","uid":"note-uid-12345","createdAt":1,
            "attachments":[{"uid":"$evilUid","kind":0},{"uid":"good-uid-12345","kind":0}]}]}"""
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("notes.json")); z.write(json.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("attachments/../../evil.jpg")); z.write(9); z.closeEntry()
                z.putNextEntry(ZipEntry("attachments/good-uid-12345.jpg")); z.write(7); z.closeEntry()
                z.putNextEntry(ZipEntry("../escape.txt")); z.write(1); z.closeEntry()
            }
        }.toByteArray()
        val b = device("b")
        assertEquals(1, BackupZip.read(app, b, bytes.inputStream(), File(dirs[0], "tmp")))
        val list = b.attachmentsFor("note-uid-12345")
        assertEquals(listOf("good-uid-12345"), list.map { it.uid })
        assertFalse(File(dirs[0].parentFile, "escape.txt").exists())
    }

    @Test fun zipWithoutNotesFileIsRejected() {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("x.txt")); z.write(1); z.closeEntry() } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { BackupZip.read(app, device("b"), bytes.inputStream(), File(dirs[0], "tmp")) }
    }

    @Test fun attachmentListsSurviveTheJsonFormat() {
        val a = device("a"); val att = seed(a)
        val back = Backup.attachmentsFromJson(Backup.toJson(a.list(false), a.attachmentsByNote()))
        assertEquals(listOf(att.uid), back[att.noteUid]!!.map { it.uid })
    }
}
