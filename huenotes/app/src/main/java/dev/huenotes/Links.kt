package dev.huenotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * A "link note" is any note whose first non-blank line is a web address; text after it is the note's own description.
 * No extra database column is needed, so backup and sync are unaffected.
 */
object Links {
    private val URL_ANYWHERE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val URL_ONLY = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val exec = Executors.newSingleThreadExecutor()

    private val URL_FINDER = Regex("""(?:https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val LENIENT_HOST = Regex("""^https?://[^/\s?#]+\.[^/\s?#]+""", RegexOption.IGNORE_CASE)

    /** Strips what usually trails a link in prose: full stops, commas, and a closing bracket that has no opening partner. */
    internal fun clean(raw: String): String {
        var s = raw
        while (s.isNotEmpty()) {
            val last = s.last()
            val unpaired = when (last) {
                ')' -> s.count { it == ')' } > s.count { it == '(' }
                ']' -> s.count { it == ']' } > s.count { it == '[' }
                '}' -> s.count { it == '}' } > s.count { it == '{' }
                '>' -> true
                '.', ',', ';', ':', '!', '?', '\'', '"' -> true
                else -> false
            }
            if (!unpaired) break
            s = s.dropLast(1)
        }
        return s
    }

    /** The first web address inside [text], without trailing punctuation such as the full stop ending a sentence. */
    fun firstUrl(text: String): String? = URL_ANYWHERE.find(text)?.value?.let { clean(it) }?.takeIf { it.length > 8 }

    /**
     * Every web address in [text], in order, without repeats. Addresses starting with "www." are given https://, so each
     * result can be opened as is. Used to gather the links of all notes into one list.
     */
    fun allUrls(text: String): List<String> = URL_FINDER.findAll(text).mapNotNull { m ->
        val c = clean(m.value)
        val withScheme = if (c.startsWith("www.", ignoreCase = true)) "https://$c" else c
        // Strict check first; real-world addresses with | or [ ] in them still count if the host looks right.
        withScheme.takeIf { normalize(it) != null || LENIENT_HOST.containsMatchIn(it) }
    }.distinct().toList()

    /** Turns what a person typed or pasted into a usable address, or null when it isn't one. "example.com/a" gets https://. */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) {
            // "mailto:a@b.com", "javascript:…", "tel:…" are not web addresses; "example.com:8080/x" (host:port) is.
            if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\d+([/?#]|$))").containsMatchIn(s)) return null
            s = "https://$s"
        }
        val u = try { URI(s) } catch (_: Exception) { return null }
        if (u.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = u.host ?: return null
        if (!host.contains('.') && host != "localhost") return null
        return s
    }

    fun domain(url: String): String = try { URI(url).host.orEmpty().removePrefix("www.").ifEmpty { url } } catch (_: Exception) { url }

    /** The address of a link note's body, or null when the body doesn't start with one. */
    fun linkOf(body: String): String? {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return first.takeIf { URL_ONLY.matches(it) }
    }

    fun linkOf(n: Note): String? = if (n.checklist) null else linkOf(n.body)
    fun isLinkNote(n: Note) = linkOf(n) != null

    /** What is written below the address (the note's own words). */
    fun restOf(body: String): String {
        val lines = body.lines(); val i = lines.indexOfFirst { it.isNotBlank() }
        return if (i < 0) "" else lines.drop(i + 1).joinToString("\n").trim()
    }

    fun open(c: Context, url: String) {
        try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Toast.makeText(c, R.string.link_open_failed, Toast.LENGTH_SHORT).show() }
    }

    // ---- page titles ---------------------------------------------------------------------------

    fun parseTitle(html: String): String? {
        val raw = TITLE_TAG.find(html)?.groupValues?.get(1) ?: return null
        val t = decodeEntities(raw).replace(Regex("\\s+"), " ").trim().take(120)
        return t.ifBlank { null }
    }

    private fun decodeEntities(s: String): String = s
        .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Downloads the start of the page and reads its <title>. Null on any problem, non-HTML content, or a slow site. */
    fun fetchTitle(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) HueNotes")
        c.setRequestProperty("Accept", "text/html")
        val type = c.contentType.orEmpty()
        if (c.responseCode !in 200..299 || (type.isNotEmpty() && !type.contains("html", ignoreCase = true))) null
        else {
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            parseTitle(String(c.inputStream.use { readUpTo(it, 64 * 1024) }, charset))
        }
    } catch (_: Exception) { null }

    private fun readUpTo(i: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (out.size() < max) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }

    /** Fills in an empty title from the page in the background (if the setting allows), then calls [onDone] on the main thread. */
    fun fetchTitleAsync(c: Context, noteId: Long, url: String, onDone: (() -> Unit)? = null) {
        val app = c.applicationContext
        if (!Prefs.fetchLinkTitles(app)) return
        exec.execute {
            val title = fetchTitle(url) ?: return@execute
            NoteStore(app).setTitleIfBlank(noteId, title)
            onDone?.let { Handler(Looper.getMainLooper()).post(it) }
        }
    }
}

/** Turns text shared from other apps ("Share → HueNotes") into a note. */
object ShareIntake {
    fun fromSharedText(text: String?, subject: String?, color: Int): Note? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val url = Links.firstUrl(t)
        val subj = subject?.trim().orEmpty()
        if (url == null) return Note(title = subj, body = t, color = color)
        val rest = t.replaceFirst(url, "").replace(Regex("[ \\t]{2,}"), " ").trim()
        // A subject that is itself the link (some apps do that) is not a title.
        val title = if (Links.firstUrl(subj) != null) "" else subj
        return Note(title = title, body = if (rest.isBlank()) url else "$url\n$rest", color = color)
    }
}
