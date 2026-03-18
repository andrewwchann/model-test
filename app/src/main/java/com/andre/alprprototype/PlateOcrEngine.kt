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
            val result = try {
                val file = File(cropPath)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (!file.exists() || bitmap == null) {
                    null
                } else {
                    buildVariants(bitmap)
                        .mapNotNull { variant -> runInference(variant, file.absolutePath) }
                        .maxByOrNull { it.score }
                        ?.let { OcrDisplayResult(text = it.text, sourcePath = file.absolutePath) }
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
        Log.d(
            tag,
            "preprocess source=$sourcePath crop=${bitmap.width}x${bitmap.height} resized=${prepared.width}x${prepared.height} " +
                "tensor=${prepared.shape.joinToString(prefix = "[", postfix = "]")} rgb=NHWC uint8",
        )

        val tensor = OnnxTensor.createTensor(env, prepared.buffer, prepared.shape, OnnxJavaType.UINT8)
        tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { output ->
                val plateValue = output.get(plateOutputName).orElse(null)
                val logits = extractPlateLogits(plateValue?.value) ?: return null
                val decoded = decodeFixedSlots(logits)
                logSlotPredictions(decoded)
                val finalText = normalizePlateText(decoded.rawText)
                if (finalText.isBlank()) {
                    Log.d(tag, "decoded blank raw='${decoded.rawText}' final='${decoded.finalText}'")
                    return null
                }
                Log.d(tag, "decoded raw='${decoded.rawText}' final='$finalText' avgConf=${format(decoded.averageConfidence)}")
                return ScoredOcrCandidate(
                    text = finalText,
                    score = scoreCandidate(finalText, decoded.averageConfidence),
                )
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): PreparedInput {
        val targetWidth = config.imgWidth
        val targetHeight = config.imgHeight
        val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val pixels = IntArray(targetWidth * targetHeight)
        resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        val buffer = ByteBuffer.allocateDirect(targetWidth * targetHeight * 3)
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
        return raw
            .trimEnd(config.padChar)
            .trim()
    }

    private fun scoreCandidate(text: String, averageConfidence: Float): Float {
        return averageConfidence + (text.length * 0.05f)
    }

    private fun logSlotPredictions(decoded: DecodedPlate) {
        decoded.slots.forEach { slot ->
            Log.d(
                tag,
                "slot=${slot.slotIndex} char='${slot.character}' class=${slot.classIndex} conf=${format(slot.confidence)}",
            )
        }
        Log.d(tag, "decoded raw='${decoded.rawText}' stripped='${decoded.finalText}'")
    }

    private fun validateModelContract(): ModelValidation {
        val inputEntry = session.inputInfo.entries.firstOrNull()
            ?: throw IllegalStateException("OCR model has no inputs")
        val inputTensor = inputEntry.value.info as? TensorInfo
            ?: throw IllegalStateException("OCR model input is not a tensor")
        val plateEntry = session.outputInfo["plate"]
            ?: throw IllegalStateException("OCR model missing 'plate' output")
        val plateTensor = plateEntry.info as? TensorInfo
            ?: throw IllegalStateException("OCR model 'plate' output is not a tensor")
        val regionEntry = session.outputInfo["region"]
        if (regionEntry == null) {
            Log.w(tag, "OCR model missing optional 'region' output")
        }

        val inputShape = inputTensor.shape.map { it.toInt() }
        val plateShape = plateTensor.shape.map { it.toInt() }
        val regionShape = (regionEntry?.info as? TensorInfo)?.shape?.map { it.toInt() }

        Log.d(tag, "yaml img=${config.imgWidth}x${config.imgHeight} color=${config.imageColorMode} keepAspect=${config.keepAspectRatio}")
        Log.d(tag, "yaml alphabetLen=${config.alphabet.length} maxSlots=${config.maxPlateSlots} pad='${config.padChar}'")
        Log.d(tag, "model input name=${inputEntry.key} shape=$inputShape type=${inputTensor.type}")
        Log.d(tag, "model output plate shape=$plateShape type=${plateTensor.type}")
        if (regionShape != null) {
            Log.d(tag, "model output region shape=$regionShape")
        }

        if (config.imageColorMode != "rgb") {
            throw IllegalStateException("Expected rgb color mode, got ${config.imageColorMode}")
        }
        if (config.keepAspectRatio) {
            throw IllegalStateException("Expected keep_aspect_ratio=false for this integration")
        }
        if (inputTensor.type != OnnxJavaType.UINT8) {
            throw IllegalStateException("Expected uint8 input tensor, got ${inputTensor.type}")
        }
        if (inputShape.size != 4 || inputShape[1] != config.imgHeight || inputShape[2] != config.imgWidth || inputShape[3] != 3) {
            throw IllegalStateException("Expected NHWC [batch, ${config.imgHeight}, ${config.imgWidth}, 3], got $inputShape")
        }
        if (plateShape.size != 3 || plateShape[1] != config.maxPlateSlots || plateShape[2] != config.alphabet.length) {
            throw IllegalStateException("Expected plate output [batch, ${config.maxPlateSlots}, ${config.alphabet.length}], got $plateShape")
        }

        return ModelValidation(
            inputName = inputEntry.key,
            plateOutputName = "plate",
        )
    }

    private fun buildVariants(bitmap: Bitmap): List<Bitmap> {
        val crops = bitmap.plateFocusedCrops()
        val variants = mutableListOf<Bitmap>()
        crops.firstOrNull()?.let { variants += it }
        crops.getOrNull(1)?.let { variants += it }
        crops.getOrNull(2)?.let { variants += it }
        return variants.distinctBy { "${it.width}x${it.height}" }
    }

    private fun format(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)
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
            if (line.isBlank() || ':' !in line) {
                return@forEach
            }
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim()
            values[key] = value
        }
    }

    fun required(key: String): String =
        values[key] ?: throw IllegalStateException("Missing '$key' in $assetPath")

    val alphabet = required("alphabet").trim('\'', '"')
    val padChar = required("pad_char").trim('\'', '"').first()
    return PlateConfig(
        maxPlateSlots = required("max_plate_slots").toInt(),
        alphabet = alphabet,
        padChar = padChar,
        imgHeight = required("img_height").toInt(),
        imgWidth = required("img_width").toInt(),
        keepAspectRatio = required("keep_aspect_ratio").toBooleanStrict(),
        imageColorMode = required("image_color_mode").trim('\'', '"').lowercase(),
    )
}

private fun Bitmap.plateFocusedCrops(): List<Bitmap> {
    val crops = mutableListOf<Bitmap>()
    crops += this
    focusedBandCrop(0.18f, 0.90f)?.let { crops += it }
    focusedBandCrop(0.28f, 0.82f)?.let { crops += it }
    focusedBandCrop(0.34f, 0.76f)?.let { crops += it }
    return crops.distinctBy { "${it.width}x${it.height}" }
}

private fun Bitmap.focusedBandCrop(
    topFraction: Float,
    bottomFraction: Float,
): Bitmap? {
    val top = (height * topFraction).toInt().coerceIn(0, height - 1)
    val bottom = (height * bottomFraction).toInt().coerceIn(top + 1, height)
    if (bottom <= top) {
        return null
    }
    return Bitmap.createBitmap(this, 0, top, width, bottom - top)
}

private fun copyAssetToCache(context: Context, assetPath: String): File {
    val outFile = File(context.cacheDir, assetPath.substringAfterLast('/'))
    context.assets.open(assetPath).use { input ->
        outFile.outputStream().use { output -> input.copyTo(output) }
    }
    return outFile
}
