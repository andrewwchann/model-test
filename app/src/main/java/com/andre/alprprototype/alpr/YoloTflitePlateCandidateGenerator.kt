package com.andre.alprprototype.alpr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class YoloTflitePlateCandidateGenerator private constructor(
    private val interpreter: Interpreter,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val inputDataType: DataType,
) : PlateCandidateGenerator {

    override val name: String = "yolo-tflite"
    private var lastDebugText: String? = null

    override fun generate(image: ImageProxy): List<PlateCandidate> {
        val uprightBitmap = image.toUprightBitmap() ?: return emptyList()
        val detections = detectOnUprightBitmap(uprightBitmap) ?: return emptyList()
        return detections
            .map { detection ->
                lastDebugText = buildLiveDebugText(
                    image = image,
                    uprightWidth = uprightBitmap.width,
                    uprightHeight = uprightBitmap.height,
                    uprightRect = detection.boundingBox,
                    finalRect = detection.boundingBox,
                )
                PlateCandidate(
                    boundingBox = detection.boundingBox,
                    confidence = detection.confidence,
                    source = name,
                )
            }
    }

    fun detectBitmap(bitmap: Bitmap): List<PlateCandidate> {
        val detections = detectOnUprightBitmap(bitmap) ?: return emptyList()
        return detections
            .map { detection ->
                PlateCandidate(
                    boundingBox = detection.boundingBox,
                    confidence = detection.confidence,
                    source = name,
                )
            }
    }

    override fun close() {
        interpreter.close()
    }

    override fun lastDebugInfo(): String? = lastDebugText

    private fun preprocess(bitmap: Bitmap): PreprocessedFrame {
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

    private fun runInference(frame: PreprocessedFrame): List<RawDetection>? {
        if (frame.inputBuffer.capacity() == 0) {
            return null
        }

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        val outputType = outputTensor.dataType()
        if (outputShape.size != 3 || outputType != DataType.FLOAT32) {
            return null
        }

        val totalValues = outputShape.fold(1) { acc, value -> acc * value }
        val outputBuffer = ByteBuffer.allocateDirect(totalValues * 4).order(ByteOrder.nativeOrder())
        interpreter.run(frame.inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val values = FloatArray(totalValues)
        outputBuffer.asFloatBuffer().get(values)
        return decodeDetections(values, outputShape)
    }

    private fun detectOnUprightBitmap(bitmap: Bitmap): List<BitmapDetection>? {
        val preprocessed = preprocess(bitmap)
        val detections = runInference(preprocessed) ?: return null
        return detections.mapNotNull { detection ->
            val uprightRect = mapDetectionToUprightRect(detection, preprocessed, bitmap.width, bitmap.height)
                ?: return@mapNotNull null
            BitmapDetection(
                boundingBox = uprightRect,
                confidence = detection.confidence,
            )
        }
    }

    private fun buildLiveDebugText(
        image: ImageProxy,
        uprightWidth: Int,
        uprightHeight: Int,
        uprightRect: RectF,
        finalRect: RectF,
    ): String {
        return "Live frame: rot=${image.imageInfo.rotationDegrees} src=${image.width}x${image.height} upright=${uprightWidth}x${uprightHeight} upBox=${uprightRect.width().toInt()}x${uprightRect.height().toInt()} finalBox=${finalRect.width().toInt()}x${finalRect.height().toInt()}"
    }

    private fun decodeDetections(values: FloatArray, shape: IntArray): List<RawDetection> {
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

    private fun mapDetectionToUprightRect(
        detection: RawDetection,
        frame: PreprocessedFrame,
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

    companion object {
        private const val MODEL_ASSET_PATH = "models/license_plate_detector.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 6

        fun createOrNull(context: Context): YoloTflitePlateCandidateGenerator? {
            val modelBuffer = loadModelBuffer(context, MODEL_ASSET_PATH) ?: return null
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interpreter = Interpreter(modelBuffer, options)
            val inputShape = interpreter.getInputTensor(0).shape()
            val inputType = interpreter.getInputTensor(0).dataType()
            if (inputShape.size != 4) {
                interpreter.close()
                return null
            }

            val inputHeight: Int
            val inputWidth: Int
            if (inputShape[1] == 3) {
                inputHeight = inputShape[2]
                inputWidth = inputShape[3]
            } else {
                inputHeight = inputShape[1]
                inputWidth = inputShape[2]
            }

            return YoloTflitePlateCandidateGenerator(
                interpreter = interpreter,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                inputDataType = inputType,
            )
        }

        private fun loadModelBuffer(context: Context, assetPath: String): ByteBuffer? {
            return try {
                context.assets.openFd(assetPath).use { descriptor ->
                    descriptor.createInputStream().channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                    )
                }
            } catch (_: Exception) {
                try {
                    val bytes = context.assets.open(assetPath).use { it.readBytes() }
                    ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                        put(bytes)
                        rewind()
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

        private fun iou(a: RawDetection, b: RawDetection): Float {
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
    }
}

private data class RawDetection(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
)

data class BitmapDetection(
    val boundingBox: RectF,
    val confidence: Float,
)

private data class PreprocessedFrame(
    val inputBuffer: ByteBuffer,
    val scale: Float,
    val dx: Float,
    val dy: Float,
)

private fun ImageProxy.toUprightBitmap(): Bitmap? {
    val nv21 = toNv21Bytes() ?: return null
    val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val jpegOutput = ByteArrayOutputStream()
    if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), 92, jpegOutput)) {
        return null
    }

    val decoded = BitmapFactory.decodeByteArray(
        jpegOutput.toByteArray(),
        0,
        jpegOutput.size(),
    ) ?: return null

    val matrix = Matrix().apply {
        postRotate(imageInfo.rotationDegrees.toFloat())
    }

    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

private fun ImageProxy.toNv21Bytes(): ByteArray? {
    if (planes.size < 3) {
        return null
    }

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuffer = yPlane.buffer.duplicate().apply { rewind() }
    val uBuffer = uPlane.buffer.duplicate().apply { rewind() }
    val vBuffer = vPlane.buffer.duplicate().apply { rewind() }

    val nv21 = ByteArray(width * height * 3 / 2)
    var outputOffset = 0

    copyPlane(
        buffer = yBuffer,
        rowStride = yPlane.rowStride,
        pixelStride = yPlane.pixelStride,
        planeWidth = width,
        planeHeight = height,
        output = nv21,
        outputOffset = outputOffset,
        outputPixelStride = 1,
    )
    outputOffset += width * height

    val chromaWidth = width / 2
    val chromaHeight = height / 2
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
            val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
            nv21[outputOffset++] = vBuffer.get(vIndex)
            nv21[outputOffset++] = uBuffer.get(uIndex)
        }
    }

    return nv21
}

private fun copyPlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    planeWidth: Int,
    planeHeight: Int,
    output: ByteArray,
    outputOffset: Int,
    outputPixelStride: Int,
) {
    var outOffset = outputOffset
    for (row in 0 until planeHeight) {
        for (col in 0 until planeWidth) {
            val index = row * rowStride + col * pixelStride
            output[outOffset] = buffer.get(index)
            outOffset += outputPixelStride
        }
    }
}
