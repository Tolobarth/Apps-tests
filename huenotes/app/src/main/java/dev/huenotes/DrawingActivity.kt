package dev.huenotes

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Full-screen sketchpad. Returns the picture as a PNG path in the "path" extra; the editor turns it into an attachment. */
class DrawingActivity : GatedActivity() {
    private lateinit var canvas: DrawView
    private lateinit var tools: LinearLayout
    private val colors = listOf("#1B1B1F", "#D32F2F", "#1976D2", "#388E3C", "#F57C00", "#7B1FA2").map(Color::parseColor)
    private var sizeIndex = 1

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg)) }
        val bar = MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.ic_back); setTitle(R.string.add_drawing)
            setTitleTextColor(getColor(R.color.on_surface)); navigationIcon?.setTint(getColor(R.color.on_surface))
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        listOf(Triple(1, R.string.undo, R.drawable.ic_undo), Triple(2, R.string.draw_save, R.drawable.ic_check)).forEach { (id, t, icon) ->
            val item = bar.menu.add(0, id, id, t).setIcon(icon)
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            item.icon?.setTint(getColor(R.color.on_surface))
        }
        bar.menu.add(0, 3, 3, R.string.draw_clear).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        bar.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> canvas.undo(); 2 -> save(); 3 -> canvas.clear() }
            true
        }
        canvas = DrawView(this).apply { penColor = colors[0] }
        intent.getStringExtra("base")?.let { BitmapFactory.decodeFile(it)?.let { b -> canvas.base = b } }
        val card = MaterialCardView(this).apply {
            radius = Palette.dp(context, 16).toFloat(); cardElevation = 0f; strokeWidth = Palette.dp(context, 1)
            strokeColor = getColor(R.color.outline); setCardBackgroundColor(Color.WHITE)
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
        }
        tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tools) }
        val m = Palette.dp(this, 12)
        root.addView(bar, LinearLayout.LayoutParams(-1, Palette.dp(this, 56)))
        root.addView(card, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(m, 0, m, m) })
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { setMargins(m, 0, m, m) })
        setContentView(root); Palette.edgeToEdge(this, root)
        renderTools()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (canvas.hasContent()) confirmDiscard() else finish() }
        })
    }

    private fun renderTools() {
        tools.removeAllViews()
        colors.forEach { c ->
            tools.addView(Palette.swatch(this, c, !canvas.erasing && canvas.penColor == c, getString(R.string.note_colour)) {
                canvas.penColor = c; canvas.erasing = false; renderTools()
            })
        }
        listOf(3, 6, 12).forEachIndexed { i, dp ->
            tools.addView(Chip(this).apply {
                text = listOf("S", "M", "L")[i]; isCheckable = true; isChecked = sizeIndex == i
                setOnClickListener { sizeIndex = i; canvas.penWidth = Palette.dp(context, dp).toFloat(); renderTools() }
            })
        }
        canvas.penWidth = Palette.dp(this, listOf(3, 6, 12)[sizeIndex]).toFloat()
        tools.addView(Chip(this).apply {
            setText(R.string.draw_eraser); isCheckable = true; isChecked = canvas.erasing
            setOnClickListener { canvas.erasing = !canvas.erasing; renderTools() }
        })
    }

    private fun save() {
        if (!canvas.hasContent()) { finish(); return }
        val tmp = File(cacheDir, "draw-${System.nanoTime()}.png")
        try { AttachmentFiles.saveDrawing(canvas.render(), tmp) } catch (e: Exception) { tmp.delete(); android.widget.Toast.makeText(this, R.string.image_failed, android.widget.Toast.LENGTH_SHORT).show(); return }
        setResult(RESULT_OK, Intent().putExtra("path", tmp.path))
        finish()
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.draw_discard_title)
            .setPositiveButton(R.string.draw_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }
}
