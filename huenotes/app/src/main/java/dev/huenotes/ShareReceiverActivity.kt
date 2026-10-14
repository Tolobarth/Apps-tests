package dev.huenotes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Target of "Share → HueNotes" in other apps: saves a shared link (or text) as a note and closes straight away. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val note = if (intent?.action == Intent.ACTION_SEND)
            ShareIntake.fromSharedText(
                intent.getStringExtra(Intent.EXTRA_TEXT),
                intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE),
                Prefs.defaultColor(this),
            ) else null
        if (note != null) {
            val id = NoteStore(this).save(note)
            Toast.makeText(this, R.string.share_saved, Toast.LENGTH_SHORT).show()
            if (note.title.isBlank()) Links.linkOf(note.body)?.let { Links.fetchTitleAsync(this, id, it) }
        }
        finish()
    }
}
