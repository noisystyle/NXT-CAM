package it.noxia.leicaai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CompositionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var enabledOverlay: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density * 0.7f
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabledOverlay) return

        canvas.drawLine(width / 3f, 0f, width / 3f, height.toFloat(), gridPaint)
        canvas.drawLine(width * 2f / 3f, 0f, width * 2f / 3f, height.toFloat(), gridPaint)
        canvas.drawLine(0f, height / 3f, width.toFloat(), height / 3f, gridPaint)
        canvas.drawLine(0f, height * 2f / 3f, width.toFloat(), height * 2f / 3f, gridPaint)
    }
}
