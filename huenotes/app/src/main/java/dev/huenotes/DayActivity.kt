package dev.huenotes

import android.content.Intent
import android.os.Bundle
import android.widget.CalendarView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Calendar

/** Calendar plus the notes whose reminder falls on the selected day. Opens on today. */
class DayActivity : GatedActivity() {
    private lateinit var store: NoteStore
    private lateinit var adapter: NoteAdapter
    private lateinit var header: TextView
    private val day: Calendar = Calendar.getInstance()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = NoteStore(this)
        setContentView(R.layout.activity_day)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        adapter = NoteAdapter({ startActivity(Intent(this, EditorActivity::class.java).putExtra("id", it.id)) })
        adapter.showTimeOnly = true
        adapter.storage = store.storage
        findViewById<RecyclerView>(R.id.recycler).apply { layoutManager = LinearLayoutManager(context); this.adapter = this@DayActivity.adapter }
        header = findViewById(R.id.dayHeader)
        findViewById<CalendarView>(R.id.calendar).apply {
            date = day.timeInMillis
            if (Prefs.firstDay(this@DayActivity) != 0) firstDayOfWeek = Prefs.firstDay(this@DayActivity)
            setOnDateChangeListener { _, y, m, d -> day.set(y, m, d); refresh() }
        }
    }

    override fun onUnlocked() = refresh()

    private fun refresh() {
        val start = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val notes = store.reminderBetween(start.timeInMillis, end.timeInMillis)
        adapter.submit(notes, attachments = store.attachmentsByNote())
        val date = DateFormat.getDateInstance(DateFormat.FULL).format(start.time)
        header.text = if (notes.isEmpty()) "$date  ·  ${getString(R.string.day_nothing)}"
        else "$date  ·  " + resources.getQuantityString(R.plurals.reminder_count, notes.size, notes.size)
    }
}
