package dev.huenotes

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*

class ListActivity : GatedActivity() {
    private lateinit var store: NoteStore
    private lateinit var list: ListView
    private lateinit var search: EditText
    private var showArchive = false
    private var notes: List<Note> = emptyList()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = NoteStore(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        search = EditText(this).apply {
            hint = "Search"; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) = refresh()
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
            })
        }
        list = ListView(this).apply {
            setOnItemClickListener { _, _, pos, _ -> openEditor(notes[pos].id) }
        }
        val add = Button(this).apply { text = "+ New note"; setOnClickListener { openEditor(0) } }
        root.addView(search); root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(add)
        setContentView(root)
    }

    override fun onUnlocked() = refresh()

    private fun openEditor(id: Long) = startActivity(Intent(this, EditorActivity::class.java).putExtra("id", id))

    private fun refresh() {
        title = if (showArchive) "Archive" else "HueNotes"
        notes = store.list(showArchive, search.text.toString())
        list.adapter = object : BaseAdapter() {
            override fun getCount() = notes.size
            override fun getItem(p: Int) = notes[p]
            override fun getItemId(p: Int) = notes[p].id
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val n = notes[p]
                val row = LinearLayout(this@ListActivity).apply { setPadding(0, 8, 16, 8) }
                row.addView(View(this@ListActivity).apply { setBackgroundColor(n.color) }, LinearLayout.LayoutParams(24, -1))
                val text = TextView(this@ListActivity).apply {
                    setPadding(24, 0, 0, 0)
                    val preview = n.body.lines().take(2).joinToString("\n") {
                        if (n.checklist) it.replace("[x] ", "☑ ").replace("[ ] ", "☐ ") else it
                    }
                    text = (n.title.ifBlank { "(untitled)" } + "\n" + preview).trim()
                }
                row.addView(text)
                return row
            }
        }
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        m.add(0, 1, 0, "Archive / Notes")
        m.add(0, 2, 1, "Calendar / Today")
        return true
    }

    override fun onPrepareOptionsMenu(m: Menu): Boolean {
        m.removeGroup(1)
        if (!Lock.isSet(this)) m.add(1, 3, 2, "Set password")
        else { m.add(1, 4, 2, "Change password"); m.add(1, 5, 3, "Remove password") }
        return true
    }

    override fun onOptionsItemSelected(i: MenuItem): Boolean {
        when (i.itemId) {
            1 -> { showArchive = !showArchive; refresh() }
            2 -> startActivity(Intent(this, DayActivity::class.java))
            3 -> Lock.setupFlow(this) { Widgets.refresh(this) }
            4 -> Lock.changeFlow(this)
            5 -> Lock.removeFlow(this)
            else -> return super.onOptionsItemSelected(i)
        }
        return true
    }
}
