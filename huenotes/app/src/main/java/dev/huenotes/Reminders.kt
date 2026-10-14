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
        // Inexact alarm: avoids needing the exact-alarm special access grant.
        am.set(AlarmManager.RTC_WAKEUP, n.reminderAt, pending(c, n.id))
    }

    fun cancel(c: Context, id: Long) = c.getSystemService(AlarmManager::class.java).cancel(pending(c, id))
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val note = NoteStore(c).get(i.getLongExtra("id", 0)) ?: return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(Reminders.CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, note.id.toInt(), Intent(c, EditorActivity::class.java).putExtra("id", note.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, Reminders.CHANNEL)
        else android.app.Notification.Builder(c)
        nm.notify(note.id.toInt(), b.setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(note.title.ifBlank { "Note" }).setContentText(note.body.lines().firstOrNull().orEmpty())
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        NoteStore(c).upcomingReminders(System.currentTimeMillis()).forEach { Reminders.schedule(c, it) }
    }
}
