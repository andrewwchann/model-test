package com.andre.alprprototype

import android.Manifest
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.BestPlateCropSaver
import com.andre.alprprototype.alpr.YoloTflitePlateCandidateGenerator
import com.andre.alprprototype.databinding.ActivityCameraBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity() {
    private data class UploadDecision(
        val allowed: Boolean,
        val reason: String,
    )

    private enum class OcrUiState {
        IDLE,
        PENDING,
        READY,
        UNAVAILABLE,
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cropSaver: BestPlateCropSaver
    private lateinit var pipeline: AlprPipeline
    private lateinit var trainingLogUploader: TrainingLogUploader
    private lateinit var registryManager: RegistryManager
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var latestFrame: AnalyzedFrame? = null
    private var lastSavedCropPath: String? = null
    private var lastOcrRequestedPath: String? = null
    private var lastTrainingUploadPath: String? = null
    private var latestOcrResult: OcrDisplayResult? = null
    private var latestOcrState: OcrUiState = OcrUiState.IDLE
    private var detectorSelfTestSummary: String = "Self-test: pending"
    private var lastConfirmedPlateText: String? = null
    private var lastConfirmedPlateAtMs: Long = 0L
    private lateinit var plateOcrEngine: PlateOcrEngine
    private var isStatusExpanded: Boolean = false
    private val isShuttingDown = AtomicBoolean(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        cropSaver = BestPlateCropSaver(this)
        pipeline = AlprPipeline.create(this)
        plateOcrEngine = PlateOcrEngine(this)
        registryManager = RegistryManager(this)
        trainingLogUploader = TrainingLogUploader(this) { message ->
            if (canUpdateUi()) {
                showTopToast(message)
            }
        }
        binding.sessionStatus.text = "Detector loaded: ${pipeline.detectorName}\n$detectorSelfTestSummary\nOpening camera stream and ALPR debug pipeline..."
        runDetectorSelfTest()
        applyStatusPanelState()

        binding.closeButton.setOnClickListener { finish() }
        binding.sessionStatusHeader.setOnClickListener {
            isStatusExpanded = !isStatusExpanded
            applyStatusPanelState()
        }

        binding.syncButton.setOnClickListener {
            syncRegistry()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun syncRegistry() {
        binding.syncButton.isEnabled = false
        binding.syncButton.text = "Syncing..."
        lifecycleScope.launch {
            val result = registryManager.syncRegistry()
            if (result.isSuccess) {
                Toast.makeText(this@CameraActivity, "Registry synced: ${result.getOrNull()} plates", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@CameraActivity, "Sync failed", Toast.LENGTH_SHORT).show()
            }
            binding.syncButton.isEnabled = true
            binding.syncButton.text = "Sync Registry"
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            if (!canUseCamera()) {
                return@addListener
            }
            try {
                val cameraProvider = cameraProviderFuture.get()
                if (!canUseCamera()) {
                    cameraProvider.unbindAll()
                    return@addListener
                }
                this.cameraProvider = cameraProvider

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(800, 800),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(
                            AspectRatio.RATIO_4_3,
                            AspectRatioStrategy.FALLBACK_RULE_AUTO,
                        ),
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        analysisUseCase = imageAnalysis
                        imageAnalysis.setAnalyzer(
                            cameraExecutor,
                            PlateFrameAnalyzer(pipeline, cropSaver) { frame ->
                                runOnUiThread {
                                    if (!canUpdateUi()) {
                                        return@runOnUiThread
                                    }
                                    latestFrame = frame
                                    if (frame.savedCropPath != null) {
                                        lastSavedCropPath = frame.savedCropPath
                                        updateCropPreview(frame.savedCropPath)
                                        requestOcrIfNeeded(frame.savedCropPath, frame.state)
                                    }
                                    binding.sessionStatus.text = buildStatusText(frame)
                                    binding.debugOverlay.render(frame.state)
                                }
                            },
                        )
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                if (canUseCamera()) {
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis)
                }
            } catch (_: Throwable) {
                // Activity may already be finishing/destroyed when camera provider resolves.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        isShuttingDown.set(true)
        analysisUseCase?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdownNow()
        try {
            cameraExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        pipeline.close()
        plateOcrEngine.close()
        trainingLogUploader.close()
        super.onDestroy()
    }

    private fun buildStatusText(frame: AnalyzedFrame): String {
        val savedText = lastSavedCropPath ?: "none yet"
        val ocrText = when (latestOcrState) {
            OcrUiState.IDLE -> "idle"
            OcrUiState.PENDING -> "pending"
            OcrUiState.READY -> latestOcrResult?.text ?: "ready"
            OcrUiState.UNAVAILABLE -> "unavailable"
        }
        return frame.state.statusText() +
            "\n$detectorSelfTestSummary" +
            "\nSaved crop: $savedText" +
            "\nPlate OCR: $ocrText"
    }

    private fun runDetectorSelfTest() {
        val detector = YoloTflitePlateCandidateGenerator.createOrNull(this)
        if (detector == null) {
            detectorSelfTestSummary = "Self-test: detector unavailable"
            binding.sessionStatus.text = "Detector loaded: ${pipeline.detectorName}\n$detectorSelfTestSummary\nOpening camera stream and ALPR debug pipeline..."
            return
        }

        cameraExecutor.execute {
            val summary = try {
                val car1 = assets.open("debug-samples/car1.png").use { BitmapFactory.decodeStream(it) }
                val car2 = assets.open("debug-samples/car2.png").use { BitmapFactory.decodeStream(it) }
                val car1Top = car1?.let { detector.detectBitmap(it).maxByOrNull { c -> c.confidence } }
                val car2Top = car2?.let { detector.detectBitmap(it).maxByOrNull { c -> c.confidence } }
                val car1Size = "${car1Top?.boundingBox?.width()?.toInt() ?: 0}x${car1Top?.boundingBox?.height()?.toInt() ?: 0}"
                val car2Size = "${car2Top?.boundingBox?.width()?.toInt() ?: 0}x${car2Top?.boundingBox?.height()?.toInt() ?: 0}"
                val car1Conf = car1Top?.confidence?.let { String.format("%.2f", it) } ?: "-"
                val car2Conf = car2Top?.confidence?.let { String.format("%.2f", it) } ?: "-"
                "Self-test car1=$car1Size c=$car1Conf | car2=$car2Size c=$car2Conf"
            } catch (_: Exception) {
                "Self-test: failed"
            } finally {
                detector.close()
            }

            runOnUiThread {
                if (!canUpdateUi()) {
                    return@runOnUiThread
                }
                detectorSelfTestSummary = summary
                latestFrame?.let { binding.sessionStatus.text = buildStatusText(it) }
                    ?: run {
                        binding.sessionStatus.text = "Detector loaded: ${pipeline.detectorName}\n$detectorSelfTestSummary\nOpening camera stream and ALPR debug pipeline..."
                    }
            }
        }
    }

    private fun requestOcrIfNeeded(cropPath: String, frameState: com.andre.alprprototype.alpr.PipelineDebugState) {
        if (cropPath == lastOcrRequestedPath) {
            return
        }
        lastOcrRequestedPath = cropPath
        latestOcrResult = null
        latestOcrState = OcrUiState.PENDING
        updateCropCaption()
        plateOcrEngine.recognize(cropPath) { result ->
            runOnUiThread {
                if (!canUpdateUi() || cropPath != lastOcrRequestedPath) {
                    return@runOnUiThread
                }
                latestOcrResult = result
                latestOcrState = if (result?.text.isNullOrBlank()) OcrUiState.UNAVAILABLE else OcrUiState.READY
                updateCropCaption()

                val allowConfirmedActions = result?.text?.let { text ->
                    shouldProcessConfirmedPlate(text)
                } ?: false

                if (allowConfirmedActions) {
                    result?.text?.let { text ->
                        val validation = registryManager.isPlateValid(text)
                        updateValidationUi(validation)
                    }
                    maybeUploadTrainingSample(cropPath, result, frameState)
                } else {
                    binding.validationIndicator.visibility = android.view.View.GONE
                }
                latestFrame?.let { frame ->
                    binding.sessionStatus.text = buildStatusText(frame)
                }
            }
        }
    }

    private fun updateValidationUi(result: RegistryManager.PlateValidationResult) {
        binding.validationIndicator.visibility = android.view.View.VISIBLE
        when (result) {
            RegistryManager.PlateValidationResult.VALID -> {
                binding.validationIndicator.setImageResource(android.R.drawable.presence_online) // Green dot/checkmark proxy
                binding.validationIndicator.setColorFilter(android.graphics.Color.GREEN)
            }
            RegistryManager.PlateValidationResult.EXPIRED -> {
                binding.validationIndicator.setImageResource(android.R.drawable.presence_busy) // Red proxy
                binding.validationIndicator.setColorFilter(android.graphics.Color.YELLOW) // Warning for expired
            }
            RegistryManager.PlateValidationResult.NOT_FOUND -> {
                binding.validationIndicator.setImageResource(android.R.drawable.presence_offline) // Red proxy
                binding.validationIndicator.setColorFilter(android.graphics.Color.RED)
            }
        }
    }

    private fun updateCropPreview(cropPath: String) {
        val bitmap = BitmapFactory.decodeFile(cropPath)
        binding.latestCropImage.setImageBitmap(bitmap)
        updateCropCaption()
    }

    private fun updateCropCaption() {
        val ocrText = when {
            latestOcrResult?.text != null -> latestOcrResult!!.text
            lastSavedCropPath != null && latestOcrState == OcrUiState.PENDING -> getString(R.string.latest_crop_ocr_pending)
            lastSavedCropPath != null -> getString(R.string.latest_crop_ocr_unavailable)
            else -> getString(R.string.latest_crop_empty)
        }
        binding.latestCropCaption.text = ocrText
    }

    private fun maybeUploadTrainingSample(
        cropPath: String,
        result: OcrDisplayResult?,
        frameState: com.andre.alprprototype.alpr.PipelineDebugState,
    ) {
        val uploadResult = result ?: return
        val normalizedPath = File(cropPath).absolutePath
        val decision = evaluateTrainingUpload(frameState, normalizedPath, uploadResult)
        logUploadDecision(normalizedPath, uploadResult, frameState, decision)
        if (!decision.allowed) {
            showTopToast("Upload skipped: ${decision.reason}")
            return
        }
        lastTrainingUploadPath = normalizedPath
        trainingLogUploader.maybeUpload(normalizedPath, uploadResult)
    }

    private fun evaluateTrainingUpload(
        frameState: com.andre.alprprototype.alpr.PipelineDebugState,
        cropPath: String,
        ocrResult: OcrDisplayResult,
    ): UploadDecision {
        if (ocrResult.text.isBlank()) {
            return UploadDecision(false, "blank_ocr")
        }
        if (cropPath == lastTrainingUploadPath) {
            return UploadDecision(false, "duplicate_crop")
        }
        val quality = frameState.quality
        if (quality == null || !quality.passes) {
            return UploadDecision(false, "quality_reject")
        }
        val activeTrack = frameState.activeTrack ?: return UploadDecision(false, "missing_track")
        if (activeTrack.ageFrames < 3) {
            return UploadDecision(false, "unstable_track")
        }
        val normalizedOcr = normalizePlateTextForUpload(ocrResult.text)
        if (normalizedOcr.isBlank()) {
            return UploadDecision(false, "blank_ocr")
        }
        if (!isPlausiblePlateText(normalizedOcr)) {
            return UploadDecision(false, "implausible_text")
        }
        val confidence = ocrResult.confidence
        if (confidence != null && confidence < BuildConfig.TRAINING_LOGGER_MIN_CONFIDENCE.toFloat()) {
            return UploadDecision(false, "low_ocr_confidence")
        }
        return UploadDecision(true, "accepted")
    }

    private fun normalizePlateTextForUpload(text: String): String {
        return text.uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' }
    }

    private fun shouldProcessConfirmedPlate(text: String): Boolean {
        val normalizedText = normalizePlateTextForUpload(text)
        if (normalizedText.isBlank()) {
            return false
        }
        val nowMs = System.currentTimeMillis()
        val isRepeatedPlate = normalizedText == lastConfirmedPlateText
        val withinCooldown = nowMs - lastConfirmedPlateAtMs < CONFIRMED_PLATE_COOLDOWN_MS
        if (isRepeatedPlate && withinCooldown) {
            android.util.Log.d(
                "ConfirmedPlateCooldown",
                "suppressed text=$normalizedText ageMs=${nowMs - lastConfirmedPlateAtMs}",
            )
            return false
        }
        lastConfirmedPlateText = normalizedText
        lastConfirmedPlateAtMs = nowMs
        return true
    }

    private fun isPlausiblePlateText(text: String): Boolean {
        if (text.length !in 5..8) {
            return false
        }
        if (text.any { it !in 'A'..'Z' && it !in '0'..'9' }) {
            return false
        }
        if (text.toSet().size == 1) {
            return false
        }
        val dominantCount = text.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return dominantCount < text.length - 1
    }

    private fun logUploadDecision(
        cropPath: String,
        ocrResult: OcrDisplayResult,
        frameState: com.andre.alprprototype.alpr.PipelineDebugState,
        decision: UploadDecision,
    ) {
        val quality = frameState.quality
        android.util.Log.d(
            "TrainingUploadGate",
            "decision=${decision.reason} path=$cropPath ocr='${ocrResult.text}' conf=${ocrResult.confidence} " +
                "trackAge=${frameState.activeTrack?.ageFrames} " +
                "quality=${quality?.totalScore}",
        )
    }

    private fun applyStatusPanelState() {
        binding.sessionStatus.visibility = if (isStatusExpanded) android.view.View.VISIBLE else android.view.View.GONE
        binding.sessionStatusToggle.setText(
            if (isStatusExpanded) R.string.session_status_collapse else R.string.session_status_expand,
        )
    }

    private fun canUpdateUi(): Boolean {
        return !isShuttingDown.get() && !isDestroyed && !isFinishing
    }

    private fun canUseCamera(): Boolean {
        return !isShuttingDown.get() && !isDestroyed && !isFinishing
    }

    private fun showTopToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 120)
            show()
        }
    }

    companion object {
        private const val CONFIRMED_PLATE_COOLDOWN_MS = 8_000L
    }
}
