package dev.huenotes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AttachmentTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: NoteStore
    private lateinit var work: File

    @Before fun setUp() {
        app.deleteDatabase("att.db")
        work = File(app.cacheDir, "att-test").also { it.deleteRecursively(); it.mkdirs() }
        store = NoteStore(app, "att.db", AttachmentStorage(File(work, "store")))
    }

    private fun bitmapFile(name: String, w: Int, h: Int, format: Bitmap.CompressFormat, argb: Int = Color.RED, transparent: Boolean = false): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (!transparent) bmp.eraseColor(argb)
        val f = File(work, name)
        FileOutputStream(f).use { bmp.compress(format, 100, it) }
        return f
    }

    private fun decode(f: File) = android.graphics.BitmapFactory.decodeFile(f.path)!!

    // ---- store ---------------------------------------------------------------------------------

    @Test fun addingAndRemovingAnAttachmentCountsAsAnEditOfTheNote() {
        val id = store.save(Note(title = "n")); val note = store.get(id)!!
        Thread.sleep(5)
        val a = Attachment(noteUid = note.uid, kind = AttachmentKind.AUDIO, durationMs = 1500)
        store.storage.file(a).writeBytes(byteArrayOf(1)); store.addAttachment(a)
        val afterAdd = store.get(id)!!
        assertTrue("sync must notice", afterAdd.updatedAt > note.updatedAt); assertEquals(note.modifiedAt, afterAdd.modifiedAt)
        assertEquals(listOf(a), store.attachmentsFor(note.uid))
        Thread.sleep(5); store.removeAttachment(a)
        assertTrue(store.attachmentsFor(note.uid).isEmpty()); assertFalse(store.storage.file(a).exists())
        assertTrue(store.get(id)!!.updatedAt > afterAdd.updatedAt)
    }

    @Test fun attachmentsKeepTheirCreationOrder() {
        val id = store.save(Note(title = "n")); val uid = store.get(id)!!.uid
        val kinds = listOf(AttachmentKind.IMAGE, AttachmentKind.AUDIO, AttachmentKind.DRAWING)
        kinds.forEach { store.addAttachment(Attachment(noteUid = uid, kind = it, createdAt = System.nanoTime())) }
        assertEquals(kinds, store.attachmentsFor(uid).map { it.kind })
    }

    @Test fun cleanupDropsOrphansAndStrayFilesButKeepsReferencedOnes() {
        val id = store.save(Note(title = "n")); val uid = store.get(id)!!.uid
        val keep = Attachment(noteUid = uid, kind = AttachmentKind.IMAGE); store.storage.file(keep).writeBytes(byteArrayOf(1)); store.addAttachment(keep)
        val orphan = Attachment(noteUid = "no-such-note", kind = AttachmentKind.IMAGE); store.storage.file(orphan).writeBytes(byteArrayOf(2)); store.addAttachment(orphan)
        val stray = File(store.storage.dir, "leftover.jpg").also { it.writeBytes(byteArrayOf(3)) }
        assertTrue(store.cleanupAttachments() >= 2)
        assertTrue(store.storage.file(keep).exists()); assertFalse(store.storage.file(orphan).exists()); assertFalse(stray.exists())
        assertEquals(listOf(keep), store.attachmentsFor(uid)); assertTrue(store.attachmentsFor("no-such-note").isEmpty())
    }

    // ---- image import --------------------------------------------------------------------------

    @Test fun largePhotosAreScaledDownKeepingProportions() {
        val src = bitmapFile("big.jpg", 3000, 2000, Bitmap.CompressFormat.JPEG)
        val out = File(work, "out.jpg")
        AttachmentFiles.importImage(app.contentResolver, Uri.fromFile(src), out)
        val b = decode(out)
        assertEquals(AttachmentFiles.MAX_EDGE, maxOf(b.width, b.height)); assertEquals(2.0 / 3, b.height.toDouble() / b.width, 0.02)
    }

    @Test fun smallPhotosAreNotEnlarged() {
        val out = File(work, "out.jpg")
        AttachmentFiles.importImage(app.contentResolver, Uri.fromFile(bitmapFile("s.jpg", 400, 300, Bitmap.CompressFormat.JPEG)), out)
        val b = decode(out); assertEquals(400, b.width); assertEquals(300, b.height)
    }

    @Test fun exifRotationIsApplied() {
        val src = bitmapFile("rot.jpg", 400, 200, Bitmap.CompressFormat.JPEG)
        ExifInterface(src.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val out = File(work, "out.jpg")
        AttachmentFiles.importImage(app.contentResolver, Uri.fromFile(src), out)
        val b = decode(out); assertEquals("a sideways phone photo must come out upright", 200, b.width); assertEquals(400, b.height)
    }

    @Test fun transparentPngsLandOnWhiteNotBlack() {
        val out = File(work, "out.jpg")
        AttachmentFiles.importImage(app.contentResolver, Uri.fromFile(bitmapFile("t.png", 50, 50, Bitmap.CompressFormat.PNG, transparent = true)), out)
        val p = decode(out).getPixel(10, 10)
        assertTrue(Color.red(p) > 240 && Color.green(p) > 240 && Color.blue(p) > 240)
    }

    @Test fun nonImagesAreRejected() {
        val junk = File(work, "junk.jpg").also { it.writeText("not a picture") }
        assertThrows(IOException::class.java) { AttachmentFiles.importImage(app.contentResolver, Uri.fromFile(junk), File(work, "o.jpg")) }
    }

    @Test fun thumbnailsAreSmallAndCached() {
        val f = bitmapFile("th.jpg", 2000, 1000, Bitmap.CompressFormat.JPEG)
        val t = AttachmentFiles.thumb(f, 480)!!
        assertTrue(maxOf(t.width, t.height) in 480..959); assertSame("second call hits the cache", t, AttachmentFiles.thumb(f, 480))
        assertNull(AttachmentFiles.thumb(File(work, "missing.jpg"), 480))
    }

    @Test fun durationsAreFormatted() {
        assertEquals("0:00", AttachmentFiles.duration(0)); assertEquals("0:09", AttachmentFiles.duration(9_400)); assertEquals("1:05", AttachmentFiles.duration(65_000))
    }

    // ---- drawing -------------------------------------------------------------------------------

    private fun DrawView.stroke(vararg pts: Pair<Float, Float>) {
        val t = SystemClock.uptimeMillis()
        pts.forEachIndexed { i, (x, y) ->
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; pts.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            dispatchTouchEvent(MotionEvent.obtain(t, t + i, action, x, y, 0))
        }
    }

    private fun canvas(): DrawView = DrawView(app).apply { measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY)); layout(0, 0, 400, 400) }

    @Test fun strokesDrawUndoAndErase() {
        val v = canvas(); v.penWidth = 10f; v.penColor = Color.BLACK
        assertFalse(v.hasContent())
        v.stroke(50f to 50f, 150f to 50f, 250f to 50f, 350f to 50f)
        assertTrue(v.hasContent())
        assertEquals("ink lands where we drew", Color.BLACK, v.render().getPixel(200, 50))
        assertEquals("paper is white elsewhere", Color.WHITE, v.render().getPixel(200, 300))
        v.erasing = true; v.stroke(150f to 50f, 200f to 50f, 250f to 50f)
        assertEquals("eraser paints paper back", Color.WHITE, v.render().getPixel(200, 50))
        v.undo(); assertEquals("undo removes the eraser stroke", Color.BLACK, v.render().getPixel(200, 50))
        v.undo(); assertFalse(v.hasContent()); v.undo()   // extra undo is harmless
        v.stroke(10f to 10f, 20f to 20f); v.clear(); assertFalse(v.hasContent())
    }

    @Test fun aTapLeavesADot() {
        val v = canvas(); v.penWidth = 14f
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 1, MotionEvent.ACTION_UP, 100f, 100f, 0))
        assertTrue(v.hasContent()); assertEquals(Color.BLACK, v.render().getPixel(100, 100))
    }

    @Test fun existingPictureShowsUnderNewStrokes() {
        val v = canvas(); v.base = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        assertEquals(Color.BLUE, v.render().getPixel(200, 200)); assertFalse("a base alone is not new content", v.hasContent())
    }

    @Test fun savedDrawingsAreCappedInSize() {
        val out = File(work, "d.png")
        AttachmentFiles.saveDrawing(Bitmap.createBitmap(3000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }, out)
        assertEquals(AttachmentFiles.MAX_EDGE, decode(out).width)
    }
}
