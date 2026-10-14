package dev.huenotes

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val work: File = Files.createTempDirectory("imp").toFile()
    private fun store() = NoteStore(app, "imp-${System.nanoTime()}.db", AttachmentStorage(File(work, "s${System.nanoTime()}")))
    @After fun cleanup() { work.deleteRecursively() }

    private val keepText = """{"color":"BLUE","isTrashed":false,"isPinned":true,"isArchived":false,"textContent":"Buy milk","title":"Shop",
        "userEditedTimestampUsec":1700000000000000,"createdTimestampUsec":1690000000000000}"""

    @Test fun keepTextNote() {
        val p = Importers.fromKeepJson(keepText)!!
        assertEquals("Shop", p.note.title); assertEquals("Buy milk", p.note.body)
        assertTrue(p.note.pinned); assertEquals(Note.COLORS[4], p.note.color)
        assertEquals(1_700_000_000_000L, p.note.modifiedAt); assertEquals(1_690_000_000_000L, p.note.createdAt)
    }

    @Test fun keepListBecomesAChecklist() {
        val p = Importers.fromKeepJson("""{"title":"L","listContent":[{"text":"a","isChecked":false},{"text":"b","isChecked":true},{"text":"","isChecked":false}]}""")!!
        assertTrue(p.note.checklist)
        assertEquals(listOf(false to "a", true to "b"), Checklist.parse(p.note.body).map { it.done to it.text })
    }

    @Test fun keepTrashAndForeignJsonAreSkipped() {
        assertNull(Importers.fromKeepJson("""{"title":"x","textContent":"y","isTrashed":true}"""))
        assertNull(Importers.fromKeepJson("""{"unrelated":1}"""))
        assertNull(Importers.fromKeepJson("not json"))
    }

    @Test fun keepAttachmentsAreListedByType() {
        val p = Importers.fromKeepJson("""{"title":"P","textContent":"","attachments":[{"filePath":"a.jpg","mimetype":"image/jpeg"},{"filePath":"b.3gp","mimetype":"audio/3gpp"},{"filePath":"c.pdf","mimetype":"application/pdf"}]}""")!!
        assertEquals(listOf("a.jpg" to AttachmentKind.IMAGE, "b.3gp" to AttachmentKind.AUDIO), p.files)
    }

    @Test fun markdownHeadingBecomesTheTitle() {
        val p = Importers.fromText("ideas.md", "\n# Big plan\n\nFirst line\nSecond")
        assertEquals("Big plan", p.note.title); assertEquals("First line\nSecond", p.note.body); assertFalse(p.note.checklist)
    }

    @Test fun plainTextUsesTheFileNameAsTitle() {
        val p = Importers.fromText("meeting_notes.txt", "Agenda\nItems")
        assertEquals("meeting notes", p.note.title); assertEquals("Agenda\nItems", p.note.body)
    }

    @Test fun taskListsBecomeChecklistsWithSubItems() {
        val p = Importers.fromText("t.md", "# T\n- [ ] one\n  - [x] sub\n- [X] two\n")
        assertTrue(p.note.checklist)
        assertEquals(listOf(Triple(false, "one", 0), Triple(true, "sub", 1), Triple(true, "two", 0)), Checklist.parse(p.note.body).map { Triple(it.done, it.text, it.level) })
        assertFalse(Importers.fromText("t.md", "- [ ] one\nplain line").note.checklist)
    }

    @Test fun importingTwiceDoesNotDuplicate() {
        val s = store(); val f = File(work, "n.md").also { it.writeText("# A\nbody") }
        assertEquals(1, Importers.importUris(app, s, listOf(Uri.fromFile(f)), Importers.Source.TEXT))
        assertEquals(0, Importers.importUris(app, s, listOf(Uri.fromFile(f)), Importers.Source.TEXT))
        assertEquals(1, s.list(false).size)
    }

    @Test fun takeoutZipBringsNotesAndPictures() {
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val zipFile = File(work, "takeout.zip")
        ZipOutputStream(zipFile.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Takeout/Keep/Pic.json")); z.write("""{"title":"Pic","textContent":"with photo","attachments":[{"filePath":"img1.png","mimetype":"image/png"}]}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("Takeout/Keep/img1.png")); z.write(png); z.closeEntry()
            z.putNextEntry(ZipEntry("Takeout/Keep/Gone.json")); z.write("""{"title":"Gone","textContent":"x","isTrashed":true}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("Takeout/Keep/Labels.txt")); z.write(1); z.closeEntry()
        }
        val s = store()
        assertEquals(1, Importers.importUris(app, s, listOf(Uri.fromFile(zipFile)), Importers.Source.KEEP))
        val n = s.list(false).single()
        assertEquals("Pic", n.title)
        val a = s.attachmentsFor(n.uid).single()
        assertEquals(AttachmentKind.IMAGE, a.kind); assertTrue(s.storage.file(a).length() > 0)
    }

    @Test fun markdownExportLinksFilesAndKeepsNamesUnique() {
        val s = store()
        val id = s.save(Note(title = "Trip: Paris/2025", body = "pack")); val uid = s.get(id)!!.uid
        val a = Attachment(noteUid = uid, kind = AttachmentKind.IMAGE); s.storage.file(a).writeBytes(byteArrayOf(1)); s.addAttachment(a)
        s.save(Note(title = "Trip: Paris/2025", body = "again"))
        s.save(Note(title = "List", body = Checklist.serialize(listOf(Checklist.Item(false, "a"), Checklist.Item(true, "b", 1))), checklist = true))
        val out = ByteArrayOutputStream(); MarkdownExport.write(s, out)
        val entries = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).let { z -> generateSequence { z.nextEntry }.map { it.name to z.readBytes().toString(Charsets.UTF_8) }.toList() }.toMap()
        assertTrue(entries.keys.containsAll(listOf("Trip Paris 2025.md", "Trip Paris 2025 (2).md", "List.md", "attachments/${a.fileName}")))
        assertTrue(entries["Trip Paris 2025.md"]!!.contains("![](attachments/${a.fileName})"))
        assertEquals("# List\n\n- [ ] a\n  - [x] b\n", entries["List.md"])
    }
}
