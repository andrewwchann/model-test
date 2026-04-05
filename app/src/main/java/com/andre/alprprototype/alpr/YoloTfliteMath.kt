package com.andre.alprprototype.alpr

import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

internal object YoloTfliteMath {
    fun preprocess(
        bitmap: Bitmap,
        inputWidth: Int,
        inputHeight: Int,
        inputDataType: DataType,
    ): PreprocessedFrame {
        val scale = min(inputWidth / bitmap.width.toFloat(), inputHeight / bitmap.height.toFloat())
        val scaledWidth = max(1, (bitmap.width * scale).toInt())
        val scaledHeight = max(1, (bitmap.height * scale).toInt())
        val dx = (inputWidth - scaledWidth) / 2f
        val dy = (inputHeight - scaledHeight) / 2f

        val resized = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val letterboxed = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(letterboxed)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(resized, dx, dy, null)

        val inputBuffer = when (inputDataType) {
            DataType.FLOAT32 -> ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4).order(ByteOrder.nativeOrder())
            DataType.UINT8 -> ByteBuffer.allocateDirect(inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder())
            else -> return PreprocessedFrame(ByteBuffer.allocateDirect(0), scale, dx, dy)
        }

        val pixels = IntArray(inputWidth * inputHeight)
        letterboxed.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        when (inputDataType) {
            DataType.FLOAT32 -> {
                for (pixel in pixels) {
                    inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                    inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                    inputBuffer.putFloat((pixel and 0xFF) / 255f)
                }
            }
            DataType.UINT8 -> {
                for (pixel in pixels) {
                    inputBuffer.put(((pixel shr 16) and 0xFF).toByte())
                    inputBuffer.put(((pixel shr 8) and 0xFF).toByte())
                    inputBuffer.put((pixel and 0xFF).toByte())
                }
            }
            else -> Unit
        }
        inputBuffer.rewind()

        return PreprocessedFrame(
            inputBuffer = inputBuffer,
            scale = scale,
            dx = dx,
            dy = dy,
        )
    }

    fun decodeDetections(values: FloatArray, shape: IntArray): List<RawDetection> {
        val second = shape[1]
        val third = shape[2]
        val channelsFirst = second <= third
        val channelCount = if (channelsFirst) second else third
        val boxCount = if (channelsFirst) third else second
        if (channelCount < 5) {
            return emptyList()
        }

        val detections = mutableListOf<RawDetection>()
        for (index in 0 until boxCount) {
            val cx = read(values, channelsFirst, boxCount, channelCount, index, 0)
            val cy = read(values, channelsFirst, boxCount, channelCount, index, 1)
            val width = read(values, channelsFirst, boxCount, channelCount, index, 2)
            val height = read(values, channelsFirst, boxCount, channelCount, index, 3)
            val confidence = readConfidence(values, channelsFirst, boxCount, channelCount, index)
            if (confidence < CONFIDENCE_THRESHOLD) {
                continue
            }
            detections += RawDetection(cx, cy, width, height, confidence)
        }

        return nonMaxSuppression(detections)
    }

    fun mapDetectionToUprightRect(
        detection: RawDetection,
        frame: PreprocessedFrame,
        inputWidth: Int,
        inputHeight: Int,
        uprightWidth: Int,
        uprightHeight: Int,
    ): RectF? {
        val normalized = max(
            max(detection.cx, detection.cy),
            max(detection.width, detection.height),
        ) <= 2f

        val scaleX = if (normalized) inputWidth.toFloat() else 1f
        val scaleY = if (normalized) inputHeight.toFloat() else 1f

        val left = ((detection.cx - detection.width / 2f) * scaleX - frame.dx) / frame.scale
        val top = ((detection.cy - detection.height / 2f) * scaleY - frame.dy) / frame.scale
        val right = ((detection.cx + detection.width / 2f) * scaleX - frame.dx) / frame.scale
        val bottom = ((detection.cy + detection.height / 2f) * scaleY - frame.dy) / frame.scale

        val clamped = RectF(
            left.coerceIn(0f, uprightWidth.toFloat()),
            top.coerceIn(0f, uprightHeight.toFloat()),
            right.coerceIn(0f, uprightWidth.toFloat()),
            bottom.coerceIn(0f, uprightHeight.toFloat()),
        )
        return if (clamped.width() < 8f || clamped.height() < 8f) null else clamped
    }

    fun iou(a: RawDetection, b: RawDetection): Float {
        val aLeft = a.cx - a.width / 2f
        val aTop = a.cy - a.height / 2f
        val aRight = a.cx + a.width / 2f
        val aBottom = a.cy + a.height / 2f

        val bLeft = b.cx - b.width / 2f
        val bTop = b.cy - b.height / 2f
        val bRight = b.cx + b.width / 2f
        val bBottom = b.cy + b.height / 2f

        val left = max(aLeft, bLeft)
        val top = max(aTop, bTop)
        val right = min(aRight, bRight)
        val bottom = min(aBottom, bBottom)
        if (right <= left || bottom <= top) {
            return 0f
        }

        val intersection = (right - left) * (bottom - top)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun read(
        values: FloatArray,
        channelsFirst: Boolean,
        boxCount: Int,
        channelCount: Int,
        boxIndex: Int,
        channelIndex: Int,
    ): Float {
        return if (channelsFirst) {
            values[channelIndex * boxCount + boxIndex]
        } else {
            values[boxIndex * channelCount + channelIndex]
        }
    }

    private fun readConfidence(
        values: FloatArray,
        channelsFirst: Boolean,
        boxCount: Int,
        channelCount: Int,
        boxIndex: Int,
    ): Float {
        if (channelCount == 5) {
            return read(values, channelsFirst, boxCount, channelCount, boxIndex, 4)
        }

        val objectness = read(values, channelsFirst, boxCount, channelCount, boxIndex, 4)
        var bestClassScore = 0f
        for (channel in 5 until channelCount) {
            bestClassScore = max(bestClassScore, read(values, channelsFirst, boxCount, channelCount, boxIndex, channel))
        }

        return when {
            bestClassScore == 0f -> objectness
            objectness in 0f..1f && bestClassScore in 0f..1f -> objectness * bestClassScore
            else -> max(objectness, bestClassScore)
        }
    }

    private fun nonMaxSuppression(detections: List<RawDetection>): List<RawDetection> {
        val sorted = detections.sortedByDescending { it.confidence }
        val accepted = mutableListOf<RawDetection>()
        for (candidate in sorted) {
            if (accepted.none { iou(it, candidate) > IOU_THRESHOLD }) {
                accepted += candidate
            }
            if (accepted.size >= MAX_DETECTIONS) {
                break
            }
        }
        return accepted
    }

    private const val CONFIDENCE_THRESHOLD = 0.35f
    private const val IOU_THRESHOLD = 0.45f
    private const val MAX_DETECTIONS = 6
}

internal data class RawDetection(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
)

internal data class PreprocessedFrame(
    val inputBuffer: ByteBuffer,
    val scale: Float,
    val dx: Float,
    val dy: Float,
)
