package dev.huenotes

import android.content.Intent
import android.os.Bundle
import android.widget.*
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Calendar plus the notes whose reminder falls on the selected day. Opens on today. */
class DayActivity : GatedActivity() {
    private lateinit var store: NoteStore
    private lateinit var list: ListView
    private lateinit var header: TextView
    private val day: Calendar = Calendar.getInstance()
    private var notes: List<Note> = emptyList()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        title = "Calendar"
        store = NoteStore(this)
        val cal = CalendarView(this).apply {
            setOnDateChangeListener { _, y, m, d -> day.set(y, m, d); refresh() }
        }
        header = TextView(this).apply { setPadding(16, 12, 16, 12); textSize = 16f }
        list = ListView(this).apply {
            setOnItemClickListener { _, _, p, _ ->
                startActivity(Intent(this@DayActivity, EditorActivity::class.java).putExtra("id", notes[p].id))
            }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(cal, LinearLayout.LayoutParams(-1, -2))
        root.addView(header)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onUnlocked() = refresh()

    private fun refresh() {
        val start = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        notes = store.reminderBetween(start.timeInMillis, end.timeInMillis)
        header.text = DateFormat.getDateInstance(DateFormat.FULL).format(start.time) +
            if (notes.isEmpty()) "\nNothing scheduled" else ""
        val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, notes.map {
            tf.format(Date(it.reminderAt)) + "  " + it.title.ifBlank { it.body.lines().firstOrNull().orEmpty() }
        })
    }
}
