package dev.huenotes

import android.content.Context
import android.content.Intent

/** Shares a note's text, plus its photos, drawings and voice notes, through the Android share sheet. */
fun shareNote(c: Context, n: Note, store: NoteStore) {
    val text = if (n.checklist) Checklist.parse(n.body).joinToString("\n") { (if (it.done) "☑ " else "☐ ") + it.text } else n.body
    val files = store.attachmentsFor(n.uid).mapNotNull { a -> AttachmentExport.stage(c, store, a, n.title)?.let { it to a.kind.mime } }
    val send = if (files.isEmpty()) Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, text)
    else AttachmentExport.shareIntent(files, text, n.title)
    c.startActivity(Intent.createChooser(send, c.getString(R.string.share_chooser)))
}
