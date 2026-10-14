package dev.huenotes

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class EditorActivity : GatedActivity() {
    private lateinit var store: NoteStore
    private lateinit var titleEdit: EditText
    private lateinit var bodyEdit: EditText
    private lateinit var list: LinearLayout
    private lateinit var swatches: LinearLayout
    private lateinit var chip: Chip
    private lateinit var meta: TextView
    private var note = Note()
    private var deleted = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = NoteStore(this)
        note = store.get(intent.getLongExtra("id", 0)) ?: Note()
        setContentView(R.layout.activity_editor)

        titleEdit = findViewById(R.id.titleEdit); bodyEdit = findViewById(R.id.bodyEdit)
        list = findViewById(R.id.checklist); swatches = findViewById(R.id.swatches)
        chip = findViewById(R.id.reminderChip); meta = findViewById(R.id.meta)
        titleEdit.setText(note.title)
        if (note.checklist) showChecklist(Checklist.parse(note.body)) else bodyEdit.setText(note.body)

        val tb = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        tb.setNavigationOnClickListener { finish() }
        listOf(Triple(1, "Share", R.drawable.ic_share), Triple(2, "Archive", R.drawable.ic_archive), Triple(3, "Delete", R.drawable.ic_delete))
            .forEach { (id, t, icon) ->
                tb.menu.add(0, id, id, t).setIcon(icon).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        tb.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> share(); 2 -> archive(); 3 -> confirmDelete() }
            true
        }
        findViewById<View>(R.id.checklistButton).setOnClickListener { toggleMode() }
        findViewById<View>(R.id.reminderButton).setOnClickListener { pickReminder() }
        chip.setOnClickListener { pickReminder() }
        chip.setOnCloseIconClickListener { note = note.copy(reminderAt = 0); renderMeta() }
        applyColor()
        renderMeta()
    }

    // ---- state -------------------------------------------------------------------------------

    private fun inChecklist() = list.visibility == View.VISIBLE

    private fun currentItems(): List<Checklist.Item> = (0 until list.childCount).map {
        val row = list.getChildAt(it)
        Checklist.Item(row.findViewById<CheckBox>(R.id.check).isChecked, row.findViewById<EditText>(R.id.text).text.toString().trim())
    }

    private fun current(): Note {
        val body = if (inChecklist()) Checklist.serialize(currentItems()) else bodyEdit.text.toString()
        return note.copy(title = titleEdit.text.toString().trim(), body = body, checklist = inChecklist())
    }

    override fun onPause() {
        super.onPause()
        if (deleted) return
        val n = current()
        if (n.isEmpty) { if (n.id != 0L) { store.delete(n.id); Reminders.cancel(this, n.id) }; return }
        note = n.copy(id = store.save(n))
        Reminders.schedule(this, note)
    }

    // ---- appearance --------------------------------------------------------------------------

    private fun applyColor() {
        val bg = Palette.surface(this, note.color)
        findViewById<View>(R.id.editorRoot).setBackgroundColor(bg)
        Palette.tintBars(window, bg)
        val tb = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        tb.navigationIcon?.setTint(getColor(R.color.on_surface))
        for (i in 0 until tb.menu.size()) tb.menu.getItem(i).icon?.setTint(getColor(R.color.on_surface))
        swatches.removeAllViews()
        Note.COLORS.forEach { c ->
            swatches.addView(Palette.swatch(this, c, c == note.color, "Note colour") { note = note.copy(color = c); applyColor() })
        }
    }

    private fun renderMeta() {
        meta.text = if (note.id == 0L) "New note"
        else "Edited " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.modifiedAt))
        if (note.reminderAt > 0) {
            chip.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.reminderAt))
            chip.visibility = View.VISIBLE
        } else chip.visibility = View.GONE
    }

    // ---- checklist ---------------------------------------------------------------------------

    private fun toggleMode() {
        if (inChecklist()) {
            bodyEdit.setText(Checklist.toPlain(currentItems()))
            list.visibility = View.GONE; bodyEdit.visibility = View.VISIBLE
        } else {
            val lines = bodyEdit.text.toString()
            showChecklist(Checklist.parse(lines).ifEmpty { listOf(Checklist.Item(false, "")) })
        }
    }

    private fun showChecklist(items: List<Checklist.Item>) {
        bodyEdit.visibility = View.GONE; list.visibility = View.VISIBLE; list.removeAllViews()
        items.forEach { addRow(it, -1, focus = false) }
        if (items.isEmpty()) addRow(Checklist.Item(false, ""), -1, focus = false)
    }

    private fun addRow(item: Checklist.Item, at: Int, focus: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_check, list, false)
        val check = row.findViewById<CheckBox>(R.id.check); val text = row.findViewById<EditText>(R.id.text)
        text.setText(item.text); check.isChecked = item.done
        fun strike() {
            text.paintFlags = if (check.isChecked) text.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            else text.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            text.alpha = if (check.isChecked) 0.5f else 1f
        }
        strike(); check.setOnCheckedChangeListener { _, _ -> strike() }
        text.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT) { addRow(Checklist.Item(false, ""), list.indexOfChild(row) + 1, true); true } else false
        }
        text.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_DEL && ev.action == KeyEvent.ACTION_DOWN && text.text.isEmpty() && list.childCount > 1) {
                val idx = list.indexOfChild(row); list.removeView(row)
                (list.getChildAt((idx - 1).coerceAtLeast(0)).findViewById<EditText>(R.id.text)).requestFocus(); true
            } else false
        }
        row.findViewById<View>(R.id.remove).setOnClickListener { if (list.childCount > 1) list.removeView(row) else text.setText("") }
        if (at < 0) list.addView(row) else list.addView(row, at)
        if (focus) text.requestFocus()
    }

    // ---- actions -----------------------------------------------------------------------------

    private fun share() {
        val n = current()
        val text = if (n.checklist) Checklist.parse(n.body).joinToString("\n") { (if (it.done) "☑ " else "☐ ") + it.text } else n.body
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, text), null))
    }

    private fun archive() {
        note = current().let { it.copy(archived = !it.archived) }
        Toast.makeText(this, if (note.archived) "Archived" else "Restored", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this).setTitle("Delete this note?").setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleted = true
                if (note.id != 0L) { store.delete(note.id); Reminders.cancel(this, note.id) }
                finish()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickReminder() {
        val base = if (note.reminderAt > System.currentTimeMillis()) note.reminderAt else System.currentTimeMillis() + 3_600_000
        val local = Calendar.getInstance().apply { timeInMillis = base }
        // MaterialDatePicker works in UTC midnight; convert the picked day back to local fields.
        val picker = MaterialDatePicker.Builder.datePicker().setTitleText("Reminder date")
            .setSelection(Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            }.timeInMillis)
            .setCalendarConstraints(CalendarConstraints.Builder().setStart(MaterialDatePicker.todayInUtcMilliseconds()).build())
            .build()
        picker.addOnPositiveButtonClickListener { utc ->
            val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
            val time = MaterialTimePicker.Builder().setTimeFormat(
                if (android.text.format.DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(local.get(Calendar.HOUR_OF_DAY)).setMinute(local.get(Calendar.MINUTE)).setTitleText("Reminder time").build()
            time.addOnPositiveButtonClickListener {
                val c = Calendar.getInstance().apply {
                    set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), time.hour, time.minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                note = note.copy(reminderAt = c.timeInMillis); renderMeta()
            }
            time.show(supportFragmentManager, "time")
        }
        picker.show(supportFragmentManager, "date")
    }
}
