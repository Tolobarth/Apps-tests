package dev.huenotes

import androidx.annotation.StringRes

enum class SortMode(@StringRes val label: Int) {
    MODIFIED(R.string.sort_modified),
    CREATED(R.string.sort_created),
    TITLE(R.string.sort_title_az),
    COLOUR(R.string.sort_colour),
    REMINDER(R.string.sort_reminder),
}

object Sorter {
    fun displayTitle(n: Note) = n.title.ifBlank { n.body.trim().lines().firstOrNull().orEmpty() }

    /** Pinned notes always come first, then the chosen order. */
    fun sort(notes: List<Note>, mode: SortMode): List<Note> {
        val base: Comparator<Note> = when (mode) {
            SortMode.MODIFIED -> compareByDescending { it.modifiedAt }
            SortMode.CREATED -> compareByDescending { it.createdAt }
            SortMode.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { displayTitle(it) }
            SortMode.COLOUR -> compareBy<Note> { n -> Note.COLORS.indexOf(n.color).let { if (it < 0) 99 else it } }
                .thenByDescending { it.modifiedAt }
            SortMode.REMINDER -> compareBy<Note> { if (it.reminderAt > 0) 0 else 1 }
                .thenBy { if (it.reminderAt > 0) it.reminderAt else 0L }
                .thenByDescending { it.modifiedAt }
        }
        return notes.sortedWith(compareByDescending<Note> { it.pinned }.then(base))
    }
}
