package com.andre.alprprototype

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class OcrDisplayResult(
    val text: String,
    val sourcePath: String,
    val confidence: Float?,
    val agreementCount: Int,
    val variantCount: Int,
    val scoreMargin: Float?,
)

class PlateOcrEngine(context: Context) {
    private val tag = "PlateOcrEngine"
    private val env = OrtEnvironment.getEnvironment()
    private val config = loadPlateConfig(context, "ocr/plate_config.yaml")
    private val session = env.createSession(
        copyAssetToCache(context, "ocr/plate_ocr.onnx").absolutePath,
        OrtSession.SessionOptions(),
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isClosed = AtomicBoolean(false)
    private val inputName: String
    private val plateOutputName: String

    init {
        val validation = validateModelContract()
        inputName = validation.inputName
        plateOutputName = validation.plateOutputName
    }

    fun recognize(
        cropPath: String,
        onResult: (OcrDisplayResult?) -> Unit,
    ) {
        if (isClosed.get()) {
            onResult(null)
            return
        }
        executor.execute {
            if (isClosed.get()) {
                onResult(null)
                return@execute
            }
            val totalStartNs = System.nanoTime()
            val result = try {
                val file = File(cropPath)
                val decodeStartNs = System.nanoTime()
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                val decodeDurationMs = nanosToMillis(System.nanoTime() - decodeStartNs)
                if (!file.exists() || bitmap == null) {
                    if (BuildConfig.ALPR_PERF_LOGS_ENABLED) {
                        Log.d("ALPR_PERF", "ocr file=${file.name} decodeMs=$decodeDurationMs result=missing")
                    }
                    null
                } else {
                    val candidates = mutableListOf<ScoredOcrCandidate>()
                    val variants = buildVariants(bitmap)
                    var inferenceDurationMs = 0L
                    var variantsTried = 0
                    
                    for (variant in variants) {
                        variantsTried += 1
                        val inferenceStartNs = System.nanoTime()
                        val current = runInference(variant, file.absolutePath)
                        inferenceDurationMs += nanosToMillis(System.nanoTime() - inferenceStartNs)
                        if (current != null) {
                            candidates += current
                            val bestSoFar = candidates.maxByOrNull { it.score }
                            // Exit early once a very confident OCR result is found.
                            // exit early to save CPU/Time.
                            if (bestSoFar != null && current.confidence >= 0.92f) {
                                break
                            }
                        }
                    }

                    val bestResult = candidates.maxByOrNull { it.score }
                    val agreementCount = bestResult?.let { best ->
                        candidates.count { it.text == best.text }
                    } ?: 0
                    val scoreMargin = bestResult?.let { best ->
                        val secondBest = candidates
                            .filterNot { it === best }
                            .maxByOrNull { it.score }
                        secondBest?.let { best.score - it.score }
                    }

                    if (BuildConfig.ALPR_PERF_LOGS_ENABLED) {
                        val totalDurationMs = nanosToMillis(System.nanoTime() - totalStartNs)
                        Log.d(
                            "ALPR_PERF",
                            "ocr file=${file.name} decodeMs=$decodeDurationMs inferMs=$inferenceDurationMs " +
                                "variants=$variantsTried agree=$agreementCount margin=${scoreMargin ?: -1f} " +
                                "text='${bestResult?.text ?: ""}' totalMs=$totalDurationMs",
                        )
                    }

                    bestResult?.let {
                        OcrDisplayResult(
                            text = it.text,
                            sourcePath = file.absolutePath,
                            confidence = it.confidence,
                            agreementCount = agreementCount,
                            variantCount = variantsTried,
                            scoreMargin = scoreMargin,
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "ocr failed for path=$cropPath", t)
                null
            }
            onResult(result)
        }
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            return
        }
        executor.shutdownNow()
        executor.awaitTermination(300, TimeUnit.MILLISECONDS)
        session.close()
    }

    private fun runInference(bitmap: Bitmap, sourcePath: String): ScoredOcrCandidate? {
        val prepared = preprocess(bitmap)
        val tensor = OnnxTensor.createTensor(env, prepared.buffer, prepared.shape, OnnxJavaType.UINT8)
        tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { output ->
                val plateValue = output.get(plateOutputName).orElse(null)
                val logits = extractPlateLogits(plateValue?.value) ?: return null
                val decoded = decodeFixedSlots(logits)
                val finalText = normalizePlateText(decoded.rawText)
                if (finalText.isBlank()) return null
                
                return ScoredOcrCandidate(
                    text = finalText,
                    score = scoreCandidate(finalText, decoded.averageConfidence),
                    confidence = decoded.averageConfidence,
                )
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): PreparedInput {
        val targetWidth = config.imgWidth
        val targetHeight = config.imgHeight
        
        // Reliability Pillar: Use bilinear filtering for better OCR quality on small plates
        val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        
        val buffer = ByteBuffer.allocateDirect(targetWidth * targetHeight * 3)
        val pixels = IntArray(targetWidth * targetHeight)
        resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        // Optimization: Manual loop is still fastest for ARGB -> RGB UINT8 conversion in pure Kotlin
        // but we ensure we are reusing the pixel array to reduce GC pressure.
        for (pixel in pixels) {
            buffer.put(((pixel shr 16) and 0xFF).toByte())
            buffer.put(((pixel shr 8) and 0xFF).toByte())
            buffer.put((pixel and 0xFF).toByte())
        }
        buffer.rewind()

        return PreparedInput(
            buffer = buffer,
            shape = longArrayOf(1, targetHeight.toLong(), targetWidth.toLong(), 3),
            width = targetWidth,
            height = targetHeight,
        )
    }

    private fun extractPlateLogits(rawValue: Any?): Array<FloatArray>? {
        return when (rawValue) {
            is Array<*> -> {
                val first = rawValue.firstOrNull()
                when {
                    rawValue.isArrayOf<FloatArray>() -> rawValue.filterIsInstance<FloatArray>().toTypedArray()
                    first is Array<*> && first.isArrayOf<FloatArray>() ->
                        first.filterIsInstance<FloatArray>().toTypedArray()
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun decodeFixedSlots(logits: Array<FloatArray>): DecodedPlate {
        val chars = StringBuilder()
        val slotPredictions = mutableListOf<SlotPrediction>()

        for (slotIndex in logits.indices) {
            val slot = logits[slotIndex]
            var bestIndex = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (classIndex in slot.indices) {
                if (slot[classIndex] > bestValue) {
                    bestValue = slot[classIndex]
                    bestIndex = classIndex
                }
            }

            val predictedChar = config.alphabet.getOrNull(bestIndex) ?: config.padChar
            chars.append(predictedChar)
            slotPredictions += SlotPrediction(
                slotIndex = slotIndex,
                classIndex = bestIndex,
                character = predictedChar,
                confidence = bestValue,
            )
        }

        val rawText = chars.toString()
        val trimmed = rawText.trimEnd(config.padChar).trim()
        val averageConfidence = if (slotPredictions.isEmpty()) 0f else slotPredictions.map { it.confidence }.average().toFloat()
        return DecodedPlate(
            rawText = rawText,
            finalText = trimmed,
            averageConfidence = averageConfidence,
            slots = slotPredictions,
        )
    }

    private fun normalizePlateText(raw: String): String {
        return raw.trimEnd(config.padChar).trim()
    }

    private fun scoreCandidate(text: String, averageConfidence: Float): Float {
        // Reliability: Reward longer strings slightly as they are less likely to be noise
        return averageConfidence + (text.length * 0.02f)
    }

    private fun validateModelContract(): ModelValidation {
        val inputEntry = session.inputInfo.entries.firstOrNull() ?: throw IllegalStateException("OCR model has no inputs")
        val inputTensor = inputEntry.value.info as? TensorInfo ?: throw IllegalStateException("OCR model input is not a tensor")
        val plateEntry = session.outputInfo["plate"] ?: throw IllegalStateException("OCR model missing 'plate' output")
        val plateTensor = plateEntry.info as? TensorInfo ?: throw IllegalStateException("OCR model 'plate' output is not a tensor")

        return ModelValidation(
            inputName = inputEntry.key,
            plateOutputName = "plate",
        )
    }

    private fun buildVariants(bitmap: Bitmap): List<Bitmap> {
        val variants = mutableListOf<Bitmap>()
        variants += bitmap // Original
        // Reliability: Different crops help if the detector bounding box was slightly off
        focusedBandCrop(bitmap, 0.20f, 0.85f)?.let { variants += it }
        return variants.distinctBy { "${it.width}x${it.height}" }
    }

    private fun focusedBandCrop(source: Bitmap, topFraction: Float, bottomFraction: Float): Bitmap? {
        val top = (source.height * topFraction).toInt().coerceIn(0, source.height - 1)
        val bottom = (source.height * bottomFraction).toInt().coerceIn(top + 1, source.height)
        if (bottom <= top) return null
        return Bitmap.createBitmap(source, 0, top, source.width, bottom - top)
    }
}

private data class PreparedInput(
    val buffer: ByteBuffer,
    val shape: LongArray,
    val width: Int,
    val height: Int,
)

private data class ScoredOcrCandidate(
    val text: String,
    val score: Float,
    val confidence: Float,
)

private data class DecodedPlate(
    val rawText: String,
    val finalText: String,
    val averageConfidence: Float,
    val slots: List<SlotPrediction>,
)

private data class SlotPrediction(
    val slotIndex: Int,
    val classIndex: Int,
    val character: Char,
    val confidence: Float,
)

private data class ModelValidation(
    val inputName: String,
    val plateOutputName: String,
)

private data class PlateConfig(
    val maxPlateSlots: Int,
    val alphabet: String,
    val padChar: Char,
    val imgHeight: Int,
    val imgWidth: Int,
    val keepAspectRatio: Boolean,
    val imageColorMode: String,
)

private fun loadPlateConfig(context: Context, assetPath: String): PlateConfig {
    val values = mutableMapOf<String, String>()
    context.assets.open(assetPath).bufferedReader().useLines { lines ->
        lines.forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isBlank() || ':' !in line) return@forEach
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim()
            values[key] = value
        }
    }

    fun required(key: String): String = values[key] ?: throw IllegalStateException("Missing '$key' in $assetPath")

    return PlateConfig(
        maxPlateSlots = required("max_plate_slots").toInt(),
        alphabet = required("alphabet").trim('\'', '"'),
        padChar = required("pad_char").trim('\'', '"').first(),
        imgHeight = required("img_height").toInt(),
        imgWidth = required("img_width").toInt(),
        keepAspectRatio = required("keep_aspect_ratio").toBooleanStrict(),
        imageColorMode = required("image_color_mode").trim('\'', '"').lowercase(),
    )
}

private fun copyAssetToCache(context: Context, assetPath: String): File {
    val outFile = File(context.cacheDir, assetPath.substringAfterLast('/'))
    context.assets.open(assetPath).use { input ->
        outFile.outputStream().use { output -> input.copyTo(output) }
    }
    return outFile
}
