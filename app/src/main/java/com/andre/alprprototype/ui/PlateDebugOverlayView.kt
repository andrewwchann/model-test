package com.andre.alprprototype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.util.AttributeSet
import android.view.View
import com.andre.alprprototype.alpr.PipelineDebugState
import com.andre.alprprototype.alpr.NormalizedRect
import kotlin.math.min

class PlateDebugOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val detectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD166")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val acceptedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#52D681")
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        style = Paint.Style.FILL
    }

    private val assistedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B6B")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private var latestState: PipelineDebugState? = null
    private var assistedTargetRect: NormalizedRect? = null
    private var onTapTargetRequested: ((Float, Float) -> Unit)? = null

    fun render(state: PipelineDebugState) {
        latestState = state
        invalidate()
    }

    fun currentStateOrNull(): PipelineDebugState? = latestState

    fun showAssistedTarget(rect: NormalizedRect?) {
        assistedTargetRect = rect
        invalidate()
    }

    fun setOnTapTargetRequested(listener: ((Float, Float) -> Unit)?) {
        onTapTargetRequested = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = latestState ?: return
        val sourceWidth = state.inputWidth.toFloat()
        val sourceHeight = state.inputHeight.toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f) {
            return
        }

        state.candidates.forEachIndexed { index, candidate ->
            val mapped = mapRect(candidate.boundingBox, sourceWidth, sourceHeight)
            canvas.drawRoundRect(mapped, 14f, 14f, detectionPaint)
            canvas.drawText(
                "raw ${index + 1} ${candidate.boundingBox.width().toInt()}x${candidate.boundingBox.height().toInt()}",
                mapped.left,
                mapped.top - 12f,
                labelPaint,
            )
        }

        state.detections.forEachIndexed { index, detection ->
            val mapped = mapRect(detection.boundingBox, sourceWidth, sourceHeight)
            canvas.drawRoundRect(mapped, 18f, 18f, trackPaint)
            canvas.drawText(
                "flt ${index + 1} ${detection.boundingBox.width().toInt()}x${detection.boundingBox.height().toInt()}",
                mapped.left,
                mapped.top - 12f,
                labelPaint,
            )
        }

        state.activeTrack?.let { track ->
            val mapped = mapRect(track.boundingBox, sourceWidth, sourceHeight)
            val paint = if (state.quality?.passes == true) acceptedPaint else trackPaint
            canvas.drawRoundRect(mapped, 20f, 20f, paint)
            canvas.drawText(
                "trk ${track.trackId} ${track.boundingBox.width().toInt()}x${track.boundingBox.height().toInt()}",
                mapped.left,
                mapped.bottom + 34f,
                labelPaint,
            )
        }

        assistedTargetRect?.let { rect ->
            val mapped = RectF(
                rect.left * width,
                rect.top * height,
                rect.right * width,
                rect.bottom * height,
            )
            canvas.drawRoundRect(mapped, 20f, 20f, assistedPaint)
            canvas.drawText("tap target", mapped.left, mapped.top - 12f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listener = onTapTargetRequested ?: return super.onTouchEvent(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                listener(event.x, event.y)
                performClick()
                true
            }
            MotionEvent.ACTION_DOWN -> true
            else -> super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun mapRect(source: RectF, sourceWidth: Float, sourceHeight: Float): RectF {
        val scale = min(width / sourceWidth, height / sourceHeight)
        val dx = (width - sourceWidth * scale) / 2f
        val dy = (height - sourceHeight * scale) / 2f

        return RectF(
            dx + source.left * scale,
            dy + source.top * scale,
            dx + source.right * scale,
            dy + source.bottom * scale,
        )
    }
}
