package dev.huenotes

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The dialogs for choosing, creating, renaming and deleting labels, shared by the editor and the list. */
object LabelDialogs {
    /** Every label known on this phone: those on notes plus ones created but not used yet. */
    fun known(a: Activity, store: NoteStore) = Labels.all(store.allForSync(), Prefs.extraLabels(a))

    /** Tick the labels a note should have; "New label" adds one. [done] gets the chosen labels. */
    fun pick(a: Activity, store: NoteStore, current: List<String>, done: (List<String>) -> Unit) {
        val all = Labels.normalize(known(a, store) + current).sortedBy { Labels.key(it) }
        val checked = BooleanArray(all.size) { i -> current.any { Labels.same(it, all[i]) } }
        val b = MaterialAlertDialogBuilder(a).setTitle(R.string.labels_title)
            .setPositiveButton(R.string.labels_done) { _, _ -> done(all.filterIndexed { i, _ -> checked[i] }) }
            .setNeutralButton(R.string.labels_new) { _, _ ->
                ask(a, R.string.labels_new, "") { name ->
                    Prefs.addExtraLabel(a, name)
                    pick(a, store, all.filterIndexed { i, _ -> checked[i] } + name, done)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
        if (all.isEmpty()) b.setMessage(R.string.labels_none) else b.setMultiChoiceItems(all.toTypedArray(), checked) { _, i, on -> checked[i] = on }
        b.show()
    }

    /** A one-line name prompt. [done] only gets a usable name. */
    fun ask(a: Activity, title: Int, initial: String, done: (String) -> Unit) {
        val input = EditText(a).apply {
            setText(initial); setSelection(initial.length); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(Labels.MAX_LENGTH))
        }
        val box = FrameLayout(a).apply { val p = Palette.dp(a, 20); setPadding(p, p / 2, p, 0); addView(input) }
        MaterialAlertDialogBuilder(a).setTitle(title).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> Labels.clean(input.text.toString())?.let(done) }
            .setNegativeButton(R.string.btn_cancel, null).show()
        input.requestFocus()
    }

    /** Rename or delete a label everywhere (notes, saved filters). [changed] runs afterwards. */
    fun manage(a: Activity, store: NoteStore, label: String, changed: () -> Unit) {
        MaterialAlertDialogBuilder(a).setTitle(label)
            .setItems(arrayOf(a.getString(R.string.labels_rename), a.getString(R.string.labels_delete))) { _, which ->
                if (which == 0) ask(a, R.string.labels_rename, label) { typed ->
                    // Renaming onto another existing label merges them under that label's spelling.
                    val to = known(a, store).firstOrNull { !Labels.same(it, label) && Labels.same(it, typed) } ?: typed
                    store.relabel(label, to); Prefs.renameLabel(a, label, to); changed()
                } else MaterialAlertDialogBuilder(a).setMessage(a.getString(R.string.labels_delete_confirm, label))
                    .setPositiveButton(R.string.labels_delete) { _, _ -> store.relabel(label, null); Prefs.renameLabel(a, label, null); changed() }
                    .setNegativeButton(R.string.btn_cancel, null).show()
            }.show()
    }
}
