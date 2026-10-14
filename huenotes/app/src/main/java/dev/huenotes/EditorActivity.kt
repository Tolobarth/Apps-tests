package dev.huenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
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
    private lateinit var attachBox: LinearLayout
    private var note = Note()
    private var deleted = false
    private val attachments = ArrayList<Attachment>()
    private var editingDrawing: Attachment? = null
    private var recorder: VoiceRecorder? = null
    private var recDialog: androidx.appcompat.app.AlertDialog? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addImage(uri) }
    private val drawLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra("path")
        if (r.resultCode == RESULT_OK && path != null) addDrawing(File(path)) else editingDrawing = null
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) showRecorder() else Toast.makeText(this, R.string.rec_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = NoteStore(this)
        note = store.get(intent.getLongExtra("id", 0)) ?: Note(color = Prefs.defaultColor(this))
        val start = if (note.id == 0L) intent.getStringExtra("start") else null
        val startAsList = start == "list"
        if (startAsList) note = note.copy(checklist = true)
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
        if (startAsList) list.getChildAt(0)?.findViewById<EditText>(R.id.text)?.requestFocus()
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
        attachBox = findViewById(R.id.attachments)
        attachments.addAll(store.attachmentsFor(note.uid))
        renderAttachments()
        findViewById<View>(R.id.imageButton).setOnClickListener { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        findViewById<View>(R.id.drawButton).setOnClickListener { launchDrawing(null) }
        findViewById<View>(R.id.micButton).setOnClickListener { showRecorder() }
        applyColor()
        renderMeta()
        if (start != null) window.decorView.post {
            when (start) {
                "image" -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "drawing" -> launchDrawing(null)
                "audio" -> showRecorder()
                "text" -> bodyEdit.requestFocus()
            }
        }
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
        if (n.isEmpty && attachments.isEmpty()) { if (n.id != 0L) { store.delete(n.id); Reminders.cancel(this, n.id) }; return }
        saveNow()
    }

    override fun onStop() {
        super.onStop()
        Player.stop()
        stopRecording(save = true)   // Android silences the mic for backgrounded apps, so keep what we have
    }

    override fun onDestroy() { super.onDestroy(); stopRecording(save = false) }

    private fun saveNow() {
        val n = current()
        if (n.isEmpty && attachments.isEmpty()) return
        note = n.copy(id = store.save(n))
        Reminders.sync(this, note)
    }

    // ---- attachments -------------------------------------------------------------------------

    private fun attach(a: Attachment) {
        saveNow()                      // the note row must exist before it can own attachments
        store.addAttachment(a)
        attachments += a
        renderAttachments()
    }

    private fun addImage(uri: Uri) {
        val a = Attachment(noteUid = note.uid, kind = AttachmentKind.IMAGE)
        try { AttachmentFiles.importImage(contentResolver, uri, store.storage.file(a)) }
        catch (e: Exception) { store.storage.file(a).delete(); Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show(); return }
        attach(a)
    }

    private fun launchDrawing(existing: Attachment?) {
        editingDrawing = existing
        val i = Intent(this, DrawingActivity::class.java)
        existing?.let { i.putExtra("base", store.storage.file(it).path) }
        drawLauncher.launch(i)
    }

    private fun addDrawing(tmp: File) {
        val a = Attachment(noteUid = note.uid, kind = AttachmentKind.DRAWING)
        try { tmp.copyTo(store.storage.file(a), overwrite = true) }
        catch (e: Exception) { Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show(); return }
        finally { tmp.delete() }
        editingDrawing?.let { old -> store.removeAttachment(old); attachments.remove(old) }   // an edit replaces the old drawing
        editingDrawing = null
        attach(a)
    }

    private fun showRecorder() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micPermission.launch(Manifest.permission.RECORD_AUDIO); return }
        if (recorder != null) return
        val r = VoiceRecorder(this) { stopRecording(save = true) }
        if (!r.start()) { Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_SHORT).show(); return }
        recorder = r
        val timer = TextView(this).apply {
            text = "● 0:00"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.on_surface))
            setPadding(0, Palette.dp(context, 24), 0, Palette.dp(context, 8))
        }
        recDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.rec_title).setView(timer)
            .setPositiveButton(R.string.rec_stop) { _, _ -> stopRecording(save = true) }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> stopRecording(save = false) }
            .setCancelable(false).show()
        val h = Handler(Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() { if (recorder === r) { timer.text = "● " + AttachmentFiles.duration(r.elapsedMs); h.postDelayed(this, 250) } }
        })
    }

    private fun stopRecording(save: Boolean) {
        val r = recorder ?: return
        recorder = null
        recDialog?.dismiss(); recDialog = null
        if (!save) { r.cancel(); return }
        val (file, ms) = r.stop() ?: run { Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_SHORT).show(); return }
        val a = Attachment(noteUid = note.uid, kind = AttachmentKind.AUDIO, durationMs = ms)
        try { file.copyTo(store.storage.file(a), overwrite = true) }
        catch (e: Exception) { Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_SHORT).show(); return }
        finally { file.delete() }
        attach(a)
    }

    private fun renderAttachments() {
        attachBox.removeAllViews()
        attachments.forEach { a -> attachBox.addView(if (a.kind == AttachmentKind.AUDIO) audioView(a) else imageView(a)) }
    }

    private fun imageView(a: Attachment): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_attach_image, attachBox, false)
        val img = v.findViewById<android.widget.ImageView>(R.id.img)
        val bmp = AttachmentFiles.thumb(store.storage.file(a), 1024)
        if (bmp != null) img.setImageBitmap(bmp) else img.setBackgroundColor(getColor(R.color.outline))
        v.setOnClickListener { if (a.kind == AttachmentKind.DRAWING) launchDrawing(a) else viewImage(a) }
        v.findViewById<View>(R.id.remove).setOnClickListener { confirmRemove(a) }
        return v
    }

    private fun audioView(a: Attachment): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_attach_audio, attachBox, false)
        val play = v.findViewById<android.widget.ImageButton>(R.id.play)
        val bar = v.findViewById<android.widget.ProgressBar>(R.id.progress)
        val time = v.findViewById<TextView>(R.id.time)
        val file = store.storage.file(a)
        time.text = AttachmentFiles.duration(a.durationMs)
        play.isEnabled = file.exists(); play.alpha = if (file.exists()) 1f else 0.4f
        play.setOnClickListener {
            Player.toggle(a.uid, file) { playing, pos, dur ->
                play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                play.contentDescription = getString(if (playing) R.string.cd_pause else R.string.cd_play)
                bar.max = maxOf(1, dur); bar.progress = pos
                time.text = if (dur > 0) AttachmentFiles.duration(pos.toLong()) + " / " + AttachmentFiles.duration(dur.toLong()) else AttachmentFiles.duration(a.durationMs)
            }
        }
        v.findViewById<View>(R.id.remove).setOnClickListener { confirmRemove(a) }
        return v
    }

    private fun viewImage(a: Attachment) {
        val bmp = AttachmentFiles.thumb(store.storage.file(a), 2048) ?: return
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(android.widget.ImageView(this).apply {
            setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setBackgroundColor(android.graphics.Color.BLACK)
            setOnClickListener { d.dismiss() }
        })
        d.show()
    }

    private fun confirmRemove(a: Attachment) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.attach_remove_title)
            .setPositiveButton(R.string.attach_remove) { _, _ ->
                Player.stop(); store.removeAttachment(a); attachments.remove(a); renderAttachments()
            }.setNegativeButton(R.string.btn_cancel, null).show()
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
