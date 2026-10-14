package dev.huenotes

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class TrashActivity : GatedActivity() {
    private lateinit var store: NoteStore
    private lateinit var adapter: NoteAdapter

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = NoteStore(this)
        setContentView(R.layout.activity_trash)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.setNavigationOnClickListener { finish() }
        tb.menu.add(0, 1, 0, R.string.action_empty_trash).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        tb.setOnMenuItemClickListener { confirmEmpty(); true }

        adapter = NoteAdapter({ choose(it) })
        adapter.storage = store.storage
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                adapter.noteAt(vh.bindingAdapterPosition)?.let { restore(it, withUndo = true) } ?: refresh()
            }
        }).attachToRecyclerView(recycler)
    }

    override fun onUnlocked() {
        store.purgeTrash(System.currentTimeMillis() - NoteStore.TRASH_RETENTION_MS)
        refresh()
    }

    private fun refresh() {
        val notes = store.trashed()
        adapter.submit(notes, attachments = store.attachmentsByNote())
        findViewById<View>(R.id.empty).visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.banner).visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun restore(n: Note, withUndo: Boolean) {
        store.restore(n.id)
        store.get(n.id)?.let { Reminders.sync(this, it) }
        refresh()
        val bar = Snackbar.make(findViewById(R.id.recycler), R.string.snack_restored, Snackbar.LENGTH_LONG)
        if (withUndo) bar.setAction(R.string.undo) { store.trash(n.id); Reminders.cancel(this, n.id); refresh() }
        bar.show()
    }

    private fun choose(n: Note) {
        MaterialAlertDialogBuilder(this)
            .setTitle(Sorter.displayTitle(n).ifBlank { getString(R.string.untitled) })
            .setItems(arrayOf(getString(R.string.action_restore), getString(R.string.action_delete_forever))) { _, i ->
                if (i == 0) restore(n, withUndo = false) else confirmForever(n)
            }.show()
    }

    private fun confirmForever(n: Note) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.dialog_forever_title).setMessage(R.string.dialog_forever_msg)
            .setPositiveButton(R.string.action_delete_forever) { _, _ -> store.delete(n.id); refresh() }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun confirmEmpty() {
        if (store.trashed().isEmpty()) return
        MaterialAlertDialogBuilder(this).setTitle(R.string.dialog_empty_title).setMessage(R.string.dialog_empty_msg)
            .setPositiveButton(R.string.action_empty_trash) { _, _ -> store.emptyTrash(); refresh() }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }
}
