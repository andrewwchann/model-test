package com.andre.alprprototype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
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
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import androidx.lifecycle.lifecycleScope
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.AssistedCropController
import com.andre.alprprototype.alpr.BestPlateCropSaver
import com.andre.alprprototype.databinding.ActivityCameraBinding
import com.andre.alprprototype.ocr.AssistedOcrPolicy
import com.andre.alprprototype.ocr.AssistedOcrPolicyConfig
import com.andre.alprprototype.ocr.PlateRecognitionAction
import com.andre.alprprototype.ocr.PlateRecognitionFlow
import com.andre.alprprototype.ocr.PlateTextNormalizer
import com.andre.alprprototype.session.CameraCropSource
import com.andre.alprprototype.session.CameraSessionPolicy
import com.andre.alprprototype.session.CenterAssistArmDecision
import com.andre.alprprototype.session.CenterAssistCaptureDecision
import com.andre.alprprototype.session.CenterAssistFlow
import com.andre.alprprototype.session.ConfirmedPlateTracker
import com.andre.alprprototype.session.EvidenceFlowState
import com.andre.alprprototype.session.FrameAnalysisFlow
import com.andre.alprprototype.session.OcrCallbackFlow
import com.andre.alprprototype.session.OcrResultUiState
import com.andre.alprprototype.session.PendingUploadFlow
import com.andre.alprprototype.session.PendingUploadPromptFormatter
import com.andre.alprprototype.session.SessionOperationFlow
import com.andre.alprprototype.session.ViolationEvidenceFlow
import com.andre.alprprototype.session.ViolationReviewFlow
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
    private lateinit var assistedCropSaver: AssistedCropController
    private lateinit var pipeline: AlprPipeline
    private lateinit var registryManager: PlateRegistry
    private lateinit var violationManager: ViolationQueue
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var latestFrame: AnalyzedFrame? = null
    private var lastSavedCropPath: String? = null
    private var lastOcrRequestedPath: String? = null
    private var latestOcrResult: OcrDisplayResult? = null
    private var latestOcrState: OcrUiState = OcrUiState.IDLE
    private var lastCropSource: CropSource = CropSource.AUTO
    private lateinit var plateOcrEngine: PlateOcrRecognizer
    private var isGuideExpanded: Boolean = false
    private var hasPromptedPendingUploadSyncOnStart: Boolean = false
    private var promptPendingUploadSyncOnResume: Boolean = false
    private val isShuttingDown = AtomicBoolean(false)
    private var framesSinceSavedCrop: Int = 0
    private var assistedPromptShown = false
    private var centerCaptureArmed = false
    private val operatorDialogCount = AtomicInteger(0)
    private var isAnalyzerAttached = false
    private val evidenceFlowState = EvidenceFlowState()
    private val confirmedPlateTracker = ConfirmedPlateTracker(CONFIRMED_PLATE_COOLDOWN_MS)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            handleCameraPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        cropSaver = CameraActivityDependencies.bestPlateCropSaverFactory(this)
        assistedCropSaver = CameraActivityDependencies.assistedPlateCropSaverFactory(this)
        pipeline = CameraActivityDependencies.pipelineFactory(this)
        plateOcrEngine = CameraActivityDependencies.plateOcrRecognizerFactory(this)
        registryManager = CameraActivityDependencies.registryManagerFactory(this)
        violationManager = CameraActivityDependencies.violationManagerFactory(this)
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
        val loadingState = SessionOperationFlow.registrySyncLoadingState()
        binding.syncButton.isEnabled = loadingState.enabled
        binding.syncButton.setText(loadingState.textRes)
        lifecycleScope.launch {
            val result = registryManager.syncRegistry()
            val outcome = SessionOperationFlow.registrySyncOutcome(result.isSuccess, result.getOrNull())
            val message = outcome.formatArg?.let { getString(outcome.messageRes, it) } ?: getString(outcome.messageRes)
            Toast.makeText(this@CameraActivity, message, Toast.LENGTH_SHORT).show()
            val idleState = SessionOperationFlow.registrySyncIdleState()
            binding.syncButton.isEnabled = idleState.enabled
            binding.syncButton.setText(idleState.textRes)
        }
    }

    private fun uploadQueue() {
        if (violationManager.getQueueSize() == 0) {
            Toast.makeText(this, getString(SessionOperationFlow.queueUploadEmpty().messageRes), Toast.LENGTH_SHORT).show()
            return
        }
        val loadingState = SessionOperationFlow.queueUploadLoadingState()
        binding.uploadQueueButton.isEnabled = loadingState.enabled
        binding.uploadQueueButton.setText(loadingState.textRes)
        lifecycleScope.launch {
            val result = violationManager.uploadQueue()
            val outcome = SessionOperationFlow.queueUploadOutcome(
                isSuccess = result.isSuccess,
                count = result.getOrNull(),
                errorMessage = result.exceptionOrNull()?.message,
            )
            val message = outcome.formatArg?.let { getString(outcome.messageRes, it) } ?: getString(outcome.messageRes)
            Toast.makeText(this@CameraActivity, message, if (outcome.longMessage) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            updateUploadButtonText()
            binding.uploadQueueButton.isEnabled = SessionOperationFlow.queueUploadIdleState().enabled
        }
    }

    private fun updateUploadButtonText() {
        binding.uploadQueueButton.text = getString(
            SessionOperationFlow.queueUploadIdleState().textRes,
            violationManager.getQueueSize(),
        )
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        CameraActivityDependencies.cameraRuntimeController.startCamera(
            activity = this,
            binding = binding,
            mainExecutor = ContextCompat.getMainExecutor(this),
            cameraExecutor = cameraExecutor,
            pipeline = pipeline,
            cropSaver = cropSaver,
            canUseCamera = ::canUseCamera,
            attachAnalyzer = { imageAnalysis ->
                analysisUseCase = imageAnalysis
                attachAnalyzerIfNeeded()
            },
        )
    }

    internal fun onCameraSessionStarted(result: CameraSessionStartResult) {
        cameraProvider = result.provider
        analysisUseCase = result.analysisUseCase
        imageCapture = result.imageCapture
    }

    internal fun handleCameraPermissionResult(granted: Boolean) {
        if (granted) {
            startCamera()
            maybePromptToSyncPendingUploadsOnStart()
        } else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
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
        if (!CameraSessionPolicy.shouldRequestOcr(cropPath, lastOcrRequestedPath, evidenceFlowState.isProcessingViolation)) {
            return
        }
        lastOcrRequestedPath = cropPath
        latestOcrResult = null
        latestOcrState = OcrUiState.PENDING
        plateOcrEngine.recognize(cropPath) { result ->
            runOnUiThread {
                val decision = OcrCallbackFlow.decide(
                    canUpdateUi = canUpdateUi(),
                    cropMatchesLatestRequest = cropPath == lastOcrRequestedPath,
                    isProcessingViolation = evidenceFlowState.isProcessingViolation,
                    cropSource = lastCropSource.toDecisionSource(),
                    result = result,
                    shouldEscalateAssistedCropToManual = ::shouldEscalateAssistedCropToManual,
                    shouldProcessConfirmedPlate = ::shouldProcessConfirmedPlate,
                    validator = registryManager::isPlateValid,
                )
                if (!decision.shouldApply) {
                    return@runOnUiThread
                }
                latestOcrResult = result
                latestOcrState = when (decision.uiState) {
                    OcrResultUiState.READY -> OcrUiState.READY
                    OcrResultUiState.UNAVAILABLE -> OcrUiState.UNAVAILABLE
                }
                if (decision.shouldResetCenterCapture) {
                    resetCenterCaptureUi()
                }
                if (decision.promptManualEntry) {
                    promptForManualPlateEntry(cropPath, decision.manualEntrySuggestion)
                    return@runOnUiThread
                }
                when (decision.recognitionAction) {
                    PlateRecognitionAction.IGNORE -> Unit
                    PlateRecognitionAction.SHOW_VALID -> {
                        showTopToast(getString(R.string.plate_valid_message, decision.normalizedText))
                    }
                    PlateRecognitionAction.PROMPT_VIOLATION -> {
                        promptForViolationCollection(decision.normalizedText.orEmpty(), result?.confidence ?: 0f, cropPath)
                    }
                }
            }
        }
    }

    private fun handleCenterAssistButtonClick() {
        if (centerCaptureArmed) {
            captureCenterAssistCrop()
        } else {
            armCenterAssistCapture()
        }
    }

    private fun armCenterAssistCapture() {
        when (
            val decision = CenterAssistFlow.decideArm(
                canUpdateUi = canUpdateUi(),
                isProcessingViolation = evidenceFlowState.isProcessingViolation,
                overlayRect = assistedCropSaver.previewCenterRect(
            imageWidth = binding.debugOverlay.width,
            imageHeight = binding.debugOverlay.height,
                ),
            )
        ) {
            CenterAssistArmDecision.Ignore -> return
            CenterAssistArmDecision.PreviewNotReady -> {
                showTopToast("Preview not ready for assisted OCR")
                return
            }
            is CenterAssistArmDecision.Arm -> {
                centerCaptureArmed = true
                binding.debugOverlay.showAssistedTarget(decision.overlayRect)
                updateCenterAssistButton()
            }
        }
    }

    private fun captureCenterAssistCrop() {
        when (
            val decision = CenterAssistFlow.capture(
                canUpdateUi = canUpdateUi(),
                isProcessingViolation = evidenceFlowState.isProcessingViolation,
                previewBitmap = CameraActivityDependencies.previewBitmapProvider(binding.previewView),
                saveFromCenter = assistedCropSaver::saveFromCenter,
            )
        ) {
            CenterAssistCaptureDecision.Ignore -> return
            CenterAssistCaptureDecision.PreviewNotReady -> {
                showTopToast("Preview not ready for assisted OCR")
                return
            }
            CenterAssistCaptureDecision.CaptureFailed -> {
                showTopToast("Unable to create assisted crop")
                return
            }
            is CenterAssistCaptureDecision.Captured -> {
                val assistedCrop = decision.crop
                centerCaptureArmed = false
                lastCropSource = CropSource.ASSISTED
                lastSavedCropPath = assistedCrop.path
                latestOcrResult = null
                latestOcrState = OcrUiState.PENDING
                updateCenterAssistButton()
                binding.debugOverlay.showAssistedTarget(assistedCrop.normalizedRect)
                requestOcrIfNeeded(assistedCrop.path)
            }
        }
    }

    private fun shouldEscalateAssistedCropToManual(result: OcrDisplayResult?): Boolean {
        return AssistedOcrPolicy.shouldEscalateToManual(
            result = result,
            config = AssistedOcrPolicyConfig(
                minTextLength = MIN_ASSISTED_TEXT_LENGTH,
                minConfidence = MANUAL_ENTRY_CONFIDENCE_THRESHOLD,
                minAgreement = MIN_ASSISTED_OCR_AGREEMENT,
                minScoreMargin = MIN_ASSISTED_SCORE_MARGIN,
            ),
        )
    }

    private fun promptForManualPlateEntry(cropPath: String, suggestedText: String?) {
        val spec = ViolationReviewFlow.manualPlateInputSpec(suggestedText)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(spec.maxLength))
            setText(spec.initialText)
            setSelection(text.length)
            hint = getString(spec.hintRes)
        }

        val dialogBuilder = MaterialAlertDialogBuilder(this)
            .setMessage(spec.messageRes)
            .setView(input)
            .setPositiveButton(spec.positiveButtonRes, null)
            .setNegativeButton(spec.negativeButtonRes, null)
        if (spec.useCenteredTitle) {
            dialogBuilder.setCustomTitle(buildCenteredDialogTitle(getString(spec.titleRes)))
        } else {
            dialogBuilder.setTitle(spec.titleRes)
        }
        val dialog = dialogBuilder.showTracked()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val manualPlate = input.text?.toString().orEmpty()
            if (handleManualPlateEntry(manualPlate, cropPath)) {
                dialog.dismiss()
            } else {
                input.error = getString(R.string.plate_validation_error)
            }
        }
    }

    private fun handleManualPlateEntry(rawText: String, cropPath: String): Boolean {
        val decision = ViolationReviewFlow.manualEntryUiDecision(
            rawText = rawText,
            cropPath = cropPath,
            shouldProcessConfirmedPlate = ::shouldProcessConfirmedPlate,
            validator = registryManager::isPlateValid,
        )
        if (!decision.accepted) {
            return false
        }
        latestOcrResult = decision.ocrResult
        latestOcrState = OcrUiState.READY
        if (decision.shouldResetCenterCapture) {
            resetCenterCaptureUi()
        }
        decision.toastMessageRes?.let { messageRes ->
            val message = decision.toastFormatArg?.let { getString(messageRes, it) } ?: getString(messageRes)
            showTopToast(message)
        }
        decision.violationPlateText?.let { plateText ->
            promptForViolationCollection(plateText, 0f, cropPath)
        }
        return true
    }

    private fun promptForViolationCollection(plateText: String, confidence: Float, cropPath: String) {
        evidenceFlowState.beginViolationReview()
        updateSessionChromeVisibility()
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
            val displayState = ViolationReviewFlow.reviewDisplayState(text, registryManager.isPlateValid(text))
            currentPlateText = displayState.plateText
            plateTextView.text = displayState.plateText
            statusTextView.setText(displayState.statusTextRes)
        }

        renderPlateDetails(plateText)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.violation_found_title)
            .setView(dialogView)
            .setCancelable(false)
            .showTracked()

        editButton.setOnClickListener {
            promptForViolationPlateEdit(currentPlateText, confidence, cropPath) { correctedPlate, validation ->
                if (!dialog.isShowing) {
                    return@promptForViolationPlateEdit
                }
                renderPlateDetails(correctedPlate)
                val decision = ViolationEvidenceFlow.reviewPlateUpdated(validation)
                if (decision.shouldFinishEvidenceFlow) {
                    evidenceFlowState.finish()
                    updateSessionChromeVisibility()
                }
                if (decision.shouldDismissDialog) {
                    dialog.dismiss()
                }
                decision.toastMessageRes?.let { messageRes ->
                    showTopToast(getString(messageRes))
                }
            }
        }

        dismissButton.setOnClickListener {
            val decision = ViolationEvidenceFlow.reviewDismissed()
            if (decision.shouldFinishEvidenceFlow) {
                evidenceFlowState.finish()
                updateSessionChromeVisibility()
            }
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
        val spec = ViolationReviewFlow.violationPlateEditSpec(originalPlateText)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(spec.maxLength))
            setText(spec.initialText)
            setSelection(text.length)
            hint = getString(spec.hintRes)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(spec.titleRes)
            .setMessage(spec.messageRes)
            .setView(input)
            .setPositiveButton(spec.positiveButtonRes, null)
            .setNegativeButton(spec.negativeButtonRes, null)
            .setCancelable(false)
            .showTracked()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val correctedPlate = input.text?.toString().orEmpty()
            val validation = handleViolationPlateEdit(correctedPlate, confidence, cropPath, onPlateUpdated)
            if (validation != null) {
                dialog.dismiss()
            } else {
                input.error = getString(R.string.plate_validation_error)
            }
        }
    }

    private fun handleViolationPlateEdit(
        rawText: String,
        confidence: Float,
        cropPath: String,
        onPlateUpdated: ((String, RegistryManager.PlateValidationResult) -> Unit)? = null,
    ): RegistryManager.PlateValidationResult? {
        val decision = ViolationReviewFlow.handleViolationPlateEdit(
            rawText = rawText,
            confidence = confidence,
            cropPath = cropPath,
            previousResult = latestOcrResult,
            validator = registryManager::isPlateValid,
        )
        if (!decision.accepted) {
            return null
        }
        latestOcrResult = decision.ocrResult
        latestOcrState = OcrUiState.READY
        val validation = decision.validation ?: return null
        onPlateUpdated?.invoke(decision.normalizedText.orEmpty(), validation)
        if (onPlateUpdated == null) {
            val uiDecision = ViolationReviewFlow.standalonePlateEditUiDecision(validation)
            if (uiDecision.shouldFinishEvidenceFlow) {
                evidenceFlowState.finish()
                updateSessionChromeVisibility()
            }
            uiDecision.toastMessageRes?.let { messageRes ->
                showTopToast(getString(messageRes))
            }
        }
        return validation
    }

    private fun promptToCaptureVehiclePhoto(plateText: String, confidence: Float, cropPath: String) {
        evidenceFlowState.beginCapturePrompt()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_capture_evidence, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .showTracked()

        styleBottomDialog(dialog)

        dialogView.findViewById<View>(R.id.evidenceCancelButton).setOnClickListener {
            val decision = ViolationEvidenceFlow.capturePromptCancelled()
            if (decision.shouldFinishEvidenceFlow) {
                evidenceFlowState.finish()
                updateSessionChromeVisibility()
            }
            if (decision.shouldDismissDialog) {
                dialog.dismiss()
            }
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
            val decision = ViolationEvidenceFlow.vehicleCaptureUnavailable()
            decision.toastMessageRes?.let { messageRes ->
                Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show()
            }
            if (decision.shouldFinishEvidenceFlow) {
                evidenceFlowState.finish()
                updateSessionChromeVisibility()
            }
            return
        }

        val photoFile = File(
            cacheDir,
            "vehicle_${System.currentTimeMillis()}.jpg"
        )

        CameraActivityDependencies.vehiclePhotoCaptureExecutor.capture(
            imageCapture = capture,
            photoFile = photoFile,
            cameraExecutor,
            onSaved = {
                runOnUiThread {
                    onCaptured(photoFile.absolutePath)
                }
            },
            onError = { exception ->
                runOnUiThread {
                    Toast.makeText(this@CameraActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    val decision = ViolationEvidenceFlow.vehicleCaptureFailed()
                    if (decision.shouldFinishEvidenceFlow) {
                        evidenceFlowState.finish()
                        updateSessionChromeVisibility()
                    }
                }
            },
        )
    }

    private fun confirmVehiclePhoto(plateText: String, confidence: Float, cropPath: String, vehiclePhotoPath: String) {
        evidenceFlowState.beginPhotoConfirmation()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_vehicle_photo, null)
        dialogView.findViewById<ImageView>(R.id.confirmVehicleImage).setImageBitmap(loadDisplayBitmap(vehiclePhotoPath))

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .showTracked()

        dialogView.findViewById<View>(R.id.acceptVehicleButton).setOnClickListener {
            val decision = ViolationEvidenceFlow.photoAccepted()
            if (decision.shouldFinishEvidenceFlow) {
                evidenceFlowState.finish()
            }
            if (decision.shouldQueueViolation) {
                queueViolation(plateText, confidence, cropPath, vehiclePhotoPath)
            }
            updateSessionChromeVisibility()
            if (decision.shouldDismissDialog) {
                dialog.dismiss()
            }
        }

        dialogView.findViewById<View>(R.id.retakeVehicleButton).setOnClickListener {
            val decision = ViolationEvidenceFlow.photoRetake()
            File(vehiclePhotoPath).delete()
            if (decision.shouldDismissDialog) {
                dialog.dismiss()
            }
            if (decision.shouldRestartCapturePrompt) {
                promptToCaptureVehiclePhoto(plateText, confidence, cropPath)
            }
        }
    }

    private fun queueViolation(plateText: String, confidence: Float, cropPath: String, vehiclePath: String) {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = sdf.format(Date())

        val violation = ViolationReviewFlow.buildViolationEvent(
            plateText = plateText,
            confidence = confidence,
            timestamp = timestamp,
            cropPath = cropPath,
            vehiclePath = vehiclePath,
        )
        val result = violationManager.addViolation(violation)
        val decision = ViolationEvidenceFlow.queuedViolationResult(result.isSuccess, plateText)
        if (result.isSuccess) {
            updateUploadButtonText()
        }
        decision.toastMessageRes?.let { messageRes ->
            val message = decision.toastFormatArg?.let { getString(messageRes, it) } ?: getString(messageRes)
            showTopToast(message)
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
        val targetWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtLeast(1)
        val targetHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt().coerceAtLeast(1)
        return DisplayBitmapLoader.load(imagePath, targetWidth, targetHeight)
    }

    private fun styleBottomDialog(dialog: AlertDialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun maybePromptToSyncPendingUploadsOnStart() {
        val pendingCount = violationManager.getQueueSize()
        val decision = PendingUploadFlow.promptDecision(pendingCount, atSessionEnd = false)
        if (hasPromptedPendingUploadSyncOnStart || !decision.shouldShowPrompt) {
            return
        }
        hasPromptedPendingUploadSyncOnStart = true
        showPendingUploadSyncPrompt(atSessionEnd = false)
    }

    private fun attemptFinishSession() {
        val pendingCount = violationManager.getQueueSize()
        val decision = PendingUploadFlow.promptDecision(pendingCount, atSessionEnd = true)
        if (decision.shouldFinishSession) {
            finish()
            return
        }
        showPendingUploadSyncPrompt(atSessionEnd = true)
    }

    private fun showPendingUploadSyncPrompt(atSessionEnd: Boolean) {
        val pendingCount = violationManager.getQueueSize()
        val decision = PendingUploadFlow.promptDecision(pendingCount, atSessionEnd)
        if (!decision.shouldShowPrompt) {
            if (decision.shouldFinishSession) {
                finish()
            }
            return
        }

        val promptSpec = decision.promptSpec ?: return
        val dialog = AlertDialog.Builder(this)
            .setTitle(promptSpec.titleRes)
            .setMessage(buildPendingUploadPromptMessage(pendingCount, atSessionEnd))
            .setPositiveButton(R.string.pending_upload_sync_now) { _, _ ->
                lifecycleScope.launch {
                    val result = violationManager.uploadQueue()
                    updateUploadButtonText()
                    val outcome = PendingUploadFlow.syncOutcome(result.isSuccess, result.getOrNull(), atSessionEnd)
                    if (outcome.shouldShowSuccess) {
                        showTopToast(getString(R.string.pending_upload_synced_message, outcome.uploadedCount))
                    } else {
                        showTopToast(getString(R.string.pending_upload_not_synced))
                    }
                    if (outcome.shouldFinishSession) {
                        finish()
                    }
                }
            }
            .setNeutralButton(R.string.pending_upload_wifi_settings) { _, _ ->
                val action = PendingUploadFlow.wifiSettingsAction()
                promptPendingUploadSyncOnResume = action.shouldPromptOnResume
                if (action.shouldOpenWifiSettings) {
                    openWifiSettings()
                }
            }
            .setNegativeButton(promptSpec.negativeButtonRes) { _, _ ->
                val action = PendingUploadFlow.dismissAction(atSessionEnd)
                if (action.shouldFinishSession) {
                    finish()
                }
            }
            .showTracked()
        stylePendingUploadDialog(dialog)
    }

    private fun buildPendingUploadPromptMessage(pendingCount: Int, atSessionEnd: Boolean): String {
        return PendingUploadPromptFormatter.buildMessage(pendingCount, atSessionEnd)
    }

    private fun openWifiSettings() {
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    private fun shouldProcessConfirmedPlate(text: String): Boolean {
        val normalizedText = PlateTextNormalizer.normalize(text)
        return confirmedPlateTracker.shouldProcess(normalizedText)
    }

    private fun applyGuidePanelState() {
        binding.guideText.visibility = if (isGuideExpanded) android.view.View.VISIBLE else android.view.View.GONE
        binding.guideToggle.setText(
            if (isGuideExpanded) R.string.session_status_collapse else R.string.session_status_expand,
        )
    }

    private fun updateSessionChromeVisibility() {
        val decision = CameraSessionPolicy.chromeDecision(
            operatorDialogVisible = isOperatorDialogVisible(),
            evidenceFlowActive = evidenceFlowState.isActive,
            isAnalyzerAttached = isAnalyzerAttached,
            canUseCamera = canUseCamera(),
        )
        binding.topActionCard.visibility = decision.topActionVisibility
        binding.centerAssistButton.visibility = decision.centerAssistVisibility
        binding.guideCard.visibility = decision.guideVisibility
        binding.debugOverlay.visibility = decision.debugOverlayVisibility
        if (decision.shouldClearDebugState) {
            binding.debugOverlay.clearDebugState()
        }
        if (decision.shouldAttachAnalyzer) {
            attachAnalyzerIfNeeded()
        }
        if (decision.shouldClearAnalyzer) {
            clearAnalyzerIfAttached()
        }
    }

    private fun attachAnalyzerIfNeeded() {
        val imageAnalysis = analysisUseCase ?: return
        if (!CameraSessionPolicy.shouldAttachAnalyzer(
                isAnalyzerAttached = isAnalyzerAttached,
                operatorDialogVisible = isOperatorDialogVisible(),
                evidenceFlowActive = evidenceFlowState.isActive,
                canUseCamera = canUseCamera(),
            )
        ) {
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
                    handleAnalyzedFrame(frame)
                }
            },
        )
        isAnalyzerAttached = true
    }

    internal fun handleAnalyzedFrame(frame: AnalyzedFrame) {
        if (!canUpdateUi()) {
            return
        }
        latestFrame = frame
        val decision = FrameAnalysisFlow.decide(
            savedCropPath = frame.savedCropPath,
            previousFramesSinceSavedCrop = framesSinceSavedCrop,
            assistedPromptShown = assistedPromptShown,
            isProcessingViolation = evidenceFlowState.isProcessingViolation,
            assistedPromptThreshold = ASSISTED_PROMPT_FRAME_THRESHOLD,
        )
        lastSavedCropPath = decision.savedCropPath ?: lastSavedCropPath
        framesSinceSavedCrop = decision.framesSinceSavedCrop
        assistedPromptShown = decision.assistedPromptShown
        if (decision.savedCropPath != null) {
            lastCropSource = CropSource.AUTO
        }
        if (decision.shouldResetCenterCapture) {
            resetCenterCaptureUi()
        }
        decision.ocrCropPath?.let(::requestOcrIfNeeded)
        if (decision.shouldPromptAssistedCapture) {
            showTopToast(getString(R.string.center_capture_prompt))
        }
        binding.debugOverlay.render(frame.state)
    }

    private fun clearAnalyzerIfAttached() {
        if (!isAnalyzerAttached) {
            return
        }
        CameraActivityDependencies.cameraRuntimeController.clearAnalyzer(analysisUseCase)
        isAnalyzerAttached = false
    }

    private fun resetCenterCaptureUi() {
        centerCaptureArmed = false
        binding.debugOverlay.showAssistedTarget(null)
        updateCenterAssistButton()
    }

    private fun updateCenterAssistButton() {
        binding.centerAssistButton.text = getString(
            if (centerCaptureArmed) R.string.center_capture_confirm_button else R.string.center_capture_button,
        )
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

    private fun CropSource.toDecisionSource(): CameraCropSource = when (this) {
        CropSource.AUTO -> CameraCropSource.AUTO
        CropSource.ASSISTED -> CameraCropSource.ASSISTED
    }

    private enum class CropSource {
        AUTO,
        ASSISTED,
    }
}
