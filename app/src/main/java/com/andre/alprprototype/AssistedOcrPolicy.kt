package com.andre.alprprototype

internal data class AssistedOcrPolicyConfig(
    val minTextLength: Int,
    val minConfidence: Float,
    val minAgreement: Int,
    val minScoreMargin: Float,
)

internal object AssistedOcrPolicy {
    fun shouldEscalateToManual(
        result: OcrDisplayResult?,
        config: AssistedOcrPolicyConfig,
    ): Boolean {
        if (result == null) {
            return true
        }
        if (result.text.isBlank()) {
            return true
        }
        if (result.text.length < config.minTextLength) {
            return true
        }
        if ((result.confidence ?: 0f) < config.minConfidence) {
            return true
        }
        if (result.variantCount > 1 && result.agreementCount < config.minAgreement) {
            return true
        }
        if (result.variantCount > 1 && (result.scoreMargin ?: 0f) < config.minScoreMargin) {
            return true
        }
        return false
    }
}
