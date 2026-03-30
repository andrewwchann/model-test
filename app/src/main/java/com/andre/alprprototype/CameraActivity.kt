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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    private lateinit var violationManager: ViolationManager
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var latestFrame: AnalyzedFrame? = null
    private var lastSavedCropPath: String? = null
    private var lastOcrRequestedPath: String? = null
    private var lastTrainingUploadPath: String? = null
    private var latestOcrResult: OcrDisplayResult? = null
    private var latestOcrState: OcrUiState = OcrUiState.IDLE
    private var detectorSelfTestSummary: String = "Self-test: pending"
    private var lastConfirmedPlateText: String? = null
    private var lastConfirmedPlateAtMs: Long = 0L
    private var currentOcrVoteTrackId: Int? = null
    private val currentTrackOcrVotes = ArrayDeque<String>()
    private lateinit var plateOcrEngine: PlateOcrEngine
    private var isStatusExpanded: Boolean = false
    private val isShuttingDown = AtomicBoolean(false)
    private var isProcessingViolation = false

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
        violationManager = ViolationManager(this)
        trainingLogUploader = TrainingLogUploader(this) { message ->
            if (canUpdateUi()) {
                showTopToast(message)
            }
        }
        binding.sessionStatus.text = "Detector loaded: ${pipeline.detectorName}\n$detectorSelfTestSummary\nOpening camera stream and ALPR debug pipeline..."
        runDetectorSelfTest()
        applyStatusPanelState()
        updateUploadButtonText()

        binding.closeButton.setOnClickListener { finish() }
        binding.sessionStatusHeader.setOnClickListener {
            isStatusExpanded = !isStatusExpanded
            applyStatusPanelState()
        }

        binding.syncButton.setOnClickListener {
            syncRegistry()
        }

        binding.uploadQueueButton.setOnClickListener {
            uploadQueue()
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

    private fun uploadQueue() {
        if (violationManager.getQueueSize() == 0) {
            Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show()
            return
        }
        binding.uploadQueueButton.isEnabled = false
        binding.uploadQueueButton.text = "Uploading..."
        lifecycleScope.launch {
            val result = violationManager.uploadQueue()
            if (result.isSuccess) {
                Toast.makeText(this@CameraActivity, "Uploaded ${result.getOrNull()} violations", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@CameraActivity, "Upload failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
            updateUploadButtonText()
            binding.uploadQueueButton.isEnabled = true
        }
    }

    private fun updateUploadButtonText() {
        binding.uploadQueueButton.text = "Upload (${violationManager.getQueueSize()})"
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

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(800, 600),
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
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis, imageCapture)
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
        if (cropPath == lastOcrRequestedPath || isProcessingViolation) {
            return
        }
        lastOcrRequestedPath = cropPath
        latestOcrResult = null
        latestOcrState = OcrUiState.PENDING
        updateCropCaption()
        plateOcrEngine.recognize(cropPath) { result ->
            runOnUiThread {
                if (!canUpdateUi() || cropPath != lastOcrRequestedPath || isProcessingViolation) {
                    return@runOnUiThread
                }
                latestOcrResult = result
                latestOcrState = if (result?.text.isNullOrBlank()) OcrUiState.UNAVAILABLE else OcrUiState.READY
                updateCropCaption()
                recordTrackOcrVote(frameState, result)

                val allowConfirmedActions = result?.text?.let { text ->
                    shouldProcessConfirmedPlate(text)
                } ?: false

                if (allowConfirmedActions) {
                    result?.text?.let { text ->
                        val validation = registryManager.isPlateValid(text)
                        updateValidationUi(validation)
                        if (validation == RegistryManager.PlateValidationResult.NOT_FOUND || validation == RegistryManager.PlateValidationResult.EXPIRED) {
                            promptForViolationCollection(text, result.confidence ?: 0f, cropPath)
                        }
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

    private fun promptForViolationCollection(plateText: String, confidence: Float, cropPath: String) {
        isProcessingViolation = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Violation Found")
            .setMessage("Plate: $plateText\nDo you want to collect evidence for this violation?")
            .setPositiveButton("Yes") { _, _ ->
                promptToCaptureVehiclePhoto(plateText, confidence, cropPath)
            }
            .setNegativeButton("No") { _, _ ->
                isProcessingViolation = false
            }
            .setCancelable(false)
            .show()
    }

    private fun promptToCaptureVehiclePhoto(plateText: String, confidence: Float, cropPath: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Evidence Collection")
            .setMessage("Please point the camera at the vehicle and press 'Capture'.")
            .setPositiveButton("Capture") { _, _ ->
                takeVehiclePhoto { vehiclePhotoPath ->
                    confirmVehiclePhoto(plateText, confidence, cropPath, vehiclePhotoPath)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                isProcessingViolation = false
            }
            .setCancelable(false)
            .show()
    }

    private fun takeVehiclePhoto(onCaptured: (String) -> Unit) {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            isProcessingViolation = false
            return
        }

        val photoFile = File(
            cacheDir,
            "vehicle_${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        onCaptured(photoFile.absolutePath)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(this@CameraActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                        isProcessingViolation = false
                    }
                }
            }
        )
    }

    private fun confirmVehiclePhoto(plateText: String, confidence: Float, cropPath: String, vehiclePhotoPath: String) {
        // Show the captured photo in a dialog for confirmation
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(vehiclePhotoPath))
            adjustViewBounds = true
            setPadding(20, 20, 20, 20)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Confirm Vehicle Photo")
            .setView(imageView)
            .setPositiveButton("Good") { _, _ ->
                queueViolation(plateText, confidence, cropPath, vehiclePhotoPath)
                isProcessingViolation = false
            }
            .setNegativeButton("Retake") { _, _ ->
                File(vehiclePhotoPath).delete()
                promptToCaptureVehiclePhoto(plateText, confidence, cropPath)
            }
            .setCancelable(false)
            .show()
    }

    private fun queueViolation(plateText: String, confidence: Float, cropPath: String, vehiclePath: String) {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = sdf.format(Date())

        val violation = ViolationEvent(
            rawOcrText = plateText,
            confidenceScore = confidence,
            timestamp = timestamp,
            operatorId = "Device_01", // Placeholder
            localPlatePath = cropPath,
            localVehiclePath = vehiclePath
        )
        violationManager.addViolation(violation)
        updateUploadButtonText()
        showTopToast("Violation queued: $plateText")
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
        if (!decision.allowed) {
            // showTopToast("Upload skipped: ${decision.reason}")
            return
        }
        lastTrainingUploadPath = normalizedPath
        // showTopToast("Upload accepted: queued")
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
        if (quality.totalScore < MIN_UPLOAD_QUALITY_SCORE) {
            return UploadDecision(false, "low_quality_score")
        }
        val activeTrack = frameState.activeTrack ?: return UploadDecision(false, "missing_track")
        if (activeTrack.ageFrames < 3) {
            return UploadDecision(false, "unstable_track")
        }
        val normalizedOcr = normalizePlateTextForUpload(ocrResult.text)
        if (normalizedOcr.isBlank()) {
            return UploadDecision(false, "blank_ocr")
        }
        val supportingVotes = countTrackOcrVotes(frameState, normalizedOcr)
        val hasLongerSimilarVote = hasLongerSimilarTrackVote(frameState, normalizedOcr)
        val hasStrongSingleRead =
            (ocrResult.confidence ?: 0f) >= STRONG_SINGLE_READ_CONFIDENCE &&
                quality.totalScore >= STRONG_SINGLE_READ_QUALITY_SCORE &&
                activeTrack.ageFrames >= STRONG_SINGLE_READ_MIN_TRACK_AGE &&
                !hasLongerSimilarVote &&
                !isAmbiguousOcrResult(ocrResult)
        if (supportingVotes < MIN_TRACK_OCR_SUPPORT && hasLongerSimilarVote) {
            return UploadDecision(false, "partial_ocr_conflict")
        }
        if (supportingVotes < MIN_TRACK_OCR_SUPPORT && !hasStrongSingleRead) {
            return UploadDecision(false, "insufficient_ocr_support")
        }
        if (!isPlausiblePlateText(normalizedOcr)) {
            return UploadDecision(false, "implausible_text")
        }
        if (isAmbiguousOcrResult(ocrResult)) {
            return UploadDecision(false, "ambiguous_ocr")
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

    private fun recordTrackOcrVote(
        frameState: com.andre.alprprototype.alpr.PipelineDebugState,
        result: OcrDisplayResult?,
    ) {
        val trackId = frameState.activeTrack?.trackId ?: return
        val normalizedText = result?.text?.let(::normalizePlateTextForUpload).orEmpty()
        if (normalizedText.isBlank()) {
            return
        }
        if (currentOcrVoteTrackId != trackId) {
            currentOcrVoteTrackId = trackId
            currentTrackOcrVotes.clear()
        }
        currentTrackOcrVotes.addLast(normalizedText)
        while (currentTrackOcrVotes.size > OCR_VOTE_WINDOW_SIZE) {
            currentTrackOcrVotes.removeFirst()
        }
    }

    private fun countTrackOcrVotes(
        frameState: com.andre.alprprototype.alpr.PipelineDebugState,
        normalizedText: String,
    ): Int {
        val trackId = frameState.activeTrack?.trackId ?: return 0
        if (currentOcrVoteTrackId != trackId) {
            return 0
        }
        return currentTrackOcrVotes.count { it == normalizedText }
    }

    private fun hasLongerSimilarTrackVote(
        frameState: com.andre.alprprototype.alpr.PipelineDebugState,
        normalizedText: String,
    ): Boolean {
        val trackId = frameState.activeTrack?.trackId ?: return false
        if (currentOcrVoteTrackId != trackId) {
            return false
        }
        return currentTrackOcrVotes.any { priorText ->
            priorText != normalizedText &&
                priorText.length > normalizedText.length &&
                (priorText.startsWith(normalizedText) || priorText.endsWith(normalizedText))
        }
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

    private fun isAmbiguousOcrResult(result: OcrDisplayResult): Boolean {
        if (result.variantCount <= 1) {
            return false
        }
        if (result.agreementCount >= 2) {
            return false
        }
        val margin = result.scoreMargin ?: return true
        return margin < MIN_OCR_SCORE_MARGIN
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
        private const val CONFIRMED_PLATE_COOLDOWN_MS = 4_000L
        private const val MIN_UPLOAD_QUALITY_SCORE = 0.62f
        private const val MIN_OCR_SCORE_MARGIN = 0.08f
        private const val MIN_TRACK_OCR_SUPPORT = 2
        private const val OCR_VOTE_WINDOW_SIZE = 4
        private const val STRONG_SINGLE_READ_CONFIDENCE = 0.76f
        private const val STRONG_SINGLE_READ_QUALITY_SCORE = 0.63f
        private const val STRONG_SINGLE_READ_MIN_TRACK_AGE = 3
    }
}
