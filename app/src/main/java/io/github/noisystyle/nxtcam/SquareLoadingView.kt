package io.github.noisystyle.nxtcam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class SquareLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(68, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val outline = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    fun start() {
        if (animator?.isRunning == true) return
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1050L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = progressPaint.strokeWidth / 2f + density
        val bounds = RectF(inset, inset, width - inset, height - inset)
        outline.reset()
        outline.addRect(bounds, Path.Direction.CW)
        canvas.drawPath(outline, trackPaint)

        measure.setPath(outline, false)
        val length = measure.length
        if (length <= 0f) return
        val start = phase * length
        val end = start + length * 0.28f
        segment.reset()
        if (end <= length) {
            measure.getSegment(start, end, segment, true)
        } else {
            measure.getSegment(start, length, segment, true)
            measure.getSegment(0f, end - length, segment, true)
        }
        canvas.drawPath(segment, progressPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
