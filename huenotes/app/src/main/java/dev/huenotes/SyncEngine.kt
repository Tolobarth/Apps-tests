package dev.huenotes

import org.json.JSONArray
import org.json.JSONObject

/** [attachments] maps a note's uid to its attachments (only non-empty lists). */
data class Snapshot(
    val notes: List<Note>,
    val tombstones: Map<String, Long>,
    val attachments: Map<String, List<Attachment>> = emptyMap(),
)

class MergeResult(
    val merged: Snapshot,
    val upserts: List<Note>,
    /** For every upserted note, its full attachment list (possibly empty, which clears the local one). */
    val upsertAttachments: Map<String, List<Attachment>>,
    val deleteUids: Set<String>,
    val needUpload: Boolean,
    /** Uids of the copies created because both devices edited the same note since the last sync. */
    val conflictCopies: List<String> = emptyList(),
)

/**
 * Pure merge of two devices' notes: last change wins per note (by updatedAt), and the winner's attachment list comes with it;
 * a permanent deletion (tombstone) wins over any edit made before it, and a later edit wins over an older tombstone.
 * Nothing here touches Android or the network.
 */
object SyncEngine {
    const val TOMBSTONE_KEEP_MS = 90L * 24 * 3600 * 1000

    /** Same on every device, so two devices resolving the same conflict produce the same copy instead of two. */
    fun copyUid(uid: String, loserUpdatedAt: Long): String =
        "c" + java.security.MessageDigest.getInstance("SHA-256").digest("$uid:$loserUpdatedAt".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }

    /**
     * [base] maps a uid to its updatedAt at the last completed sync. A note changed on both sides since then, with different
     * text, keeps the newer version as the note and the older one as a separate "[conflictSuffix]" copy; attachments are unioned.
     */
    fun merge(
        local: Snapshot, remote: Snapshot?, now: Long = System.currentTimeMillis(),
        base: Map<String, Long> = emptyMap(), conflictSuffix: String = "(conflict copy)",
    ): MergeResult {
        val tombs = HashMap(local.tombstones)
        remote?.tombstones?.forEach { (k, v) -> tombs.merge(k, v) { a, b -> maxOf(a, b) } }
        val l = local.notes.associateBy { it.uid }
        val r = remote?.notes?.associateBy { it.uid } ?: emptyMap()
        val kept = LinkedHashMap<String, Note>()
        val keptAtt = LinkedHashMap<String, List<Attachment>>()
        val copies = ArrayList<String>(); val upserts = ArrayList<Note>(); val upsertAtt = LinkedHashMap<String, List<Attachment>>(); val deletes = HashSet<String>()
        for (uid in l.keys + r.keys) {
            val a = l[uid]; val b = r[uid]
            val remoteWins = a == null || (b != null && b.updatedAt > a.updatedAt)
            val winner = if (remoteWins) b!! else a!!
            val t = tombs[uid]
            if (t != null && t >= winner.updatedAt) { if (a != null) deletes += uid; continue }
            kept[uid] = winner
            val winAtt = (if (remoteWins) remote?.attachments else local.attachments)?.get(uid).orEmpty()
            var att = winAtt
            val bs = base[uid] ?: 0L
            if (a != null && b != null && a.updatedAt > bs && b.updatedAt > bs && (a.title != b.title || a.body != b.body)) {
                val loser = if (remoteWins) a else b
                val loserAtt = (if (remoteWins) local.attachments else remote?.attachments)?.get(uid).orEmpty()
                att = winAtt + loserAtt.filter { x -> winAtt.none { it.uid == x.uid } }
                val cu = copyUid(uid, loser.updatedAt)
                if (l[cu] == null && r[cu] == null) {
                    val copy = loser.copy(id = 0, uid = cu, title = (loser.title.ifBlank { loser.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(40) } + " " + conflictSuffix).trim(),
                        pinned = false, reminderAt = 0, deletedAt = 0)
                    kept[cu] = copy; upserts += copy; upsertAtt[cu] = emptyList(); copies += cu
                }
                if (!remoteWins && att.size != winAtt.size) upserts += winner
                if (!remoteWins) upsertAtt[uid] = att
            }
            if (att.isNotEmpty()) keptAtt[uid] = att
            if (remoteWins) { upserts += winner; upsertAtt[uid] = att }
        }
        val liveTombs = tombs.filterValues { now - it < TOMBSTONE_KEEP_MS }
        val needUpload = remote == null ||
            kept.any { (uid, n) -> r[uid]?.updatedAt != n.updatedAt } || copies.isNotEmpty() ||
            r.keys.any { it !in kept } ||
            liveTombs != remote.tombstones
        return MergeResult(Snapshot(kept.values.toList(), liveTombs, keptAtt), upserts, upsertAtt, deletes, needUpload, copies)
    }
}

/** JSON wire format of the synced file. */
object SyncDoc {
    private const val FORMAT = 2

