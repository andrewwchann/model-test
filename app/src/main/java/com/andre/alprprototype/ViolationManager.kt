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
import java.util.UUID

class ViolationManager(
    private val context: Context,
    private val apiFactory: (() -> ViolationApi)? = null,
) : ViolationQueue {
    private val tag = "ViolationManager"
    private val gson = Gson()
    private val queueFile = File(context.filesDir, "violation_queue.json")
    private val stagedImageDir = File(context.filesDir, "queued-violation-images").apply { mkdirs() }
    private val queueLock = Any()
    
    private val api: ViolationApi by lazy {
        apiFactory?.invoke() ?: createDefaultApi()
    }

    private fun createDefaultApi(): ViolationApi {
        val logging = HttpLoggingInterceptor { message -> Log.d("API_LOG", message) }
        logging.level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
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
                synchronized(queueLock) {
                    queuedViolations = gson.fromJson(json, type) ?: mutableListOf()
                }
                Log.d(tag, "Loaded ${queuedViolations.size} violations from queue")
            } catch (e: Exception) {
                Log.e(tag, "Failed to load queue", e)
            }
        }
    }

    private fun saveQueue(): Boolean {
        return try {
            val json = synchronized(queueLock) {
                gson.toJson(queuedViolations)
            }
            val tempFile = File(queueFile.parentFile, "${queueFile.name}.tmp")
            tempFile.writeText(json)
            if (queueFile.exists() && !queueFile.delete()) {
                throw IllegalStateException("Failed deleting old queue file")
            }
            if (!tempFile.renameTo(queueFile)) {
                throw IllegalStateException("Failed replacing queue file")
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to save queue", e)
            false
        }
    }

    override fun addViolation(violation: ViolationEvent): Result<ViolationEvent> {
        val stagedViolation = stageViolationAssets(violation)
            ?: return Result.failure(IllegalStateException("Failed to stage violation assets"))
        synchronized(queueLock) {
            queuedViolations.add(stagedViolation)
        }
        if (!saveQueue()) {
            synchronized(queueLock) {
                queuedViolations.removeAll { it.queueKey() == stagedViolation.queueKey() }
            }
            deleteFileQuietly(stagedViolation.localPlatePath)
            deleteFileQuietly(stagedViolation.localVehiclePath)
            return Result.failure(IllegalStateException("Failed to persist queued violation"))
        }
        deleteOriginalAssetIfStaged(violation.localPlatePath, stagedViolation.localPlatePath)
        deleteOriginalAssetIfStaged(violation.localVehiclePath, stagedViolation.localVehiclePath)
        return Result.success(stagedViolation)
    }

    override fun getQueueSize(): Int = synchronized(queueLock) { queuedViolations.size }

    override suspend fun uploadQueue(): Result<Int> = withContext(Dispatchers.IO) {
        val queueSnapshot = synchronized(queueLock) { queuedViolations.toList() }
        if (queueSnapshot.isEmpty()) return@withContext Result.success(0)

        try {
            for (violation in queueSnapshot) {
                // Step 1: Upload Plate Image
                if (violation.s3PlateUri == null && violation.localPlatePath != null) {
                    val file = File(violation.localPlatePath!!)
                    if (file.exists()) {
                        val uploadInfo = api.getUploadUrl(file.name)
                        val response = api.uploadToS3(uploadInfo.uploadUrl, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                        if (response.isSuccessful) {
                            violation.s3PlateUri = uploadInfo.finalS3Uri
                            saveQueue()
                        } else {
                            Log.w(tag, "Plate upload failed for ${file.name} code=${response.code()}")
                        }
                    } else {
                        Log.w(tag, "Missing queued plate image path=${violation.localPlatePath}")
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
                        } else {
                            Log.w(tag, "Vehicle upload failed for ${file.name} code=${response.code()}")
                        }
                    } else {
                        Log.w(tag, "Missing queued vehicle image path=${violation.localVehiclePath}")
                    }
                }
            }

            // Step 3: Batch Upload Metadata
            val uploadable = synchronized(queueLock) {
                queuedViolations.filter { it.s3EvidenceUri != null && it.s3PlateUri != null }
            }
            if (uploadable.isNotEmpty()) {
                val response = api.uploadViolations(uploadable)
                Log.d(tag, "Batch upload success: ${response.message}")

                val uploadedKeys = uploadable.map { it.queueKey() }.toSet()
                val uploadedEntries = mutableListOf<ViolationEvent>()
                synchronized(queueLock) {
                    val iterator = queuedViolations.iterator()
                    while (iterator.hasNext()) {
                        val queued = iterator.next()
                        if (queued.queueKey() in uploadedKeys) {
                            uploadedEntries += queued
                            iterator.remove()
                        }
                    }
                }
                uploadedEntries.forEach { event ->
                    event.localPlatePath?.let { File(it).delete() }
                    event.localVehiclePath?.let { File(it).delete() }
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

    private fun stageViolationAssets(violation: ViolationEvent): ViolationEvent? {
        val stagedPlatePath = stageFile(violation.localPlatePath, "plate") ?: return null
        val stagedVehiclePath = stageFile(violation.localVehiclePath, "vehicle")
        if (stagedVehiclePath == null) {
            deleteFileQuietly(stagedPlatePath)
            return null
        }
        return violation.copy(
            localPlatePath = stagedPlatePath,
            localVehiclePath = stagedVehiclePath,
        )
    }

    private fun stageFile(sourcePath: String?, prefix: String): String? {
        val normalizedSourcePath = sourcePath ?: return null
        val sourceFile = File(normalizedSourcePath)
        if (!sourceFile.exists() || !sourceFile.isFile) {
            Log.w(tag, "Cannot queue missing file path=$normalizedSourcePath")
            return null
        }
        if (sourceFile.parentFile?.absolutePath == stagedImageDir.absolutePath) {
            return sourceFile.absolutePath
        }

        val stagedFile = File(stagedImageDir, "${prefix}_${UUID.randomUUID()}_${sourceFile.name}")
        return try {
            sourceFile.copyTo(stagedFile, overwrite = false)
            stagedFile.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Failed staging queued file path=$normalizedSourcePath", e)
            null
        }
    }

    private fun deleteOriginalAssetIfStaged(originalPath: String?, stagedPath: String?) {
        if (originalPath.isNullOrBlank() || stagedPath.isNullOrBlank()) {
            return
        }
        if (originalPath == stagedPath) {
            return
        }
        try {
            File(originalPath).delete()
        } catch (e: Exception) {
            Log.w(tag, "Failed deleting original staged asset path=$originalPath", e)
        }
    }

    private fun deleteFileQuietly(path: String?) {
        if (path.isNullOrBlank()) {
            return
        }
        try {
            File(path).delete()
        } catch (e: Exception) {
            Log.w(tag, "Failed deleting file path=$path", e)
        }
    }

    private fun ViolationEvent.queueKey(): String {
        return listOf(
            rawOcrText,
            timestamp,
            localPlatePath.orEmpty(),
            localVehiclePath.orEmpty(),
        ).joinToString("|")
    }
}
