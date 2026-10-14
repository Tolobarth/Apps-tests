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
    private lateinit var layoutManager: StaggeredGridLayoutManager
    private lateinit var search: EditText
    private lateinit var filterRow: LinearLayout
    private lateinit var nav: BottomNavigationView
    private var showArchive = false
    private var colorFilter: Int? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = NoteStore(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        setContentView(R.layout.activity_main)

        adapter = NoteAdapter({ openEditor(it.id) }, { n, v -> showNoteMenu(n, v) })
        layoutManager = StaggeredGridLayoutManager(if (Prefs.grid(this)) 2 else 1, StaggeredGridLayoutManager.VERTICAL)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (adapter.noteAt(vh.bindingAdapterPosition) == null) 0 else super.getSwipeDirs(rv, vh)
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
        findViewById<View>(R.id.fab).setOnClickListener { showAddSheet() }
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
        Snapshots.maybeRun(this, store)
        refresh()
        SyncManager.syncIfDue(this) { if (!isFinishing) refresh() }
    }

    override fun onResume() {
        super.onResume()
        nav.menu.findItem(if (showArchive) R.id.nav_archive else R.id.nav_notes).isChecked = true
    }

    private fun openEditor(id: Long, asList: Boolean = false) =
        startActivity(Intent(this, EditorActivity::class.java).putExtra("id", id).putExtra("checklist", asList))

    /** "+" opens a small chooser: a text note or a checklist. */
    private fun showAddSheet() {
        val sheet = BottomSheetDialog(this)
        val pad = Palette.dp(this, 20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        col.addView(TextView(this).apply {
            setText(R.string.add_title); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.on_surface)); setPadding(0, 0, 0, pad / 2)
        })
        val ripple = android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        fun option(icon: Int, label: Int, asList: Boolean) = col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = Palette.dp(context, 56); setBackgroundResource(ripple); isClickable = true
            addView(android.widget.ImageView(context).apply {
                setImageResource(icon); setColorFilter(getColor(R.color.on_surface))
                layoutParams = LinearLayout.LayoutParams(Palette.dp(context, 24), Palette.dp(context, 24)).apply { marginEnd = Palette.dp(context, 20) }
            })
            addView(TextView(context).apply { setText(label); textSize = 17f; setTextColor(getColor(R.color.on_surface)) })
            setOnClickListener { sheet.dismiss(); openEditor(0, asList) }
        })
        option(R.drawable.ic_notes, R.string.add_text, false)
        option(R.drawable.ic_checklist, R.string.add_list, true)
        sheet.setContentView(col)
        sheet.show()
    }

    private fun refresh() {
        findViewById<TextView>(R.id.screenTitle).setText(if (showArchive) R.string.nav_archive else R.string.nav_notes)
        findViewById<View>(R.id.fab).visibility = if (showArchive) View.GONE else View.VISIBLE
        layoutManager.spanCount = if (Prefs.grid(this)) 2 else 1
        val all = store.list(showArchive, search.text.toString())
        val shown = Sorter.sort(colorFilter?.let { f -> all.filter { it.color == f } } ?: all, Prefs.sort(this))
        adapter.submit(shown, groupPinned = !showArchive,
            pinnedLabel = getString(R.string.section_pinned), othersLabel = getString(R.string.section_others))

        findViewById<View>(R.id.empty).visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        val searching = search.text.isNotBlank() || colorFilter != null
        findViewById<TextView>(R.id.emptyTitle).setText(
            if (searching) R.string.empty_search_title else if (showArchive) R.string.empty_archive_title else R.string.empty_notes_title)
        findViewById<TextView>(R.id.emptyBody).setText(
            if (searching) R.string.empty_search_body else if (showArchive) R.string.empty_archive_body else R.string.empty_notes_body)

        filterRow.removeAllViews()
        filterRow.addView(Palette.swatch(this, null, colorFilter == null, getString(R.string.filter_all_colours)) { colorFilter = null; refresh() })
        Note.COLORS.forEach { c ->
            filterRow.addView(Palette.swatch(this, c, colorFilter == c, getString(R.string.filter_by_colour)) {
                colorFilter = if (colorFilter == c) null else c; refresh()
            })
        }
    }

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
                3 -> shareNote(this, n)
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