    fun toJson(s: Snapshot): String = JSONObject().put("app", "huenotes").put("format", FORMAT)
        .put("notes", JSONArray(s.notes.map { n ->
            JSONObject().put("uid", n.uid).put("title", n.title).put("body", n.body).put("checklist", n.checklist)
                .put("color", n.color).put("archived", n.archived).put("pinned", n.pinned).put("reminderAt", n.reminderAt)
                .put("modifiedAt", n.modifiedAt).put("createdAt", n.createdAt).put("updatedAt", n.updatedAt).put("deletedAt", n.deletedAt)
                .put("attachments", JSONArray(s.attachments[n.uid].orEmpty().map {
                    JSONObject().put("uid", it.uid).put("kind", it.kind.code).put("durationMs", it.durationMs).put("createdAt", it.createdAt)
                }))
        }))
        .put("tombstones", JSONArray(s.tombstones.map { (k, v) -> JSONObject().put("uid", k).put("at", v) }))
        .toString()

    fun fromJson(text: String): Snapshot {
        val root = JSONObject(text)
        require(root.optString("app") == "huenotes") { "Not a HueNotes sync file" }
        require(root.optInt("format") <= FORMAT) { "Sync file is from a newer version of HueNotes" }
        val atts = LinkedHashMap<String, List<Attachment>>()
        val notes = root.getJSONArray("notes").let { a ->
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val uid = o.getString("uid")
                o.optJSONArray("attachments")?.let { arr ->
                    val list = (0 until arr.length()).map { i ->
                        val x = arr.getJSONObject(i)
                        Attachment(x.getString("uid"), uid, AttachmentKind.of(x.optInt("kind")), x.optLong("durationMs"), x.optLong("createdAt"))
                    }
                    if (list.isNotEmpty()) atts[uid] = list
                }
                Note(
                    title = o.optString("title"), body = o.optString("body"), checklist = o.optBoolean("checklist"),
                    color = o.optInt("color", Note.COLORS[0]), archived = o.optBoolean("archived"), pinned = o.optBoolean("pinned"),
                    reminderAt = o.optLong("reminderAt"), modifiedAt = o.optLong("modifiedAt"), createdAt = o.optLong("createdAt"),
                    deletedAt = o.optLong("deletedAt"), uid = uid, updatedAt = o.optLong("updatedAt"),
                )
            }
        }
        val tombs = root.optJSONArray("tombstones")?.let { a ->
            (0 until a.length()).associate { a.getJSONObject(it).getString("uid") to a.getJSONObject(it).getLong("at") }
        } ?: emptyMap()
        return Snapshot(notes, tombs, atts)
    }
}

class BlobInfo(val name: String, val createdAt: Long)

interface SyncRemote {
    /** The notes file's text, or null when nothing has been uploaded yet. */
    fun download(): String?
    fun upload(json: String)

    /** Attachment files ("att-<uid>") currently stored remotely. */
    fun listBlobs(): List<BlobInfo>
    fun downloadBlob(name: String): ByteArray?
    fun uploadBlob(name: String, bytes: ByteArray, mime: String)
    fun deleteBlob(name: String)
}

object SyncRunner {
    /** Remote attachment files nobody references are only removed once this old, so a slower device's upload isn't swept. */
    const val BLOB_GRACE_MS = 24L * 3600 * 1000

    class Outcome(val upserted: List<Note>, val removedIds: List<Long>, val uploaded: Boolean, val filesPending: Int, val conflicts: Int = 0)

    /** One full sync round against [remote]. Blocking: call off the main thread. */
    fun sync(store: NoteStore, remote: SyncRemote, now: Long = System.currentTimeMillis(), conflictSuffix: String = "(conflict copy)"): Outcome {
        val remoteSnap = remote.download()?.let { SyncDoc.fromJson(it) }
        val localNotes = store.allForSync()
        val local = Snapshot(localNotes, store.tombstones(), store.attachmentsByNote())
        val result = SyncEngine.merge(local, remoteSnap, now, store.syncBase(), conflictSuffix)
        val applied = store.applySync(result.upserts, result.deleteUids, result.merged.tombstones,
            localNotes.associate { it.uid to it.updatedAt }, result.upsertAttachments)

        // Files first, notes file last: the notes file must never mention an attachment the cloud doesn't have yet.
        val wanted = result.merged.attachments.values.flatten()
        val remoteBlobs = remote.listBlobs()
        val remoteNames = remoteBlobs.map { it.name }.toHashSet()
        for (a in wanted) {
            val f = store.storage.file(a)
            if (a.blobName !in remoteNames && f.exists()) remote.uploadBlob(a.blobName, f.readBytes(), a.kind.mime)
        }
        var pending = 0
        for (a in wanted) {
            val f = store.storage.file(a)
            if (f.exists()) continue
            val bytes = if (a.blobName in remoteNames) runCatching { remote.downloadBlob(a.blobName) }.getOrNull() else null
            if (bytes != null) f.writeBytes(bytes) else pending++
        }
        if (result.needUpload) remote.upload(SyncDoc.toJson(result.merged))
        store.setSyncBase(result.merged.notes.associate { it.uid to it.updatedAt })   // both sides now agree on these versions
        val keep = wanted.map { it.blobName }.toHashSet()
        remoteBlobs.filter { it.name !in keep && now - it.createdAt > BLOB_GRACE_MS }.forEach { runCatching { remote.deleteBlob(it.name) } }
        return Outcome(applied.upserted, applied.removedIds, result.needUpload, pending, result.conflictCopies.size)
    }
}
