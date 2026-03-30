package com.andre.alprprototype

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class TrainingLogPayload(
    val secret: String,
    val timestamp: String,
    val ocrPredicted: String,
    val confidence: Float? = null,
    val filename: String,
    val source: String = "camera_live",
    val needsReview: Boolean = true,
    val imageBase64: String,
    val correctedLabel: String? = null,
    val notes: String? = null,
)

data class PendingUploadSyncResult(
    val attempted: Int,
    val uploaded: Int,
    val remaining: Int,
)

class TrainingLogUploader(
    context: Context,
    private val onUploadStatus: ((String) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val queueStore = PendingTrainingUploadStore(appContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isClosed = AtomicBoolean(false)
    private val tag = "TrainingLogUploader"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun pendingUploadCount(): Int = queueStore.count()

    fun hasPendingUploads(): Boolean = pendingUploadCount() > 0

    fun canSyncPendingUploadsNow(): Boolean = syncAvailability() == SyncAvailability.READY

    fun maybeUpload(cropPath: String, ocrResult: OcrDisplayResult) {
        if (!BuildConfig.TRAINING_LOGGING_ENABLED) {
            notifyStatus("Training upload disabled")
            return
        }
        if (BuildConfig.TRAINING_LOGGER_ENDPOINT.isBlank()) {
            Log.w(tag, "skipping upload because TRAINING_LOGGER_ENDPOINT is blank")
            notifyStatus("Upload skipped: endpoint missing")
            return
        }
        if (ocrResult.text.isBlank()) {
            notifyStatus("Upload skipped: blank OCR")
            return
        }

        val confidence = ocrResult.confidence
        if (confidence != null && confidence < BuildConfig.TRAINING_LOGGER_MIN_CONFIDENCE.toFloat()) {
            Log.d(tag, "skipping upload below confidence threshold path=$cropPath conf=$confidence")
            notifyStatus("Upload skipped: low confidence")
            return
        }
        if (isClosed.get()) {
            return
        }

        executor.execute {
            if (isClosed.get()) {
                return@execute
            }

            val file = File(cropPath)
            if (!file.exists() || !file.isFile) {
                Log.w(tag, "skipping upload because crop file is missing path=$cropPath")
                notifyStatus("Upload skipped: crop missing")
                return@execute
            }

            val payload = try {
                buildPayload(file, ocrResult, confidence)
            } catch (t: Throwable) {
                Log.e(tag, "failed to prepare upload payload path=$cropPath", t)
                notifyStatus("Upload failed: payload error")
                return@execute
            }

            val entryId = queueStore.enqueue(payload)
            when (syncAvailability()) {
                SyncAvailability.OFFLINE -> {
                    notifyStatus("Offline: match saved for Wi-Fi sync")
                }
                SyncAvailability.WIFI_REQUIRED -> {
                    notifyStatus("Match saved. Connect to Wi-Fi to sync")
                }
                SyncAvailability.READY -> {
                    when (upload(payload)) {
                        UploadResult.SUCCESS -> {
                            queueStore.removeByIds(listOf(entryId))
                            notifyStatus("Training sample sent")
                        }
                        UploadResult.FAILED -> {
                            notifyStatus("Upload deferred. Saved for Wi-Fi sync")
                        }
                    }
                }
            }
        }
    }

    fun syncPendingUploads(onComplete: ((PendingUploadSyncResult) -> Unit)? = null) {
        if (!BuildConfig.TRAINING_LOGGING_ENABLED) {
            val result = PendingUploadSyncResult(attempted = 0, uploaded = 0, remaining = pendingUploadCount())
            notifyStatus("Training upload disabled")
            postSyncResult(result, onComplete)
            return
        }
        if (BuildConfig.TRAINING_LOGGER_ENDPOINT.isBlank()) {
            val result = PendingUploadSyncResult(attempted = 0, uploaded = 0, remaining = pendingUploadCount())
            notifyStatus("Upload skipped: endpoint missing")
            postSyncResult(result, onComplete)
            return
        }

        val availability = syncAvailability()
        if (availability != SyncAvailability.READY) {
            val result = PendingUploadSyncResult(attempted = 0, uploaded = 0, remaining = pendingUploadCount())
            notifyStatus(
                when (availability) {
                    SyncAvailability.OFFLINE -> "Offline: queued uploads stay saved"
                    SyncAvailability.WIFI_REQUIRED -> "Connect to Wi-Fi to sync queued uploads"
                    SyncAvailability.READY -> "Queued uploads ready"
                },
            )
            postSyncResult(result, onComplete)
            return
        }
        if (isClosed.get()) {
            val result = PendingUploadSyncResult(attempted = 0, uploaded = 0, remaining = pendingUploadCount())
            postSyncResult(result, onComplete)
            return
        }

        executor.execute {
            if (isClosed.get()) {
                postSyncResult(
                    PendingUploadSyncResult(attempted = 0, uploaded = 0, remaining = pendingUploadCount()),
                    onComplete,
                )
                return@execute
            }

            val pendingEntries = queueStore.snapshot()
            if (pendingEntries.isEmpty()) {
                val result = PendingUploadSyncResult(attempted = 0, uploaded = 0, remaining = 0)
                notifyStatus("No queued uploads to sync")
                postSyncResult(result, onComplete)
                return@execute
            }

            val uploadedIds = mutableListOf<String>()
            pendingEntries.forEach { entry ->
                if (isClosed.get()) {
                    return@forEach
                }
                if (upload(entry.payload) == UploadResult.SUCCESS) {
                    uploadedIds += entry.id
                }
            }
            queueStore.removeByIds(uploadedIds)

            val remaining = queueStore.count()
            val result = PendingUploadSyncResult(
                attempted = pendingEntries.size,
                uploaded = uploadedIds.size,
                remaining = remaining,
            )
            notifyStatus(
                when {
                    result.uploaded == 0 -> "Queued uploads not synced. They remain saved"
                    result.remaining == 0 -> "Synced ${result.uploaded} queued upload(s)"
                    else -> "Synced ${result.uploaded}; ${result.remaining} still queued"
                },
            )
            postSyncResult(result, onComplete)
        }
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            return
        }
        executor.shutdown()
        if (!executor.awaitTermination(1_500, TimeUnit.MILLISECONDS)) {
            executor.shutdownNow()
            executor.awaitTermination(300, TimeUnit.MILLISECONDS)
        }
    }

    private fun buildPayload(
        file: File,
        ocrResult: OcrDisplayResult,
        confidence: Float?,
    ): TrainingLogPayload {
        return TrainingLogPayload(
            secret = BuildConfig.TRAINING_LOGGER_SECRET,
            timestamp = Instant.now().toString(),
            ocrPredicted = ocrResult.text,
            confidence = confidence,
            filename = file.name,
            source = "camera_live",
            needsReview = true,
            imageBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP),
        )
    }

    private fun upload(payload: TrainingLogPayload): UploadResult {
        var connection: HttpURLConnection? = null
        try {
            val requestJson = JSONObject().apply {
                put("secret", payload.secret)
                put("timestamp", payload.timestamp)
                put("ocr_predicted", payload.ocrPredicted)
                if (payload.confidence != null) {
                    put("confidence", payload.confidence.toDouble())
                }
                put("filename", payload.filename)
                put("source", payload.source)
                put("needs_review", payload.needsReview)
                put("image_base64", payload.imageBase64)
                put("corrected_label", payload.correctedLabel ?: "")
                put("notes", payload.notes ?: "")
            }.toString()

            connection = (URL(BuildConfig.TRAINING_LOGGER_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestJson)
            }

            val responseCode = connection.responseCode
            val responseText = runCatching {
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")

            return if (responseCode in 200..299) {
                Log.i(tag, "upload ok file=${payload.filename} response=$responseText")
                UploadResult.SUCCESS
            } else {
                Log.w(tag, "upload failed code=$responseCode file=${payload.filename} response=$responseText")
                UploadResult.FAILED
            }
        } catch (t: Throwable) {
            Log.e(tag, "upload exception file=${payload.filename}", t)
            return UploadResult.FAILED
        } finally {
            connection?.disconnect()
        }
    }

    private fun postSyncResult(
        result: PendingUploadSyncResult,
        onComplete: ((PendingUploadSyncResult) -> Unit)?,
    ) {
        if (onComplete == null) {
            return
        }
        mainHandler.post {
            onComplete(result)
        }
    }

    private fun notifyStatus(message: String) {
        if (isClosed.get()) {
            return
        }
        mainHandler.post {
            if (!isClosed.get()) {
                onUploadStatus?.invoke(message)
            }
        }
    }

    private fun syncAvailability(): SyncAvailability {
        if (!hasNetworkConnection()) {
            return SyncAvailability.OFFLINE
        }
        if (BuildConfig.TRAINING_LOGGER_WIFI_ONLY && !isOnUnmeteredWifi()) {
            return SyncAvailability.WIFI_REQUIRED
        }
        return SyncAvailability.READY
    }

    private fun hasNetworkConnection(): Boolean {
        return try {
            val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (t: SecurityException) {
            Log.w(tag, "network state unavailable; treating connection as offline", t)
            false
        }
    }

    private fun isOnUnmeteredWifi(): Boolean {
        return try {
            val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (t: SecurityException) {
            Log.w(tag, "network state unavailable; treating Wi-Fi-only check as unmet", t)
            false
        }
    }

    private enum class SyncAvailability {
        OFFLINE,
        WIFI_REQUIRED,
        READY,
    }

    private enum class UploadResult {
        SUCCESS,
        FAILED,
    }
}
