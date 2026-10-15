package dev.huenotes

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Labels are plain names kept on each note, so they travel with it through sync, backup and restore. Names are compared
 * without regard to case ("Work" and "work" are one label); the first spelling seen is kept.
 */
object Labels {
    const val MAX_LENGTH = 30

    /** A usable label name, or null: trimmed, single spaces, no line breaks, at most [MAX_LENGTH] characters. */
    fun clean(raw: String): String? = raw.replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH).trim().ifEmpty { null }

    fun key(name: String) = name.lowercase(Locale.ROOT)

    fun same(a: String, b: String) = key(a) == key(b)

    /** Cleaned, without repeats (case-insensitive), order kept. */
    fun normalize(names: List<String>): List<String> {
        val seen = HashSet<String>()
        return names.mapNotNull { clean(it) }.filter { seen.add(key(it)) }
    }

    /** Stored in the database as one name per line. */
    fun encode(names: List<String>) = normalize(names).joinToString("\n")
    fun decode(stored: String?): List<String> = if (stored.isNullOrEmpty()) emptyList() else normalize(stored.split('\n'))

    fun has(n: Note, label: String) = n.labels.any { same(it, label) }

    /** Every label in use, by name. [extra] adds labels created but not used yet. */
    fun all(notes: List<Note>, extra: List<String> = emptyList()): List<String> =
        normalize(notes.flatMap { it.labels } + extra).sortedBy { key(it) }

    /** Renaming onto a label the note already has merges the two, keeping the existing spelling. */
    fun rename(names: List<String>, from: String, to: String): List<String> {
        if (names.none { same(it, from) }) return names
        val others = names.filterNot { same(it, from) }
        return if (others.any { same(it, to) }) others else normalize(names.map { if (same(it, from)) to else it })
    }
    fun remove(names: List<String>, label: String): List<String> = names.filterNot { same(it, label) }

    fun toJson(names: List<String>) = JSONArray(names)
    fun fromJson(o: JSONObject): List<String> = o.optJSONArray("labels")?.let { a -> normalize((0 until a.length()).map { a.optString(it) }) } ?: emptyList()
}

/** What kind of content a note has, for the type chips in the list. */
enum class NoteType(val icon: Int, val title: Int) {
    PHOTOS(R.drawable.ic_image, R.string.type_photos), VOICE(R.drawable.ic_mic, R.string.type_voice),
    LISTS(R.drawable.ic_checklist, R.string.type_lists), FILES(R.drawable.ic_file, R.string.type_files);

    fun matches(n: Note, atts: List<Attachment>) = when (this) {
        PHOTOS -> atts.any { it.kind == AttachmentKind.IMAGE || it.kind == AttachmentKind.DRAWING || (it.kind == AttachmentKind.FILE && it.mimeType.startsWith("image/")) }
        VOICE -> atts.any { it.kind == AttachmentKind.AUDIO || (it.kind == AttachmentKind.FILE && it.mimeType.startsWith("audio/")) }
        LISTS -> n.checklist
        FILES -> atts.any { it.kind == AttachmentKind.FILE }
    }

    companion object { fun of(name: String?) = values().firstOrNull { it.name == name } }
}

/** A named combination of search text, colour, label and type, shown as a chip at the top of the list. Kept on this phone. */
data class SavedFilter(val name: String, val query: String = "", val color: Int? = null, val label: String? = null, val type: NoteType? = null) {
    fun matches(n: Note, atts: List<Attachment> = emptyList()) = (color == null || n.color == color) && (label == null || Labels.has(n, label)) &&
        (type == null || type.matches(n, atts)) &&
        (query.isBlank() || listOf(n.title, n.body).any { it.contains(query.trim(), ignoreCase = true) } || n.labels.any { it.contains(query.trim(), ignoreCase = true) })

    fun toJson(): JSONObject = JSONObject().put("name", name).put("query", query).apply {
        color?.let { put("color", it) }; label?.let { put("label", it) }; type?.let { put("type", it.name) }
    }

    companion object {
        fun fromJson(o: JSONObject) = SavedFilter(o.optString("name"), o.optString("query"),
            if (o.has("color")) o.optInt("color") else null, o.optString("label").ifEmpty { null }, NoteType.of(o.optString("type")))

        fun listToJson(l: List<SavedFilter>) = JSONArray(l.map { it.toJson() }).toString()
        fun listFromJson(s: String): List<SavedFilter> = runCatching {
            JSONArray(s).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.filter { it.name.isNotBlank() } }
        }.getOrDefault(emptyList())
    }
}
