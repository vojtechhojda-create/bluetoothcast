package app.proxylink

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/** Animovana "koule" se zvlnenymi kruhy. mode: 0 vypnuto, 1 ceka, 2 parovani, 3 streamuje. */
class OrbView(ctx: Context) : View(ctx) {

    var accent: Int = Color.GRAY
        set(value) { field = value; invalidate() }

    var mode: Int = 0
        set(value) {
            if (field != value) {
                field = value
                applyMode()
            }
        }

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val link = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    private fun applyMode() {
        anim.cancel()
        if (mode == 0) {
            t = 0f
            invalidate()
            return
        }
        anim.duration = when (mode) {
            2 -> 1400L
            3 -> 2200L
            else -> 3400L
        }
        if (isAttachedToWindow) anim.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode != 0) {
            anim.duration = if (mode == 2) 1400L else if (mode == 3) 2200L else 3400L
            anim.start()
        }
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val r0 = maxR * 0.34f
        val live = mode != 0

        if (live) {
            for (i in 0..2) {
                val p = (t + i / 3f) % 1f
                ring.color = accent
                ring.alpha = ((1f - p) * (if (mode == 2) 170 else 120)).toInt()
                ring.strokeWidth = 2.5f * density
                c.drawCircle(cx, cy, r0 * 1.1f + p * (maxR - r0 * 1.1f), ring)
            }
        }
        val breathe = if (mode == 3) 1f + 0.05f * sin(t * 6.2831855f) else 1f
        fill.color = if (live) accent else 0xFF5B6070.toInt()
        c.drawCircle(cx, cy, r0 * breathe, fill)

        link.strokeWidth = r0 * 0.14f
        val lr = r0 * 0.36f
        c.drawCircle(cx - r0 * 0.24f, cy, lr, link)
        c.drawCircle(cx + r0 * 0.24f, cy, lr, link)
    }
}
