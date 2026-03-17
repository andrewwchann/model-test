package com.andre.alprprototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

data class OcrDisplayResult(
    val text: String,
    val sourcePath: String,
)

class PlateOcrEngine(context: Context) {
    private val bannedWords = listOf("ALBERTA", "WILDROSE", "COUNTRY")
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(copyAssetToCache(context, "ocr/plate_ocr.onnx").absolutePath, OrtSession.SessionOptions())
    private val vocab = context.assets.open("ocr/ppocr_keys_v1.txt").bufferedReader().useLines { lines -> lines.toList() }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun recognize(
        cropPath: String,
        onResult: (OcrDisplayResult?) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val file = File(cropPath)
                if (!file.exists()) {
                    null
                } else {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap == null) {
                        null
                    } else {
                        val best = buildVariants(bitmap)
                            .mapNotNull { variant -> runInference(variant) }
                            .maxByOrNull { it.score }

                        best?.text?.let {
                            OcrDisplayResult(
                                text = it,
                                sourcePath = file.absolutePath,
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                null
            }
            onResult(result)
        }
    }

    fun close() {
        executor.shutdown()
        session.close()
    }

    private fun runInference(bitmap: Bitmap): ScoredOcrCandidate? {
        val input = preprocess(bitmap)
        val shape = longArrayOf(1, 1, 70, 140)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
        tensor.use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { output ->
                val logits = extractLogits(output[0].value) ?: return null
                val decoded = decode(logits)
                val normalized = normalizePlateText(decoded)
                if (normalized.isBlank()) {
                    return null
                }
                return ScoredOcrCandidate(
                    text = normalized,
                    score = scorePlateText(normalized),
                )
            }
        }
    }

    private fun extractLogits(rawValue: Any?): Array<FloatArray>? {
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

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap.toGrayscale(), 140, 70, true)
        val pixels = IntArray(140 * 70)
        resized.getPixels(pixels, 0, 140, 0, 0, 140, 70)
        return FloatArray(140 * 70) { index ->
            val pixel = pixels[index]
            val gray = pixel and 0xFF
            gray / 255f
        }
    }

    private fun decode(logits: Array<FloatArray>): String {
        val sb = StringBuilder()
        var prevIndex = -1
        for (step in logits) {
            var bestIndex = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in step.indices) {
                if (step[i] > bestScore) {
                    bestScore = step[i]
                    bestIndex = i
                }
            }
            if (bestIndex != 0 && bestIndex != prevIndex) {
                val charIndex = bestIndex - 1
                if (charIndex in vocab.indices) {
                    sb.append(vocab[charIndex])
                }
            }
            prevIndex = bestIndex
        }
        return sb.toString()
    }

    private fun normalizePlateText(raw: String): String {
        val compact = raw
            .uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9]"), "")

        return when {
            compact.length in 4..8 -> compact
            compact.length > 8 -> compact.take(8)
            else -> ""
        }
    }

    private fun buildVariants(bitmap: Bitmap): List<Bitmap> {
        val crops = bitmap.plateFocusedCrops()
        return crops.flatMap { crop ->
            listOf(
                crop,
                crop.scaled(2f),
                crop.focusedBandCrop(0.26f, 0.82f) ?: crop,
                crop.focusedBandCrop(0.34f, 0.78f) ?: crop,
            )
        }.distinctBy { "${it.width}x${it.height}" }
    }

    private fun scorePlateText(text: String): Int {
        if (bannedWords.any { text.contains(it) }) {
            return -1000
        }
        val letterCount = text.count { it in 'A'..'Z' }
        val digitCount = text.count { it in '0'..'9' }
        val mixedBonus = if (letterCount > 0 && digitCount > 0) 20 else 0
        val allLettersPenalty = if (digitCount == 0) 30 else 0
        return text.length * 10 + minOf(letterCount, 3) * 3 + minOf(digitCount, 4) * 4 + mixedBonus - allLettersPenalty
    }
}

private data class ScoredOcrCandidate(
    val text: String,
    val score: Int,
)

private fun Bitmap.scaled(multiplier: Float): Bitmap {
    val scaledWidth = (width * multiplier).toInt().coerceAtLeast(width)
    val scaledHeight = (height * multiplier).toInt().coerceAtLeast(height)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

private fun Bitmap.toGrayscale(): Bitmap {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
    val paint = android.graphics.Paint().apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
    }
    canvas.drawBitmap(this, 0f, 0f, paint)
    return output
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
    if (!outFile.exists()) {
        context.assets.open(assetPath).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
    return outFile
}
