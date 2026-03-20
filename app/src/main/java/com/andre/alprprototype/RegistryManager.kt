package com.andre.alprprototype

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RegistryManager(private val context: Context) {
    private val tag = "RegistryManager"
    private val gson = Gson()
    private val cacheFile = File(context.filesDir, "plate_registry.json")
    
    // In-memory set for fast lookups
    private var registeredPlates: Map<String, RegistryPlate> = emptyMap()

    private val api: RegistryApi by lazy {
        val logging = HttpLoggingInterceptor { message -> Log.d("API_LOG", message) }
        logging.level = HttpLoggingInterceptor.Level.BODY

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl("https://lrxxawq4kk.execute-api.us-east-1.amazonaws.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RegistryApi::class.java)
    }

    init {
        loadFromCache()
    }

    private fun loadFromCache() {
        if (cacheFile.exists()) {
            try {
                val json = cacheFile.readText()
                val type = object : TypeToken<List<RegistryPlate>>() {}.type
                val list: List<RegistryPlate> = gson.fromJson(json, type)
                registeredPlates = list.associateBy { it.plateString.uppercase() }
                Log.d(tag, "Loaded ${registeredPlates.size} plates from cache")
            } catch (e: Exception) {
                Log.e(tag, "Failed to load cache", e)
            }
        }
    }

    suspend fun syncRegistry(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Using version=0 as requested by user's URL
            val plates = api.getRegistry(version = 0)
            // Print out what is received from the API as requested
            Log.d(tag, "Received from API: ${gson.toJson(plates)}")
            
            val json = gson.toJson(plates)
            cacheFile.writeText(json)
            
            registeredPlates = plates.associateBy { it.plateString.uppercase() }
            Result.success(plates.size)
        } catch (e: Exception) {
            Log.e(tag, "Sync failed", e)
            Result.failure(e)
        }
    }

    fun isPlateValid(plateText: String): PlateValidationResult {
        val cleanPlate = plateText.uppercase().trim()
        val entry = registeredPlates[cleanPlate] ?: return PlateValidationResult.NOT_FOUND

        // Check expiry if it exists
        entry.expiryDate?.let { dateStr ->
            try {
                // Assuming ISO format from user's example: 2026-12-31T23:59:59.000Z
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val expiryDate = sdf.parse(dateStr)
                if (expiryDate != null && expiryDate.before(Date())) {
                    return PlateValidationResult.EXPIRED
                }
            } catch (e: Exception) {
                Log.e(tag, "Date parse error for $dateStr", e)
            }
            Unit
        }

        return PlateValidationResult.VALID
    }

    enum class PlateValidationResult {
        VALID,
        EXPIRED,
        NOT_FOUND
    }
}
