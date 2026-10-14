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
class VersionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var t = 1_000_000L
    private val store = NoteStore(app, "versions-${System.nanoTime()}.db").also { it.clock = { t } }
    @After fun close() = store.close()

    private fun edit(id: Long, body: String): Note = store.get(id)!!.copy(body = body).also { store.save(it) }
    private val MIN = 60_000L

    @Test fun firstSaveHasNoHistory() {
        val id = store.save(Note(title = "T", body = "one"))
        assertTrue(store.versions(store.get(id)!!.uid).isEmpty())
    }

    @Test fun editKeepsThePreviousContent() {
        val id = store.save(Note(title = "T", body = "one"))
        edit(id, "two")
        val v = store.versions(store.get(id)!!.uid)
        assertEquals(listOf("one"), v.map { it.body })
    }

    @Test fun burstOfEditsKeepsOnlyTheFirstPreEditState() {
        val id = store.save(Note(body = "one"))
        edit(id, "two"); t += MIN; edit(id, "three"); t += MIN; edit(id, "four")
        assertEquals(listOf("one"), store.versions(store.get(id)!!.uid).map { it.body })
        t += 6 * MIN; edit(id, "five")
        assertEquals(listOf("four", "one"), store.versions(store.get(id)!!.uid).map { it.body })
    }

    @Test fun unchangedSaveAndBlankStatesAreSkipped() {
        val id = store.save(Note(body = ""))
        t += 10 * MIN; edit(id, "a")                      // blank -> a: nothing worth keeping
        t += 10 * MIN; store.save(store.get(id)!!)         // same content
        assertTrue(store.versions(store.get(id)!!.uid).isEmpty())
    }

    @Test fun capsAtFiftyVersions() {
        val id = store.save(Note(body = "v0"))
        for (i in 1..60) { t += 10 * MIN; edit(id, "v$i") }
        val v = store.versions(store.get(id)!!.uid)
        assertEquals(NoteStore.MAX_VERSIONS, v.size)
        assertEquals("v59", v.first().body)
    }

    @Test fun deletingTheNoteDeletesItsHistory() {
        val id = store.save(Note(body = "one")); val uid = store.get(id)!!.uid
        edit(id, "two"); assertEquals(1, store.versions(uid).size)
        store.delete(id)
        assertTrue(store.versions(uid).isEmpty())
    }

    @Test fun syncOverwriteKeepsTheLocalContent() {
        val id = store.save(Note(body = "local text")); val cur = store.get(id)!!
        val remote = cur.copy(body = "remote text", updatedAt = cur.updatedAt + 10)
        store.applySync(listOf(remote), emptyList(), emptyMap(), mapOf(cur.uid to cur.updatedAt))
        assertEquals("remote text", store.get(id)!!.body)
        assertEquals(listOf("local text"), store.versions(cur.uid).map { it.body })
    }
}
