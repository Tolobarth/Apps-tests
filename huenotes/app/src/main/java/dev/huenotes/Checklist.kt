package dev.huenotes

/** Checklist bodies are plain text, one item per line: "[x] done" / "[ ] todo". Unprefixed lines are open items. */
object Checklist {
    data class Item(val done: Boolean, val text: String)

    fun parse(body: String): List<Item> = body.lines().filter { it.isNotBlank() }.map {
        when {
            it.startsWith("[x] ") -> Item(true, it.removePrefix("[x] "))
            it.startsWith("[ ] ") -> Item(false, it.removePrefix("[ ] "))
            else -> Item(false, it)
        }
    }

    fun serialize(items: List<Item>): String =
        items.filter { it.text.isNotBlank() || it.done }.joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text }

    fun toPlain(items: List<Item>): String = items.joinToString("\n") { it.text }
}
