package com.andre.alprprototype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.google.android.material.color.MaterialColors
import androidx.lifecycle.lifecycleScope
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.AssistedPlateCropSaver
import com.andre.alprprototype.alpr.BestPlateCropSaver
import com.andre.alprprototype.databinding.ActivityCameraBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraActivity : AppCompatActivity() {
    private enum class OcrUiState {
        IDLE,
        PENDING,
        READY,
        UNAVAILABLE,
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cropSaver: BestPlateCropSaver
    private lateinit var assistedCropSaver: AssistedPlateCropSaver
    private lateinit var pipeline: AlprPipeline
    private lateinit var registryManager: RegistryManager
    private lateinit var violationManager: ViolationManager
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var latestFrame: AnalyzedFrame? = null
    private var lastSavedCropPath: String? = null
    private var lastOcrRequestedPath: String? = null
    private var latestOcrResult: OcrDisplayResult? = null
    private var latestOcrState: OcrUiState = OcrUiState.IDLE
    private var lastCropSource: CropSource = CropSource.AUTO
    private var lastConfirmedPlateText: String? = null
    private var lastConfirmedPlateAtMs: Long = 0L
    private lateinit var plateOcrEngine: PlateOcrEngine
    private var isGuideExpanded: Boolean = false
    private var hasPromptedPendingUploadSyncOnStart: Boolean = false
    private var promptPendingUploadSyncOnResume: Boolean = false
    private val isShuttingDown = AtomicBoolean(false)
    private var isProcessingViolation = false
    private var framesSinceSavedCrop: Int = 0
    private var assistedPromptShown = false
    private var centerCaptureArmed = false
    private val operatorDialogCount = AtomicInteger(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
                maybePromptToSyncPendingUploadsOnStart()
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
        assistedCropSaver = AssistedPlateCropSaver(this)
        pipeline = AlprPipeline.create(this)
        plateOcrEngine = PlateOcrEngine(this)
        registryManager = RegistryManager(this)
        violationManager = ViolationManager(this)
        applyGuidePanelState()
        updateUploadButtonText()
        updateCenterAssistButton()

        binding.closeButton.setOnClickListener { attemptFinishSession() }
        binding.centerAssistButton.setOnClickListener { handleCenterAssistButtonClick() }
        binding.guideHeader.setOnClickListener {
            isGuideExpanded = !isGuideExpanded
            applyGuidePanelState()
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    attemptFinishSession()
                }
            },
        )

        binding.syncButton.setOnClickListener {
            syncRegistry()
        }

        binding.uploadQueueButton.setOnClickListener {
            uploadQueue()
        }

        if (hasCameraPermission()) {
            startCamera()
            maybePromptToSyncPendingUploadsOnStart()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (promptPendingUploadSyncOnResume) {
            promptPendingUploadSyncOnResume = false
            showPendingUploadSyncPrompt(atSessionEnd = false)
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
                            PlateFrameAnalyzer(
                                pipeline = pipeline,
                                cropSaver = cropSaver,
                                shouldAnalyze = { !isOperatorDialogVisible() },
                            ) { frame ->
                                runOnUiThread {
                                    if (!canUpdateUi()) {
                                        return@runOnUiThread
                                    }
                                    latestFrame = frame
                                    if (frame.savedCropPath != null) {
                                        lastSavedCropPath = frame.savedCropPath
                                        lastCropSource = CropSource.AUTO
                                        framesSinceSavedCrop = 0
                                        assistedPromptShown = false
                                        resetCenterCaptureUi()
                                        updateCropPreview(frame.savedCropPath)
                                        requestOcrIfNeeded(frame.savedCropPath)
                                    } else {
                                        framesSinceSavedCrop += 1
                                    }
                                    maybePromptAssistedCapture(frame)
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
        super.onDestroy()
    }

    private fun requestOcrIfNeeded(cropPath: String) {
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
                if (lastCropSource == CropSource.ASSISTED && shouldEscalateAssistedCropToManual(result)) {
                    resetCenterCaptureUi()
                    promptForManualPlateEntry(cropPath, result?.text)
                    return@runOnUiThread
                }

                if (lastCropSource == CropSource.ASSISTED) {
                    resetCenterCaptureUi()
                }

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
                } else {
                    binding.validationIndicator.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun maybePromptAssistedCapture(frame: AnalyzedFrame) {
        if (frame.savedCropPath != null || isProcessingViolation || assistedPromptShown) {
            return
        }
        if (framesSinceSavedCrop < ASSISTED_PROMPT_FRAME_THRESHOLD) {
            return
        }
        assistedPromptShown = true
        showTopToast("Plate not detected. Use Capture Center Plate to grab a centered crop.")
    }

    private fun handleCenterAssistButtonClick() {
        if (centerCaptureArmed) {
            captureCenterAssistCrop()
        } else {
            armCenterAssistCapture()
        }
    }

    private fun armCenterAssistCapture() {
        if (!canUpdateUi() || isProcessingViolation) {
            return
        }
        val overlayRect = assistedCropSaver.previewCenterRect(
            imageWidth = binding.debugOverlay.width,
            imageHeight = binding.debugOverlay.height,
        )
        if (overlayRect == null) {
            showTopToast("Preview not ready for assisted OCR")
            return
        }
        centerCaptureArmed = true
        binding.debugOverlay.showAssistedTarget(overlayRect)
        updateCenterAssistButton()
    }

    private fun captureCenterAssistCrop() {
        if (!canUpdateUi() || isProcessingViolation) {
            return
        }
        val previewBitmap = binding.previewView.bitmap
        if (previewBitmap == null) {
            showTopToast("Preview not ready for assisted OCR")
            return
        }

        val assistedCrop = try {
            assistedCropSaver.saveFromCenter(previewBitmap)
        } catch (_: Exception) {
            showTopToast("Unable to create assisted crop")
            return
        } finally {
            if (!previewBitmap.isRecycled) {
                previewBitmap.recycle()
            }
        }
        if (assistedCrop == null) {
            showTopToast("Unable to create assisted crop")
            return
        }

        centerCaptureArmed = false
        lastCropSource = CropSource.ASSISTED
        lastSavedCropPath = assistedCrop.path
        latestOcrResult = null
        latestOcrState = OcrUiState.PENDING
        binding.validationIndicator.visibility = View.GONE
        updateCenterAssistButton()
        binding.debugOverlay.showAssistedTarget(assistedCrop.normalizedRect)
        updateCropPreview(assistedCrop.path)
        requestOcrIfNeeded(assistedCrop.path)
    }

    private fun shouldEscalateAssistedCropToManual(result: OcrDisplayResult?): Boolean {
        if (result == null) {
            return true
        }
        if (result.text.isBlank()) {
            return true
        }
        if (result.text.length < MIN_ASSISTED_TEXT_LENGTH) {
            return true
        }
        if ((result.confidence ?: 0f) < MANUAL_ENTRY_CONFIDENCE_THRESHOLD) {
            return true
        }
        if (result.variantCount > 1 && result.agreementCount < MIN_ASSISTED_OCR_AGREEMENT) {
            return true
        }
        if (result.variantCount > 1 && (result.scoreMargin ?: 0f) < MIN_ASSISTED_SCORE_MARGIN) {
            return true
        }
        return false
    }

    private fun promptForManualPlateEntry(cropPath: String, suggestedText: String?) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(10))
            setText(suggestedText.orEmpty())
            setSelection(text.length)
            hint = "Enter plate"
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setCustomTitle(buildCenteredDialogTitle("Manual Plate Entry"))
            .setMessage("Assisted OCR could not read the plate reliably. Enter the plate manually.")
            .setView(input)
            .setPositiveButton("Use plate", null)
            .setNegativeButton("Cancel", null)
            .showTracked()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val manualPlate = input.text?.toString().orEmpty()
            if (handleManualPlateEntry(manualPlate, cropPath)) {
                dialog.dismiss()
            } else {
                input.error = "Enter a valid plate"
            }
        }
    }

    private fun handleManualPlateEntry(rawText: String, cropPath: String): Boolean {
        val normalizedText = rawText.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        if (normalizedText.isBlank()) {
            return false
        }
        latestOcrResult = OcrDisplayResult(
            text = normalizedText,
            sourcePath = cropPath,
            confidence = null,
            agreementCount = 0,
            variantCount = 0,
            scoreMargin = null,
        )
        latestOcrState = OcrUiState.READY
        resetCenterCaptureUi()
        updateCropCaption()
        if (!shouldProcessConfirmedPlate(normalizedText)) {
            return true
        }
        val validation = registryManager.isPlateValid(normalizedText)
        updateValidationUi(validation)
        if (validation == RegistryManager.PlateValidationResult.NOT_FOUND || validation == RegistryManager.PlateValidationResult.EXPIRED) {
            promptForViolationCollection(normalizedText, 0f, cropPath)
        }
        return true
    }

    private fun promptForViolationCollection(plateText: String, confidence: Float, cropPath: String) {
        isProcessingViolation = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Violation Found")
            .setMessage("Plate: $plateText\nDo you want to collect evidence for this violation?")
            .setPositiveButton("Yes") { _, _ ->
                promptToCaptureVehiclePhoto(plateText, confidence, cropPath)
            }
            .setNeutralButton("Edit plate") { _, _ ->
                promptForViolationPlateEdit(plateText, confidence, cropPath)
            }
            .setNegativeButton("No") { _, _ ->
                isProcessingViolation = false
            }
            .setCancelable(false)
            .showTracked()
    }

    private fun promptForViolationPlateEdit(
        originalPlateText: String,
        confidence: Float,
        cropPath: String,
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(10))
            setText(originalPlateText)
            setSelection(text.length)
            hint = "Edit plate"
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Edit Plate")
            .setMessage("Correct the plate text before continuing.")
            .setView(input)
            .setPositiveButton("Use corrected", null)
            .setNegativeButton("Cancel") { _, _ ->
                promptForViolationCollection(originalPlateText, confidence, cropPath)
            }
            .setCancelable(false)
            .showTracked()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val correctedPlate = input.text?.toString().orEmpty()
            if (handleViolationPlateEdit(correctedPlate, confidence, cropPath)) {
                dialog.dismiss()
            } else {
                input.error = "Enter a valid plate"
            }
        }
    }

    private fun handleViolationPlateEdit(
        rawText: String,
        confidence: Float,
        cropPath: String,
    ): Boolean {
        val normalizedText = rawText.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        if (normalizedText.isBlank()) {
            return false
        }

        latestOcrResult = OcrDisplayResult(
            text = normalizedText,
            sourcePath = cropPath,
            confidence = confidence,
            agreementCount = latestOcrResult?.agreementCount ?: 0,
            variantCount = latestOcrResult?.variantCount ?: 0,
            scoreMargin = latestOcrResult?.scoreMargin,
        )
        latestOcrState = OcrUiState.READY
        updateCropCaption()

        val validation = registryManager.isPlateValid(normalizedText)
        updateValidationUi(validation)
        when (validation) {
            RegistryManager.PlateValidationResult.NOT_FOUND,
            RegistryManager.PlateValidationResult.EXPIRED -> {
                promptForViolationCollection(normalizedText, confidence, cropPath)
            }
            RegistryManager.PlateValidationResult.VALID -> {
                isProcessingViolation = false
                showTopToast("Plate corrected: valid registration found")
            }
        }
        return true
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
            .showTracked()
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
            .showTracked()
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
        val result = violationManager.addViolation(violation)
        if (result.isSuccess) {
            updateUploadButtonText()
            showTopToast("Violation queued: $plateText")
        } else {
            showTopToast("Failed to queue violation")
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

    private fun maybePromptToSyncPendingUploadsOnStart() {
        if (hasPromptedPendingUploadSyncOnStart || violationManager.getQueueSize() <= 0) {
            return
        }
        hasPromptedPendingUploadSyncOnStart = true
        showPendingUploadSyncPrompt(atSessionEnd = false)
    }

    private fun attemptFinishSession() {
        if (violationManager.getQueueSize() <= 0) {
            finish()
            return
        }
        showPendingUploadSyncPrompt(atSessionEnd = true)
    }

    private fun showPendingUploadSyncPrompt(atSessionEnd: Boolean) {
        val pendingCount = violationManager.getQueueSize()
        if (pendingCount <= 0) {
            if (atSessionEnd) {
                finish()
            }
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (atSessionEnd) "Queued uploads before closing" else "Queued uploads saved")
            .setMessage(buildPendingUploadPromptMessage(pendingCount, atSessionEnd))
            .setPositiveButton("Sync now") { _, _ ->
                lifecycleScope.launch {
                    val result = violationManager.uploadQueue()
                    updateUploadButtonText()
                    if (result.isSuccess) {
                        showTopToast("Uploaded ${result.getOrNull() ?: 0} queued item(s)")
                    } else {
                        showTopToast("Queued uploads not synced")
                    }
                    if (atSessionEnd) {
                        finish()
                    }
                }
            }
            .setNeutralButton("Wi-Fi settings") { _, _ ->
                promptPendingUploadSyncOnResume = true
                openWifiSettings()
            }
            .setNegativeButton(if (atSessionEnd) "End session" else "Later") { _, _ ->
                if (atSessionEnd) {
                    finish()
                }
            }
            .showTracked()
        stylePendingUploadDialog(dialog)
    }

    private fun buildPendingUploadPromptMessage(pendingCount: Int, atSessionEnd: Boolean): String {
        val itemLabel = if (pendingCount == 1) "upload" else "uploads"
        val presentVerb = if (pendingCount == 1) "is" else "are"
        val pastVerb = if (pendingCount == 1) "was" else "were"
        val syncHint = "Sync them now if the device is online, or keep them saved for the next session."
        return if (atSessionEnd) {
            "$pendingCount queued $itemLabel $presentVerb still saved on this device. $syncHint"
        } else {
            "$pendingCount queued $itemLabel $pastVerb saved from an earlier session. $syncHint"
        }
    }

    private fun openWifiSettings() {
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    private fun shouldProcessConfirmedPlate(text: String): Boolean {
        val normalizedText = text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
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

    private fun applyGuidePanelState() {
        binding.guideText.visibility = if (isGuideExpanded) android.view.View.VISIBLE else android.view.View.GONE
        binding.guideToggle.setText(
            if (isGuideExpanded) R.string.session_status_collapse else R.string.session_status_expand,
        )
    }

    private fun resetCenterCaptureUi() {
        centerCaptureArmed = false
        binding.debugOverlay.showAssistedTarget(null)
        updateCenterAssistButton()
    }

    private fun updateCenterAssistButton() {
        binding.centerAssistButton.text = if (centerCaptureArmed) "Capture" else "Capture Center Plate"
    }

    private fun canUpdateUi(): Boolean {
        return !isShuttingDown.get() && !isDestroyed && !isFinishing
    }

    private fun canUseCamera(): Boolean {
        return !isShuttingDown.get() && !isDestroyed && !isFinishing
    }

    private fun isOperatorDialogVisible(): Boolean = operatorDialogCount.get() > 0

    private fun MaterialAlertDialogBuilder.showTracked(): androidx.appcompat.app.AlertDialog {
        operatorDialogCount.incrementAndGet()
        return show().also { dialog ->
            dialog.setOnDismissListener {
                operatorDialogCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            }
        }
    }

    private fun AlertDialog.Builder.showTracked(): AlertDialog {
        operatorDialogCount.incrementAndGet()
        return show().also { dialog ->
            dialog.setOnDismissListener {
                operatorDialogCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            }
        }
    }

    private fun showTopToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 120)
            show()
        }
    }

    private fun buildCenteredDialogTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            textSize = 20f
            setPadding(
                24.dpToPx(),
                18.dpToPx(),
                24.dpToPx(),
                6.dpToPx(),
            )
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun stylePendingUploadDialog(dialog: AlertDialog) {
        val syncColor = ContextCompat.getColor(this, R.color.dialog_button_sync)
        val wifiColor = ContextCompat.getColor(this, R.color.dialog_button_wifi)
        val dismissColor = ContextCompat.getColor(this, R.color.dialog_button_dismiss)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(syncColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(wifiColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(dismissColor)
    }

    companion object {
        private const val CONFIRMED_PLATE_COOLDOWN_MS = 4_000L
        private const val ASSISTED_PROMPT_FRAME_THRESHOLD = 24
        private const val MANUAL_ENTRY_CONFIDENCE_THRESHOLD = 0.70f
        private const val MIN_ASSISTED_OCR_AGREEMENT = 2
        private const val MIN_ASSISTED_SCORE_MARGIN = 0.08f
        private const val MIN_ASSISTED_TEXT_LENGTH = 5
    }

    private enum class CropSource {
        AUTO,
        ASSISTED,
    }
}
