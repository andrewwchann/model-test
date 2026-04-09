package com.andre.alprprototype.session

internal class ConfirmedPlateTracker(
    private val cooldownMs: Long,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var lastConfirmedPlateText: String? = null
    private var lastConfirmedPlateAtMs: Long = 0L

    fun shouldProcess(normalizedPlateText: String): Boolean {
        if (normalizedPlateText.isBlank()) {
            return false
        }
        val nowMs = clockMs()
        val isRepeatedPlate = normalizedPlateText == lastConfirmedPlateText
        val withinCooldown = nowMs - lastConfirmedPlateAtMs < cooldownMs
        if (isRepeatedPlate && withinCooldown) {
            return false
        }
        lastConfirmedPlateText = normalizedPlateText
        lastConfirmedPlateAtMs = nowMs
        return true
    }
}
