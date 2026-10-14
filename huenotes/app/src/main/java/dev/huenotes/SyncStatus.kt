package dev.huenotes

import com.google.android.gms.common.api.ApiException
import android.content.Context
import java.io.IOException

/** Turns sync failures into a sentence a person can act on. */
object SyncStatus {
    /** Failures that are likely to go away on their own, so a background retry makes sense. */
    fun isTransient(e: Throwable) = when {
        e is DriveException -> e.code == 429 || e.code >= 500
        e is ApiException -> false
        else -> e is IOException
    }

    fun describe(c: Context, e: Throwable): String {
        val l = Loc.ctx(c)
        return when {
            e is ApiException && e.statusCode == 10 -> l.getString(R.string.sync_not_configured)
            e is DriveException && (e.code == 401 || e.code == 403) -> l.getString(R.string.sync_err_signin)
            e is DriveException && (e.code == 429 || e.code >= 500) -> l.getString(R.string.sync_err_busy)
            e is DriveException -> l.getString(R.string.sync_failed, e.message ?: e.code.toString())
            e is IOException -> l.getString(R.string.sync_err_offline)
            else -> l.getString(R.string.sync_failed, e.message ?: e.javaClass.simpleName)
        }
    }
}
