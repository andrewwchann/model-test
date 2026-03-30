package com.andre.alprprototype

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class ViolationManager(private val context: Context) {
    private val tag = "ViolationManager"
    private val gson = Gson()
    private val queueFile = File(context.filesDir, "violation_queue.json")
    
    private val api: ViolationApi by lazy {
        val logging = HttpLoggingInterceptor { message -> Log.d("API_LOG", message) }
        logging.level = HttpLoggingInterceptor.Level.BODY

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://lrxxawq4kk.execute-api.us-east-1.amazonaws.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ViolationApi::class.java)
    }

    private var queuedViolations: MutableList<ViolationEvent> = mutableListOf()

    init {
        loadQueue()
    }

    private fun loadQueue() {
        if (queueFile.exists()) {
            try {
                val json = queueFile.readText()
                val type = object : TypeToken<MutableList<ViolationEvent>>() {}.type
                queuedViolations = gson.fromJson(json, type) ?: mutableListOf()
                Log.d(tag, "Loaded ${queuedViolations.size} violations from queue")
            } catch (e: Exception) {
                Log.e(tag, "Failed to load queue", e)
            }
        }
    }

    private fun saveQueue() {
        try {
            val json = gson.toJson(queuedViolations)
            queueFile.writeText(json)
        } catch (e: Exception) {
            Log.e(tag, "Failed to save queue", e)
        }
    }

    fun addViolation(violation: ViolationEvent) {
        queuedViolations.add(violation)
        saveQueue()
    }

    fun getQueueSize(): Int = queuedViolations.size

    suspend fun uploadQueue(): Result<Int> = withContext(Dispatchers.IO) {
        if (queuedViolations.isEmpty()) return@withContext Result.success(0)

        try {
            for (violation in queuedViolations) {
                // Step 1: Upload Plate Image
                if (violation.s3PlateUri == null && violation.localPlatePath != null) {
                    val file = File(violation.localPlatePath!!)
                    if (file.exists()) {
                        val uploadInfo = api.getUploadUrl(file.name)
                        val response = api.uploadToS3(uploadInfo.uploadUrl, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                        if (response.isSuccessful) {
                            violation.s3PlateUri = uploadInfo.finalS3Uri
                            saveQueue()
                        }
                    }
                }

                // Step 2: Upload Vehicle Image
                if (violation.s3EvidenceUri == null && violation.localVehiclePath != null) {
                    val file = File(violation.localVehiclePath!!)
                    if (file.exists()) {
                        val uploadInfo = api.getUploadUrl(file.name)
                        val response = api.uploadToS3(uploadInfo.uploadUrl, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                        if (response.isSuccessful) {
                            violation.s3EvidenceUri = uploadInfo.finalS3Uri
                            saveQueue()
                        }
                    }
                }
            }

            // Step 3: Batch Upload Metadata
            val uploadable = queuedViolations.filter { it.s3EvidenceUri != null && it.s3PlateUri != null }
            if (uploadable.isNotEmpty()) {
                val response = api.uploadViolations(uploadable)
                Log.d(tag, "Batch upload success: ${response.message}")
                
                val iterator = queuedViolations.iterator()
                while (iterator.hasNext()) {
                    val v = iterator.next()
                    if (v.s3EvidenceUri != null && v.s3PlateUri != null) {
                        v.localPlatePath?.let { File(it).delete() }
                        v.localVehiclePath?.let { File(it).delete() }
                        iterator.remove()
                    }
                }
                saveQueue()
                Result.success(uploadable.size)
            } else {
                Result.success(0)
            }
        } catch (e: Exception) {
            Log.e(tag, "Queue upload failed", e)
            Result.failure(e)
        }
    }
}
