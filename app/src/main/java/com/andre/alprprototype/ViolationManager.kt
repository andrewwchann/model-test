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
    
    // Using the same API Gateway host as RegistryManager, as the RDS endpoint in the spec
    // is a database address and does not host the HTTP REST API.
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
            // Step 1: Upload images to S3
            for (violation in queuedViolations) {
                if (violation.s3EvidenceUri == null && violation.localImagePath != null) {
                    val file = File(violation.localImagePath)
                    if (!file.exists()) {
                        Log.e(tag, "Local image not found: ${violation.localImagePath}")
                        continue
                    }

                    // Get Presigned URL
                    val uploadInfo = api.getUploadUrl(file.name)
                    
                    // PUT to S3
                    val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    val response = api.uploadToS3(uploadInfo.uploadUrl, requestBody)
                    
                    if (response.isSuccessful) {
                        violation.s3EvidenceUri = uploadInfo.finalS3Uri
                        saveQueue() // Persist the S3 URI in case of batch failure
                    } else {
                        Log.e(tag, "S3 Upload failed for ${file.name}: ${response.code()}")
                        return@withContext Result.failure(Exception("S3 Upload failed: HTTP ${response.code()}"))
                    }
                }
            }

            // Step 2: Batch Upload Metadata
            val uploadable = queuedViolations.filter { it.s3EvidenceUri != null }
            if (uploadable.isNotEmpty()) {
                val response = api.uploadViolations(uploadable)
                Log.d(tag, "Batch upload success: ${response.message}")
                
                // Cleanup: remove uploaded violations and their local files
                val iterator = queuedViolations.iterator()
                while (iterator.hasNext()) {
                    val v = iterator.next()
                    if (v.s3EvidenceUri != null) {
                        v.localImagePath?.let { path -> File(path).delete() }
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
