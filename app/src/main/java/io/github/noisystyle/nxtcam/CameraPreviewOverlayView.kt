package io.github.noisystyle.nxtcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot

class CameraPreviewOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var strength: Float = 0.62f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateShader(width, height)
            invalidate()
        }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateShader(width, height)
    }

    private fun updateShader(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val edgeAlpha = (82f * strength).toInt().coerceIn(0, 82)
        paint.shader = RadialGradient(
            width / 2f,
            height * 0.48f,
            hypot(width.toFloat(), height.toFloat()) * 0.58f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(edgeAlpha / 3, 0, 0, 0),
                Color.argb(edgeAlpha, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
