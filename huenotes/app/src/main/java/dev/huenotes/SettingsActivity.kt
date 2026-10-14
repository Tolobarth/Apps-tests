package dev.huenotes

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class SettingsActivity : GatedActivity() {
    private lateinit var list: LinearLayout

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) guarded { Backup.export(this, NoteStore(this), uri); toast(getString(R.string.backup_saved)) }
    }
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) guarded {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            Prefs.setBackupFolder(this, uri.toString(), AutoBackup.displayName(this, uri)); Prefs.setBackupError(this, "")
            AutoBackup.apply(this); render(); backupNow()
        }
    }
    private var importSource = Importers.Source.KEEP
    private val otherImportLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFromOthers(uris)
    }
    private val markdownLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) guarded { contentResolver.openOutputStream(uri, "wt")!!.use { MarkdownExport.write(NoteStore(this), it) }; toast(getString(R.string.backup_saved)) }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) guarded { toast(imported(Backup.import(this, NoteStore(this), uri))) }
    }

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            try { onAuthorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data!!)) }
            catch (e: Exception) { syncFailed(e) }
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_settings); Palette.edgeToEdge(this, findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        list = findViewById(R.id.list)
    }

    override fun onUnlocked() = render()

    private fun render() {
        list.removeAllViews()
        val themes = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark).map(::getString)
        val langCodes = listOf("", "en", "fr", "es", "pt", "de")
        val langNames = listOf(getString(R.string.language_system), "English", "Français", "Español", "Português", "Deutsch")
        val fonts = listOf(R.string.font_small, R.string.font_medium, R.string.font_large).map(::getString)
        val screens = listOf(R.string.nav_notes, R.string.nav_calendar).map(::getString)
        val sorts = SortMode.values().map { getString(it.label) }
        val dayValues = listOf(0, Calendar.MONDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val days = listOf(R.string.day_system, R.string.day_monday, R.string.day_saturday, R.string.day_sunday).map(::getString)
        val opacities = listOf(40, 50, 60, 70, 80, 90, 100)

        section(R.string.settings_appearance)
        row(getString(R.string.settings_theme), themes[Prefs.theme(this)]) {
            choose(R.string.settings_theme, themes, Prefs.theme(this)) { Prefs.setTheme(this, it) }
        }
        row(getString(R.string.settings_language), langNames[langCodes.indexOf(Prefs.language(this)).coerceAtLeast(0)]) {
            choose(R.string.settings_language, langNames, langCodes.indexOf(Prefs.language(this)).coerceAtLeast(0)) { Prefs.setLanguage(this, langCodes[it]) }
        }
        row(getString(R.string.settings_font_size), fonts[Prefs.fontSize(this)]) {
            choose(R.string.settings_font_size, fonts, Prefs.fontSize(this)) { Prefs.setFontSize(this, it); render() }
        }

        section(R.string.settings_sync)
        if (!SyncManager.isConnected(this)) {
            row(getString(R.string.sync_connect), getString(R.string.sync_connect_sum)) { connect() }
        } else {
            val last = Prefs.lastSync(this)
            row(getString(R.string.sync_account), Prefs.syncEmail(this))
            val err = Prefs.syncError(this)
            row(getString(R.string.sync_now), if (err.isNotEmpty()) getString(R.string.sync_status_error, err) else if (last == 0L) getString(R.string.sync_never)
                else getString(R.string.sync_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)))) { connect() }
            switchRow(getString(R.string.sync_auto), getString(R.string.sync_auto_sum), Prefs.autoSync(this)) { Prefs.setAutoSync(this, it); SyncScheduler.apply(this) }
            row(getString(R.string.sync_disconnect)) { confirmDisconnect() }
        }

        section(R.string.settings_notes)
        row(getString(R.string.settings_default_colour), dot = Prefs.defaultColor(this)) { pickColour() }
        row(getString(R.string.settings_default_sort), sorts[Prefs.sort(this).ordinal]) {
            choose(R.string.settings_default_sort, sorts, Prefs.sort(this).ordinal) { Prefs.setSort(this, SortMode.values()[it]); render() }
        }
        row(getString(R.string.settings_default_screen), screens[Prefs.defaultScreen(this)]) {
            choose(R.string.settings_default_screen, screens, Prefs.defaultScreen(this)) { Prefs.setDefaultScreen(this, it); render() }
        }
        switchRow(getString(R.string.settings_spell), getString(R.string.settings_spell_sum), Prefs.suggestions(this)) { Prefs.setSuggestions(this, it) }
        switchRow(getString(R.string.settings_link_titles), getString(R.string.settings_link_titles_sum), Prefs.fetchLinkTitles(this)) { Prefs.setFetchLinkTitles(this, it) }

        section(R.string.settings_calendar)
        row(getString(R.string.settings_first_day), days[dayValues.indexOf(Prefs.firstDay(this)).coerceAtLeast(0)]) {
            choose(R.string.settings_first_day, days, dayValues.indexOf(Prefs.firstDay(this)).coerceAtLeast(0)) { Prefs.setFirstDay(this, dayValues[it]); render() }
        }

        section(R.string.settings_widgets)
        row(getString(R.string.settings_widget_opacity), getString(R.string.widget_opacity_value, Prefs.widgetOpacity(this))) {
            val labels = opacities.map { getString(R.string.widget_opacity_value, it) }
            choose(R.string.settings_widget_opacity, labels, opacities.indexOf(Prefs.widgetOpacity(this)).coerceAtLeast(opacities.size - 1)) {
                Prefs.setWidgetOpacity(this, opacities[it]); Widgets.refresh(this); render()
            }
        }

        section(R.string.settings_security)
        if (!Lock.isSet(this)) {
            row(getString(R.string.settings_password_set), getString(R.string.settings_password_set_sum)) {
                Lock.setupFlow(this) { Widgets.refresh(this); render() }
            }
        } else {
            row(getString(R.string.settings_password_change)) { Lock.changeFlow(this) }
            row(getString(R.string.settings_password_remove)) { Lock.removeFlow(this) { render() } }
        }
        note(R.string.settings_password_note)

        section(R.string.settings_backup)
        row(getString(R.string.settings_export), getString(R.string.settings_export_sum)) { exportLauncher.launch("huenotes-backup.zip") }
        row(getString(R.string.settings_import), getString(R.string.settings_import_sum)) { importLauncher.launch(arrayOf("*/*")) }
        row(getString(R.string.import_others), getString(R.string.import_others_sum)) { pickImportSource() }
        row(getString(R.string.export_markdown), getString(R.string.export_markdown_sum)) { markdownLauncher.launch("huenotes-markdown.zip") }
        run {
            val err = Prefs.backupError(this); val last = Prefs.lastBackup(this)
            if (!AutoBackup.enabled(this)) row(getString(R.string.backup_auto_title), getString(R.string.backup_auto_off)) { folderLauncher.launch(null) }
            else {
                row(getString(R.string.backup_auto_title), getString(R.string.backup_auto_sum, Prefs.backupFolderName(this), AutoBackup.KEEP)) { folderLauncher.launch(null) }
                row(getString(R.string.backup_now), if (err.isNotEmpty()) err else if (last == 0L) getString(R.string.backup_none)
                    else getString(R.string.backup_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)))) { backupNow() }
                row(getString(R.string.backup_auto_stop)) { Prefs.setBackupFolder(this, "", ""); Prefs.setBackupError(this, ""); AutoBackup.apply(this); render() }
            }
        }
        switchRow(getString(R.string.settings_snapshots), getString(R.string.settings_snapshots_sum), Prefs.autoSnapshots(this)) { Prefs.setAutoSnapshots(this, it) }
        row(getString(R.string.settings_restore_snapshot)) { pickSnapshot() }

        section(R.string.settings_about)
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        row(getString(R.string.app_name), getString(R.string.about_version, version))
        row(getString(R.string.about_privacy), getString(R.string.about_privacy_sum))
    }

    // ---- row helpers ---------------------------------------------------------------------------

    private fun section(@StringRes t: Int) {
        val v = layoutInflater.inflate(R.layout.item_section, list, false) as TextView
        v.setText(t); list.addView(v)
    }

    private fun note(@StringRes t: Int) {
        list.addView(TextView(this).apply {
            setText(t); textSize = 12f; setTextColor(getColor(R.color.on_surface_variant))
            setPadding(Palette.dp(context, 20), 0, Palette.dp(context, 20), Palette.dp(context, 8))
        })
    }

    private fun row(title: String, summary: String? = null, dot: Int? = null, onClick: (() -> Unit)? = null): View {
        val v = layoutInflater.inflate(R.layout.item_setting, list, false)
        v.findViewById<TextView>(R.id.title).text = title
        if (summary != null) v.findViewById<TextView>(R.id.summary).apply { text = summary; visibility = View.VISIBLE }
        if (dot != null) v.findViewById<View>(R.id.dot).apply {
            visibility = View.VISIBLE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(dot); setStroke(Palette.dp(context, 1), getColor(R.color.outline))
            }
        }
        if (onClick != null) v.setOnClickListener { onClick() } else v.isClickable = false
        list.addView(v)
        return v
    }

    private fun switchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val v = row(title, summary)
        val sw = v.findViewById<MaterialSwitch>(R.id.toggle)
        sw.visibility = View.VISIBLE; sw.isChecked = checked
        v.setOnClickListener { sw.toggle(); onChange(sw.isChecked) }
    }

    private fun choose(@StringRes title: Int, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, i -> d.dismiss(); onPick(i); render() }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun pickColour() {
        val pad = Palette.dp(this, 20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        var dialog: AlertDialog? = null
        Note.COLORS.toList().chunked(4).forEach { chunk ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, Palette.dp(context, 12))
                chunk.forEach { c ->
                    addView(Palette.swatch(context, c, c == Prefs.defaultColor(context), getString(R.string.note_colour)) {
                        Prefs.setDefaultColor(this@SettingsActivity, c); dialog?.dismiss(); render()
                    })
                }
            })
        }
        dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.settings_default_colour).setView(col)
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun pickSnapshot() {
        val files = Snapshots.list(this)
        if (files.isEmpty()) { toast(getString(R.string.snapshot_none)); return }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        MaterialAlertDialogBuilder(this).setTitle(R.string.snapshot_pick_title)
            .setItems(files.map { fmt.format(Date(it.lastModified())) }.toTypedArray()) { _, i ->
                guarded { toast(imported(Backup.importText(this, NoteStore(this), files[i].readText()))) }
            }.setNegativeButton(R.string.btn_cancel, null).show()
    }

    // ---- Google sync ---------------------------------------------------------------------------

    /** Authorizes (showing Google's consent screen the first time) and then syncs. */
    private fun connect() {
        SyncManager.authorize(this)
            .addOnSuccessListener { r ->
                if (r.hasResolution()) authLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                else onAuthorized(r)
            }
            .addOnFailureListener { syncFailed(it) }
    }

    private fun onAuthorized(r: AuthorizationResult) {
        val token = r.accessToken ?: return syncFailed(IllegalStateException("No access token"))
        Prefs.setSyncEmail(this, r.toGoogleSignInAccount()?.email ?: Prefs.syncEmail(this).ifEmpty { "Google" })
        toast(getString(R.string.sync_running))
        render()
        SyncManager.run(this, token) { res ->
            SyncScheduler.apply(this)
            res.onSuccess { toast(getString(R.string.sync_done)); render() }.onFailure { syncFailed(it); render() }
        }
    }

    private fun syncFailed(e: Throwable) {
        toast(SyncStatus.describe(this, e))
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.sync_disconnect_title).setMessage(R.string.sync_disconnect_msg)
            .setPositiveButton(R.string.sync_disconnect) { _, _ -> Prefs.setSyncEmail(this, ""); Prefs.setLastSync(this, 0); Prefs.setSyncError(this, ""); SyncScheduler.apply(this); render() }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun pickImportSource() {
        val items = arrayOf(getString(R.string.import_keep), getString(R.string.import_text))
        MaterialAlertDialogBuilder(this).setTitle(R.string.import_others).setItems(items) { _, i ->
            importSource = if (i == 0) Importers.Source.KEEP else Importers.Source.TEXT
            otherImportLauncher.launch(arrayOf("*/*"))
        }.setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun importFromOthers(uris: List<Uri>) {
        toast(getString(R.string.sync_running))
        val source = importSource
        Thread {
            val r = runCatching { Importers.importUris(applicationContext, NoteStore(applicationContext), uris, source) }
            runOnUiThread { toast(r.fold({ imported(it) }, { getString(R.string.backup_failed, it.message ?: "") })) }
        }.start()
    }

    private fun backupNow() {
        toast(getString(R.string.sync_running))
        Thread {
            val r = AutoBackup.runAndRecord(applicationContext)
            runOnUiThread { render(); toast(if (r.isSuccess) getString(R.string.backup_saved) else Prefs.backupError(this)) }
        }.start()
    }

    private fun imported(n: Int) = resources.getQuantityString(R.plurals.backup_imported, n, n)
    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()
    private fun guarded(block: () -> Unit) {
        try { block() } catch (e: Exception) { toast(getString(R.string.backup_failed, e.message ?: "")) }
    }
}
