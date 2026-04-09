package com.andre.alprprototype

internal interface ViolationQueue {
    fun addViolation(violation: ViolationEvent): Result<ViolationEvent>

    fun getQueueSize(): Int

    suspend fun uploadQueue(): Result<Int>
}
