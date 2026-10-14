package dev.huenotes

import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date

class NoteAdapter(private val onClick: (Note) -> Unit) : RecyclerView.Adapter<NoteAdapter.VH>() {
    var notes: List<Note> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var showTimeOnly = false

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card)
        val title: TextView = v.findViewById(R.id.title)
        val body: TextView = v.findViewById(R.id.body)
        val reminder: TextView = v.findViewById(R.id.reminder)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_note, p, false))
    override fun getItemCount() = notes.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val n = notes[i]; val c = h.itemView.context
        h.card.setCardBackgroundColor(Palette.surface(c, n.color))
        h.card.setOnClickListener { onClick(n) }
        h.title.text = n.title
        h.title.visibility = if (n.title.isBlank()) View.GONE else View.VISIBLE
        val preview = if (n.checklist) checklistPreview(c, n.body) else n.body.trim()
        h.body.text = preview
        h.body.visibility = if (preview.isEmpty()) View.GONE else View.VISIBLE
        if (n.title.isBlank() && preview.isEmpty()) { h.title.text = "(empty)"; h.title.visibility = View.VISIBLE }
        if (n.reminderAt > 0) {
            val d = Date(n.reminderAt)
            h.reminder.text = if (showTimeOnly) DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
            else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(d)
            h.reminder.visibility = View.VISIBLE
        } else h.reminder.visibility = View.GONE
    }

    private fun checklistPreview(c: android.content.Context, body: String): CharSequence {
        val dim = ContextCompat.getColor(c, R.color.on_surface_variant)
        val sb = SpannableStringBuilder()
        Checklist.parse(body).take(8).forEachIndexed { idx, item ->
            if (idx > 0) sb.append('\n')
            val start = sb.length
            sb.append(if (item.done) "☑  " else "☐  ").append(item.text)
            if (item.done) {
                sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(dim), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }
}
