package dev.huenotes

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * On-device translation of notes with Google's ML Kit: each language pack (about 30 MB) is downloaded once, when first
 * needed, and translation then runs on the phone. Links, blank lines, indentation and checklist structure are kept.
 */
object Translate {
    /** What the editor needs from a translation engine; tests swap in a fake. Callbacks arrive on the main thread. */
    interface Engine {
        fun languages(): List<String>
        /** The language [text] is written in, or null when unsure. */
        fun detect(text: String, done: (String?) -> Unit)
        fun downloaded(done: (Set<String>) -> Unit)
        /** Downloads whatever [from] → [to] still needs (nothing if both are there). [done] gets null, or why it failed. */
        fun download(from: String, to: String, done: (String?) -> Unit)
        /** Translates each piece; null on failure. */
        fun translate(from: String, to: String, pieces: List<String>, done: (List<String>?) -> Unit)
        fun delete(lang: String, done: (Boolean) -> Unit)
    }

    var engine: Engine = MlKit

    /** A language name in the app's language, capitalised for lists ("French", "Français"). */
    fun displayName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /** The app's language as a translation code, if it is one. */
    fun appLanguage(c: Context): String? = TranslateLanguage.fromLanguageTag(Speech.language(c))

    /** Target choices: the app's language, then recent ones, then the rest by name. [source] itself is left out. */
    fun targets(all: List<String>, source: String?, app: String?, recent: List<String>): List<String> {
        val first = (listOfNotNull(app) + recent).filter { it in all }.distinct()
        val rest = (all - first.toSet()).sortedBy { displayName(it).lowercase() }
        return (first + rest).filter { it != source }
    }

    // ---- keeping the shape of a note ----------------------------------------------------------

    /** A run of text: translated when [translate], kept as is otherwise (links, whitespace, line breaks). */
    data class Piece(val text: String, val translate: Boolean)

    private val KEEP = Regex("""(?:https?://|www\.)\S+|\n|^[ \t]+|[ \t]+$""", RegexOption.MULTILINE)

    /** Splits [text] so that only real words go to the translator. */
    fun pieces(text: String): List<Piece> {
        val out = ArrayList<Piece>()
        var at = 0
        for (m in KEEP.findAll(text)) {
            if (m.range.first > at) out += Piece(text.substring(at, m.range.first), true)
            out += Piece(m.value, false)
            at = m.range.last + 1
        }
        if (at < text.length) out += Piece(text.substring(at), true)
        return out.map { if (it.translate && it.text.isBlank()) it.copy(translate = false) else it }
    }

    /** Puts translations (one per translatable piece, in order) back between the kept pieces. */
    fun assemble(pieces: List<Piece>, translated: List<String>): String {
        val it = translated.iterator()
        return pieces.joinToString("") { p -> if (p.translate) it.next() else p.text }
    }

    /** What the translator sees for a note: its title, then its text (or each checklist item). */
    fun inputs(n: Note): List<String> =
        listOf(n.title) + if (n.checklist) Checklist.parse(n.body).map { it.text } else listOf(n.body)

    /** The note with translated title and text; checklist ticks and levels stay. */
    fun applyTo(n: Note, outputs: List<String>): Note {
        val title = outputs[0]
        val body = if (n.checklist) Checklist.serialize(Checklist.parse(n.body).mapIndexed { i, item -> item.copy(text = outputs[i + 1]) })
            else outputs[1]
        return n.copy(title = title, body = body)
    }

    /** Translates [texts] keeping their shape: one engine call for all their words. */
    fun translateTexts(from: String, to: String, texts: List<String>, done: (List<String>?) -> Unit) {
        val split = texts.map { pieces(it) }
        val words = split.flatten().filter { it.translate }.map { it.text }
        if (words.isEmpty()) { done(texts); return }
        engine.translate(from, to, words) { out ->
            if (out == null || out.size != words.size) { done(null); return@translate }
            var k = 0
            done(split.map { ps -> val n = ps.count { it.translate }; assemble(ps, out.subList(k, k + n)).also { k += n } })
        }
    }

    /** ML Kit: Google's on-device translation; language packs come from Google's servers. */
    object MlKit : Engine {
        override fun languages(): List<String> = TranslateLanguage.getAllLanguages()

        // Every call is guarded: an ML Kit problem must end in a message, never in the app closing.
        override fun detect(text: String, done: (String?) -> Unit) = guard({ done(null) }) {
            val id = LanguageIdentification.getClient()
            id.identifyLanguage(text.take(2000))
                .addOnSuccessListener { tag -> done(if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)) }
                .addOnFailureListener { done(null) }
                .addOnCompleteListener { id.close() }
        }

        override fun downloaded(done: (Set<String>) -> Unit) = guard({ done(emptySet()) }) {
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models -> done(models.map { it.language }.toSet()) }
                .addOnFailureListener { done(emptySet()) }
        }

        private inline fun guard(fallback: () -> Unit, block: () -> Unit) = try { block() } catch (e: Exception) { fallback() }

        private fun client(from: String, to: String) =
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())

        override fun download(from: String, to: String, done: (String?) -> Unit) = guard({ done("ML Kit unavailable") }) {
            val t = client(from, to)
            t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message ?: it.javaClass.simpleName) }
                .addOnCompleteListener { t.close() }
        }

        override fun translate(from: String, to: String, pieces: List<String>, done: (List<String>?) -> Unit) = guard({ done(null) }) {
            val t = client(from, to)
            Tasks.whenAllSuccess<String>(pieces.map { t.translate(it) })
                .addOnSuccessListener { done(it) }.addOnFailureListener { done(null) }
                .addOnCompleteListener { t.close() }
        }

        override fun delete(lang: String, done: (Boolean) -> Unit) = guard({ done(false) }) {
            RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(lang).build())
                .addOnSuccessListener { done(true) }.addOnFailureListener { done(false) }
        }
    }
}
