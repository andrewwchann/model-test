package com.andre.alprprototype

import kotlin.math.abs

internal class MotionStabilityGate(
    private val stableThreshold: Float = 9f,
    private val pauseThreshold: Float = 18f,
    private val stableFramesToResume: Int = 4,
) {
    private var previousMotionSignature: IntArray? = null
    private var stableFrameCount: Int = 0
    private var motionPauseActive: Boolean = false

    fun shouldProcess(signature: IntArray?): Boolean {
        if (signature == null) {
            return true
        }

        val motionScore = previousMotionSignature?.let { computeMotionScore(it, signature) } ?: 0f
        previousMotionSignature = signature
        val stableNow = motionScore <= stableThreshold
        stableFrameCount = if (stableNow) stableFrameCount + 1 else 0
        if (!stableNow && motionScore >= pauseThreshold) {
            motionPauseActive = true
        } else if (motionPauseActive && stableFrameCount >= stableFramesToResume) {
            motionPauseActive = false
        }
        return !motionPauseActive
    }

    fun reset() {
        previousMotionSignature = null
        stableFrameCount = 0
        motionPauseActive = false
    }

    private fun computeMotionScore(previous: IntArray, current: IntArray): Float {
        if (previous.size != current.size || previous.isEmpty()) {
            return Float.MAX_VALUE
        }
        var totalDelta = 0f
        for (index in previous.indices) {
            totalDelta += abs(previous[index] - current[index]).toFloat()
        }
        return totalDelta / previous.size
    }
}
