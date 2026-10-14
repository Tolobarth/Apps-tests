package dev.huenotes

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android glue for sync: Google authorization, a background thread, and follow-up work on the main thread. */
object SyncManager {
    private const val AUTO_INTERVAL_MS = 5 * 60 * 1000L
    private val exec = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun isConnected(c: Context) = Prefs.syncEmail(c).isNotEmpty()

    /** Asks Google for Drive app-data access plus the account email. May need a consent screen (hasResolution). */
    fun authorize(activity: Activity): Task<AuthorizationResult> =
        Identity.getAuthorizationClient(activity).authorize(
            AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata"), Scope(Scopes.EMAIL)))
                .build()
        )

    /** Runs one sync in the background. Ignored when one is already running. */
    fun run(c: Context, token: String, onDone: (Result<SyncRunner.Outcome>) -> Unit = {}) {
        val app = c.applicationContext
        if (!running.compareAndSet(false, true)) return
        exec.execute {
            val r = runCatching { SyncRunner.sync(NoteStore(app), DriveRemote(token)) }
            main.post {
                running.set(false)
                r.onSuccess { o ->
                    o.upserted.forEach { Reminders.sync(app, it) }
                    o.removedIds.forEach { Reminders.cancel(app, it) }
                    Prefs.setLastSync(app, System.currentTimeMillis())
                    Widgets.refresh(app)
                }
                onDone(r)
            }
        }
    }

    /** Quiet background sync when opening the app: only if connected, enabled, due, and Google needs no consent screen. */
    fun syncIfDue(activity: Activity, onDone: () -> Unit = {}) {
        if (!isConnected(activity) || !Prefs.autoSync(activity)) return
        if (System.currentTimeMillis() - Prefs.lastSync(activity) < AUTO_INTERVAL_MS) return
        authorize(activity).addOnSuccessListener { r ->
            val token = r.accessToken
            if (!r.hasResolution() && token != null) run(activity, token) { res -> if (res.isSuccess) onDone() }
        }
    }
}
