package dev.huenotes

import org.json.JSONArray
import org.json.JSONObject

data class Snapshot(val notes: List<Note>, val tombstones: Map<String, Long>)

class MergeResult(
    val merged: Snapshot,
    val upserts: List<Note>,
    val deleteUids: Set<String>,
    val needUpload: Boolean,
)

/**
 * Pure merge of two devices' notes: last change wins per note (by updatedAt); a permanent deletion (tombstone) wins over
 * any edit made before it, and a later edit wins over an older tombstone. Nothing here touches Android or the network.
 */
object SyncEngine {
    const val TOMBSTONE_KEEP_MS = 90L * 24 * 3600 * 1000

    fun merge(local: Snapshot, remote: Snapshot?, now: Long = System.currentTimeMillis()): MergeResult {
        val tombs = HashMap(local.tombstones)
        remote?.tombstones?.forEach { (k, v) -> tombs.merge(k, v) { a, b -> maxOf(a, b) } }
        val l = local.notes.associateBy { it.uid }
        val r = remote?.notes?.associateBy { it.uid } ?: emptyMap()
        val kept = LinkedHashMap<String, Note>()
        val upserts = ArrayList<Note>(); val deletes = HashSet<String>()
        for (uid in l.keys + r.keys) {
            val a = l[uid]; val b = r[uid]
            val winner = when { a == null -> b!!; b == null -> a; b.updatedAt > a.updatedAt -> b; else -> a }
            val t = tombs[uid]
            if (t != null && t >= winner.updatedAt) { if (a != null) deletes += uid; continue }
            kept[uid] = winner
            if (winner !== a) upserts += winner
        }
        val liveTombs = tombs.filterValues { now - it < TOMBSTONE_KEEP_MS }
        val needUpload = remote == null ||
            kept.any { (uid, n) -> r[uid]?.updatedAt != n.updatedAt } ||
            r.keys.any { it !in kept } ||
            liveTombs != remote.tombstones
        return MergeResult(Snapshot(kept.values.toList(), liveTombs), upserts, deletes, needUpload)
    }
}

/** JSON wire format of the synced file. */
object SyncDoc {
    private const val FORMAT = 1

    fun toJson(s: Snapshot): String = JSONObject().put("app", "huenotes").put("format", FORMAT)
        .put("notes", JSONArray(s.notes.map {
            JSONObject().put("uid", it.uid).put("title", it.title).put("body", it.body).put("checklist", it.checklist)
                .put("color", it.color).put("archived", it.archived).put("pinned", it.pinned).put("reminderAt", it.reminderAt)
                .put("modifiedAt", it.modifiedAt).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).put("deletedAt", it.deletedAt)
        }))
        .put("tombstones", JSONArray(s.tombstones.map { (k, v) -> JSONObject().put("uid", k).put("at", v) }))
        .toString()

    fun fromJson(text: String): Snapshot {
        val root = JSONObject(text)
        require(root.optString("app") == "huenotes") { "Not a HueNotes sync file" }
        require(root.optInt("format") <= FORMAT) { "Sync file is from a newer version of HueNotes" }
        val notes = root.getJSONArray("notes").let { a ->
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Note(
                    title = o.optString("title"), body = o.optString("body"), checklist = o.optBoolean("checklist"),
                    color = o.optInt("color", Note.COLORS[0]), archived = o.optBoolean("archived"), pinned = o.optBoolean("pinned"),
                    reminderAt = o.optLong("reminderAt"), modifiedAt = o.optLong("modifiedAt"), createdAt = o.optLong("createdAt"),
                    deletedAt = o.optLong("deletedAt"), uid = o.getString("uid"), updatedAt = o.optLong("updatedAt"),
                )
            }
        }
        val tombs = root.optJSONArray("tombstones")?.let { a ->
            (0 until a.length()).associate { a.getJSONObject(it).getString("uid") to a.getJSONObject(it).getLong("at") }
        } ?: emptyMap()
        return Snapshot(notes, tombs)
    }
}

interface SyncRemote {
    /** The remote file's text, or null when nothing has been uploaded yet. */
    fun download(): String?
    fun upload(json: String)
}

object SyncRunner {
    class Outcome(val upserted: List<Note>, val removedIds: List<Long>, val uploaded: Boolean)

    /** One full sync round against [remote]. Blocking: call off the main thread. */
    fun sync(store: NoteStore, remote: SyncRemote, now: Long = System.currentTimeMillis()): Outcome {
        val remoteSnap = remote.download()?.let { SyncDoc.fromJson(it) }
        val localNotes = store.allForSync()
        val local = Snapshot(localNotes, store.tombstones())
        val result = SyncEngine.merge(local, remoteSnap, now)
        val applied = store.applySync(result.upserts, result.deleteUids, result.merged.tombstones, localNotes.associate { it.uid to it.updatedAt })
        if (result.needUpload) remote.upload(SyncDoc.toJson(result.merged))
        return Outcome(applied.upserted, applied.removedIds, result.needUpload)
    }
}
