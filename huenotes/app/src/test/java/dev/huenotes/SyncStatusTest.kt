package dev.huenotes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncStatusTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = app.getString(id)

    @Test fun offlineErrorsAreReadable() {
        assertEquals(s(R.string.sync_err_offline), SyncStatus.describe(app, UnknownHostException("x")))
        assertEquals(s(R.string.sync_err_offline), SyncStatus.describe(app, IOException("boom")))
    }

    @Test fun driveCodesMapToAdvice() {
        assertEquals(s(R.string.sync_err_signin), SyncStatus.describe(app, DriveException(401, "x")))
        assertEquals(s(R.string.sync_err_signin), SyncStatus.describe(app, DriveException(403, "x")))
        assertEquals(s(R.string.sync_err_busy), SyncStatus.describe(app, DriveException(429, "x")))
        assertEquals(s(R.string.sync_err_busy), SyncStatus.describe(app, DriveException(503, "x")))
        assertTrue(SyncStatus.describe(app, DriveException(404, "Google Drive error 404")).contains("404"))
    }

    @Test fun onlyPassingProblemsAreRetried() {
        assertTrue(SyncStatus.isTransient(UnknownHostException()))
        assertTrue(SyncStatus.isTransient(DriveException(503, "x")))
        assertFalse(SyncStatus.isTransient(DriveException(401, "x")))
        assertFalse(SyncStatus.isTransient(IllegalStateException()))
    }

    @Test fun recordingAFailureAndThenASuccessUpdatesTheStatusLine() {
        SyncManager.record(app, Result.failure(UnknownHostException()))
        assertEquals(s(R.string.sync_err_offline), Prefs.syncError(app))
        SyncManager.record(app, Result.success(SyncRunner.Outcome(emptyList(), emptyList(), false, 0)))
        assertEquals("", Prefs.syncError(app)); assertTrue(Prefs.lastSync(app) > 0)
    }

    @Test fun savingNotesDoesNotTouchWorkManagerWhenSyncIsOff() {
        val store = NoteStore(app, "sched-${System.nanoTime()}.db")
        store.save(Note(title = "x"))      // would throw if the scheduler were reached without a connected account
        store.close()
    }
}
