package dev.huenotes

import android.content.Context
import java.io.File
import java.util.UUID

enum class AttachmentKind(val code: Int, val ext: String, val mime: String) {
    IMAGE(0, "jpg", "image/jpeg"),
    DRAWING(1, "png", "image/png"),
    AUDIO(2, "m4a", "audio/mp4");

    companion object { fun of(code: Int) = values().firstOrNull { it.code == code } ?: IMAGE }
}

/** A photo, drawing or voice note belonging to a note. The file name is derived from [uid] so every device agrees on it. */
data class Attachment(
    val uid: String = UUID.randomUUID().toString(),
    val noteUid: String,
    val kind: AttachmentKind,
    val durationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val fileName get() = "$uid.${kind.ext}"
    /** Name of the matching file in the Drive app-data folder. */
    val blobName get() = "att-$uid"
}

/** Where attachment files live. Injectable so tests can simulate two devices. */
class AttachmentStorage(val dir: File) {
    init { dir.mkdirs() }
    /** The folder is recreated on demand, so a missing one never makes a write fail. */
    fun file(a: Attachment): File { dir.mkdirs(); return File(dir, a.fileName) }

    companion object { fun of(c: Context) = AttachmentStorage(File(c.filesDir, "attachments")) }
}
