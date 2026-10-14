package dev.huenotes

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
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
    private lateinit var toolbar: MaterialToolbar
    private var note = Note()
    private var deleted = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = NoteStore(this)
        note = store.get(intent.getLongExtra("id", 0)) ?: Note(color = Prefs.defaultColor(this))
        setContentView(R.layout.activity_editor)

        titleEdit = findViewById(R.id.titleEdit); bodyEdit = findViewById(R.id.bodyEdit)
        list = findViewById(R.id.checklist); swatches = findViewById(R.id.swatches)
        chip = findViewById(R.id.reminderChip); meta = findViewById(R.id.meta)
        val scale = Prefs.fontScale(this)
        titleEdit.textSize = 26f * scale; bodyEdit.textSize = 17f * scale
        if (!Prefs.suggestions(this)) {
            titleEdit.inputType = titleEdit.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            bodyEdit.inputType = bodyEdit.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        titleEdit.setText(note.title)
        if (note.checklist) showChecklist(Checklist.parse(note.body)) else bodyEdit.setText(note.body)
        bodyEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = renderMeta()
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
        })

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        listOf(Triple(0, R.string.action_pin, R.drawable.ic_pin), Triple(1, R.string.action_share, R.drawable.ic_share),
            Triple(2, R.string.action_archive, R.drawable.ic_archive), Triple(3, R.string.action_delete, R.drawable.ic_delete))
            .forEach { (id, t, icon) ->
                toolbar.menu.add(0, id, id, t).setIcon(icon).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> { note = note.copy(pinned = !note.pinned); applyColor() }
                1 -> shareNote(this, current())
                2 -> archive()
                3 -> confirmDelete()
            }
            true
        }
        findViewById<View>(R.id.checklistButton).setOnClickListener { toggleMode() }
        findViewById<View>(R.id.reminderButton).setOnClickListener { quickReminders() }
        chip.setOnClickListener { quickReminders() }
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
        Reminders.sync(this, note)
    }

    // ---- appearance --------------------------------------------------------------------------

    private fun applyColor() {
        val bg = Palette.surface(this, note.color)
        findViewById<View>(R.id.editorRoot).setBackgroundColor(bg)
        Palette.tintBars(window, bg)
        toolbar.navigationIcon?.setTint(getColor(R.color.on_surface))
        for (i in 0 until toolbar.menu.size()) toolbar.menu.getItem(i).icon?.setTint(getColor(R.color.on_surface))
        toolbar.menu.findItem(0).apply {
            setTitle(if (note.pinned) R.string.action_unpin else R.string.action_pin)
            icon?.mutate()?.alpha = if (note.pinned) 255 else 110
        }
        swatches.removeAllViews()
        Note.COLORS.forEach { c ->
            swatches.addView(Palette.swatch(this, c, c == note.color, getString(R.string.note_colour)) { note = note.copy(color = c); applyColor() })
        }
    }

    private fun renderMeta() {
        val text = if (inChecklist()) currentItems().joinToString(" ") { it.text } else bodyEdit.text.toString()
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val base = if (note.id == 0L) getString(R.string.meta_new)
        else getString(R.string.meta_edited, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.modifiedAt)))
        meta.text = "$base  ·  " + resources.getQuantityString(R.plurals.word_count, words, words)
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
            showChecklist(Checklist.parse(bodyEdit.text.toString()).ifEmpty { listOf(Checklist.Item(false, "")) })
        }
        renderMeta()
    }

    private fun showChecklist(items: List<Checklist.Item>) {
        bodyEdit.visibility = View.GONE; list.visibility = View.VISIBLE; list.removeAllViews()
        items.forEach { addRow(it, -1, focus = false) }
        if (items.isEmpty()) addRow(Checklist.Item(false, ""), -1, focus = false)
    }

    private fun addRow(item: Checklist.Item, at: Int, focus: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_check, list, false)
        val check = row.findViewById<CheckBox>(R.id.check); val text = row.findViewById<EditText>(R.id.text)
        text.textSize = 17f * Prefs.fontScale(this)
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

    private fun archive() {
        note = current().let { it.copy(archived = !it.archived) }
        Toast.makeText(this, if (note.archived) R.string.snack_archived else R.string.snack_unarchived, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.dialog_trash_title).setMessage(R.string.dialog_trash_msg)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                deleted = true
                if (note.id != 0L) { store.trash(note.id); Reminders.cancel(this, note.id) }
                Toast.makeText(this, R.string.snack_trashed, Toast.LENGTH_SHORT).show()
                finish()
            }.setNegativeButton(R.string.btn_cancel, null).show()
    }

    /** Smart presets first; "pick date and time" falls through to the Material pickers. */
    private fun quickReminders() {
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val later = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, (get(Calendar.MINUTE) / 15) * 15); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val monday = Calendar.getInstance().apply {
            do { add(Calendar.DAY_OF_MONTH, 1) } while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val presets = listOf(R.string.reminder_later_today to later, R.string.reminder_tomorrow to tomorrow, R.string.reminder_next_week to monday)
        val labels = presets.map { (r, c) -> getString(R.string.reminder_option, getString(r), fmt.format(c.time)) } + getString(R.string.reminder_pick)
        MaterialAlertDialogBuilder(this).setTitle(R.string.reminder_title)
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < presets.size) { note = note.copy(reminderAt = presets[i].second.timeInMillis); renderMeta() } else pickReminder()
            }.show()
    }

    private fun pickReminder() {
        val base = if (note.reminderAt > System.currentTimeMillis()) note.reminderAt else System.currentTimeMillis() + 3_600_000
        val local = Calendar.getInstance().apply { timeInMillis = base }
        // MaterialDatePicker works in UTC midnight; convert the picked day back to local fields.
        val picker = MaterialDatePicker.Builder.datePicker().setTitleText(R.string.reminder_date_title)
            .setSelection(Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            }.timeInMillis)
            .setCalendarConstraints(CalendarConstraints.Builder().setStart(MaterialDatePicker.todayInUtcMilliseconds()).build())
            .build()
        picker.addOnPositiveButtonClickListener { utc ->
            val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
            val time = MaterialTimePicker.Builder().setTimeFormat(
                if (android.text.format.DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(local.get(Calendar.HOUR_OF_DAY)).setMinute(local.get(Calendar.MINUTE)).setTitleText(R.string.reminder_time_title).build()
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
