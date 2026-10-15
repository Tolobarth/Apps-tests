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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private lateinit var labelChips: com.google.android.material.chip.ChipGroup
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
    private var dictation: Dictation? = null
    private var transcriber: Transcriber? = null
    private var speechDialog: androidx.appcompat.app.AlertDialog? = null
    private var dictateAfterPermission = false
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val dictate = dictateAfterPermission; dictateAfterPermission = false
        if (ok) { if (dictate) startDictation() else showRecorder() }
        else Toast.makeText(this, if (dictate) R.string.speech_permission else R.string.rec_permission, Toast.LENGTH_LONG).show()
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
        toolbar.menu.add(0, 5, 5, R.string.action_translate).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, 6, 3, R.string.labels_title).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> { note = note.copy(pinned = !note.pinned); applyColor() }
                1 -> shareNote(this, current(), store)
                2 -> archive()
                3 -> confirmDelete()
                4 -> showHistory()
                5 -> startTranslation()
                6 -> pickLabels()
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
        labelChips = findViewById(R.id.labelChips)
        if (note.id == 0L) intent.getStringExtra("label")?.let { note = note.copy(labels = listOf(it)) }   // new note from a label view
        renderLabels()
        attachments.addAll(store.attachmentsFor(note.uid))
        renderAttachments()
        findViewById<View>(R.id.imageButton).setOnClickListener { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        findViewById<View>(R.id.drawButton).setOnClickListener { launchDrawing(null) }
        findViewById<View>(R.id.micButton).setOnClickListener { showRecorder() }
        findViewById<View>(R.id.dictateButton).setOnClickListener { startDictation() }
        applyColor()
        renderMeta()
        if (start != null) window.decorView.post {
            when (start) {
                "image" -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "drawing" -> launchDrawing(null)
                "audio" -> showRecorder()
                "dictate" -> { bodyEdit.requestFocus(); startDictation() }
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
        // Android silences the mic for apps in the background: keep what was recognised so far (it is saved just below).
        dictation?.cancel(); transcriber?.cancel(); mlkit?.cancel()
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

    override fun onDestroy() { super.onDestroy(); stopRecording(save = false); scope.cancel() }

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

    // ---- speech ------------------------------------------------------------------------------

    /** Where dictated words go: the field being typed in, else a new checklist row, else the end of the text. */
    private fun dictationTarget(): EditText {
        val f = currentFocus as? EditText
        if (f != null && (f === titleEdit || f === bodyEdit || isInChecklist(f))) return f
        if (!inChecklist()) return bodyEdit.also { it.requestFocus(); it.setSelection(it.text.length) }
        val last = list.getChildAt(list.childCount - 1)?.findViewById<EditText>(R.id.text)
        if (last != null && last.text.isBlank()) return last.also { it.requestFocus() }
        val (items, pos) = Checklist.insertBelow(currentItems(), list.childCount - 1)
        addRow(items[pos], pos, focus = true); applyStates(items)
        return list.getChildAt(pos).findViewById(R.id.text)
    }

    private fun isInChecklist(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p === list) return true; p = p.parent }
        return false
    }

    private fun speechStatusView(initial: String) = TextView(this).apply {
        text = initial; textSize = 18f; setTextColor(getColor(R.color.on_surface)); minHeight = Palette.dp(context, 96)
        setPadding(Palette.dp(context, 24), Palette.dp(context, 16), Palette.dp(context, 24), Palette.dp(context, 8))
    }

    private fun startDictation() {
        if (dictation != null || transcriber != null || recorder != null) return
        if (!Speech.canDictate(this)) { explainNoSpeech(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            dictateAfterPermission = true; micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        withSpeech { tag, online -> beginDictation(tag, online) }
    }

    private fun beginDictation(tag: String, online: Boolean) {
        if (dictation != null || transcriber != null || recorder != null) return
        CrashLog.doing(this, "dictate $tag" + if (online) " online" else "")
        Player.stop()
        val target = dictationTarget()
        val cursor = target.selectionEnd.takeIf { it >= 0 } ?: target.text.length
        val shown = speechStatusView(getString(R.string.dictate_hint))
        var discard = false
        val d = Dictation(this, { text, hearing ->
            shown.text = DictationText.join(listOf(text, hearing)).ifEmpty { getString(R.string.dictate_hint) }
        }, { text, error ->
            speechDialog?.dismiss(); speechDialog = null; dictation = null; CrashLog.doing(this, "edit")
            if (!discard && text.isNotBlank()) {
                val (t, at) = DictationText.insert(target.text.toString(), cursor.coerceAtMost(target.text.length), text)
                target.setText(t); target.setSelection(at.coerceAtMost(t.length))
            }
            if (error != null && !discard) showSpeechError(error)
        }, tag, Speech.create(this, online))
        dictation = d
        speechDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.dictate_title).setView(shown)
            .setPositiveButton(R.string.dictate_done) { _, _ -> d.stop() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> discard = true; d.cancel() }
            .setCancelable(false).show()
        d.start()
    }

    /** Voice notes (and received audio files) can be turned into text on Android 13+. */
    private fun canTranscribe(a: Attachment) =
        (a.kind == AttachmentKind.AUDIO || (a.kind == AttachmentKind.FILE && a.mimeType.startsWith("audio/"))) && Speech.canTranscribe(this)

    private val scope = kotlinx.coroutines.MainScope()
    private var mlkit: MlKitTranscriber? = null
    private fun speechBusy() = dictation != null || transcriber != null || mlkit != null || recorder != null

    /**
     * Transcribe: Google's ML Kit first (it reads the recording itself, which works on more phones); its speech model is
     * downloaded once, after asking. Android's recogniser is the fallback when ML Kit isn't there or fails before any text.
     */
    private fun transcribe(a: Attachment) {
        if (speechBusy()) return
        val file = store.storage.file(a)
        if (!file.exists()) { Toast.makeText(this, R.string.file_not_here, Toast.LENGTH_SHORT).show(); return }
        if (MlKitSpeech.mayWork(this)) transcribeWithMlKit(a, file) else platformTranscript(a, file, "")
    }

    private fun platformTranscript(a: Attachment, file: File, earlier: String) {
        if (android.os.Build.VERSION.SDK_INT < 33 || !Speech.canDictate(this)) {
            if (earlier.isEmpty()) explainNoSpeech() else transcriptNotAccepted(earlier)
            return
        }
        withSpeech { tag, online -> beginTranscript(a, file, tag, online, earlier) }
    }

    private fun transcribeWithMlKit(a: Attachment, file: File) {
        val locale = MlKitSpeech.locale(Speech.language(this))
        CrashLog.doing(this, "transcribe-mlkit-status ${locale.toLanguageTag()}")
        val engine = try { MlKitSpeech.factory(locale) } catch (e: Exception) { platformTranscript(a, file, "ml-kit: ${e.javaClass.simpleName}\n"); return }
        scope.launch {
            val st = try { engine.status() } catch (e: Exception) { MlKitSpeech.Status.UNAVAILABLE }
            if (isFinishing || isDestroyed) { engine.close(); return@launch }
            when (st) {
                MlKitSpeech.Status.AVAILABLE -> beginMlKit(a, file, engine)
                MlKitSpeech.Status.DOWNLOADABLE -> offerSpeechModel(a, file, engine, locale)
                MlKitSpeech.Status.DOWNLOADING -> downloadSpeechModel(a, file, engine, locale)
                MlKitSpeech.Status.UNAVAILABLE -> { engine.close(); platformTranscript(a, file, "ml-kit: unavailable for ${locale.toLanguageTag()}\n") }
            }
        }
    }

    /** First use: "Transcription needs Google's speech model for français" → Download / Not now. */
    private fun offerSpeechModel(a: Attachment, file: File, engine: MlKitSpeech.Engine, locale: java.util.Locale) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.speech_setup_title)
            .setMessage(getString(R.string.transcribe_model_msg, SpeechSetup.displayName(locale.toLanguageTag())))
            .setPositiveButton(R.string.speech_download) { _, _ -> downloadSpeechModel(a, file, engine, locale) }
            .setNegativeButton(R.string.speech_not_now) { _, _ -> engine.close() }
            .setOnCancelListener { engine.close() }.show()
    }

    private fun downloadSpeechModel(a: Attachment, file: File, engine: MlKitSpeech.Engine, locale: java.util.Locale) {
        val name = SpeechSetup.displayName(locale.toLanguageTag())
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = true
            setPadding(Palette.dp(context, 24), Palette.dp(context, 16), Palette.dp(context, 24), Palette.dp(context, 8))
        }
        val wait = MaterialAlertDialogBuilder(this).setTitle(getString(R.string.speech_downloading_title, name)).setView(bar)
            .setNegativeButton(R.string.speech_hide, null).show()
        CrashLog.doing(this, "transcribe-mlkit-download ${locale.toLanguageTag()}")
        scope.launch {
            var total = 0L
            var outcome: MlKitSpeech.Download? = null
            try {
                engine.download().collect { d ->
                    when (d) {
                        is MlKitSpeech.Download.Started -> total = d.totalBytes
                        is MlKitSpeech.Download.Progress -> if (total > 0) { bar.isIndeterminate = false; bar.setProgressCompat((d.bytes * 100 / total).toInt().coerceIn(0, 100), true) }
                        else -> outcome = d
                    }
                }
            } catch (e: Exception) { outcome = MlKitSpeech.Download.Failed(e.message ?: e.javaClass.simpleName) }
            val waiting = wait.isShowing
            wait.dismiss()
            if (isFinishing || isDestroyed) { engine.close(); return@launch }
            val failed = outcome as? MlKitSpeech.Download.Failed
            when {
                failed != null -> {
                    engine.close()
                    MaterialAlertDialogBuilder(this@EditorActivity).setMessage(getString(R.string.transcribe_model_failed, name) + "\n\n(" + failed.why + ")")
                        .setPositiveButton(R.string.translate_retry) { _, _ -> transcribeWithMlKit(a, file) }
                        .setNegativeButton(R.string.btn_cancel, null).show()
                }
                waiting -> { Toast.makeText(this@EditorActivity, getString(R.string.speech_ready, name), Toast.LENGTH_SHORT).show(); beginMlKit(a, file, engine) }
                else -> { engine.close(); Toast.makeText(this@EditorActivity, getString(R.string.speech_ready, name), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun beginMlKit(a: Attachment, file: File, engine: MlKitSpeech.Engine) {
        if (speechBusy()) { engine.close(); return }
        CrashLog.doing(this, "transcribe-mlkit ${a.kind} ${file.length() / 1024}KB")
        Player.stop()
        val total = if (a.durationMs > 0) " / " + AttachmentFiles.duration(a.durationMs) else ""
        val shown = speechStatusView(AttachmentFiles.duration(0) + total)
        lateinit var t: MlKitTranscriber
        t = MlKitTranscriber(file, engine, { text, ms ->
            shown.text = AttachmentFiles.duration(ms) + total + if (text.isEmpty()) "" else "\n\n" + text
        }, { text, error ->
            speechDialog?.dismiss(); speechDialog = null; mlkit = null; CrashLog.doing(this, "edit")
            if (text.isNotBlank()) { addTranscript(text); Toast.makeText(this, R.string.transcribe_added, Toast.LENGTH_SHORT).show() }
            when (error) {
                null -> Unit
                MlKitTranscriber.ENGINE_FAILED -> platformTranscript(a, file, t.details + "\n")
                Transcriber.NOT_ACCEPTED -> platformTranscript(a, file, t.details + "\n")
                else -> showSpeechError(error)
            }
        }, scope = scope)
        mlkit = t
        speechDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.transcribe_title).setView(shown)
            .setNegativeButton(R.string.transcribe_stop) { _, _ -> t.cancel() }
            .setCancelable(false).show()
        t.start()
    }

    private fun beginTranscript(a: Attachment, file: File, tag: String, online: Boolean, earlier: String = "") {
        if (android.os.Build.VERSION.SDK_INT < 33 || dictation != null || transcriber != null || mlkit != null || recorder != null) return
        CrashLog.doing(this, "transcribe $tag ${a.kind} ${file.length() / 1024}KB" + if (online) " online" else "")
        Player.stop()
        val total = if (a.durationMs > 0) " / " + AttachmentFiles.duration(a.durationMs) else ""
        val shown = speechStatusView(AttachmentFiles.duration(0) + total)
        val t = Transcriber(this, file, { text, ms ->
            shown.text = AttachmentFiles.duration(ms) + total + if (text.isEmpty()) "" else "\n\n" + text
        }, { text, error ->
            speechDialog?.dismiss(); speechDialog = null; transcriber = null; CrashLog.doing(this, "edit")
            if (text.isNotBlank()) { addTranscript(text); Toast.makeText(this, R.string.transcribe_added, Toast.LENGTH_SHORT).show() }
            if (error == Transcriber.NOT_ACCEPTED) lastTranscriber?.let { transcriptNotAccepted(earlier + it.details) }
            else if (error != null) showSpeechError(error)
        }, tag, { Speech.create(this, online) })
        transcriber = t; lastTranscriber = t
        speechDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.transcribe_title).setView(shown)
            .setNegativeButton(R.string.transcribe_stop) { _, _ -> t.cancel() }
            .setCancelable(false).show()
        t.start()
    }

    private var lastTranscriber: Transcriber? = null

    /** The phone's speech service didn't take the recording: say so, and let the person share what happened. */
    private fun transcriptNotAccepted(details: String) {
        val report = CrashLog.header(this) + "Transcription log\n" + details
        MaterialAlertDialogBuilder(this).setMessage(R.string.transcribe_not_accepted)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.transcribe_share_details) { _, _ ->
                startActivity(Intent.createChooser(CrashLog.shareIntent(report), getString(R.string.share_chooser)))
            }.show()
    }

    /** A transcript goes at the end of the note: a new paragraph, or a new checklist row. */
    private fun addTranscript(text: String) {
        if (inChecklist()) {
            val last = list.getChildAt(list.childCount - 1)?.findViewById<EditText>(R.id.text)
            if (last != null && last.text.isBlank()) last.setText(text)
            else {
                val (items, pos) = Checklist.insertBelow(currentItems(), list.childCount - 1)
                addRow(items[pos].copy(text = text), pos, focus = false); applyStates(items)
            }
        } else {
            val body = bodyEdit.text.toString().trimEnd()
            bodyEdit.setText(if (body.isEmpty()) text else body + "\n\n" + text)
        }
        saveNow(); renderMeta()
    }

    private fun showSpeechError(error: Int) {
        if (!Speech.isMissingLanguage(error)) { Toast.makeText(this, Speech.errorText(error), Toast.LENGTH_LONG).show(); return }
        val tag = Speech.language(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && Speech.onDevice(this)) { offerSpeechDownload(tag) { _, _ -> }; return }
        MaterialAlertDialogBuilder(this).setMessage(getString(R.string.speech_download_failed, SpeechSetup.displayName(tag)))
            .setPositiveButton(R.string.speech_settings) { _, _ -> SpeechSetup.openSettings(this) }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private var speechChecking = false

    /**
     * Runs [then] once the phone can recognise the app's language: straight away when it can, after a download the person
     * agrees to when it can't yet, or with online recognition when offline speech doesn't offer the language and they allow it.
     */
    private fun withSpeech(then: (tag: String, online: Boolean) -> Unit) {
        if (speechChecking) return
        val want = Speech.language(this)
        if (!Speech.onDevice(this)) { then(want, true); return }      // the regular recogniser downloads nothing up front
        speechChecking = true
        SpeechSetup.check(this, want) { st ->
            speechChecking = false
            if (isFinishing || isDestroyed) return@check
            when (st) {
                is SpeechSetup.State.Ready -> then(st.tag, false)
                is SpeechSetup.State.Unknown -> then(want, false)
                is SpeechSetup.State.NeedsDownload -> offerSpeechDownload(st.tag, then)
                is SpeechSetup.State.Downloading ->
                    MaterialAlertDialogBuilder(this).setMessage(getString(R.string.speech_still_downloading, SpeechSetup.displayName(st.tag)))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.speech_settings) { _, _ -> SpeechSetup.openSettings(this) }.show()
                is SpeechSetup.State.NotOffered -> notOfferedOffline(want, then)
            }
        }
    }

    private fun notOfferedOffline(tag: String, then: (String, Boolean) -> Unit) {
        val name = SpeechSetup.displayName(tag)
        if (Prefs.onlineSpeech(this) && android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { then(tag, true); return }
        val b = MaterialAlertDialogBuilder(this).setNegativeButton(R.string.btn_cancel, null)
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            b.setMessage(getString(R.string.speech_not_offered_online, name))
                .setPositiveButton(R.string.speech_use_online) { _, _ -> Prefs.setOnlineSpeech(this, true); then(tag, true) }
        } else b.setMessage(getString(R.string.speech_not_offered, name))
        b.show()
    }

    /** "Download French?": the phone's speech service fetches it once; Android 14+ shows progress here. */
    private fun offerSpeechDownload(tag: String, then: (String, Boolean) -> Unit) {
        val name = SpeechSetup.displayName(tag)
        MaterialAlertDialogBuilder(this).setTitle(R.string.speech_setup_title).setMessage(getString(R.string.speech_setup_msg, name))
            .setPositiveButton(R.string.speech_download) { _, _ -> downloadSpeech(tag, then) }
            .setNegativeButton(R.string.speech_not_now, null).show()
    }

    private fun downloadSpeech(tag: String, then: (String, Boolean) -> Unit) {
        val name = SpeechSetup.displayName(tag)
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = true
            setPadding(Palette.dp(context, 24), Palette.dp(context, 16), Palette.dp(context, 24), Palette.dp(context, 8))
        }
        val progress = MaterialAlertDialogBuilder(this).setTitle(getString(R.string.speech_downloading_title, name)).setView(bar)
            .setNegativeButton(R.string.speech_hide, null).show()
        fun later(msg: Int) {
            progress.dismiss()
            if (isFinishing || isDestroyed) return
            MaterialAlertDialogBuilder(this).setMessage(getString(msg, name)).setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.speech_settings) { _, _ -> SpeechSetup.openSettings(this) }.show()
        }
        SpeechSetup.download(this, tag, object : SpeechSetup.Progress {
            override fun percent(p: Int) { bar.isIndeterminate = false; bar.setProgressCompat(p, true) }
            override fun done() {
                val shown = progress.isShowing
                progress.dismiss()
                if (isFinishing || isDestroyed) return
                Toast.makeText(this@EditorActivity, getString(R.string.speech_ready, name), Toast.LENGTH_SHORT).show()
                if (shown) then(tag, false)       // still waiting here: carry on with what they asked for
            }
            override fun scheduled() = later(R.string.speech_download_started)
            override fun failed(followable: Boolean) = later(if (followable) R.string.speech_download_started else R.string.speech_download_failed)
        })
    }

    private fun explainNoSpeech() {
        val any = android.speech.SpeechRecognizer.isRecognitionAvailable(this)
        val b = MaterialAlertDialogBuilder(this).setMessage(if (any) R.string.speech_unavailable else R.string.speech_unavailable_none)
        if (any) b.setPositiveButton(R.string.speech_open_settings) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
        b.setNegativeButton(R.string.btn_cancel, null).show()
    }

    // ---- labels ------------------------------------------------------------------------------

    private fun pickLabels() = LabelDialogs.pick(this, store, note.labels) { setLabels(it) }

    private fun setLabels(l: List<String>) {
        note = note.copy(labels = Labels.normalize(l))
        if (note.id != 0L) store.setLabels(note.id, note.labels)
        renderLabels()
    }

    /** The note's labels as chips under the title: tap to change them, × to take one off. */
    private fun renderLabels() {
        labelChips.removeAllViews()
        note.labels.forEach { name ->
            labelChips.addView(Chip(this).apply {
                text = name; setChipIconResource(R.drawable.ic_label); isChipIconVisible = true
                isCloseIconVisible = true; closeIconContentDescription = getString(R.string.labels_remove, name)
                setOnClickListener { pickLabels() }
                setOnCloseIconClickListener { setLabels(Labels.remove(note.labels, name)) }
            })
        }
        labelChips.visibility = if (note.labels.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- translation -------------------------------------------------------------------------

    private var translating = false

    /** Translate: work out the note's language, ask for the target, fetch language packs if needed, then show the result. */
    private fun startTranslation() {
        if (translating) return
        val n = current()
        if (n.title.isBlank() && n.body.isBlank()) { Toast.makeText(this, R.string.translate_empty, Toast.LENGTH_SHORT).show(); return }
        saveNow()
        translating = true
        CrashLog.doing(this, "translate-detect")
        val sample = (n.title + "\n" + if (n.checklist) Checklist.toPlain(Checklist.parse(n.body)) else n.body).trim()
        Translate.engine.detect(sample) { src ->
            translating = false
            if (isFinishing || isDestroyed) return@detect
            if (src == null) pickSourceLanguage { chooseTarget(it) } else chooseTarget(src)
        }
    }

    private fun pickSourceLanguage(then: (String) -> Unit) {
        val all = Translate.targets(Translate.engine.languages(), null, Translate.appLanguage(this), Prefs.translateRecent(this))
        MaterialAlertDialogBuilder(this).setTitle(R.string.translate_source_title)
            .setItems(all.map { Translate.displayName(it) }.toTypedArray()) { _, i -> then(all[i]) }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun chooseTarget(src: String) {
        val all = Translate.targets(Translate.engine.languages(), src, Translate.appLanguage(this), Prefs.translateRecent(this))
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.translate_target_title, Translate.displayName(src)))
            .setItems(all.map { Translate.displayName(it) }.toTypedArray()) { _, i -> withLanguagePacks(src, all[i]) }
            .setNeutralButton(R.string.translate_other_source) { _, _ -> pickSourceLanguage { chooseTarget(it) } }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    /** First use of a language pair: Google's packs (about 30 MB each) are downloaded once, with the person's go-ahead. */
    private fun withLanguagePacks(src: String, dst: String) {
        Prefs.addTranslateRecent(this, dst)
        Translate.engine.downloaded { have ->
            if (isFinishing || isDestroyed) return@downloaded
            val missing = listOf(src, dst).filter { it !in have }
            if (missing.isEmpty()) { translateNote(src, dst); return@downloaded }
            MaterialAlertDialogBuilder(this).setTitle(R.string.translate_setup_title)
                .setMessage(resources.getQuantityString(R.plurals.translate_setup_msg, missing.size, missing.joinToString(", ") { Translate.displayName(it) }))
                .setPositiveButton(R.string.speech_download) { _, _ -> downloadPacks(src, dst, missing) }
                .setNegativeButton(R.string.speech_not_now, null).show()
        }
    }

    /** Like the speech download: progress while Google fetches the packs, then the translation carries on by itself. */
    private fun downloadPacks(src: String, dst: String, missing: List<String>) {
        CrashLog.doing(this, "translate-download $src>$dst")
        val names = missing.joinToString(", ") { Translate.displayName(it) }
        val wait = busyDialog(getString(R.string.translate_downloading_named, names))
        Translate.engine.download(src, dst) { error ->
            val waiting = wait.isShowing
            wait.dismiss()
            if (isFinishing || isDestroyed) return@download
            when {
                error != null -> packsFailed(src, dst, missing, error)
                waiting -> { Toast.makeText(this, getString(R.string.translate_ready_named, names), Toast.LENGTH_SHORT).show(); translateNote(src, dst, packsChecked = true) }
                else -> Toast.makeText(this, getString(R.string.translate_ready_named, names), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun packsFailed(src: String, dst: String, missing: List<String>, reason: String) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.translate_setup_title)
            .setMessage(getString(R.string.translate_download_failed) + "\n\n(" + reason + ")")
            .setPositiveButton(R.string.translate_retry) { _, _ -> downloadPacks(src, dst, missing) }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun busyDialog(title: String) = MaterialAlertDialogBuilder(this).setTitle(title)
        .setView(com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = true
            setPadding(Palette.dp(context, 24), Palette.dp(context, 16), Palette.dp(context, 24), Palette.dp(context, 8))
        }).setNegativeButton(R.string.speech_hide, null).show()

    /** Makes sure the packs are really there (a no-op when they are) before translating, so a pack that went missing is fetched. */
    private fun translateNote(src: String, dst: String, packsChecked: Boolean = false) {
        CrashLog.doing(this, "translate $src>$dst")
        val n = current()
        val wait = busyDialog(getString(R.string.translate_working, Translate.displayName(dst)))
        fun run() = Translate.translateTexts(src, dst, Translate.inputs(n)) { out ->
            val waiting = wait.isShowing
            wait.dismiss()
            if (isFinishing || isDestroyed || !waiting) return@translateTexts
            if (out == null) { Toast.makeText(this, R.string.translate_failed, Toast.LENGTH_LONG).show(); return@translateTexts }
            showTranslation(n, Translate.applyTo(n, out), dst)
        }
        if (packsChecked) { run(); return }
        Translate.engine.download(src, dst) { error ->
            if (error == null) { run(); return@download }
            wait.dismiss()
            if (!isFinishing && !isDestroyed) packsFailed(src, dst, listOf(src, dst), error)
        }
    }

    private fun showTranslation(orig: Note, t: Note, dst: String) {
        CrashLog.doing(this, "edit")
        val plain = listOf(t.title, if (t.checklist) Checklist.toPlain(Checklist.parse(t.body)) else t.body).filter { it.isNotBlank() }.joinToString("\n\n")
        MaterialAlertDialogBuilder(this).setTitle(Translate.displayName(dst)).setMessage(plain)
            .setPositiveButton(R.string.translate_add_below) { _, _ -> addTranslation(t) }
            .setNegativeButton(R.string.translate_replace) { _, _ -> replaceWithTranslation(orig, t) }
            .setNeutralButton(R.string.translate_copy) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(t.title, plain))
                Toast.makeText(this, R.string.translate_copied, Toast.LENGTH_SHORT).show()
            }.show()
    }

    /** The translation goes under the original: a paragraph (with the translated title as its first line), or more list rows. */
    private fun addTranslation(t: Note) {
        if (inChecklist()) {
            val items = currentItems().filter { it.text.isNotBlank() } + Checklist.parse(t.body).map { it.copy(done = false) }
            showChecklist(items)
        } else {
            val add = listOf(t.title, t.body.trim()).filter { it.isNotBlank() }.joinToString("\n")
            val body = bodyEdit.text.toString().trimEnd()
            bodyEdit.setText(if (body.isEmpty()) add else body + "\n\n" + add)
        }
        saveNow(); renderMeta()
    }

    /** Replacing is safe: the original stays in Version history. */
    private fun replaceWithTranslation(orig: Note, t: Note) {
        saveNow()
        store.keepVersion(store.get(note.id) ?: orig)
        titleEdit.setText(t.title)
        if (inChecklist()) showChecklist(Checklist.parse(t.body)) else bodyEdit.setText(t.body)
        saveNow(); renderMeta()
        Toast.makeText(this, R.string.translate_replaced, Toast.LENGTH_LONG).show()
    }

    private fun renderAttachments() {
        attachBox.removeAllViews()
        attachments.forEach { a ->
            attachBox.addView(when (a.kind) { AttachmentKind.AUDIO -> audioView(a); AttachmentKind.FILE -> fileView(a); else -> imageView(a) })
        }
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
        if (canTranscribe(a)) v.findViewById<View>(R.id.transcribe).apply { visibility = View.VISIBLE; setOnClickListener { transcribe(a) } }
        return v
    }

    /** A received file (PDF, document…): name, type and size; a PDF also shows its first page. Tap opens it in another app. */
    private fun fileView(a: Attachment): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_attach_file, attachBox, false)
        val file = store.storage.file(a)
        v.findViewById<TextView>(R.id.name).text = a.name.ifBlank { getString(R.string.file_untitled) }
        val type = a.ext.takeIf { it != "bin" }?.uppercase()
        v.findViewById<TextView>(R.id.info).text = if (file.exists()) listOfNotNull(type, android.text.format.Formatter.formatShortFileSize(this, file.length())).joinToString(" · ")
            else getString(R.string.file_not_here)
        AttachmentFiles.preview(store.storage, a, 1024)?.let { bmp ->
            v.findViewById<android.widget.ImageView>(R.id.preview).apply { setImageBitmap(bmp); visibility = View.VISIBLE }
        }
        v.setOnClickListener { openFile(a) }
        v.findViewById<View>(R.id.more).setOnClickListener { showAttachmentMenu(it, a) }
        return v
    }

    private fun openFile(a: Attachment) {
        val uri = AttachmentExport.stage(this, store, a, titleEdit.text.toString())
        if (uri == null) { Toast.makeText(this, R.string.file_not_here, Toast.LENGTH_SHORT).show(); return }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(view) } catch (e: android.content.ActivityNotFoundException) { Toast.makeText(this, R.string.file_no_app, Toast.LENGTH_LONG).show() }
    }

    private fun showAttachmentMenu(anchor: View, a: Attachment) {
        android.widget.PopupMenu(this, anchor).apply {
            if (a.kind == AttachmentKind.FILE) menu.add(0, 4, 0, R.string.file_open)
            if (canTranscribe(a)) menu.add(0, 5, 0, R.string.transcribe)
            menu.add(0, 1, 1, R.string.action_share); menu.add(0, 2, 2, R.string.attach_save); menu.add(0, 3, 3, R.string.attach_remove)
            setOnMenuItemClickListener {
                when (it.itemId) { 1 -> shareAttachment(a); 2 -> saveAttachment(a); 3 -> confirmRemove(a); 4 -> openFile(a); 5 -> transcribe(a) }
                true
            }
            show()
        }
    }

    private fun shareAttachment(a: Attachment) {
        val uri = AttachmentExport.stage(this, store, a, titleEdit.text.toString())
        if (uri == null) { Toast.makeText(this, R.string.attach_save_failed, Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent.createChooser(AttachmentExport.shareIntent(listOf(uri to a.mimeType), null, null), getString(R.string.share_chooser)))
    }

    /** Android 10+: straight into Pictures/HueNotes, Music/HueNotes or Download/HueNotes. Older Android: the person picks the place. */
    private fun saveAttachment(a: Attachment) {
        val file = store.storage.file(a)
        if (!file.exists()) { Toast.makeText(this, R.string.attach_save_failed, Toast.LENGTH_SHORT).show(); return }
        val name = AttachmentExport.friendlyName(a, titleEdit.text.toString())
        if (android.os.Build.VERSION.SDK_INT < 29) { pendingSave = a to name; saveLauncher.launch(name); return }
        try { Toast.makeText(this, getString(R.string.attach_saved, AttachmentExport.saveToPhone(this, file, name, a)), Toast.LENGTH_LONG).show() }
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
