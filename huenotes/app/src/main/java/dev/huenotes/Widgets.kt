package dev.huenotes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Calendar

object Widgets {
    private val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3)

    fun refresh(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        for (cls in listOf(RecentWidget::class.java, TodayWidget::class.java)) {
            val ids = mgr.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(c, cls == TodayWidget::class.java))
        }
    }

    fun build(c: Context, today: Boolean): RemoteViews {
        val lc = Loc.ctx(c)
        val rv = RemoteViews(c.packageName, R.layout.widget)
        rv.setTextViewText(R.id.header, lc.getString(if (today) R.string.widget_today else R.string.widget_recent))
        rv.setInt(R.id.bg, "setImageAlpha", Prefs.widgetOpacity(c).coerceIn(0, 100) * 255 / 100)
        fun label(n: Note) = Sorter.displayTitle(n).ifBlank { lc.getString(R.string.untitled) }
        val lines: List<String> = when {
            Lock.isSet(c) -> listOf(lc.getString(R.string.widget_locked))   // never leak note text onto the home screen
            today -> {
                val s = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                val e = (s.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
                NoteStore(c).reminderBetween(s.timeInMillis, e.timeInMillis).map(::label)
                    .ifEmpty { listOf(lc.getString(R.string.day_nothing)) }
            }
            else -> Sorter.sort(NoteStore(c).list(false), Prefs.sort(c)).map(::label)
                .ifEmpty { listOf(lc.getString(R.string.widget_no_notes)) }
        }
        ROWS.forEachIndexed { i, id -> rv.setTextViewText(id, lines.getOrElse(i) { "" }) }
        val target = if (today) DayActivity::class.java else ListActivity::class.java
        rv.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(
            c, if (today) 1 else 0, Intent(c, target), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return rv
    }
}

class RecentWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = m.updateAppWidget(ids, Widgets.build(c, false))
}

class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = m.updateAppWidget(ids, Widgets.build(c, true))
}
