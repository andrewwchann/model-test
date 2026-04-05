package com.andre.alprprototype.ocr

import android.graphics.Bitmap
import java.nio.ByteBuffer

internal object PlateOcrMath {
    fun preprocess(bitmap: Bitmap, config: PlateConfig): PreparedInput {
        val targetWidth = config.imgWidth
        val targetHeight = config.imgHeight
        val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        val buffer = ByteBuffer.allocateDirect(targetWidth * targetHeight * 3)
        val pixels = IntArray(targetWidth * targetHeight)
        resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

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

    fun extractPlateLogits(rawValue: Any?): Array<FloatArray>? {
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

    fun decodeFixedSlots(logits: Array<FloatArray>, config: PlateConfig): DecodedPlate {
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

    fun normalizePlateText(raw: String, padChar: Char): String {
        return raw.trimEnd(padChar).trim()
    }

    fun scoreCandidate(text: String, averageConfidence: Float): Float {
        return averageConfidence + (text.length * 0.02f)
    }

    fun buildVariants(bitmap: Bitmap): List<Bitmap> {
        val variants = mutableListOf<Bitmap>()
        variants += bitmap
        focusedBandCrop(bitmap, 0.20f, 0.85f)?.let { variants += it }
        return variants.distinctBy { "${it.width}x${it.height}" }
    }

    fun focusedBandCrop(source: Bitmap, topFraction: Float, bottomFraction: Float): Bitmap? {
        val top = (source.height * topFraction).toInt().coerceIn(0, source.height - 1)
        val bottom = (source.height * bottomFraction).toInt().coerceIn(top + 1, source.height)
        if (bottom <= top) return null
        return Bitmap.createBitmap(source, 0, top, source.width, bottom - top)
    }
}

internal data class PreparedInput(
    val buffer: ByteBuffer,
    val shape: LongArray,
    val width: Int,
    val height: Int,
)

internal data class ScoredOcrCandidate(
    val text: String,
    val score: Float,
    val confidence: Float,
)

internal data class DecodedPlate(
    val rawText: String,
    val finalText: String,
    val averageConfidence: Float,
    val slots: List<SlotPrediction>,
)

internal data class SlotPrediction(
    val slotIndex: Int,
    val classIndex: Int,
    val character: Char,
    val confidence: Float,
)

internal data class ModelValidation(
    val inputName: String,
    val plateOutputName: String,
)

internal data class PlateConfig(
    val maxPlateSlots: Int,
    val alphabet: String,
    val padChar: Char,
    val imgHeight: Int,
    val imgWidth: Int,
    val keepAspectRatio: Boolean,
    val imageColorMode: String,
)
