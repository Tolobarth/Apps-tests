package dev.huenotes

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/** Background sync: runs when the phone is online, without the app being open. Never shows a consent screen. */
class SyncWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        val app = applicationContext
        if (!SyncManager.isConnected(app) || !Prefs.autoSync(app)) return Result.success()
        val token = try {
            val r = Tasks.await(Identity.getAuthorizationClient(app).authorize(SyncManager.request()), 30, TimeUnit.SECONDS)
            if (r.hasResolution()) null else r.accessToken
        } catch (e: Exception) {
            val cause = e.cause ?: e
            Prefs.setSyncError(app, SyncStatus.describe(app, cause))
            return if (SyncStatus.isTransient(cause) && runAttemptCount < 3) Result.retry() else Result.success()
        }
        if (token == null) { Prefs.setSyncError(app, Loc.ctx(app).getString(R.string.sync_err_signin)); return Result.success() }
        if (!SyncManager.tryLock()) return Result.success()      // a sync started from the app is already running
        val r = try { runCatching { SyncRunner.sync(NoteStore(app), DriveRemote(token), conflictSuffix = Loc.ctx(app).getString(R.string.sync_conflict_suffix)) } }
        finally { SyncManager.unlock() }
        SyncManager.record(app, r)
        val e = r.exceptionOrNull()
        return if (e != null && SyncStatus.isTransient(e) && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

object SyncScheduler {
    private const val PERIODIC = "huenotes-sync-periodic"
    private const val SOON = "huenotes-sync-soon"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun wanted(c: Context) = SyncManager.isConnected(c) && Prefs.autoSync(c)

    /** Call at start-up and whenever connection or the auto-sync switch changes. */
    fun apply(c: Context) = runCatching {
        val wm = WorkManager.getInstance(c)
        if (wanted(c)) wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build())
        else { wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(SOON) }
    }

    /** After a local change: sync shortly, once, however many edits arrive in the meantime. */
    fun requestSoon(c: Context) {
        if (!wanted(c)) return
        runCatching {
            WorkManager.getInstance(c).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(30, TimeUnit.SECONDS).setConstraints(online).build())
        }
    }
}
