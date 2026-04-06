package com.andre.alprprototype

import com.andre.alprprototype.ocr.AssistedOcrPolicy
import com.andre.alprprototype.ocr.AssistedOcrPolicyConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistedOcrPolicyTest {
    private val config = AssistedOcrPolicyConfig(
        minTextLength = 5,
        minConfidence = 0.70f,
        minAgreement = 2,
        minScoreMargin = 0.08f,
    )

    @Test
    fun shouldEscalate_when_result_is_null() {
        assertTrue(AssistedOcrPolicy.shouldEscalateToManual(null, config))
    }

    @Test
    fun shouldEscalate_when_text_is_blank() {
        assertTrue(
            AssistedOcrPolicy.shouldEscalateToManual(
                result = ocrResult(text = ""),
                config = config,
            ),
        )
    }

    @Test
    fun shouldEscalate_when_text_is_too_short() {
        assertTrue(
            AssistedOcrPolicy.shouldEscalateToManual(
                result = ocrResult(text = "AB12"),
                config = config,
            ),
        )
    }

    @Test
    fun shouldEscalate_when_confidence_is_too_low() {
        assertTrue(
            AssistedOcrPolicy.shouldEscalateToManual(
                result = ocrResult(text = "ABC123", confidence = 0.69f),
                config = config,
            ),
        )
    }

    @Test
    fun shouldEscalate_when_confidence_is_missing() {
        assertTrue(
            AssistedOcrPolicy.shouldEscalateToManual(
                result = ocrResult(text = "ABC123", confidence = null),
                config = config,
            ),
        )
    }

    @Test
    fun shouldEscalate_when_multi_variant_agreement_is_too_low() {
        assertTrue(
            AssistedOcrPolicy.shouldEscalateToManual(
                result = ocrResult(text = "ABC123", agreementCount = 1, variantCount = 2),
                config = config,
            ),
        )
    }

    @Test
    fun shouldEscalate_when_multi_variant_margin_is_too_low() {
        assertTrue(
            AssistedOcrPolicy.shouldEscalateToManual(
                result = ocrResult(text = "ABC123", agreementCount = 2, variantCount = 2, scoreMargin = 0.07f),
                config = config,
            ),
        )
    }

    @Test
    fun shouldEscalate_when_multi_variant_margin_is_missing() {
        assertTrue(
            AssistedOcrPolicy.shouldEscalateToManual(
                result = ocrResult(text = "ABC123", agreementCount = 2, variantCount = 2, scoreMargin = null),
                config = config,
            ),
        )
    }

    @Test
    fun shouldNotEscalate_when_result_meets_thresholds() {
        assertFalse(
            AssistedOcrPolicy.shouldEscalateToManual(
                result = ocrResult(text = "ABC123", confidence = 0.85f, agreementCount = 2, variantCount = 2, scoreMargin = 0.10f),
                config = config,
            ),
        )
    }

    @Test
    fun shouldNotRequire_agreement_or_margin_for_single_variant() {
        assertFalse(
            AssistedOcrPolicy.shouldEscalateToManual(
                result = ocrResult(text = "ABC123", confidence = 0.85f, agreementCount = 0, variantCount = 1, scoreMargin = 0.0f),
                config = config,
            ),
        )
    }

    private fun ocrResult(
        text: String,
        confidence: Float? = 0.80f,
        agreementCount: Int = 2,
        variantCount: Int = 2,
        scoreMargin: Float? = 0.10f,
    ): OcrDisplayResult {
        return OcrDisplayResult(
            text = text,
            sourcePath = "crop.jpg",
            confidence = confidence,
            agreementCount = agreementCount,
            variantCount = variantCount,
            scoreMargin = scoreMargin,
        )
    }
}
