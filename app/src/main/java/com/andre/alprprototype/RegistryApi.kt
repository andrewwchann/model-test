package com.andre.alprprototype

import retrofit2.http.GET
import retrofit2.http.Query

interface RegistryApi {
    @GET("sync")
    suspend fun getRegistry(@Query("version") version: Int = 0): List<RegistryPlate>
}
