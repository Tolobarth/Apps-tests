package dev.huenotes

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LinkAdapter(
    private val onOpen: (LinkEntry) -> Unit,
    private val onMenu: (LinkEntry, View) -> Unit,
) : RecyclerView.Adapter<LinkAdapter.VH>() {

    var entries: List<LinkEntry> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val badge: ImageView = v.findViewById(R.id.badge)
        val title: TextView = v.findViewById(R.id.title)
        val url: TextView = v.findViewById(R.id.url)
        val source: TextView = v.findViewById(R.id.source)
        val more: View = v.findViewById(R.id.more)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_link, p, false))
    override fun getItemCount() = entries.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val e = entries[i]; val c = h.itemView.context
        h.badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.surface(c, e.note.color)) }
        h.badge.setColorFilter(c.getColor(R.color.note_text))
        h.title.text = e.label
        h.url.text = e.shown
        val source = e.sourceName
        if (source.isEmpty()) h.source.visibility = View.GONE
        else { h.source.text = c.getString(R.string.link_in_note, source); h.source.visibility = View.VISIBLE }
        h.itemView.setOnClickListener { onOpen(e) }
        h.more.setOnClickListener { onMenu(e, it) }
    }
}
