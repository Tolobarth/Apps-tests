package dev.huenotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class ListActivity : GatedActivity() {
    private lateinit var store: NoteStore
    private lateinit var adapter: NoteAdapter
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

        adapter = NoteAdapter { openEditor(it.id) }
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            this.adapter = this@ListActivity.adapter
        }
        search = findViewById(R.id.search)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = refresh()
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
        })
        filterRow = findViewById(R.id.filterRow)
        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener { openEditor(0) }
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
    }

    override fun onUnlocked() = refresh()

    private fun openEditor(id: Long) = startActivity(Intent(this, EditorActivity::class.java).putExtra("id", id))

    private fun refresh() {
        findViewById<TextView>(R.id.screenTitle).text = if (showArchive) "Archive" else "Notes"
        findViewById<View>(R.id.fab).visibility = if (showArchive) View.GONE else View.VISIBLE
        val all = store.list(showArchive, search.text.toString())
        val shown = colorFilter?.let { f -> all.filter { it.color == f } } ?: all
        adapter.notes = shown

        val empty = findViewById<View>(R.id.empty)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        val searching = search.text.isNotBlank() || colorFilter != null
        findViewById<TextView>(R.id.emptyTitle).text =
            if (searching) "No matches" else if (showArchive) "Archive is empty" else "No notes yet"
        findViewById<TextView>(R.id.emptyBody).text =
            if (searching) "Try a different search or colour." else if (showArchive) "Archived notes show up here."
            else "Tap “New note” to capture your first thought."

        filterRow.removeAllViews()
        filterRow.addView(Palette.swatch(this, null, colorFilter == null, "All colours") { colorFilter = null; refresh() })
        Note.COLORS.forEach { c ->
            filterRow.addView(Palette.swatch(this, c, colorFilter == c, "Filter by colour") {
                colorFilter = if (colorFilter == c) null else c; refresh()
            })
        }
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        if (!Lock.isSet(this)) pm.menu.add(0, 3, 0, "Set password")
        else { pm.menu.add(0, 4, 0, "Change password"); pm.menu.add(0, 5, 1, "Remove password") }
        pm.menu.add(0, 6, 2, "Export backup")
        pm.menu.add(0, 7, 3, "Import backup")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                3 -> Lock.setupFlow(this) { Widgets.refresh(this) }
                4 -> Lock.changeFlow(this)
                5 -> Lock.removeFlow(this)
                6 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "huenotes-backup.json"), REQ_EXPORT)
                7 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), REQ_IMPORT)
            }
            true
        }
        pm.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri: Uri = data?.data ?: return
        if (res != RESULT_OK) return
        try {
            when (req) {
                REQ_EXPORT -> { Backup.export(this, store, uri); toast("Backup saved") }
                REQ_IMPORT -> { toast("Imported ${Backup.import(this, store, uri)} notes"); refresh() }
            }
        } catch (e: Exception) { toast("Failed: ${e.message}") }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        nav.menu.findItem(if (showArchive) R.id.nav_archive else R.id.nav_notes).isChecked = true
    }

    private companion object { const val REQ_EXPORT = 10; const val REQ_IMPORT = 11 }
}
