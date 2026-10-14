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
import java.io.File
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
        s.save(Note(title = "Idea", body = "A notes app where colour is the first-class way to organise.", color = Note.COLORS[0]))
        s.save(Note(title = "Book list", body = "Piranesi\nThe Dispossessed\nA Psalm for the Wild-Built\nBraiding Sweetgrass", color = Note.COLORS[5]))
        s.save(Note(title = "", body = "Call dentist tomorrow morning", color = Note.COLORS[1]))
        s.save(Note(title = "Gift ideas", body = "Ceramic mug set\nPlant for Mum\nConcert tickets", color = Note.COLORS[6], reminderAt = tomorrow + 86_400_000))
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

    @Test fun day() = shot("day", Robolectric.buildActivity(DayActivity::class.java).setup().get())
}
