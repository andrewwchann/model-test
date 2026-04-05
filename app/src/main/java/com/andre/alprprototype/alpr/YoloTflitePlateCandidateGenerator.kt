package com.andre.alprprototype.alpr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.andre.alprprototype.BuildConfig
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class YoloTflitePlateCandidateGenerator private constructor(
    private val interpreter: Interpreter,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val inputDataType: DataType,
) : PlateCandidateGenerator {

    override val name: String = "yolo-tflite"

    override fun generate(image: ImageProxy, uprightBitmapProvider: (() -> Bitmap?)?): List<PlateCandidate> {
        val bitmap = uprightBitmapProvider?.invoke() ?: image.toUprightBitmap() ?: return emptyList()
        val detectStartNs = System.nanoTime()
        val detections = detectOnUprightBitmap(bitmap) ?: return emptyList()
        if (BuildConfig.ALPR_PERF_LOGS_ENABLED) {
            val detectDurationMs = (System.nanoTime() - detectStartNs) / 1_000_000L
            Log.d(
                "ALPR_PERF",
                "detect src=${image.width}x${image.height} upright=${bitmap.width}x${bitmap.height} " +
                    "detections=${detections.size} inferMs=$detectDurationMs",
            )
        }
        return detections
            .map { detection ->
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

    private fun preprocess(bitmap: Bitmap): PreprocessedFrame =
        YoloTfliteMath.preprocess(
            bitmap = bitmap,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            inputDataType = inputDataType,
        )

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
        return YoloTfliteMath.decodeDetections(values, outputShape)
    }

    private fun detectOnUprightBitmap(bitmap: Bitmap): List<BitmapDetection>? {
        val preprocessed = preprocess(bitmap)
        val detections = runInference(preprocessed) ?: return null
        return detections.mapNotNull { detection ->
            val uprightRect = YoloTfliteMath.mapDetectionToUprightRect(
                detection = detection,
                frame = preprocessed,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                uprightWidth = bitmap.width,
                uprightHeight = bitmap.height,
            )
                ?: return@mapNotNull null
            BitmapDetection(
                boundingBox = uprightRect,
                confidence = detection.confidence,
            )
        }
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/license_plate_detector.tflite"

        fun createOrNull(context: Context): YoloTflitePlateCandidateGenerator? {
            val modelBuffer = loadModelBuffer(context, MODEL_ASSET_PATH) ?: return null
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            val interpreter = Interpreter(modelBuffer, options)
            val inputShape = interpreter.getInputTensor(0).shape()
            val inputType = interpreter.getInputTensor(0).dataType()
            val dimensions = YoloTfliteModelLoader.interpretInputDimensions(inputShape)
            if (dimensions == null) {
                interpreter.close()
                return null
            }
            val (inputHeight, inputWidth) = dimensions

            return YoloTflitePlateCandidateGenerator(
                interpreter = interpreter,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                inputDataType = inputType,
            )
        }

        private fun loadModelBuffer(context: Context, assetPath: String): ByteBuffer? {
            return YoloTfliteModelLoader.loadModelBuffer(
                mapAssetBuffer = {
                    context.assets.openFd(assetPath).use { descriptor ->
                        descriptor.createInputStream().channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            descriptor.startOffset,
                            descriptor.declaredLength,
                        )
                    }
                },
                readAssetBytes = {
                    context.assets.open(assetPath).use { it.readBytes() }
                },
            )
        }
    }
}

data class BitmapDetection(
    val boundingBox: RectF,
    val confidence: Float,
)
