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
    private lateinit var linkChip: Chip
    private var pendingSave: Pair<Attachment, String>? = null
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val (a, name) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri != null) try { AttachmentExport.copyToUri(this, store.storage.file(a), uri); Toast.makeText(this, getString(R.string.attach_saved, name), Toast.LENGTH_LONG).show() }
        catch (e: Exception) { Toast.makeText(this, R.string.attach_save_failed, Toast.LENGTH_SHORT).show() }
    }
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
        setContentView(R.layout.activity_editor); Palette.edgeToEdge(this, findViewById(R.id.editorRoot))

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
        toolbar.menu.add(0, 4, 4, R.string.action_history).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> { note = note.copy(pinned = !note.pinned); applyColor() }
                1 -> shareNote(this, current(), store)
                2 -> archive()
                3 -> confirmDelete()
                4 -> showHistory()
            }
            true
        }
        findViewById<View>(R.id.checklistButton).setOnClickListener { toggleMode() }
        findViewById<View>(R.id.reminderButton).setOnClickListener { quickReminders() }
        chip.setOnClickListener { quickReminders() }
        chip.setOnCloseIconClickListener { note = note.copy(reminderAt = 0); renderMeta() }
        attachBox = findViewById(R.id.attachments)
        linkChip = findViewById(R.id.linkChip)
        linkChip.setOnClickListener { Links.linkOf(current().body)?.let { Links.open(this, it) } }
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
        Checklist.Item(row.findViewById<CheckBox>(R.id.check).isChecked, row.findViewById<EditText>(R.id.text).text.toString().trim(), row.tag as? Int ?: 0)
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

    // ---- version history ---------------------------------------------------------------------

    private fun showHistory() {
        saveNow()
        val versions = store.versions(note.uid)
        if (versions.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.action_history).setMessage(R.string.history_empty)
                .setPositiveButton(R.string.history_close, null).show()
            return
        }
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        val labels = versions.map { v ->
            val first = (v.title.ifBlank { v.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }).trim().take(60)
            fmt.format(java.util.Date(v.savedAt)) + "\n" + first
        }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(R.string.action_history)
            .setItems(labels) { _, i -> previewVersion(versions[i]) }
            .setNegativeButton(R.string.history_close, null).show()
    }

    private fun previewVersion(v: NoteStore.Version) {
        val text = (if (v.checklist) Checklist.toPlain(Checklist.parse(v.body)) else v.body)
        val body = (v.title.takeIf { it.isNotBlank() }?.let { it + "\n\n" } ?: "") + text
        MaterialAlertDialogBuilder(this).setTitle(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(v.savedAt)))
            .setMessage(body)
            .setPositiveButton(R.string.history_restore) { _, _ -> restoreVersion(v) }
            .setNegativeButton(R.string.history_close, null).show()
    }

    private fun restoreVersion(v: NoteStore.Version) {
        titleEdit.setText(v.title)
        if (v.checklist) showChecklist(Checklist.parse(v.body).ifEmpty { listOf(Checklist.Item(false, "")) })
        else { if (inChecklist()) { list.visibility = View.GONE; bodyEdit.visibility = View.VISIBLE }; bodyEdit.setText(v.body) }
        saveNow(); renderMeta()
        Toast.makeText(this, R.string.history_restored, Toast.LENGTH_SHORT).show()
    }

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
        v.findViewById<View>(R.id.more).setOnClickListener { showAttachmentMenu(it, a) }
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
        v.findViewById<View>(R.id.more).setOnClickListener { showAttachmentMenu(it, a) }
        return v
    }

    private fun showAttachmentMenu(anchor: View, a: Attachment) {
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.action_share); menu.add(0, 2, 1, R.string.attach_save); menu.add(0, 3, 2, R.string.attach_remove)
            setOnMenuItemClickListener {
                when (it.itemId) { 1 -> shareAttachment(a); 2 -> saveAttachment(a); 3 -> confirmRemove(a) }
                true
            }
            show()
        }
    }

    private fun shareAttachment(a: Attachment) {
        val uri = AttachmentExport.stage(this, store, a, titleEdit.text.toString())
        if (uri == null) { Toast.makeText(this, R.string.attach_save_failed, Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent.createChooser(AttachmentExport.shareIntent(listOf(uri to a.kind.mime), null, null), getString(R.string.share_chooser)))
    }

    /** Android 10+: straight into Pictures/HueNotes or Music/HueNotes. Older Android: the person picks the place. */
    private fun saveAttachment(a: Attachment) {
        val file = store.storage.file(a)
        if (!file.exists()) { Toast.makeText(this, R.string.attach_save_failed, Toast.LENGTH_SHORT).show(); return }
        val name = AttachmentExport.friendlyName(a, titleEdit.text.toString())
        if (android.os.Build.VERSION.SDK_INT < 29) { pendingSave = a to name; saveLauncher.launch(name); return }
        try { Toast.makeText(this, getString(R.string.attach_saved, AttachmentExport.saveToPhone(this, file, name, a.kind)), Toast.LENGTH_LONG).show() }
        catch (e: Exception) { Toast.makeText(this, R.string.attach_save_failed, Toast.LENGTH_SHORT).show() }
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
        if (!::linkChip.isInitialized) return      // views are still being set up
        val base = if (note.id == 0L) getString(R.string.meta_new)
        else getString(R.string.meta_edited, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.modifiedAt)))
        // A list is counted in items (and how many are done); a text note in words.
        val size = if (inChecklist()) {
            val (total, done) = Checklist.counts(currentItems())
            resources.getQuantityString(R.plurals.item_count, total, total) +
                if (done > 0) "  ·  " + resources.getQuantityString(R.plurals.items_done, done, done) else ""
        } else {
            val words = bodyEdit.text.toString().trim().split(Regex("\\s+")).count { it.isNotBlank() }
            resources.getQuantityString(R.plurals.word_count, words, words)
        }
        meta.text = "$base  ·  $size"
        if (note.reminderAt > 0) {
            chip.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.reminderAt))
            chip.visibility = View.VISIBLE
        } else chip.visibility = View.GONE
        val url = if (inChecklist()) null else Links.linkOf(bodyEdit.text.toString())
        if (url != null) { linkChip.text = getString(R.string.link_open) + " · " + Links.domain(url); linkChip.visibility = View.VISIBLE } else linkChip.visibility = View.GONE
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

    private var applying = false      // true while we set the rows' states ourselves, so tick listeners stay quiet

    private fun showChecklist(items: List<Checklist.Item>) {
        bodyEdit.visibility = View.GONE; list.visibility = View.VISIBLE; list.removeAllViews()
        val rows = Checklist.normalize(items).ifEmpty { listOf(Checklist.Item(false, "")) }
        rows.forEach { addRow(it, -1, focus = false) }
        styleRows()
    }

    /** Writes [items] (same rows, new ticks/levels) back onto the existing rows, keeping what is being typed. */
    private fun applyStates(items: List<Checklist.Item>) {
        applying = true
        items.forEachIndexed { i, it -> list.getChildAt(i).let { row -> row.tag = it.level; row.findViewById<CheckBox>(R.id.check).isChecked = it.done } }
        applying = false
        styleRows()
        renderMeta()
    }

    /** Indent, guide line, bold parents, dash for half-done parents, strike-through, and the indent/outdent button. */
    private fun styleRows() {
        val items = currentItems()
        val scale = Prefs.fontScale(this)
        items.forEachIndexed { i, item ->
            val row = list.getChildAt(i)
            val text = row.findViewById<EditText>(R.id.text); val check = row.findViewById<CheckBox>(R.id.check)
            val sub = item.level == 1
            row.findViewById<View>(R.id.indentBox).layoutParams.width = if (sub) Palette.dp(this, 30) else 0
            row.findViewById<View>(R.id.indentBox).requestLayout()
            row.findViewById<View>(R.id.guide).visibility = if (sub) View.VISIBLE else View.GONE
            text.textSize = (if (sub) 16f else 17f) * scale
            text.setTypeface(null, if (Checklist.isParent(items, i)) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            text.paintFlags = if (item.done) text.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else text.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            text.alpha = if (item.done) 0.5f else 1f
            check.setButtonDrawable(if (Checklist.half(items, i)) R.drawable.check_half else R.drawable.check_selector)
            val btn = row.findViewById<android.widget.ImageButton>(R.id.indent)
            // Sub-items always show "move out"; the row being typed in shows "make sub-item" (not on the very first row).
            btn.visibility = if (sub || (i > 0 && text.hasFocus())) View.VISIBLE else View.GONE
            btn.setImageResource(if (sub) R.drawable.ic_outdent else R.drawable.ic_indent)
            btn.contentDescription = getString(if (sub) R.string.cd_outdent else R.string.cd_indent)
        }
    }

    private fun focusRow(i: Int, atEnd: Boolean = true) {
        val t = list.getChildAt(i.coerceIn(0, list.childCount - 1)).findViewById<EditText>(R.id.text)
        t.requestFocus(); if (atEnd) t.setSelection(t.text.length)
    }

    private fun addRow(item: Checklist.Item, at: Int, focus: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_check, list, false)
        row.tag = item.level
        val check = row.findViewById<CheckBox>(R.id.check); val text = row.findViewById<EditText>(R.id.text)
        text.setText(item.text); check.isChecked = item.done
        check.setOnCheckedChangeListener { _, checked ->
            if (!applying) applyStates(Checklist.tick(currentItems(), list.indexOfChild(row), checked))
        }
        text.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = renderMeta()
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
        })
        text.setOnFocusChangeListener { _, _ -> if (list.indexOfChild(row) >= 0) styleRows() }
        text.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT) {
                val (items, pos) = Checklist.insertBelow(currentItems(), list.indexOfChild(row))
                addRow(items[pos], pos, focus = true)
                applyStates(items)
                true
            } else false
        }
        text.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_DEL && ev.action == KeyEvent.ACTION_DOWN && text.text.isEmpty()) {
                val i = list.indexOfChild(row); val items = currentItems()
                if (items[i].level == 1) { applyStates(Checklist.outdent(items, i)); true }        // first Backspace moves it out
                else if (list.childCount > 1) { removeRow(i); true } else false                    // then it deletes
            } else false
        }
        row.findViewById<View>(R.id.indent).setOnClickListener {
            val i = list.indexOfChild(row); val items = currentItems()
            applyStates(if (items[i].level == 1) Checklist.outdent(items, i) else Checklist.indent(items, i))
        }
        row.findViewById<View>(R.id.remove).setOnClickListener {
            if (list.childCount > 1) removeRow(list.indexOfChild(row)) else { text.setText(""); applyStates(currentItems()) }
        }
        if (at < 0) list.addView(row) else list.addView(row, at)
        if (focus) text.requestFocus()
    }

    private fun removeRow(i: Int) {
        val after = Checklist.remove(currentItems(), i)
        list.removeViewAt(i)
        applyStates(after)
        focusRow(i - 1)
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
