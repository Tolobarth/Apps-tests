package dev.huenotes

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

object Palette {
    fun isNight(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Soft tint of the note colour over the screen background: pastel in light mode, muted in dark mode. */
    fun surface(c: Context, noteColor: Int): Int {
        val bg = ContextCompat.getColor(c, R.color.bg)
        return ColorUtils.blendARGB(bg, noteColor, if (isNight(c)) 0.28f else 0.55f)
    }

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /** Paints status/navigation bars to [color] with matching icon contrast. */
    @Suppress("DEPRECATION")
    fun tintBars(w: Window, color: Int) {
        w.statusBarColor = color; w.navigationBarColor = color
        var f = w.decorView.systemUiVisibility
        f = if (ColorUtils.calculateLuminance(color) > 0.5) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        w.decorView.systemUiVisibility = f
    }

    /** A round colour swatch; [color]==null draws the "all" ring. Shows a check when [selected]. */
    fun swatch(c: Context, color: Int?, selected: Boolean, label: String, onClick: () -> Unit): View {
        val size = dp(c, 34)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (color != null) setColor(color) else setColor(Color.TRANSPARENT)
            setStroke(dp(c, if (selected) 2 else 1), ContextCompat.getColor(c, if (selected) R.color.on_surface else R.color.outline))
        }
        return FrameLayout(c).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply { setMargins(dp(c, 5), 0, dp(c, 5), 0) }
            background = circle
            contentDescription = label
            isClickable = true
            setOnClickListener { onClick() }
            if (selected) addView(ImageView(c).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(if (color == null) ContextCompat.getColor(c, R.color.on_surface) else Color.parseColor("#1B1B1F"))
                setPadding(dp(c, 7), dp(c, 7), dp(c, 7), dp(c, 7))
            })
        }
    }
}
