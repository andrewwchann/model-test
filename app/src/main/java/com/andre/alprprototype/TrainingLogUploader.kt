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

class TrainingLogUploader(
    context: Context,
    private val onUploadStatus: ((String) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isClosed = AtomicBoolean(false)
    private val tag = "TrainingLogUploader"
    private val mainHandler = Handler(Looper.getMainLooper())

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
        if (BuildConfig.TRAINING_LOGGER_WIFI_ONLY && !isOnUnmeteredWifi()) {
            Log.d(tag, "skipping upload because Wi-Fi-only mode is enabled")
            notifyStatus("Upload skipped: Wi-Fi only")
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
                TrainingLogPayload(
                    secret = BuildConfig.TRAINING_LOGGER_SECRET,
                    timestamp = Instant.now().toString(),
                    ocrPredicted = ocrResult.text,
                    confidence = confidence,
                    filename = file.name,
                    source = "camera_live",
                    needsReview = true,
                    imageBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP),
                )
            } catch (t: Throwable) {
                Log.e(tag, "failed to prepare upload payload path=$cropPath", t)
                notifyStatus("Upload failed: payload error")
                return@execute
            }

            upload(payload)
        }
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            return
        }
        executor.shutdownNow()
        executor.awaitTermination(300, TimeUnit.MILLISECONDS)
    }

    private fun upload(payload: TrainingLogPayload) {
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

            if (responseCode in 200..299) {
                Log.i(tag, "upload ok file=${payload.filename} response=$responseText")
                notifyStatus("Training sample sent")
            } else {
                Log.w(tag, "upload failed code=$responseCode file=${payload.filename} response=$responseText")
                notifyStatus("Upload failed: HTTP $responseCode")
            }
        } catch (t: Throwable) {
            Log.e(tag, "upload exception file=${payload.filename}", t)
            notifyStatus("Upload failed: network error")
        } finally {
            connection?.disconnect()
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
}
