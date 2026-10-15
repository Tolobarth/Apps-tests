package dev.huenotes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Target of "Share → HueNotes" in other apps: saves a shared link, text, photos, PDFs or other files as one note and closes.
 * Files are copied before the screen closes, while the sending app's read permission is certain to hold.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val i = intent
        if (s != null || (i?.action != Intent.ACTION_SEND && i?.action != Intent.ACTION_SEND_MULTIPLE)) { finish(); return }
        val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: i.getStringExtra(Intent.EXTRA_TITLE)
        val uris = SharedFiles.streams(i)
        val color = Prefs.defaultColor(this)
        val app = applicationContext
        Thread {
            val store = NoteStore(app)
            val r = try { SharedFiles.receive(app, store, text, subject, color, uris.map { SharedFiles.describe(contentResolver, it, i.type) }) }
            catch (e: Exception) { SharedFiles.Result(null, 0, uris.size) }
            runOnUiThread {
                val msg = when {
                    r.note == null -> if (uris.isNotEmpty()) R.string.share_failed else null
                    r.failed > 0 -> R.string.share_some_failed
                    else -> R.string.share_saved
                }
                msg?.let { Toast.makeText(app, it, if (r.failed > 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
                r.note?.let { n -> if (n.title.isBlank()) Links.linkOf(n.body)?.let { Links.fetchTitleAsync(app, n.id, it) } }
                finish()
            }
        }.start()
    }
}
