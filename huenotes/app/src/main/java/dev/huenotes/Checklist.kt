package dev.huenotes

/**
 * Checklist bodies are plain text, one item per line: "[x] done" / "[ ] todo"; unprefixed lines are open items.
 * A line indented by spaces is a sub-item (one level only): "  [ ] Croissants". Because this is just text, older lists,
 * backups, sync and sharing need no changes.
 *
 * Rules: a parent with sub-items is done exactly when all its (non-blank) sub-items are; ticking a parent ticks them all.
 */
object Checklist {
    data class Item(val done: Boolean, val text: String, val level: Int = 0)

    private const val INDENT = "  "

    fun parse(body: String): List<Item> = normalize(body.lines().filter { it.isNotBlank() }.map { line ->
        val level = if (line.first().isWhitespace()) 1 else 0
        val t = line.trim()
        when {
            t.startsWith("[x] ") -> Item(true, t.removePrefix("[x] ").trim(), level)
            t.startsWith("[ ] ") -> Item(false, t.removePrefix("[ ] ").trim(), level)
            t == "[x]" -> Item(true, "", level)
            t == "[ ]" -> Item(false, "", level)
            else -> Item(false, t, level)
        }
    })

    /** Blank rows are dropped; sub-items keep their two-space indent. */
    fun serialize(items: List<Item>): String =
        normalize(items.filter { it.text.isNotBlank() }).joinToString("\n") { INDENT.repeat(it.level) + (if (it.done) "[x] " else "[ ] ") + it.text }

    /** For turning a list into a plain text note: structure survives as indentation. */
    fun toPlain(items: List<Item>): String = items.joinToString("\n") { INDENT.repeat(it.level) + it.text }

    /** One level only, the first row can't be a sub-item, and parents follow their sub-items. */
    fun normalize(items: List<Item>): List<Item> =
        reconcile(items.mapIndexed { i, it -> it.copy(level = if (i == 0) 0 else it.level.coerceIn(0, 1)) })

    /** Index just after the last sub-item of the group that starts at [i] (for a sub-item, just after itself). */
    fun groupEnd(items: List<Item>, i: Int): Int {
        var j = i + 1
        if (items[i].level == 0) while (j < items.size && items[j].level == 1) j++
        return j
    }

    private fun kids(items: List<Item>, i: Int): List<Int> =
        if (items[i].level != 0) emptyList() else (i + 1 until groupEnd(items, i)).filter { items[it].text.isNotBlank() }

    /** True when sub-items sit under this row (even a blank one just added). */
    fun isParent(items: List<Item>, i: Int) = items[i].level == 0 && i + 1 < items.size && items[i + 1].level == 1

    /** Some, but not all, of the parent's sub-items are done: shown as a dash. */
    fun half(items: List<Item>, i: Int): Boolean {
        val k = kids(items, i)
        return k.isNotEmpty() && k.any { items[it].done } && !k.all { items[it].done }
    }

    fun reconcile(items: List<Item>): List<Item> {
        val out = items.toMutableList()
        for (i in out.indices) if (out[i].level == 0) {
            val k = kids(out, i)
            if (k.isNotEmpty()) out[i] = out[i].copy(done = k.all { out[it].done })
        }
        return out
    }

    /** The result of ticking or unticking row [index]. */
    fun tick(items: List<Item>, index: Int, checked: Boolean): List<Item> {
        val out = items.toMutableList()
        out[index] = out[index].copy(done = checked)
        if (out[index].level == 0) kids(out, index).forEach { out[it] = out[it].copy(done = checked) }
        return reconcile(out)
    }

    /** Tucks a top-level row (with its own sub-items, flattened) under the row above. */
    fun indent(items: List<Item>, index: Int): List<Item> {
        if (index <= 0 || items[index].level == 1) return items
        val out = items.toMutableList()
        for (j in index until groupEnd(items, index)) out[j] = out[j].copy(level = 1)
        return normalize(out)
    }

    fun outdent(items: List<Item>, index: Int): List<Item> =
        if (items[index].level == 0) items else normalize(items.toMutableList().also { it[index] = it[index].copy(level = 0) })

    /** Removes a row. A parent's sub-items are kept: they become top-level rows. The last remaining row can't be removed. */
    fun remove(items: List<Item>, index: Int): List<Item> {
        if (items.size <= 1) return items
        val out = items.toMutableList()
        if (out[index].level == 0) for (j in index + 1 until groupEnd(items, index)) out[j] = out[j].copy(level = 0)
        out.removeAt(index)
        return normalize(out)
    }

    /** Enter: a sub-item gets a sibling; a top-level row gets a new top-level row after its whole group. Returns the list and the new row's index. */
    fun insertBelow(items: List<Item>, index: Int): Pair<List<Item>, Int> {
        val level = items[index].level
        val pos = if (level == 0) groupEnd(items, index) else index + 1
        val out = items.toMutableList().also { it.add(pos, Item(false, "", level)) }
        return reconcile(out) to pos
    }

    /** (all rows with text, how many of those are done) */
    fun counts(items: List<Item>): Pair<Int, Int> = items.filter { it.text.isNotBlank() }.let { it.size to it.count { i -> i.done } }
}
