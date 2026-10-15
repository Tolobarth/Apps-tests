package dev.huenotes

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Every note as a Markdown file in a zip, with pictures, recordings and files in `attachments/` linked from the note. */
object MarkdownExport {
    fun markdown(n: Note, atts: List<Attachment>): String {
        val sb = StringBuilder()
        if (n.title.isNotBlank()) sb.append("# ").append(n.title).append("\n\n")
        if (n.labels.isNotEmpty()) sb.append("Labels: ").append(n.labels.joinToString(", ")).append("\n\n")
        if (n.checklist) Checklist.parse(n.body).forEach { sb.append("  ".repeat(it.level)).append(if (it.done) "- [x] " else "- [ ] ").append(it.text).append('\n') }
        else sb.append(n.body.trimEnd()).append('\n')
        if (atts.isNotEmpty()) {
            sb.append('\n')
            atts.forEach { a ->
                val path = "attachments/" + a.fileName
                sb.append(when (a.kind) {
                    AttachmentKind.AUDIO -> "[Audio recording]($path)\n"
                    AttachmentKind.FILE -> "[" + a.name.ifBlank { "File" }.replace("[", "\\[").replace("]", "\\]") + "]($path)\n"
                    else -> "![]($path)\n"
                })
            }
        }
        return sb.toString()
    }

    fun fileName(n: Note, taken: MutableSet<String>): String {
        val raw = n.title.ifBlank { n.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
        val base = raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().take(60).trim('.', ' ').ifEmpty { "Note" }
        var name = "$base.md"; var i = 2
        while (!taken.add(name.lowercase())) name = "$base ($i).md".also { i++ }
        return name
    }

    fun write(store: NoteStore, out: OutputStream) {
        val notes = store.list(false) + store.list(true)
        val atts = store.attachmentsByNote()
        val taken = HashSet<String>()
        ZipOutputStream(out).use { z ->
            val written = HashSet<String>()
            for (n in notes) {
                val a = atts[n.uid].orEmpty().filter { store.storage.file(it).exists() }
                z.putNextEntry(ZipEntry(fileName(n, taken))); z.write(markdown(n, a).toByteArray()); z.closeEntry()
                for (x in a) if (written.add(x.fileName)) {
                    z.putNextEntry(ZipEntry("attachments/" + x.fileName)); store.storage.file(x).inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
        }
    }
}
