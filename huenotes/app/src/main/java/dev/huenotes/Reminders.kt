package dev.huenotes

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

object Reminders {
    const val CHANNEL = "reminders"

    private fun pending(c: Context, id: Long) = PendingIntent.getBroadcast(
        c, id.toInt(), Intent(c, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context, n: Note) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c, n.id))
        if (n.reminderAt <= System.currentTimeMillis()) return
        // Inexact but fires in Doze: needs no exact-alarm special access grant.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.reminderAt, pending(c, n.id))
    }

    fun cancel(c: Context, id: Long) = c.getSystemService(AlarmManager::class.java).cancel(pending(c, id))

    /** Arms the alarm only for live (not archived, not trashed) notes with a future reminder. */
    fun sync(c: Context, n: Note) { if (n.archived || n.deletedAt > 0) cancel(c, n.id) else schedule(c, n) }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val note = NoteStore(c).get(i.getLongExtra("id", 0)) ?: return
        if (note.archived || note.deletedAt > 0) return
        val lc = Loc.ctx(c)
        val locked = Lock.isSet(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(Reminders.CHANNEL, lc.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, note.id.toInt(), Intent(c, EditorActivity::class.java).putExtra("id", note.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, Reminders.CHANNEL)
        else android.app.Notification.Builder(c)
        nm.notify(note.id.toInt(), b.setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (locked) lc.getString(R.string.notif_title_locked) else Sorter.displayTitle(note).ifBlank { lc.getString(R.string.notif_note) })
            .setContentText(if (locked) lc.getString(R.string.notif_text_locked) else note.body.lines().firstOrNull().orEmpty())
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        NoteStore(c).upcomingReminders(System.currentTimeMillis()).forEach { Reminders.schedule(c, it) }
    }
}
