package dev.huenotes

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class AttachmentKind(val code: Int, val ext: String, val mime: String) {
    IMAGE(0, "jpg", "image/jpeg"),
    DRAWING(1, "png", "image/png"),
    AUDIO(2, "m4a", "audio/mp4"),
    /** Any other file (PDF, document, video…) received from another app; kept byte for byte under its own name. */
    FILE(3, "bin", "application/octet-stream");

    companion object { fun of(code: Int) = values().firstOrNull { it.code == code } ?: IMAGE }
}

/**
 * A photo, drawing, voice note or file belonging to a note. The file name is derived from [uid] (and, for files, the
 * extension of [name]) so every device agrees on it. [name] and [mime] are only set for [AttachmentKind.FILE].
 */
data class Attachment(
    val uid: String = UUID.randomUUID().toString(),
    val noteUid: String,
    val kind: AttachmentKind,
    val durationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val name: String = "",
    val mime: String = "",
) {
    val fileName get() = "$uid.$ext"
    val ext get() = if (kind == AttachmentKind.FILE) extOf(name) else kind.ext
    val mimeType get() = mime.ifBlank { kind.mime }
    /** Name of the matching file in the Drive app-data folder. */
    val blobName get() = "att-$uid"

    companion object {
        private val SAFE_EXT = Regex("[a-z0-9]{1,8}")
        /** A lower-case extension safe to put in a file name, or "bin". */
        fun extOf(name: String) = name.substringAfterLast('.', "").lowercase().takeIf { '.' in name && SAFE_EXT.matches(it) } ?: "bin"
    }
}

/** Where attachment files live. Injectable so tests can simulate two devices. */
class AttachmentStorage(val dir: File) {
    init { dir.mkdirs() }
    /** The folder is recreated on demand, so a missing one never makes a write fail. */
    fun file(a: Attachment): File { dir.mkdirs(); return File(dir, a.fileName) }

    companion object { fun of(c: Context) = AttachmentStorage(File(c.filesDir, "attachments")) }
}

/** The attachment entry used in both the sync file and backups. */
object AttachmentJson {
    fun toJson(a: Attachment): JSONObject = JSONObject().put("uid", a.uid).put("kind", a.kind.code).put("durationMs", a.durationMs).put("createdAt", a.createdAt)
        .apply { if (a.kind == AttachmentKind.FILE) put("name", a.name).put("mime", a.mime) }

    fun fromJson(o: JSONObject, noteUid: String) = Attachment(o.getString("uid"), noteUid, AttachmentKind.of(o.optInt("kind")),
        o.optLong("durationMs"), o.optLong("createdAt"), o.optString("name"), o.optString("mime"))

    /** Older versions of the app would show a file as a broken photo, so files raise the format number they must understand. */
    fun hasFiles(atts: Map<String, List<Attachment>>) = atts.values.any { l -> l.any { it.kind == AttachmentKind.FILE } }
}
