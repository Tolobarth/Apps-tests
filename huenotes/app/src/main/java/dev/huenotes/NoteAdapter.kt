package dev.huenotes

import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date

class NoteAdapter(
    private val onClick: (Note) -> Unit,
    private val onLong: (Note, View) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface Row {
        data class Header(val text: String) : Row
        data class Item(val note: Note) : Row
    }

    private var rows: List<Row> = emptyList()
    var showTimeOnly = false
    var storage: AttachmentStorage? = null
    private var attachments: Map<String, List<Attachment>> = emptyMap()

    /** With [groupPinned], pinned notes get a "Pinned" header and the rest an "Others" header. */
    fun submit(
        notes: List<Note>, groupPinned: Boolean = false, pinnedLabel: String = "", othersLabel: String = "",
        attachments: Map<String, List<Attachment>> = emptyMap(),
    ) {
        this.attachments = attachments
        val out = ArrayList<Row>()
        val pinned = if (groupPinned) notes.filter { it.pinned } else emptyList()
        val rest = if (groupPinned) notes.filterNot { it.pinned } else notes
        if (pinned.isNotEmpty()) {
            out += Row.Header(pinnedLabel); pinned.forEach { out += Row.Item(it) }
            if (rest.isNotEmpty()) out += Row.Header(othersLabel)
        }
        rest.forEach { out += Row.Item(it) }
        rows = out
        notifyDataSetChanged()
    }

    fun noteAt(pos: Int): Note? = (rows.getOrNull(pos) as? Row.Item)?.note

    class NoteVH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card)
        val thumb: android.widget.ImageView = v.findViewById(R.id.thumb)
        val content: View = v.findViewById(R.id.content)
        val audio: TextView = v.findViewById(R.id.audio)
        val files: TextView = v.findViewById(R.id.files)
        val labels: TextView = v.findViewById(R.id.labels)
        val link: TextView = v.findViewById(R.id.link)
        val title: TextView = v.findViewById(R.id.title)
        val body: TextView = v.findViewById(R.id.body)
        val progress: TextView = v.findViewById(R.id.progress)
        val reminder: TextView = v.findViewById(R.id.reminder)
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) { val text: TextView = v as TextView }

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(p.context)
        return if (t == 0) HeaderVH(inf.inflate(R.layout.item_header, p, false)) else NoteVH(inf.inflate(R.layout.item_note, p, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
        when (val row = rows[i]) {
            is Row.Header -> {
                (h as HeaderVH).text.text = row.text.uppercase()
                (h.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = true
            }
            is Row.Item -> bindNote(h as NoteVH, row.note)
        }
    }

    private fun bindNote(h: NoteVH, n: Note) {
        val c = h.itemView.context
        val s = Prefs.fontScale(c)
        h.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * s)
        h.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * s)
        h.card.setCardBackgroundColor(Palette.surface(c, n.color))
        h.card.setOnClickListener { onClick(n) }
        h.card.setOnLongClickListener { onLong(n, h.card); true }
        h.title.text = if (n.pinned && n.title.isNotBlank()) "📌 ${n.title}" else n.title
        h.title.visibility = if (n.title.isBlank()) View.GONE else View.VISIBLE
        val items = if (n.checklist) Checklist.parse(n.body) else emptyList()
        val linkUrl = Links.linkOf(n)
        val preview = if (n.checklist) checklistPreview(c, items) else if (linkUrl != null) Links.restOf(n.body) else n.body.trim()
        if (linkUrl != null) {
            h.link.text = Links.domain(linkUrl); h.link.visibility = View.VISIBLE
            h.link.setOnClickListener { Links.open(c, linkUrl) }
        } else { h.link.visibility = View.GONE; h.link.setOnClickListener(null); h.link.isClickable = false }
        h.body.text = preview
        h.body.visibility = if (preview.isEmpty()) View.GONE else View.VISIBLE
        val atts = attachments[n.uid].orEmpty()
        if (n.title.isBlank() && preview.isEmpty() && atts.isEmpty() && linkUrl == null) { h.title.text = c.getString(R.string.empty_note); h.title.visibility = View.VISIBLE }
        val visual = atts.firstOrNull { it.kind == AttachmentKind.IMAGE || it.kind == AttachmentKind.DRAWING }
            ?: atts.firstOrNull { it.kind == AttachmentKind.FILE && it.mimeType == "application/pdf" }
        val bmp = visual?.let { v -> storage?.let { AttachmentFiles.preview(it, v, 480) } }
        if (bmp != null) { h.thumb.setImageBitmap(bmp); h.thumb.visibility = View.VISIBLE } else { h.thumb.setImageDrawable(null); h.thumb.visibility = View.GONE }
        val audios = atts.filter { it.kind == AttachmentKind.AUDIO }
        if (audios.isNotEmpty()) {
            h.audio.text = AttachmentFiles.duration(audios.sumOf { it.durationMs }) + if (audios.size > 1) " (×${audios.size})" else ""
            h.audio.visibility = View.VISIBLE
        } else h.audio.visibility = View.GONE
        val files = atts.filter { it.kind == AttachmentKind.FILE }
        if (files.isNotEmpty()) {
            h.files.text = files.singleOrNull()?.name?.ifBlank { null } ?: c.resources.getQuantityString(R.plurals.card_files, files.size, files.size)
            h.files.visibility = View.VISIBLE
        } else h.files.visibility = View.GONE
        h.labels.text = n.labels.joinToString(" · ")
        h.labels.visibility = if (n.labels.isEmpty()) View.GONE else View.VISIBLE
        if (items.any { it.text.isNotBlank() }) {
            val (total, done) = Checklist.counts(items)
            h.progress.text = c.getString(R.string.checklist_progress, done, total)
            h.progress.visibility = View.VISIBLE
        } else h.progress.visibility = View.GONE
        if (n.reminderAt > 0) {
            val d = Date(n.reminderAt)
            h.reminder.text = if (showTimeOnly) DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
            else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(d)
            h.reminder.visibility = View.VISIBLE
        } else h.reminder.visibility = View.GONE
        val anyText = listOf(h.title, h.body, h.link, h.progress, h.audio, h.files, h.labels, h.reminder).any { it.visibility == View.VISIBLE }
        h.content.visibility = if (anyText) View.VISIBLE else View.GONE
    }

    private fun checklistPreview(c: android.content.Context, items: List<Checklist.Item>): CharSequence {
        val dim = ContextCompat.getColor(c, R.color.on_surface_variant)
        val sb = SpannableStringBuilder()
        items.take(8).forEachIndexed { idx, item ->
            if (idx > 0) sb.append('\n')
            if (item.level == 1) sb.append("\u2003\u2003")
            val start = sb.length          // the strike-through begins after the indent, not inside it
            sb.append(if (item.done) "☑  " else "☐  ").append(item.text)
            if (item.done) {
                sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(dim), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }
}
