package dev.huenotes

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class ListActivity : GatedActivity() {
    private lateinit var store: NoteStore
    private lateinit var adapter: NoteAdapter
    private lateinit var linkAdapter: LinkAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: StaggeredGridLayoutManager
    private lateinit var search: EditText
    private lateinit var filterRow: LinearLayout
    private lateinit var nav: BottomNavigationView
    private var showArchive = false
    private var colorFilter: Int? = null
    private var linksOnly = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = NoteStore(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        setContentView(R.layout.activity_main); Palette.edgeToEdge(this, findViewById(R.id.mainRoot))

        adapter = NoteAdapter({ openEditor(it.id) }, { n, v -> showNoteMenu(n, v) })
        adapter.storage = store.storage
        layoutManager = StaggeredGridLayoutManager(if (Prefs.grid(this)) 2 else 1, StaggeredGridLayoutManager.VERTICAL)
        linkAdapter = LinkAdapter({ Links.open(this, it.url) }, { e, v -> showLinkMenu(e, v) })
        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (linksOnly || adapter.noteAt(vh.bindingAdapterPosition) == null) 0 else super.getSwipeDirs(rv, vh)
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                adapter.noteAt(vh.bindingAdapterPosition)?.let { archive(it, !showArchive) } ?: refresh()
            }
        }).attachToRecyclerView(recycler)

        search = findViewById(R.id.search)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = refresh()
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
        })
        filterRow = findViewById(R.id.filterRow)
        onBackPressedDispatcher.addCallback(this, closeDial)
        findViewById<View>(R.id.fab).setOnClickListener { setDial(!dialOpen) }
        findViewById<View>(R.id.scrim).setOnClickListener { setDial(false) }
        dialItems.forEach { (id, start) ->
            findViewById<View>(id).setOnClickListener { setDial(false, animate = false); if (start == "link") showLinkDialog() else openEditor(0, start) }
        }
        findViewById<View>(R.id.sortButton).setOnClickListener { showSortSheet() }
        findViewById<View>(R.id.moreButton).setOnClickListener { showMenu(it) }

        nav = findViewById(R.id.bottomNav)
        nav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_notes -> { showArchive = false; refresh(); true }
                R.id.nav_archive -> { showArchive = true; refresh(); true }
                R.id.nav_calendar -> { startActivity(Intent(this, DayActivity::class.java)); false }
                else -> false
            }
        }
        if (s == null && Prefs.defaultScreen(this) == Prefs.SCREEN_CALENDAR) startActivity(Intent(this, DayActivity::class.java))
    }

    override fun onUnlocked() {
        store.purgeTrash(System.currentTimeMillis() - NoteStore.TRASH_RETENTION_MS)
        store.cleanupAttachments()
        Snapshots.maybeRun(this, store)
        refresh()
        SyncManager.syncIfDue(this) { if (!isFinishing) refresh() }
        offerCrashReport()
    }

    private var crashAsked = false

    /** After HueNotes closed because of a bug: offer to share what went wrong (nothing is sent unless the person shares it). */
    private fun offerCrashReport() {
        if (crashAsked) return
        val report = CrashLog.pending(this) ?: return
        crashAsked = true
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.crash_title).setMessage(R.string.crash_msg)
            .setPositiveButton(R.string.crash_share) { _, _ ->
                CrashLog.clear(this)
                startActivity(Intent.createChooser(CrashLog.shareIntent(report), getString(R.string.share_chooser)))
            }
            .setNegativeButton(R.string.crash_dismiss) { _, _ -> CrashLog.clear(this) }
            .setCancelable(false).show()
    }

    override fun onResume() {
        super.onResume()
        nav.menu.findItem(if (showArchive) R.id.nav_archive else R.id.nav_notes).isChecked = true
    }

    /** [start] makes a new note begin with that action: "image", "drawing", "audio", "dictate", "list" or "text". */
    private fun openEditor(id: Long, start: String? = null) =
        startActivity(Intent(this, EditorActivity::class.java).putExtra("id", id).putExtra("start", start))

    private val dialItems = listOf(R.id.dialText to "text", R.id.dialList to "list", R.id.dialLink to "link", R.id.dialDictate to "dictate", R.id.dialAudio to "audio", R.id.dialDrawing to "drawing", R.id.dialImage to "image")
    private var dialOpen = false

    private val closeDial = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setDial(false)
    }

    /** The "+" button fans out Image / Drawing / Audio / Dictate / Link / List / Text over a dimmed screen. */
    internal fun setDial(open: Boolean, animate: Boolean = true) {
        dialOpen = open
        closeDial.isEnabled = open
        val scrim = findViewById<View>(R.id.scrim); val dial = findViewById<View>(R.id.dial)
        val fab = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab)
        fab.setImageResource(if (open) R.drawable.ic_close else R.drawable.ic_add)   // swap the icon; rotating would tilt the whole button
        fab.contentDescription = getString(if (open) R.string.cd_close_menu else R.string.new_note)
        val rise = Palette.dp(this, 24).toFloat()
        if (!animate) {
            scrim.visibility = if (open) View.VISIBLE else View.GONE; scrim.alpha = 1f
            dial.visibility = if (open) View.VISIBLE else View.GONE
            (dial as android.view.ViewGroup).let { g -> for (k in 0 until g.childCount) { g.getChildAt(k).alpha = 1f; g.getChildAt(k).translationY = 0f } }
            return
        }
        if (open) {
            scrim.alpha = 0f; scrim.visibility = View.VISIBLE; scrim.animate().alpha(1f).setDuration(180).start()
            dial.visibility = View.VISIBLE
            dialItems.forEachIndexed { i, (id, _) ->
                findViewById<View>(id).apply {
                    alpha = 0f; translationY = rise
                    animate().alpha(1f).translationY(0f).setStartDelay(i * 35L).setDuration(160).start()
                }
            }
        } else {
            scrim.animate().alpha(0f).setDuration(140).withEndAction { if (!dialOpen) scrim.visibility = View.GONE }.start()
            dialItems.forEach { (id, _) -> findViewById<View>(id).animate().alpha(0f).setStartDelay(0).setDuration(120).start() }
            dial.postDelayed({ if (!dialOpen) dial.visibility = View.GONE }, 140)
        }
    }

    private fun refresh() {
        findViewById<TextView>(R.id.screenTitle).setText(if (showArchive) R.string.nav_archive else R.string.nav_notes)
        findViewById<View>(R.id.fab).visibility = if (showArchive) View.GONE else View.VISIBLE
        if (showArchive && dialOpen) setDial(false, animate = false)
        layoutManager.spanCount = if (Prefs.grid(this) && !linksOnly) 2 else 1
        // The links section is built from all notes of this tab, so every link appears by itself.
        val everyNote = Sorter.sort(store.list(showArchive), Prefs.sort(this))
        val allLinks = LinkIndex.entries(everyNote)
        val searching = search.text.isNotBlank() || colorFilter != null
        val visibleCount: Int
        if (linksOnly) {
            val rows = LinkIndex.filter(allLinks.filter { colorFilter == null || it.note.color == colorFilter }, search.text.toString())
            if (recycler.adapter !== linkAdapter) recycler.adapter = linkAdapter
            linkAdapter.entries = rows
            visibleCount = rows.size
        } else {
            val all = store.list(showArchive, search.text.toString())
            val byColour = colorFilter?.let { f -> all.filter { it.color == f } } ?: all
            val shown = Sorter.sort(byColour, Prefs.sort(this))
            if (recycler.adapter !== adapter) recycler.adapter = adapter
            adapter.submit(shown, groupPinned = !showArchive,
                pinnedLabel = getString(R.string.section_pinned), othersLabel = getString(R.string.section_others),
                attachments = store.attachmentsByNote())
            visibleCount = shown.size
        }

        findViewById<View>(R.id.empty).visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
        val noLinksAtAll = linksOnly && allLinks.isEmpty()
        findViewById<TextView>(R.id.emptyTitle).setText(
            if (noLinksAtAll) R.string.empty_links_title else if (searching) R.string.empty_search_title else if (showArchive) R.string.empty_archive_title else R.string.empty_notes_title)
        findViewById<TextView>(R.id.emptyBody).setText(
            if (noLinksAtAll) R.string.empty_links_body else if (searching) R.string.empty_search_body else if (showArchive) R.string.empty_archive_body else R.string.empty_notes_body)

        filterRow.removeAllViews()
        filterRow.addView(Chip(this).apply {
            text = getString(R.string.filter_links_count, allLinks.size); isCheckable = true; isChecked = linksOnly
            setChipIconResource(R.drawable.ic_link); isChipIconVisible = true; isCheckedIconVisible = false
            setOnClickListener { linksOnly = !linksOnly; refresh() }
        })
        filterRow.addView(Palette.swatch(this, null, colorFilter == null, getString(R.string.filter_all_colours)) { colorFilter = null; refresh() })
        Note.COLORS.forEach { c ->
            filterRow.addView(Palette.swatch(this, c, colorFilter == c, getString(R.string.filter_by_colour)) {
                colorFilter = if (colorFilter == c) null else c; refresh()
            })
        }
    }

    // ---- the links section -----------------------------------------------------------------------

    private fun showLinkMenu(e: LinkEntry, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, R.string.link_open); pm.menu.add(0, 2, 1, R.string.link_action_copy)
        pm.menu.add(0, 3, 2, R.string.link_action_share); pm.menu.add(0, 4, 3, R.string.link_action_open_note)
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> Links.open(this, e.url); 2 -> copyLink(e.url); 3 -> shareLink(e.url); 4 -> openEditor(e.note.id) }
            true
        }
        pm.show()
    }

    internal fun copyLink(url: String) {
        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("link", url))
        Snackbar.make(findViewById(R.id.mainRoot), R.string.link_copied, Snackbar.LENGTH_SHORT).setAnchorView(nav).show()
    }

    internal fun shareLink(url: String) = startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), getString(R.string.share_chooser)))

    // ---- actions with Undo ---------------------------------------------------------------------

    private fun archive(n: Note, toArchive: Boolean) {
        store.setArchived(n.id, toArchive)
        Reminders.sync(this, n.copy(archived = toArchive))
        refresh()
        Snackbar.make(findViewById(R.id.mainRoot), if (toArchive) R.string.snack_archived else R.string.snack_unarchived, Snackbar.LENGTH_LONG)
            .setAnchorView(nav)
            .setAction(R.string.undo) {
                store.setArchived(n.id, !toArchive); Reminders.sync(this, n.copy(archived = !toArchive)); refresh()
            }.show()
    }

    private fun trash(n: Note) {
        store.trash(n.id); Reminders.cancel(this, n.id); refresh()
        Snackbar.make(findViewById(R.id.mainRoot), R.string.snack_trashed, Snackbar.LENGTH_LONG).setAnchorView(nav)
            .setAction(R.string.undo) { store.restore(n.id); Reminders.sync(this, n); refresh() }.show()
    }

    private fun showNoteMenu(n: Note, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, if (n.pinned) R.string.action_unpin else R.string.action_pin)
        pm.menu.add(0, 2, 1, if (n.archived) R.string.action_unarchive else R.string.action_archive)
        pm.menu.add(0, 3, 2, R.string.action_share)
        pm.menu.add(0, 4, 3, R.string.action_delete)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { store.setPinned(n.id, !n.pinned); refresh() }
                2 -> archive(n, !n.archived)
                3 -> shareNote(this, n, store)
                4 -> trash(n)
            }
            true
        }
        pm.show()
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, R.string.menu_trash)
        pm.menu.add(0, 2, 1, R.string.menu_settings)
        pm.setOnMenuItemClickListener {
            startActivity(Intent(this, if (it.itemId == 1) TrashActivity::class.java else SettingsActivity::class.java)); true
        }
        pm.show()
    }

    // ---- save a link -----------------------------------------------------------------------------

    private fun clipboardUrl(): String? = try {
        (getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString())?.let { Links.firstUrl(it) }
    } catch (_: Exception) { null }

    /** "+ → Link": paste or type an address (optional title); a copied link is filled in for you. */
    private fun showLinkDialog() {
        val pad = Palette.dp(this, 20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, Palette.dp(context, 8), pad, 0) }
        val urlBox = com.google.android.material.textfield.TextInputLayout(this).apply { hint = getString(R.string.link_hint_url) }
        val url = com.google.android.material.textfield.TextInputEditText(urlBox.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; setSingleLine()
        }
        urlBox.addView(url)
        val titleBox = com.google.android.material.textfield.TextInputLayout(this).apply { hint = getString(R.string.link_hint_title) }
        val title = com.google.android.material.textfield.TextInputEditText(titleBox.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setSingleLine()
        }
        titleBox.addView(title)
        col.addView(urlBox); col.addView(titleBox)
        clipboardUrl()?.let { url.setText(it); url.selectAll() }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.link_dialog_title).setView(col)
            .setPositiveButton(R.string.btn_save, null).setNegativeButton(R.string.btn_cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = Links.normalize(url.text.toString())
                if (address == null) urlBox.error = getString(R.string.link_invalid)
                else { saveLink(address, title.text.toString().trim()); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun saveLink(address: String, title: String) {
        val id = store.save(Note(title = title, body = address, color = Prefs.defaultColor(this)))
        if (title.isBlank()) Links.fetchTitleAsync(this, id, address) { if (!isFinishing) refresh() }
        refresh()
        Snackbar.make(findViewById(R.id.mainRoot), R.string.link_saved, Snackbar.LENGTH_SHORT).setAnchorView(nav).show()
    }

    // ---- sort & view sheet ---------------------------------------------------------------------

    private fun showSortSheet() {
        val sheet = BottomSheetDialog(this)
        val pad = Palette.dp(this, 20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad * 2) }
        col.addView(TextView(this).apply {
            setText(R.string.sort_title); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.on_surface))
        })
        val chips = ChipGroup(this).apply { isSingleSelection = true; setPadding(0, pad / 2, 0, pad / 2) }
        SortMode.values().forEach { m ->
            chips.addView(Chip(this).apply {
                setText(m.label); isCheckable = true; isCheckedIconVisible = true
                isChecked = m == Prefs.sort(this@ListActivity)
                setOnClickListener { Prefs.setSort(this@ListActivity, m); refresh() }
            })
        }
        col.addView(chips)
        val toggle = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val outlined = com.google.android.material.R.attr.materialButtonOutlinedStyle
        val gridBtn = MaterialButton(this, null, outlined).apply { id = View.generateViewId(); setText(R.string.view_grid) }
        val listBtn = MaterialButton(this, null, outlined).apply { id = View.generateViewId(); setText(R.string.view_list) }
        toggle.addView(gridBtn); toggle.addView(listBtn)
        toggle.check(if (Prefs.grid(this)) gridBtn.id else listBtn.id)
        toggle.addOnButtonCheckedListener { _, id, checked -> if (checked) { Prefs.setGrid(this, id == gridBtn.id); refresh() } }
        col.addView(toggle)
        col.addView(TextView(this).apply {
            setText(R.string.sort_pinned_hint); textSize = 13f; setPadding(0, pad / 2, 0, 0)
            setTextColor(getColor(R.color.on_surface_variant))
        })
        sheet.setContentView(col)
        sheet.show()
    }
}
