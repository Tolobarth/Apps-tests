package dev.huenotes

import android.content.Context
import android.content.Intent

fun shareNote(c: Context, n: Note) {
    val text = if (n.checklist) Checklist.parse(n.body).joinToString("\n") { (if (it.done) "☑ " else "☐ ") + it.text } else n.body
    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, text), null))
}
