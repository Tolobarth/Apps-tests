package dev.huenotes

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/** Renders real screens to PNGs in build/screens for visual review. Also acts as an inflation smoke test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val out = File("build/screens").also { it.mkdirs() }

    @Before fun seed() {
        app.deleteDatabase("huenotes.db"); Lock.clear(app)
        val s = NoteStore(app)
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 0); set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 30) }.timeInMillis
        s.save(Note(title = "Weekend trip", body = "Book the cabin, check train times and ask Sam about the car.", color = Note.COLORS[4], reminderAt = tomorrow))
        s.save(Note(title = "Groceries", body = Checklist.serialize(listOf(
            Checklist.Item(true, "Oat milk"), Checklist.Item(false, "Tomatoes"), Checklist.Item(false, "Sourdough"), Checklist.Item(true, "Coffee beans"))),
            checklist = true, color = Note.COLORS[3]))
        s.save(Note(title = "Idea", body = "A notes app where colour is the first-class way to organise.", color = Note.COLORS[0], pinned = true))
        s.save(Note(title = "Book list", body = "Piranesi\nThe Dispossessed\nA Psalm for the Wild-Built\nBraiding Sweetgrass", color = Note.COLORS[5]))
        s.save(Note(title = "", body = "Call dentist tomorrow morning", color = Note.COLORS[1]))
        s.save(Note(title = "Gift ideas", body = "Ceramic mug set\nPlant for Mum\nConcert tickets", color = Note.COLORS[6], reminderAt = tomorrow + 86_400_000))
        File(app.filesDir, "attachments").deleteRecursively()
        fun uidOf(title: String) = s.list(false).first { it.title == title }.uid
        fun addPicture(title: String, kind: AttachmentKind, bmp: Bitmap) {
            val a = Attachment(noteUid = uidOf(title), kind = kind)
            FileOutputStream(s.storage.file(a)).use { bmp.compress(if (kind == AttachmentKind.IMAGE) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it) }
            s.addAttachment(a)
        }
        addPicture("Weekend trip", AttachmentKind.IMAGE, landscape())
        addPicture("Idea", AttachmentKind.DRAWING, sketch())
        s.addAttachment(Attachment(noteUid = uidOf("Weekend trip"), kind = AttachmentKind.AUDIO, durationMs = 23_000))
        s.addAttachment(Attachment(noteUid = uidOf("Book list"), kind = AttachmentKind.AUDIO, durationMs = 83_000))
    }

    private fun landscape(): Bitmap {
        val b = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 800f, Color.parseColor("#FFB27A"), Color.parseColor("#6FA8DC"), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, 1200f, 800f, p); p.shader = null
        p.color = Color.parseColor("#FFF2B2"); c.drawCircle(850f, 260f, 90f, p)
        p.color = Color.parseColor("#3E5C76"); c.drawPath(Path().apply { moveTo(0f, 800f); lineTo(0f, 560f); lineTo(300f, 380f); lineTo(560f, 600f); lineTo(820f, 420f); lineTo(1200f, 640f); lineTo(1200f, 800f); close() }, p)
        p.color = Color.parseColor("#1D2D44"); c.drawPath(Path().apply { moveTo(0f, 800f); lineTo(0f, 700f); lineTo(400f, 560f); lineTo(900f, 720f); lineTo(1200f, 620f); lineTo(1200f, 800f); close() }, p)
        return b
    }

    private fun sketch(): Bitmap {
        val b = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888); val c = Canvas(b); c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND }
        p.color = Color.parseColor("#D32F2F"); c.drawCircle(300f, 300f, 160f, p)
        p.color = Color.parseColor("#1976D2"); c.drawPath(Path().apply { moveTo(120f, 520f); quadTo(450f, 120f, 780f, 520f) }, p)
        p.color = Color.parseColor("#388E3C"); c.drawLine(560f, 120f, 800f, 220f, p); c.drawLine(560f, 220f, 800f, 120f, p)
        return b
    }

    private fun shot(name: String, a: Activity) {
        val v: View = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun list() = shot("list", Robolectric.buildActivity(ListActivity::class.java).setup().get())

    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun listDark() = shot("list_dark", Robolectric.buildActivity(ListActivity::class.java).setup().get())

    @Test fun editorText() {
        val id = NoteStore(app).list(false).first { it.title == "Weekend trip" }.id
        shot("editor", Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup().get())
    }

    @Test fun editorChecklist() {
        val id = NoteStore(app).list(false).first { it.title == "Groceries" }.id
        shot("editor_checklist", Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup().get())
    }

    @Test @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun listFrench() = shot("list_fr", Robolectric.buildActivity(ListActivity::class.java).setup().get())

    @Test fun settings() = shot("settings", Robolectric.buildActivity(SettingsActivity::class.java).setup().get())

    @Test @Config(qualifiers = "de-w411dp-h891dp-night-xxhdpi")
    fun settingsGermanDark() = shot("settings_de_dark", Robolectric.buildActivity(SettingsActivity::class.java).setup().get())

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun settingsFull() {
        Prefs.setSyncEmail(app, "you@gmail.com"); Prefs.setLastSync(app, System.currentTimeMillis() - 3_600_000)
        shot("settings_full_synced", Robolectric.buildActivity(SettingsActivity::class.java).setup().get())
    }

    @Test fun trash() {
        val s = NoteStore(app)
        s.trash(s.list(false).first { it.title == "Book list" }.id)
        shot("trash", Robolectric.buildActivity(TrashActivity::class.java).setup().get())
    }

    @Test fun speedDial() {
        val a = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        a.setDial(true, animate = false)
        shot("dial", a)
    }

    @Test fun editorWithAttachments() {
        val id = NoteStore(app).list(false).first { it.title == "Weekend trip" }.id
        shot("editor_attachments", Robolectric.buildActivity(EditorActivity::class.java, Intent(app, EditorActivity::class.java).putExtra("id", id)).setup().get())
    }

    private fun find(v: android.view.View, cls: Class<*>): android.view.View? {
        if (cls.isInstance(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), cls)?.let { return it }
        return null
    }

    @Test fun drawingScreen() {
        val a = Robolectric.buildActivity(DrawingActivity::class.java).setup().get()
        a.window.decorView.measure(android.view.View.MeasureSpec.makeMeasureSpec(a.resources.displayMetrics.widthPixels, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(a.resources.displayMetrics.heightPixels, android.view.View.MeasureSpec.EXACTLY))
        a.window.decorView.layout(0, 0, a.resources.displayMetrics.widthPixels, a.resources.displayMetrics.heightPixels)
        val d = find(a.window.decorView, DrawView::class.java) as DrawView
        fun stroke(color: Int, vararg pts: Pair<Float, Float>) {
            d.penColor = color; d.penWidth = 14f
            val t = SystemClock.uptimeMillis()
            pts.forEachIndexed { i, (x, y) -> d.dispatchTouchEvent(MotionEvent.obtain(t, t + i, when (i) { 0 -> MotionEvent.ACTION_DOWN; pts.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }, x, y, 0)) }
        }
        val w = d.width.toFloat(); val h = d.height.toFloat()
        stroke(Color.parseColor("#1976D2"), w * .1f to h * .6f, w * .3f to h * .25f, w * .5f to h * .45f, w * .7f to h * .2f, w * .9f to h * .5f)
        stroke(Color.parseColor("#D32F2F"), w * .2f to h * .8f, w * .4f to h * .7f, w * .6f to h * .85f, w * .8f to h * .72f)
        stroke(Color.parseColor("#388E3C"), w * .15f to h * .1f, w * .25f to h * .15f, w * .35f to h * .1f)
        shot("drawing", a)
    }

    @Test fun day() = shot("day", Robolectric.buildActivity(DayActivity::class.java).setup().get())
}
