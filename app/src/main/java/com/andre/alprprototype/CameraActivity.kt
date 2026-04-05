package com.andre.alprprototype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.media.ExifInterface
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Size
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
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
import android.view.ViewGroup
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
    private var isAnalyzerAttached = false

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
        updateSessionChromeVisibility()

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
                        attachAnalyzerIfNeeded()
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
        clearAnalyzerIfAttached()
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
        plateOcrEngine.recognize(cropPath) { result ->
            runOnUiThread {
                if (!canUpdateUi() || cropPath != lastOcrRequestedPath || isProcessingViolation) {
                    return@runOnUiThread
                }
                latestOcrResult = result
                latestOcrState = if (result?.text.isNullOrBlank()) OcrUiState.UNAVAILABLE else OcrUiState.READY
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
                        if (validation == RegistryManager.PlateValidationResult.NOT_FOUND || validation == RegistryManager.PlateValidationResult.EXPIRED) {
                            promptForViolationCollection(text, result.confidence ?: 0f, cropPath)
                        }
                    }
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
        updateCenterAssistButton()
        binding.debugOverlay.showAssistedTarget(assistedCrop.normalizedRect)
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
        if (!shouldProcessConfirmedPlate(normalizedText)) {
            return true
        }
        val validation = registryManager.isPlateValid(normalizedText)
        if (validation == RegistryManager.PlateValidationResult.NOT_FOUND || validation == RegistryManager.PlateValidationResult.EXPIRED) {
            promptForViolationCollection(normalizedText, 0f, cropPath)
        }
        return true
    }

    private fun promptForViolationCollection(plateText: String, confidence: Float, cropPath: String) {
        isProcessingViolation = true
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_violation_found, null)
        val cropImage = dialogView.findViewById<ImageView>(R.id.violationCropImage)
        val plateTextView = dialogView.findViewById<TextView>(R.id.violationPlateText)
        val statusTextView = dialogView.findViewById<TextView>(R.id.violationStatusText)
        val editButton = dialogView.findViewById<View>(R.id.violationEditButton)
        val dismissButton = dialogView.findViewById<View>(R.id.violationDismissButton)
        val captureButton = dialogView.findViewById<View>(R.id.violationCaptureButton)

        loadViolationCropImage(cropImage, cropPath)

        var currentPlateText = plateText

        fun renderPlateDetails(text: String) {
            currentPlateText = text
            plateTextView.text = text
            statusTextView.text = getRegistryStatusText(registryManager.isPlateValid(text))
        }

        renderPlateDetails(plateText)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Violation Found")
            .setView(dialogView)
            .setCancelable(false)
            .showTracked()

        editButton.setOnClickListener {
            promptForViolationPlateEdit(currentPlateText, confidence, cropPath) { correctedPlate, validation ->
                if (!dialog.isShowing) {
                    return@promptForViolationPlateEdit
                }
                renderPlateDetails(correctedPlate)
                if (validation == RegistryManager.PlateValidationResult.VALID) {
                    dialog.dismiss()
                    isProcessingViolation = false
                    showTopToast("Plate corrected: valid registration found")
                }
            }
        }

        dismissButton.setOnClickListener {
            isProcessingViolation = false
            dialog.dismiss()
        }

        captureButton.setOnClickListener {
            promptToCaptureVehiclePhoto(currentPlateText, confidence, cropPath)
            dialog.dismiss()
        }
    }

    private fun promptForViolationPlateEdit(
        originalPlateText: String,
        confidence: Float,
        cropPath: String,
        onPlateUpdated: ((String, RegistryManager.PlateValidationResult) -> Unit)? = null,
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
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .showTracked()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val correctedPlate = input.text?.toString().orEmpty()
            val validation = handleViolationPlateEdit(correctedPlate, confidence, cropPath, onPlateUpdated)
            if (validation != null) {
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
        onPlateUpdated: ((String, RegistryManager.PlateValidationResult) -> Unit)? = null,
    ): RegistryManager.PlateValidationResult? {
        val normalizedText = rawText.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        if (normalizedText.isBlank()) {
            return null
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

        val validation = registryManager.isPlateValid(normalizedText)
        onPlateUpdated?.invoke(normalizedText, validation)
        if (onPlateUpdated == null && validation == RegistryManager.PlateValidationResult.VALID) {
            isProcessingViolation = false
            showTopToast("Plate corrected: valid registration found")
        }
        return validation
    }

    private fun promptToCaptureVehiclePhoto(plateText: String, confidence: Float, cropPath: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_capture_evidence, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .showTracked()

        styleBottomDialog(dialog)

        dialogView.findViewById<View>(R.id.evidenceCancelButton).setOnClickListener {
            isProcessingViolation = false
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.evidenceCaptureButton).setOnClickListener {
            takeVehiclePhoto { vehiclePhotoPath ->
                confirmVehiclePhoto(plateText, confidence, cropPath, vehiclePhotoPath)
            }
            dialog.dismiss()
        }
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
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_vehicle_photo, null)
        dialogView.findViewById<ImageView>(R.id.confirmVehicleImage).setImageBitmap(loadDisplayBitmap(vehiclePhotoPath))

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .showTracked()

        dialogView.findViewById<View>(R.id.acceptVehicleButton).setOnClickListener {
            queueViolation(plateText, confidence, cropPath, vehiclePhotoPath)
            isProcessingViolation = false
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.retakeVehicleButton).setOnClickListener {
            File(vehiclePhotoPath).delete()
            dialog.dismiss()
            promptToCaptureVehiclePhoto(plateText, confidence, cropPath)
        }
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

    private fun loadViolationCropImage(imageView: ImageView, cropPath: String) {
        val bitmap = BitmapFactory.decodeFile(cropPath)
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
        } else {
            imageView.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }

    private fun loadDisplayBitmap(imagePath: String): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return null
        val rotationDegrees = try {
            when (ExifInterface(imagePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }

        if (rotationDegrees == 0f) {
            return bitmap
        }

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotatedBitmap != bitmap) {
            bitmap.recycle()
        }
        return rotatedBitmap
    }

    private fun styleBottomDialog(dialog: AlertDialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun getRegistryStatusText(result: RegistryManager.PlateValidationResult): String {
        return when (result) {
            RegistryManager.PlateValidationResult.VALID -> getString(R.string.registry_status_valid)
            RegistryManager.PlateValidationResult.EXPIRED -> getString(R.string.registry_status_expired)
            RegistryManager.PlateValidationResult.NOT_FOUND -> getString(R.string.registry_status_not_found)
        }
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

    private fun updateSessionChromeVisibility() {
        val showSessionChrome = !isOperatorDialogVisible()
        binding.topActionCard.visibility = if (showSessionChrome) View.VISIBLE else View.GONE
        binding.centerAssistButton.visibility = if (showSessionChrome) View.VISIBLE else View.GONE
        binding.guideCard.visibility = if (showSessionChrome) View.VISIBLE else View.GONE
        binding.debugOverlay.visibility = if (showSessionChrome) View.VISIBLE else View.INVISIBLE
        if (!showSessionChrome) {
            binding.debugOverlay.clearDebugState()
        }
        if (showSessionChrome) {
            attachAnalyzerIfNeeded()
        } else {
            clearAnalyzerIfAttached()
        }
    }

    private fun attachAnalyzerIfNeeded() {
        val imageAnalysis = analysisUseCase ?: return
        if (isAnalyzerAttached || isOperatorDialogVisible() || !canUseCamera()) {
            return
        }
        imageAnalysis.setAnalyzer(
            cameraExecutor,
            PlateFrameAnalyzer(
                pipeline = pipeline,
                cropSaver = cropSaver,
                shouldAnalyze = { true },
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
                        requestOcrIfNeeded(frame.savedCropPath)
                    } else {
                        framesSinceSavedCrop += 1
                    }
                    maybePromptAssistedCapture(frame)
                    binding.debugOverlay.render(frame.state)
                }
            },
        )
        isAnalyzerAttached = true
    }

    private fun clearAnalyzerIfAttached() {
        if (!isAnalyzerAttached) {
            return
        }
        analysisUseCase?.clearAnalyzer()
        isAnalyzerAttached = false
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
        updateSessionChromeVisibility()
        return show().also { dialog ->
            dialog.setOnDismissListener {
                operatorDialogCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
                updateSessionChromeVisibility()
            }
        }
    }

    private fun AlertDialog.Builder.showTracked(): AlertDialog {
        operatorDialogCount.incrementAndGet()
        updateSessionChromeVisibility()
        return show().also { dialog ->
            dialog.setOnDismissListener {
                operatorDialogCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
                updateSessionChromeVisibility()
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
