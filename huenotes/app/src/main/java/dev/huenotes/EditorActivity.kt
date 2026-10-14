package dev.huenotes

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import java.util.Calendar

class EditorActivity : Activity() {
    private lateinit var store: NoteStore
    private lateinit var titleEdit: EditText
    private lateinit var bodyEdit: EditText
    private lateinit var stripe: View
    private var note = Note()
    private var deleted = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = NoteStore(this)
        note = store.get(intent.getLongExtra("id", 0)) ?: Note()
        titleEdit = EditText(this).apply { hint = "Title"; setText(note.title) }
        bodyEdit = EditText(this).apply {
            hint = "Note"; setText(note.body); gravity = android.view.Gravity.TOP
        }
        stripe = View(this).apply { setBackgroundColor(note.color) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(stripe, LinearLayout.LayoutParams(-1, 16))
        root.addView(titleEdit)
        root.addView(bodyEdit, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun current() = note.copy(title = titleEdit.text.toString().trim(), body = bodyEdit.text.toString())

    override fun onPause() {
        super.onPause()
        if (deleted) return
        val n = current()
        if (n.isEmpty) { if (n.id != 0L) { store.delete(n.id); Reminders.cancel(this, n.id) }; return }
        note = n.copy(id = store.save(n))
        Reminders.schedule(this, note)
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        listOf("Colour", "Toggle checklist line", "Reminder", "Share", "Archive/Unarchive", "Delete")
            .forEachIndexed { i, t -> m.add(0, i, i, t) }
        return true
    }

    override fun onOptionsItemSelected(i: MenuItem): Boolean {
        when (i.itemId) {
            0 -> AlertDialog.Builder(this).setItems(arrayOf("Yellow", "Orange", "Red", "Green", "Blue", "Purple")) { _, w ->
                note = note.copy(color = Note.COLORS[w]); stripe.setBackgroundColor(note.color)
            }.show()
            1 -> toggleLine()
            2 -> pickReminder()
            3 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, titleEdit.text.toString())
                .putExtra(Intent.EXTRA_TEXT, bodyEdit.text.toString()), null))
            4 -> { note = current().let { it.copy(archived = !it.archived) }; finish() }
            5 -> { deleted = true; if (note.id != 0L) { store.delete(note.id); Reminders.cancel(this, note.id) }; finish() }
        }
        return true
    }

    /** Cycles the current line between plain, "[ ] " and "[x] ". */
    private fun toggleLine() {
        val t = bodyEdit.text; val pos = bodyEdit.selectionStart.coerceAtLeast(0)
        val start = t.lastIndexOf('\n', pos - 1) + 1
        val line = t.substring(start, t.indexOf('\n', start).let { if (it < 0) t.length else it })
        val (old, new) = when {
            line.startsWith("[ ] ") -> "[ ] " to "[x] "
            line.startsWith("[x] ") -> "[x] " to ""
            else -> "" to "[ ] "
        }
        t.replace(start, start + old.length, new)
        note = note.copy(checklist = true)
    }

    private fun pickReminder() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, mo, d ->
            TimePickerDialog(this, { _, h, mi ->
                c.set(y, mo, d, h, mi, 0)
                note = note.copy(reminderAt = c.timeInMillis)
                Toast.makeText(this, "Reminder set", Toast.LENGTH_SHORT).show()
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
}
