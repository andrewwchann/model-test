package com.andre.alprprototype.ocr

import android.graphics.Bitmap
import com.andre.alprprototype.OcrDisplayResult

internal object PlateOcrRecognitionFlow {
    fun recognize(
        fileExists: Boolean,
        filePath: String,
        bitmap: Bitmap?,
        buildVariants: (Bitmap) -> List<Bitmap>,
        runInference: (Bitmap) -> ScoredOcrCandidate?,
    ): OcrDisplayResult? {
        if (!fileExists || bitmap == null) {
            return null
        }

        val candidates = mutableListOf<ScoredOcrCandidate>()
        val variants = buildVariants(bitmap)
        var variantsTried = 0

        for (variant in variants) {
            variantsTried += 1
            val current = runInference(variant)
            if (current != null) {
                candidates += current
                if (current.confidence >= 0.92f) {
                    break
                }
            }
        }

        val bestResult = candidates.maxByOrNull { it.score } ?: return null
        val agreementCount = candidates.count { it.text == bestResult.text }
        val secondBest = candidates
            .filterNot { it === bestResult }
            .maxByOrNull { it.score }
        val scoreMargin = secondBest?.let { bestResult.score - it.score }

        return OcrDisplayResult(
            text = bestResult.text,
            sourcePath = filePath,
            confidence = bestResult.confidence,
            agreementCount = agreementCount,
            variantCount = variantsTried,
            scoreMargin = scoreMargin,
        )
    }
}
