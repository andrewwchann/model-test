package com.andre.alprprototype

internal interface PlateRegistry {
    fun isPlateValid(plateText: String): RegistryManager.PlateValidationResult

    suspend fun syncRegistry(): Result<Int>
}
