package dev.huenotes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConflictTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun n(body: String, at: Long, uid: String = "a") = Note(uid = uid, title = "T", body = body, updatedAt = at, modifiedAt = at)
    private fun snap(vararg notes: Note, att: Map<String, List<Attachment>> = emptyMap()) = Snapshot(notes.toList(), emptyMap(), att)

    @Test fun bothEditedSinceBaseKeepsNewerAndACopyOfTheOlder() {
        val r = SyncEngine.merge(snap(n("local edit", 300)), snap(n("remote edit", 200)), 1000, mapOf("a" to 100L), "(conflict)")
        assertEquals(1, r.conflictCopies.size)
        val bodies = r.merged.notes.associate { it.uid to it.body }
        assertEquals("local edit", bodies["a"])
        assertEquals("remote edit", bodies[r.conflictCopies[0]])
        assertEquals("T (conflict)", r.merged.notes.first { it.uid == r.conflictCopies[0] }.title)
        assertTrue(r.needUpload)
    }

    @Test fun remoteNewerKeepsLocalAsCopy() {
        val r = SyncEngine.merge(snap(n("local edit", 150)), snap(n("remote edit", 200)), 1000, mapOf("a" to 100L))
        assertEquals("remote edit", r.merged.notes.first { it.uid == "a" }.body)
        val copy = r.merged.notes.first { it.uid == r.conflictCopies.single() }
        assertEquals("local edit", copy.body)
        assertTrue(r.upserts.any { it.uid == copy.uid })      // the copy is also created locally
    }

    @Test fun onlyOneSideChangedIsNotAConflict() {
        val r = SyncEngine.merge(snap(n("same", 100)), snap(n("remote edit", 200)), 1000, mapOf("a" to 100L))
        assertTrue(r.conflictCopies.isEmpty()); assertEquals(1, r.merged.notes.size)
    }

    @Test fun sameTextOnBothSidesIsNotAConflict() {
        val r = SyncEngine.merge(snap(n("same", 300)), snap(n("same", 200)), 1000, mapOf("a" to 100L))
        assertTrue(r.conflictCopies.isEmpty())
    }

    @Test fun copyIdentityIsTheSameOnBothDevices() {
        val x = SyncEngine.merge(snap(n("L", 300)), snap(n("R", 200)), 1000, mapOf("a" to 100L))
        val y = SyncEngine.merge(snap(n("R", 200)), snap(n("L", 300)), 1000, mapOf("a" to 100L))
        assertEquals(x.conflictCopies, y.conflictCopies)
    }

    @Test fun attachmentsFromBothSidesAreKept() {
        val la = Attachment(noteUid = "a", kind = AttachmentKind.IMAGE); val ra = Attachment(noteUid = "a", kind = AttachmentKind.AUDIO)
        val r = SyncEngine.merge(snap(n("L", 300), att = mapOf("a" to listOf(la))), Snapshot(listOf(n("R", 200)), emptyMap(), mapOf("a" to listOf(ra))), 1000, mapOf("a" to 100L))
        assertEquals(setOf(la.uid, ra.uid), r.merged.attachments["a"]!!.map { it.uid }.toSet())
    }

    @Test fun runnerCreatesTheCopyAndRecordsTheBaseline() {
        val store = NoteStore(app, "conf-${System.nanoTime()}.db")
        val id = store.save(Note(title = "T", body = "local edit")); val uid = store.get(id)!!.uid
        val remote = FakeRemote(SyncDoc.toJson(Snapshot(listOf(Note(uid = uid, title = "T", body = "remote edit", updatedAt = 1, modifiedAt = 1)), emptyMap())))
        store.setSyncBase(mapOf(uid to 0L))
        val out = SyncRunner.sync(store, remote, conflictSuffix = "(conflict)")
        assertEquals(1, out.conflicts)
        val titles = store.list(false).map { it.title to it.body }.toSet()
        assertTrue(titles.contains("T" to "local edit")); assertTrue(titles.contains("T (conflict)" to "remote edit"))
        assertEquals(store.allForSync().associate { it.uid to it.updatedAt }, store.syncBase())
        // a second round is quiet: nothing new to resolve
        assertEquals(0, SyncRunner.sync(store, remote, conflictSuffix = "(conflict)").conflicts)
        assertEquals(2, store.list(false).size)
        store.close()
    }

    private class FakeRemote(var doc: String?) : SyncRemote {
        override fun download() = doc
        override fun upload(json: String) { doc = json }
        override fun listBlobs() = emptyList<BlobInfo>()
        override fun downloadBlob(name: String): ByteArray? = null
        override fun uploadBlob(name: String, bytes: ByteArray, mime: String) = Unit
        override fun deleteBlob(name: String) = Unit
    }
}
