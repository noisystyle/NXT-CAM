package io.github.noisystyle.nxtcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class OnboardingArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val architecture: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.onboarding_architecture)
    }
    private val portrait: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.onboarding_portrait)
    }
    private val rollFrame: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.onboarding_roll)
    }
    private val comparison: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.onboarding_compare)
    }
    private var pageIndex = 0

    fun showPage(index: Int) {
        pageIndex = index
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (pageIndex) {
            0 -> drawManifesto(canvas)
            1 -> drawRoll(canvas)
            2 -> drawLooks(canvas)
            else -> drawComparison(canvas)
        }
    }

    private fun drawManifesto(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawBitmapCenterCrop(canvas, architecture, RectF(0f, 0f, w, h), null)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(237, 28, 36)
        canvas.drawCircle(w - dp(14f), dp(14f), dp(4f), paint)
    }

    private fun drawRoll(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val frame = RectF(0f, h * 0.08f, w, h * 0.88f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.WHITE
        canvas.drawRect(frame, paint)
        canvas.drawLine(w * 0.42f, frame.top, w * 0.42f, frame.bottom, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.typeface = condensedBold
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(w * 0.22f, dp(82f))
        canvas.drawText("12", w * 0.21f, h * 0.52f, paint)
        paint.typeface = condensed
        paint.letterSpacing = 0.12f
        paint.textSize = dp(22f)
        canvas.drawText("FRAMES", w * 0.21f, h * 0.67f, paint)

        val rightStart = w * 0.46f
        val square = dp(11f)
        val gap = (w - rightStart - square * 5) / 6f
        paint.color = Color.rgb(95, 95, 98)
        repeat(5) { index ->
            val left = rightStart + gap + index * (square + gap)
            canvas.drawRect(left, frame.top + dp(20f), left + square, frame.top + dp(31f), paint)
            canvas.drawRect(left, frame.bottom - dp(31f), left + square, frame.bottom - dp(20f), paint)
        }

        val image = RectF(rightStart + gap, frame.top + dp(48f), w - gap, frame.bottom - dp(48f))
        drawBitmapCenterCrop(canvas, rollFrame, image, null)
        paint.letterSpacing = 0f
    }

    private fun drawLooks(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = dp(3f)
        val panelWidth = (w - gap * 2f) / 3f
        val panelBottom = h - dp(34f)
        val labels = listOf("COLOR", "B&W", "PORTRAIT")
        repeat(3) { index ->
            val left = index * (panelWidth + gap)
            val right = left + panelWidth
            val filter = when (index) {
                0 -> ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            1.08f, 0f, 0f, 0f, 4f,
                            0f, 1.01f, 0f, 0f, 0f,
                            0f, 0f, 0.92f, 0f, -3f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
                1 -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                else -> ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            1.12f, 0f, 0f, 0f, 5f,
                            0f, 1.02f, 0f, 0f, 1f,
                            0f, 0f, 0.88f, 0f, -4f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            drawBitmapCenterCrop(
                canvas,
                portrait,
                RectF(left, 0f, right, panelBottom),
                filter
            )

            paint.style = Paint.Style.FILL
            paint.typeface = condensedBold
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(12f)
            paint.color = Color.WHITE
            canvas.drawText(labels[index], left + panelWidth / 2f, h - dp(10f), paint)
        }
        paint.color = Color.WHITE
        canvas.drawRect(0f, h - dp(3f), panelWidth, h - dp(1f), paint)
    }

    private fun drawComparison(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bottom = h - dp(28f)
        val center = w / 2f

        canvas.save()
        canvas.clipRect(0f, 0f, center, bottom)
        drawBitmapCenterCrop(
            canvas,
            comparison,
            RectF(0f, 0f, w, bottom),
            ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(0.38f)
                    postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                0.72f, 0f, 0f, 0f, 34f,
                                0f, 0.72f, 0f, 0f, 34f,
                                0f, 0f, 0.72f, 0f, 34f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
            )
        )
        drawBeforeTexture(canvas, center, bottom)
        canvas.restore()

        canvas.save()
        canvas.clipRect(center, 0f, w, bottom)
        drawBitmapCenterCrop(
            canvas,
            comparison,
            RectF(0f, 0f, w, bottom),
            ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        1.14f, 0f, 0f, 0f, 6f,
                        0f, 1.03f, 0f, 0f, 1f,
                        0f, 0f, 0.88f, 0f, -5f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        )
        canvas.restore()

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(center - dp(0.75f), 0f, center + dp(0.75f), bottom, paint)
        canvas.drawRect(center - dp(18f), bottom * 0.47f, center + dp(18f), bottom * 0.58f, paint)
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = condensedBold
        paint.textSize = dp(13f)
        canvas.drawText("↔", center, bottom * 0.555f, paint)

        paint.color = Color.rgb(190, 190, 193)
        paint.typeface = condensed
        paint.textSize = dp(11f)
        canvas.drawText("ORIGINAL", w * 0.25f, h - dp(7f), paint)
        paint.color = Color.WHITE
        canvas.drawText("NXT", w * 0.75f, h - dp(7f), paint)
    }

    private fun drawBeforeTexture(canvas: Canvas, panelWidth: Float, panelHeight: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(24, 224, 226, 222)
        canvas.drawRect(0f, 0f, panelWidth, panelHeight, paint)

        var seed = 0x4E585443
        val speckCount = ((panelWidth * panelHeight) / dp(24f)).toInt().coerceIn(420, 1_200)
        repeat(speckCount) {
            seed = seed * 1_664_525 + 1_013_904_223
            val x = ((seed ushr 8) and 0xFFFF) / 65_535f * panelWidth
            seed = seed * 1_664_525 + 1_013_904_223
            val y = ((seed ushr 8) and 0xFFFF) / 65_535f * panelHeight
            seed = seed * 1_664_525 + 1_013_904_223
            paint.color = if ((seed and 1) == 0) {
                Color.argb(30, 255, 255, 255)
            } else {
                Color.argb(24, 0, 0, 0)
            }
            val radius = if ((seed and 3) == 0) dp(0.55f) else dp(0.32f)
            canvas.drawCircle(x, y, radius, paint)
        }
        paint.color = Color.WHITE
    }

    private fun drawBitmapCenterCrop(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        filter: ColorMatrixColorFilter?
    ) {
        val scale = max(
            destination.width() / bitmap.width.toFloat(),
            destination.height() / bitmap.height.toFloat()
        )
        val sourceWidth = destination.width() / scale
        val sourceHeight = destination.height() / scale
        val sourceLeft = (bitmap.width - sourceWidth) / 2f
        val sourceTop = (bitmap.height - sourceHeight) / 2f
        val source = Rect(
            sourceLeft.toInt(),
            sourceTop.toInt(),
            (sourceLeft + sourceWidth).toInt(),
            (sourceTop + sourceHeight).toInt()
        )
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.colorFilter = filter
        canvas.drawBitmap(bitmap, source, destination, paint)
        paint.colorFilter = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
