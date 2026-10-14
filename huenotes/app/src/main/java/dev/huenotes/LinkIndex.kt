package dev.huenotes

/** One web address found in a note. [url] can be opened as is. */
data class LinkEntry(val url: String, val note: Note) {
    /** "example.com/path" for display. */
    val shown: String get() = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    val domain: String get() = Links.domain(url)

    /** True when this note is nothing but this link (a note made with "+ → Link"). */
    val isLinkNote: Boolean get() = Links.linkOf(note) == url

    /** The note's own name for the link: its title if it is a link note, otherwise the site. */
    val label: String get() = if (isLinkNote && note.title.isNotBlank()) note.title else domain

    /** What to show after "in": the note's title, or its first words. Empty for link notes (the note *is* the link). */
    val sourceName: String get() = if (isLinkNote) "" else Sorter.displayTitle(note)
}

/** The links section: built on the fly from the notes, so every new link appears (and every removed one disappears) by itself. */
object LinkIndex {
    /** All links in [notes] (text, lists and titles), note by note in the order given. */
    fun entries(notes: List<Note>): List<LinkEntry> =
        notes.flatMap { n -> Links.allUrls(n.title + "\n" + n.body).map { LinkEntry(it, n) } }

    /** Keeps entries whose address, site, label or note name contains [query] (ignoring case). Blank keeps everything. */
    fun filter(entries: List<LinkEntry>, query: String): List<LinkEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter { e -> listOf(e.url, e.domain, e.label, e.sourceName).any { it.contains(q, ignoreCase = true) } }
    }
}
