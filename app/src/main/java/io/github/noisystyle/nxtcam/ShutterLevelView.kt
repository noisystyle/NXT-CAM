package io.github.noisystyle.nxtcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class ShutterLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var enabledLevel: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private var rollDegrees = 0f
    private var topDownLevel = false
    private var bubbleX = 0f
    private var bubbleY = 0f
    private var filteredFlatness = 0f
    private var horizonIsLevel = false
    private var planeIsLevel = false
    private var initialized = false
    private var lastUpdateNanos = 0L
    private var pendingTopDownMode: Boolean? = null
    private var pendingModeSinceMs = 0L

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun updateLevel(roll: Float, deviceX: Float, deviceY: Float, deviceZ: Float) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val deltaSeconds = if (lastUpdateNanos == 0L) {
            1f / 30f
        } else {
            ((nowNanos - lastUpdateNanos) / 1_000_000_000f).coerceIn(1f / 120f, 0.12f)
        }
        lastUpdateNanos = nowNanos

        val targetRoll = roll
            .coerceIn(-MAX_ROLL_DEGREES, MAX_ROLL_DEGREES)
            .let { if (abs(it) <= HORIZON_ZERO_DEADBAND) 0f else it }
        val flatness = abs(deviceZ).coerceIn(0f, 1f)
        if (!initialized) {
            rollDegrees = targetRoll
            filteredFlatness = flatness
            topDownLevel = flatness >= TOP_DOWN_ENTER_THRESHOLD
            val initialBubble = stabilizedBubble(deviceX, deviceY)
            bubbleX = initialBubble.first
            bubbleY = initialBubble.second
            initialized = true
        } else {
            // Roll becomes ill-defined near a flat position. Freeze it before switching to
            // the two-axis plane level instead of letting the horizon indicator twitch.
            if (filteredFlatness < HORIZON_FREEZE_THRESHOLD) {
                rollDegrees = smooth(
                    current = rollDegrees,
                    target = targetRoll,
                    deltaSeconds = deltaSeconds,
                    timeConstant = HORIZON_TIME_CONSTANT
                )
            }
            filteredFlatness = smooth(
                current = filteredFlatness,
                target = flatness,
                deltaSeconds = deltaSeconds,
                timeConstant = FLATNESS_TIME_CONSTANT
            )
        }

        updateLevelMode(filteredFlatness)
        if (topDownLevel) {
            val (targetX, targetY) = stabilizedBubble(deviceX, deviceY)
            bubbleX = smooth(
                current = bubbleX,
                target = targetX,
                deltaSeconds = deltaSeconds,
                timeConstant = PLANE_TIME_CONSTANT
            )
            bubbleY = smooth(
                current = bubbleY,
                target = targetY,
                deltaSeconds = deltaSeconds,
                timeConstant = PLANE_TIME_CONSTANT
            )
        }
        updateStableLevelStates()
        postInvalidateOnAnimation()
    }

    fun resetSmoothing() {
        initialized = false
        lastUpdateNanos = 0L
        pendingTopDownMode = null
        horizonIsLevel = false
        planeIsLevel = false
    }

    private fun stabilizedBubble(deviceX: Float, deviceY: Float): Pair<Float, Float> {
        val normalizedX = (deviceX / MAX_BUBBLE_TILT).coerceIn(-1f, 1f)
        val normalizedY = (deviceY / MAX_BUBBLE_TILT).coerceIn(-1f, 1f)
        val distance = hypot(normalizedX.toDouble(), normalizedY.toDouble()).toFloat()
        if (distance <= PLANE_CENTER_DEAD_ZONE) return 0f to 0f

        // Remove the noisy center continuously, without a jump at the dead-zone edge.
        val adjustedDistance = ((distance - PLANE_CENTER_DEAD_ZONE) /
            (1f - PLANE_CENTER_DEAD_ZONE)).coerceIn(0f, 1f)
        val scale = adjustedDistance / distance
        return normalizedX * scale to normalizedY * scale
    }

    private fun updateLevelMode(flatness: Float) {
        val requestedMode = if (topDownLevel) {
            flatness >= TOP_DOWN_EXIT_THRESHOLD
        } else {
            flatness >= TOP_DOWN_ENTER_THRESHOLD
        }
        if (requestedMode == topDownLevel) {
            pendingTopDownMode = null
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (pendingTopDownMode != requestedMode) {
            pendingTopDownMode = requestedMode
            pendingModeSinceMs = now
            return
        }
        if (now - pendingModeSinceMs >= MODE_SWITCH_STABILITY_MS) {
            topDownLevel = requestedMode
            pendingTopDownMode = null
        }
    }

    private fun updateStableLevelStates() {
        val absoluteRoll = abs(rollDegrees)
        horizonIsLevel = if (horizonIsLevel) {
            absoluteRoll <= HORIZON_LEVEL_EXIT_DEGREES
        } else {
            absoluteRoll <= HORIZON_LEVEL_ENTER_DEGREES
        }

        val planeDistance = hypot(bubbleX.toDouble(), bubbleY.toDouble())
        planeIsLevel = if (planeIsLevel) {
            planeDistance <= PLANE_LEVEL_EXIT
        } else {
            planeDistance <= PLANE_LEVEL_ENTER
        }
    }

    private fun smooth(
        current: Float,
        target: Float,
        deltaSeconds: Float,
        timeConstant: Float
    ): Float {
        val difference = target - current
        val alpha = (deltaSeconds / (timeConstant + deltaSeconds)).coerceIn(0.02f, 0.22f)
        return current + difference * alpha
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabledLevel) return
        val cx = width / 2f
        val cy = height / 2f
        if (topDownLevel) {
            drawTopDownLevel(canvas, cx, cy)
        } else {
            drawHorizonLevel(canvas, cx, cy)
        }
    }

    private fun drawHorizonLevel(canvas: Canvas, cx: Float, cy: Float) {
        linePaint.color = indicatorColor(horizonIsLevel)
        canvas.save()
        canvas.rotate(-rollDegrees, cx, cy)
        val halfWidth = 23f * density
        val centerGap = 5f * density
        canvas.drawLine(cx - halfWidth, cy, cx - centerGap, cy, linePaint)
        canvas.drawLine(cx + centerGap, cy, cx + halfWidth, cy, linePaint)
        canvas.restore()
        bubblePaint.color = indicatorColor(horizonIsLevel)
        canvas.drawRect(
            cx - 1.5f * density,
            cy - 1.5f * density,
            cx + 1.5f * density,
            cy + 1.5f * density,
            bubblePaint
        )
    }

    private fun drawTopDownLevel(canvas: Canvas, cx: Float, cy: Float) {
        val color = indicatorColor(planeIsLevel)
        linePaint.color = color
        bubblePaint.color = color
        val targetRadius = 11f * density
        val travelRadius = 18f * density
        canvas.drawRect(
            cx - targetRadius,
            cy - targetRadius,
            cx + targetRadius,
            cy + targetRadius,
            linePaint
        )
        canvas.drawRect(
            cx + bubbleX * travelRadius - 3f * density,
            cy + bubbleY * travelRadius - 3f * density,
            cx + bubbleX * travelRadius + 3f * density,
            cy + bubbleY * travelRadius + 3f * density,
            bubblePaint
        )
    }

    private fun indicatorColor(leveled: Boolean): Int =
        if (leveled) Color.rgb(18, 18, 18) else Color.WHITE

    private companion object {
        const val MAX_ROLL_DEGREES = 25f
        const val MAX_BUBBLE_TILT = 0.34f
        const val HORIZON_TIME_CONSTANT = 0.34f
        const val FLATNESS_TIME_CONSTANT = 0.30f
        const val PLANE_TIME_CONSTANT = 0.40f
        const val HORIZON_ZERO_DEADBAND = 0.45f
        const val PLANE_CENTER_DEAD_ZONE = 0.075f
        const val HORIZON_FREEZE_THRESHOLD = 0.80f
        const val HORIZON_LEVEL_ENTER_DEGREES = 0.75f
        const val HORIZON_LEVEL_EXIT_DEGREES = 1.35f
        const val PLANE_LEVEL_ENTER = 0.045
        const val PLANE_LEVEL_EXIT = 0.075
        const val TOP_DOWN_ENTER_THRESHOLD = 0.88f
        const val TOP_DOWN_EXIT_THRESHOLD = 0.78f
        const val MODE_SWITCH_STABILITY_MS = 450L
    }
}
