package dev.huenotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** A simple finger/stylus canvas on white paper with undo, an eraser and an optional picture underneath. */
class DrawView(context: Context) : View(context) {
    private class Stroke(val path: Path, val paint: Paint)

    private val strokes = ArrayList<Stroke>()
    private var lastX = 0f
    private var lastY = 0f
    private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    var penColor = Color.BLACK
    var penWidth = 6f
    var erasing = false
    var base: Bitmap? = null
        set(v) { field = v; invalidate() }

    fun hasContent() = strokes.isNotEmpty()

    fun undo() { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex); invalidate() } }

    fun clear() { strokes.clear(); invalidate() }

    private fun newPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = if (erasing) penWidth * 2.5f else penWidth
        color = if (erasing) Color.WHITE else penColor
    }

    private fun paintAll(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        base?.let { b ->
            val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * s; val h = b.height * s
            val l = (width - w) / 2; val t = (height - h) / 2
            canvas.drawBitmap(b, null, RectF(l, t, l + w, t + h), basePaint)
        }
        strokes.forEach { canvas.drawPath(it.path, it.paint) }
    }

    override fun onDraw(canvas: Canvas) = paintAll(canvas)

    /** The drawing as it appears on screen. */
    fun render(): Bitmap {
        val bmp = Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), Bitmap.Config.ARGB_8888)
        paintAll(Canvas(bmp))
        return bmp
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val p = Path().apply { moveTo(e.x, e.y); lineTo(e.x + 0.01f, e.y) }   // a tap leaves a dot
                strokes += Stroke(p, newPaint()); lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> strokes.lastOrNull()?.let { s ->
                for (i in 0 until e.historySize) smooth(s.path, e.getHistoricalX(i), e.getHistoricalY(i))
                smooth(s.path, e.x, e.y)
            }
            MotionEvent.ACTION_UP -> strokes.lastOrNull()?.path?.lineTo(e.x, e.y)
            else -> return false
        }
        invalidate()
        return true
    }

    private fun smooth(path: Path, x: Float, y: Float) {
        path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
        lastX = x; lastY = y
    }
}
