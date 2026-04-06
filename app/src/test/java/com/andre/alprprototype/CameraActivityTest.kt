package com.andre.alprprototype

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.AssistedCropController
import com.andre.alprprototype.alpr.AssistedCropResult
import com.andre.alprprototype.alpr.NormalizedRect
import com.andre.alprprototype.alpr.PlateCandidate
import com.andre.alprprototype.alpr.PipelineDebugState
import com.andre.alprprototype.alpr.PlateCandidateGenerator
import androidx.camera.core.ImageProxy
import com.andre.alprprototype.databinding.ActivityCameraBinding
import com.andre.alprprototype.session.EvidenceFlowState
import kotlin.jvm.functions.Function2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import kotlin.jvm.functions.Function1

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class CameraActivityTest {
    @After
    fun tearDown() {
        CameraActivityDependencies.reset()
    }

    @Test
    fun setup_smoke_test() {
        val activity = buildActivity().get()
        assertNotNull(activity)
        assertNotNull(activity.findViewById(android.R.id.content))
    }

    @Test
    fun startCamera_delegates_to_runtime_controller() {
        val runtimeController = FakeCameraRuntimeController()
        val activity = buildActivity(cameraRuntimeController = runtimeController).get()
        runtimeController.startCount = 0

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "startCamera")

        assertEquals(1, runtimeController.startCount)
    }

    @Test
    fun handleCameraPermissionResult_starts_camera_when_permission_is_granted() {
        val runtimeController = FakeCameraRuntimeController()
        val activity = buildActivity(cameraRuntimeController = runtimeController).get()
        runtimeController.startCount = 0

        activity.handleCameraPermissionResult(granted = true)

        assertEquals(1, runtimeController.startCount)
    }

    @Test
    fun handleCameraPermissionResult_shows_toast_and_finishes_when_permission_is_denied() {
        val activity = buildActivity().get()

        activity.handleCameraPermissionResult(granted = false)

        assertEquals(activity.getString(R.string.camera_permission_required), ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun attachAnalyzerIfNeeded_sets_attached_flag_when_allowed() {
        val activity = buildActivity().get()
        ReflectionHelpers.setField(activity, "analysisUseCase", ImageAnalysis.Builder().build())
        ReflectionHelpers.setField(activity, "isAnalyzerAttached", false)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "attachAnalyzerIfNeeded")

        assertTrue(ReflectionHelpers.getField(activity, "isAnalyzerAttached"))
    }

    @Test
    fun clearAnalyzerIfAttached_clears_flag_via_runtime_controller() {
        val runtimeController = FakeCameraRuntimeController()
        val activity = buildActivity(cameraRuntimeController = runtimeController).get()
        ReflectionHelpers.setField(activity, "analysisUseCase", ImageAnalysis.Builder().build())
        ReflectionHelpers.setField(activity, "isAnalyzerAttached", true)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "clearAnalyzerIfAttached")

        assertEquals(1, runtimeController.clearCount)
        assertFalse(ReflectionHelpers.getField(activity, "isAnalyzerAttached"))
    }

    @Test
    fun onCameraSessionStarted_updates_camera_fields() {
        val activity = buildActivity().get()
        val analysis = ImageAnalysis.Builder().build()
        val capture = ImageCapture.Builder().build()
        val result = CameraSessionStartResult(
            provider = null,
            analysisUseCase = analysis,
            imageCapture = capture,
        )

        activity.onCameraSessionStarted(result)

        assertEquals(analysis, ReflectionHelpers.getField<ImageAnalysis?>(activity, "analysisUseCase"))
        assertEquals(capture, ReflectionHelpers.getField<ImageCapture?>(activity, "imageCapture"))
    }

    @Test
    fun onDestroy_closes_runtime_dependencies() {
        val runtimeController = FakeCameraRuntimeController()
        val plateOcrRecognizer = FakePlateOcrRecognizer()
        val candidateGenerator = TrackingCandidateGenerator()
        val controller = buildActivity(
            cameraRuntimeController = runtimeController,
            plateOcrRecognizer = plateOcrRecognizer,
            candidateGenerator = candidateGenerator,
        )
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "analysisUseCase", ImageAnalysis.Builder().build())
        ReflectionHelpers.setField(activity, "isAnalyzerAttached", true)

        controller.destroy()

        assertEquals(1, runtimeController.clearCount)
        assertTrue(plateOcrRecognizer.closed)
        assertTrue(candidateGenerator.closed)
    }

    @Test
    fun attemptFinishSession_finishes_immediately_when_queue_is_empty() {
        val activity = buildActivity().get()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "attemptFinishSession")

        assertTrue(activity.isFinishing)
    }

    @Test
    fun attemptFinishSession_shows_pending_upload_prompt_when_queue_has_items() {
        val activity = buildActivity(
            queuedViolations = mutableListOf(
                ViolationEvent(
                    rawOcrText = "ABC123",
                    confidenceScore = 0.9f,
                    timestamp = "2026-04-05T00:00:00Z",
                    operatorId = "Device_01",
                    localVehiclePath = "vehicle.jpg",
                    localPlatePath = "plate.jpg",
                ),
            ),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "attemptFinishSession")

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun onResume_shows_pending_prompt_when_requested() {
        val controller = buildActivity(
            queuedViolations = mutableListOf(
                ViolationEvent(
                    rawOcrText = "ABC123",
                    confidenceScore = 0.9f,
                    timestamp = "2026-04-05T00:00:00Z",
                    operatorId = "Device_01",
                    localVehiclePath = "vehicle.jpg",
                    localPlatePath = "plate.jpg",
                ),
            ),
        )
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "promptPendingUploadSyncOnResume", true)

        controller.pause().resume()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "promptPendingUploadSyncOnResume"))
    }

    @Test
    fun onCreate_click_listeners_delegate_to_actions() {
        val activity = buildActivity(syncRegistryResult = Result.success(2)).get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")

        binding.guideHeader.performClick()
        assertEquals(View.VISIBLE, binding.guideText.visibility)

        binding.syncButton.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(activity.getString(R.string.registry_sync_success, 2), ShadowToast.getTextOfLatestToast())

        binding.uploadQueueButton.performClick()
        assertEquals(activity.getString(R.string.upload_queue_empty), ShadowToast.getTextOfLatestToast())

        binding.centerAssistButton.performClick()
        assertEquals("Preview not ready for assisted OCR", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun handleManualPlateEntry_returns_false_for_blank_text() {
        val activity = buildActivity().get()

        val accepted = ReflectionHelpers.callInstanceMethod<Boolean>(
            activity,
            "handleManualPlateEntry",
            ClassParameter.from(String::class.java, " - "),
            ClassParameter.from(String::class.java, "crop.jpg"),
        )

        assertFalse(accepted)
    }

    @Test
    fun handleManualPlateEntry_resets_center_capture_and_shows_valid_toast() {
        val activity = buildActivity(
            registeredPlates = listOf(
                RegistryPlate(
                    plateString = "ABC123",
                    permitType = "A",
                    expiryDate = "2099-12-31T23:59:59.000Z",
                    lotZone = "Z1",
                    listVersion = 1,
                ),
            ),
        ).get()
        ReflectionHelpers.setField(activity, "centerCaptureArmed", true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateCenterAssistButton")

        val accepted = ReflectionHelpers.callInstanceMethod<Boolean>(
            activity,
            "handleManualPlateEntry",
            ClassParameter.from(String::class.java, "abc 123"),
            ClassParameter.from(String::class.java, "crop.jpg"),
        )

        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        assertTrue(accepted)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "centerCaptureArmed"))
        assertEquals(activity.getString(R.string.center_capture_button), binding.centerAssistButton.text.toString())
        assertEquals(activity.getString(R.string.plate_valid_message, "ABC123"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun handleManualPlateEntry_prompts_violation_for_unknown_plate() {
        val activity = buildActivity().get()

        val accepted = ReflectionHelpers.callInstanceMethod<Boolean>(
            activity,
            "handleManualPlateEntry",
            ClassParameter.from(String::class.java, "bad123"),
            ClassParameter.from(String::class.java, "missing.jpg"),
        )

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(accepted)
        assertNotNull(dialog.findViewById<View>(R.id.violationCaptureButton))
    }

    @Test
    fun handleManualPlateEntry_ignores_recently_confirmed_plate_without_toast() {
        val activity = buildActivity(
            registeredPlates = listOf(
                RegistryPlate(
                    plateString = "ABC123",
                    permitType = "A",
                    expiryDate = "2099-12-31T23:59:59.000Z",
                    lotZone = "Z1",
                    listVersion = 1,
                ),
            ),
        ).get()

        ReflectionHelpers.callInstanceMethod<Boolean>(
            activity,
            "handleManualPlateEntry",
            ClassParameter.from(String::class.java, "ABC123"),
            ClassParameter.from(String::class.java, "crop-a.jpg"),
        )
        ShadowToast.reset()

        val accepted = ReflectionHelpers.callInstanceMethod<Boolean>(
            activity,
            "handleManualPlateEntry",
            ClassParameter.from(String::class.java, "ABC123"),
            ClassParameter.from(String::class.java, "crop-b.jpg"),
        )

        assertTrue(accepted)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun handleViolationPlateEdit_returns_null_for_blank_text() {
        val activity = buildActivity().get()

        val validation = ReflectionHelpers.callInstanceMethod<RegistryManager.PlateValidationResult?>(
            activity,
            "handleViolationPlateEdit",
            ClassParameter.from(String::class.java, ""),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.8f),
            ClassParameter.from(String::class.java, "crop.jpg"),
            ClassParameter.from(Function2::class.java, null),
        )

        assertEquals(null, validation)
    }

    @Test
    fun handleViolationPlateEdit_finishes_evidence_flow_for_valid_plate_without_callback() {
        val activity = buildActivity(
            registeredPlates = listOf(
                RegistryPlate(
                    plateString = "XYZ999",
                    permitType = "A",
                    expiryDate = "2099-12-31T23:59:59.000Z",
                    lotZone = "Z1",
                    listVersion = 1,
                ),
            ),
        ).get()
        val evidenceFlowState = ReflectionHelpers.getField<EvidenceFlowState>(activity, "evidenceFlowState")
        evidenceFlowState.beginViolationReview()

        val validation = ReflectionHelpers.callInstanceMethod<RegistryManager.PlateValidationResult>(
            activity,
            "handleViolationPlateEdit",
            ClassParameter.from(String::class.java, "xyz999"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.8f),
            ClassParameter.from(String::class.java, "crop.jpg"),
            ClassParameter.from(Function2::class.java, null),
        )

        assertEquals(RegistryManager.PlateValidationResult.VALID, validation)
        assertFalse(evidenceFlowState.isActive)
        assertEquals(activity.getString(R.string.plate_corrected_valid_message), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun handleViolationPlateEdit_invokes_callback_for_non_valid_plate_without_finishing_flow() {
        val activity = buildActivity().get()
        val evidenceFlowState = ReflectionHelpers.getField<EvidenceFlowState>(activity, "evidenceFlowState")
        evidenceFlowState.beginViolationReview()
        var callbackPlate: String? = null
        var callbackValidation: RegistryManager.PlateValidationResult? = null

        val validation = ReflectionHelpers.callInstanceMethod<RegistryManager.PlateValidationResult>(
            activity,
            "handleViolationPlateEdit",
            ClassParameter.from(String::class.java, "bad123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.8f),
            ClassParameter.from(String::class.java, "crop.jpg"),
            ClassParameter.from(Function2::class.java, { plate: String, result: RegistryManager.PlateValidationResult ->
                callbackPlate = plate
                callbackValidation = result
                Unit
            }),
        )

        assertEquals(RegistryManager.PlateValidationResult.NOT_FOUND, validation)
        assertEquals("BAD123", callbackPlate)
        assertEquals(RegistryManager.PlateValidationResult.NOT_FOUND, callbackValidation)
        assertTrue(evidenceFlowState.isActive)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun uploadQueue_shows_empty_toast_when_queue_has_no_items() {
        val activity = buildActivity().get()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "uploadQueue")

        assertEquals(activity.getString(R.string.upload_queue_empty), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun promptForManualPlateEntry_blank_input_sets_error_and_keeps_dialog_open() {
        val activity = buildActivity().get()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "promptForManualPlateEntry",
            ClassParameter.from(String::class.java, "crop.jpg"),
            ClassParameter.from(String::class.java, null),
        )

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = requireView(dialog.window!!.decorView, EditText::class.java)

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()

        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.plate_validation_error), input.error.toString())
    }

    @Test
    fun promptForManualPlateEntry_valid_input_dismisses_dialog() {
        val activity = buildActivity(
            registeredPlates = listOf(
                RegistryPlate(
                    plateString = "ABC123",
                    permitType = "A",
                    expiryDate = "2099-12-31T23:59:59.000Z",
                    lotZone = "Z1",
                    listVersion = 1,
                ),
            ),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "promptForManualPlateEntry",
            ClassParameter.from(String::class.java, "crop.jpg"),
            ClassParameter.from(String::class.java, "abc123"),
        )

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()

        assertFalse(dialog.isShowing)
        assertEquals(activity.getString(R.string.plate_valid_message, "ABC123"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun promptForViolationCollection_dismiss_finishes_evidence_flow() {
        val activity = buildActivity().get()
        val evidenceFlowState = ReflectionHelpers.getField<EvidenceFlowState>(activity, "evidenceFlowState")

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "promptForViolationCollection",
            ClassParameter.from(String::class.java, "BAD123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.6f),
            ClassParameter.from(String::class.java, "missing.jpg"),
        )

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(evidenceFlowState.isActive)

        dialog.findViewById<View>(R.id.violationDismissButton).performClick()

        assertFalse(dialog.isShowing)
        assertFalse(evidenceFlowState.isActive)
    }

    @Test
    fun promptForViolationCollection_capture_opens_capture_prompt() {
        val activity = buildActivity().get()
        val evidenceFlowState = ReflectionHelpers.getField<EvidenceFlowState>(activity, "evidenceFlowState")

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "promptForViolationCollection",
            ClassParameter.from(String::class.java, "BAD123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.6f),
            ClassParameter.from(String::class.java, "missing.jpg"),
        )

        val reviewDialog = ShadowDialog.getLatestDialog()
        reviewDialog.findViewById<View>(R.id.violationCaptureButton).performClick()

        val captureDialog = ShadowDialog.getLatestDialog()
        assertFalse(reviewDialog.isShowing)
        assertNotNull(captureDialog.findViewById<View>(R.id.evidenceCaptureButton))
        assertTrue(evidenceFlowState.isActive)
    }

    @Test
    fun promptForViolationCollection_edit_valid_plate_finishes_flow_and_dismisses_review() {
        val activity = buildActivity(
            registeredPlates = listOf(
                RegistryPlate(
                    plateString = "ABC123",
                    permitType = "A",
                    expiryDate = "2099-12-31T23:59:59.000Z",
                    lotZone = "Z1",
                    listVersion = 1,
                ),
            ),
        ).get()
        val evidenceFlowState = ReflectionHelpers.getField<EvidenceFlowState>(activity, "evidenceFlowState")

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "promptForViolationCollection",
            ClassParameter.from(String::class.java, "BAD123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.6f),
            ClassParameter.from(String::class.java, "missing.jpg"),
        )

        val reviewDialog = ShadowDialog.getLatestDialog()
        reviewDialog.findViewById<View>(R.id.violationEditButton).performClick()

        val editDialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = requireView(editDialog.window!!.decorView, EditText::class.java)
        input.setText("abc123")

        editDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()

        assertFalse(editDialog.isShowing)
        assertFalse(reviewDialog.isShowing)
        assertFalse(evidenceFlowState.isActive)
        assertEquals(activity.getString(R.string.plate_corrected_valid_message), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun promptForViolationPlateEdit_blank_input_sets_error_and_keeps_dialog_open() {
        val activity = buildActivity().get()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "promptForViolationPlateEdit",
            ClassParameter.from(String::class.java, "BAD123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.6f),
            ClassParameter.from(String::class.java, "crop.jpg"),
            ClassParameter.from(Function2::class.java, null),
        )

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = requireView(dialog.window!!.decorView, EditText::class.java)
        input.setText(" ")

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()

        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.plate_validation_error), input.error.toString())
    }

    @Test
    fun promptToCaptureVehiclePhoto_cancel_finishes_evidence_flow() {
        val activity = buildActivity().get()
        val evidenceFlowState = ReflectionHelpers.getField<EvidenceFlowState>(activity, "evidenceFlowState")

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "promptToCaptureVehiclePhoto",
            ClassParameter.from(String::class.java, "BAD123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.6f),
            ClassParameter.from(String::class.java, "plate.jpg"),
        )

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(evidenceFlowState.isActive)

        dialog.findViewById<View>(R.id.evidenceCancelButton).performClick()

        assertFalse(dialog.isShowing)
        assertFalse(evidenceFlowState.isActive)
    }

    @Test
    fun promptToCaptureVehiclePhoto_capture_dismisses_prompt_and_opens_confirmation() {
        val activity = buildActivity(
            vehiclePhotoCaptureExecutor = object : VehiclePhotoCaptureExecutor {
                override fun capture(
                    imageCapture: ImageCapture,
                    photoFile: File,
                    executor: Executor,
                    onSaved: () -> Unit,
                    onError: (ImageCaptureException) -> Unit,
                ) {
                    onSaved()
                }
            },
        ).get()
        ReflectionHelpers.setField(activity, "imageCapture", ImageCapture.Builder().build())

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "promptToCaptureVehiclePhoto",
            ClassParameter.from(String::class.java, "BAD123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.6f),
            ClassParameter.from(String::class.java, "plate.jpg"),
        )

        val captureDialog = ShadowDialog.getLatestDialog()
        captureDialog.findViewById<View>(R.id.evidenceCaptureButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val confirmDialog = ShadowDialog.getLatestDialog()
        assertFalse(captureDialog.isShowing)
        assertNotNull(confirmDialog.findViewById<View>(R.id.acceptVehicleButton))
    }

    @Test
    fun takeVehiclePhoto_shows_camera_not_ready_when_capture_is_missing() {
        val activity = buildActivity().get()
        val evidenceFlowState = ReflectionHelpers.getField<EvidenceFlowState>(activity, "evidenceFlowState")
        evidenceFlowState.beginCapturePrompt()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "takeVehiclePhoto",
            ClassParameter.from(Function1::class.java, { _: String -> Unit }),
        )

        assertEquals(activity.getString(R.string.camera_not_ready_message), ShadowToast.getTextOfLatestToast())
        assertFalse(evidenceFlowState.isActive)
    }

    @Test
    fun takeVehiclePhoto_shows_failure_toast_when_capture_executor_errors() {
        val activity = buildActivity(
            vehiclePhotoCaptureExecutor = object : VehiclePhotoCaptureExecutor {
                override fun capture(
                    imageCapture: ImageCapture,
                    photoFile: File,
                    executor: Executor,
                    onSaved: () -> Unit,
                    onError: (ImageCaptureException) -> Unit,
                ) {
                    onError(
                        ImageCaptureException(
                            ImageCapture.ERROR_CAPTURE_FAILED,
                            "boom",
                            null,
                        ),
                    )
                }
            },
        ).get()
        val evidenceFlowState = ReflectionHelpers.getField<EvidenceFlowState>(activity, "evidenceFlowState")
        evidenceFlowState.beginCapturePrompt()
        ReflectionHelpers.setField(activity, "imageCapture", ImageCapture.Builder().build())

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "takeVehiclePhoto",
            ClassParameter.from(Function1::class.java, { _: String -> Unit }),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Capture failed: boom", ShadowToast.getTextOfLatestToast())
        assertFalse(evidenceFlowState.isActive)
    }

    @Test
    fun confirmVehiclePhoto_accept_queues_violation_and_updates_upload_text() {
        val activity = buildActivity().get()
        val evidenceFlowState = ReflectionHelpers.getField<EvidenceFlowState>(activity, "evidenceFlowState")
        val violationManager = ReflectionHelpers.getField<ViolationQueue>(activity, "violationManager")
        val plateFile = createImageFile(activity.cacheDir, "plate")
        val vehicleFile = createImageFile(activity.cacheDir, "vehicle")

        evidenceFlowState.beginPhotoConfirmation()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "confirmVehiclePhoto",
            ClassParameter.from(String::class.java, "BAD123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.6f),
            ClassParameter.from(String::class.java, plateFile.absolutePath),
            ClassParameter.from(String::class.java, vehicleFile.absolutePath),
        )

        val dialog = ShadowDialog.getLatestDialog()
        dialog.findViewById<View>(R.id.acceptVehicleButton).performClick()

        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        assertFalse(dialog.isShowing)
        assertFalse(evidenceFlowState.isActive)
        assertEquals(1, violationManager.getQueueSize())
        assertEquals(activity.getString(R.string.upload_queue_button_format, 1), binding.uploadQueueButton.text.toString())
        assertEquals(activity.getString(R.string.violation_queued_message, "BAD123"), ShadowToast.getTextOfLatestToast())
        assertFalse(plateFile.exists())
        assertFalse(vehicleFile.exists())
    }

    @Test
    fun queueViolation_shows_failure_toast_when_add_fails() {
        val activity = buildActivity(
            addViolationResult = Result.failure(IllegalStateException("queue failed")),
        ).get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "queueViolation",
            ClassParameter.from(String::class.java, "BAD123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.6f),
            ClassParameter.from(String::class.java, "plate.jpg"),
            ClassParameter.from(String::class.java, "vehicle.jpg"),
        )

        assertEquals(activity.getString(R.string.violation_queue_failed_message), ShadowToast.getTextOfLatestToast())
        assertEquals(activity.getString(R.string.upload_queue_button_format, 0), binding.uploadQueueButton.text.toString())
    }

    @Test
    fun confirmVehiclePhoto_retake_deletes_photo_and_reopens_capture_prompt() {
        val activity = buildActivity().get()
        val vehicleFile = createImageFile(activity.cacheDir, "vehicle")

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "confirmVehiclePhoto",
            ClassParameter.from(String::class.java, "BAD123"),
            ClassParameter.from(Float::class.javaPrimitiveType!!, 0.6f),
            ClassParameter.from(String::class.java, "plate.jpg"),
            ClassParameter.from(String::class.java, vehicleFile.absolutePath),
        )

        val confirmDialog = ShadowDialog.getLatestDialog()
        confirmDialog.findViewById<View>(R.id.retakeVehicleButton).performClick()

        val captureDialog = ShadowDialog.getLatestDialog()
        assertFalse(confirmDialog.isShowing)
        assertFalse(vehicleFile.exists())
        assertNotNull(captureDialog.findViewById<View>(R.id.evidenceCaptureButton))
    }

    @Test
    fun maybePromptToSyncPendingUploadsOnStart_shows_prompt_only_once() {
        val activity = buildActivity(
            queuedViolations = mutableListOf(
                ViolationEvent(
                    rawOcrText = "ABC123",
                    confidenceScore = 0.9f,
                    timestamp = "2026-04-05T00:00:00Z",
                    operatorId = "Device_01",
                    localVehiclePath = "vehicle.jpg",
                    localPlatePath = "plate.jpg",
                ),
            ),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "maybePromptToSyncPendingUploadsOnStart")
        val firstDialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        firstDialog.dismiss()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "maybePromptToSyncPendingUploadsOnStart")

        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "hasPromptedPendingUploadSyncOnStart").not())
        assertNull(ShadowDialog.getLatestDialog()?.takeIf { it !== firstDialog && it.isShowing })
    }

    @Test
    fun pendingUploadPrompt_negative_at_session_end_finishes_activity() {
        val activity = buildActivity(
            queuedViolations = mutableListOf(
                ViolationEvent(
                    rawOcrText = "ABC123",
                    confidenceScore = 0.9f,
                    timestamp = "2026-04-05T00:00:00Z",
                    operatorId = "Device_01",
                    localVehiclePath = "vehicle.jpg",
                    localPlatePath = "plate.jpg",
                ),
            ),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "showPendingUploadSyncPrompt",
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
        )

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun pendingUploadPrompt_neutral_sets_resume_prompt_flag() {
        val activity = buildActivity(
            queuedViolations = mutableListOf(
                ViolationEvent(
                    rawOcrText = "ABC123",
                    confidenceScore = 0.9f,
                    timestamp = "2026-04-05T00:00:00Z",
                    operatorId = "Device_01",
                    localVehiclePath = "vehicle.jpg",
                    localPlatePath = "plate.jpg",
                ),
            ),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "showPendingUploadSyncPrompt",
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
        )

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(ReflectionHelpers.getField(activity, "promptPendingUploadSyncOnResume"))
    }

    @Test
    fun pendingUploadPrompt_positive_uploads_and_finishes_session() {
        val queuedViolations = mutableListOf(
            ViolationEvent(
                rawOcrText = "ABC123",
                confidenceScore = 0.9f,
                timestamp = "2026-04-05T00:00:00Z",
                operatorId = "Device_01",
                localVehiclePath = "vehicle.jpg",
                localPlatePath = "plate.jpg",
            ),
        )
        val activity = buildActivity(
            queuedViolations = queuedViolations,
            uploadQueueResult = Result.success(1),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "showPendingUploadSyncPrompt",
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
        )

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(activity.getString(R.string.pending_upload_synced_message, 1), ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun stylePendingUploadDialog_colors_buttons_when_present() {
        val activity = buildActivity(
            queuedViolations = mutableListOf(
                ViolationEvent(
                    rawOcrText = "ABC123",
                    confidenceScore = 0.9f,
                    timestamp = "2026-04-05T00:00:00Z",
                    operatorId = "Device_01",
                    localVehiclePath = "vehicle.jpg",
                    localPlatePath = "plate.jpg",
                ),
            ),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "showPendingUploadSyncPrompt",
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
        )

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals(activity.getColor(R.color.dialog_button_sync), dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).currentTextColor)
        assertEquals(activity.getColor(R.color.dialog_button_wifi), dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).currentTextColor)
        assertEquals(activity.getColor(R.color.dialog_button_dismiss), dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).currentTextColor)
    }

    @Test
    fun stylePendingUploadDialog_handles_missing_buttons() {
        val activity = buildActivity().get()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity).create()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "stylePendingUploadDialog",
            ClassParameter.from(androidx.appcompat.app.AlertDialog::class.java, dialog),
        )
    }

    @Test
    fun showPendingUploadSyncPrompt_returns_without_dialog_when_queue_is_empty_mid_session() {
        val activity = buildActivity().get()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "showPendingUploadSyncPrompt",
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
        )

        assertNull(ShadowDialog.getLatestDialog())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun showPendingUploadSyncPrompt_finishes_without_dialog_when_queue_is_empty_at_session_end() {
        val activity = buildActivity().get()

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "showPendingUploadSyncPrompt",
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
        )

        assertNull(ShadowDialog.getLatestDialog())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun uploadQueue_shows_success_toast_for_non_empty_queue() {
        val queued = mutableListOf(
            ViolationEvent(
                rawOcrText = "ABC123",
                confidenceScore = 0.9f,
                timestamp = "2026-04-05T00:00:00Z",
                operatorId = "Device_01",
                localVehiclePath = "vehicle.jpg",
                localPlatePath = "plate.jpg",
            ),
        )
        val activity = buildActivity(
            queuedViolations = queued,
            uploadQueueResult = Result.success(1),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "uploadQueue")
        shadowOf(Looper.getMainLooper()).idle()

        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        assertEquals(activity.getString(R.string.upload_queue_success, "1"), ShadowToast.getTextOfLatestToast())
        assertEquals(activity.getString(R.string.upload_queue_button_format, 0), binding.uploadQueueButton.text.toString())
        assertTrue(binding.uploadQueueButton.isEnabled)
    }

    @Test
    fun uploadQueue_shows_failure_toast_for_upload_error() {
        val activity = buildActivity(
            queuedViolations = mutableListOf(
                ViolationEvent(
                    rawOcrText = "ABC123",
                    confidenceScore = 0.9f,
                    timestamp = "2026-04-05T00:00:00Z",
                    operatorId = "Device_01",
                    localVehiclePath = "vehicle.jpg",
                    localPlatePath = "plate.jpg",
                ),
            ),
            uploadQueueResult = Result.failure(IllegalStateException("network down")),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "uploadQueue")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(activity.getString(R.string.upload_queue_failed, "network down"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun syncRegistry_shows_success_toast_and_restores_button_state() {
        val activity = buildActivity(syncRegistryResult = Result.success(3)).get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "syncRegistry")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(activity.getString(R.string.registry_sync_success, 3), ShadowToast.getTextOfLatestToast())
        assertTrue(binding.syncButton.isEnabled)
        assertEquals(activity.getString(R.string.sync_registry_button), binding.syncButton.text.toString())
    }

    @Test
    fun syncRegistry_shows_failure_toast_and_restores_button_state() {
        val activity = buildActivity(syncRegistryResult = Result.failure(IllegalStateException("sync failed"))).get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "syncRegistry")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(activity.getString(R.string.registry_sync_failed), ShadowToast.getTextOfLatestToast())
        assertTrue(binding.syncButton.isEnabled)
        assertEquals(activity.getString(R.string.sync_registry_button), binding.syncButton.text.toString())
    }

    @Test
    fun requestOcrIfNeeded_shows_valid_toast_for_registered_plate() {
        val recognizer = FakePlateOcrRecognizer()
        val activity = buildActivity(
            registeredPlates = listOf(
                RegistryPlate(
                    plateString = "ABC123",
                    permitType = "A",
                    expiryDate = "2099-12-31T23:59:59.000Z",
                    lotZone = "Z1",
                    listVersion = 1,
                ),
            ),
            plateOcrRecognizer = recognizer,
        ).get()

        recognizer.nextResult = OcrDisplayResult(
            text = "abc123",
            sourcePath = "crop.jpg",
            confidence = 0.91f,
            agreementCount = 2,
            variantCount = 2,
            scoreMargin = 0.3f,
        )

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "requestOcrIfNeeded",
            ClassParameter.from(String::class.java, "crop.jpg"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(activity.getString(R.string.plate_valid_message, "ABC123"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun requestOcrIfNeeded_opens_violation_dialog_for_unregistered_plate() {
        val recognizer = FakePlateOcrRecognizer()
        val activity = buildActivity(plateOcrRecognizer = recognizer).get()
        recognizer.nextResult = OcrDisplayResult(
            text = "BAD123",
            sourcePath = "crop.jpg",
            confidence = 0.75f,
            agreementCount = 2,
            variantCount = 2,
            scoreMargin = 0.2f,
        )

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "requestOcrIfNeeded",
            ClassParameter.from(String::class.java, "crop.jpg"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog.findViewById<View>(R.id.violationCaptureButton))
    }

    @Test
    fun requestOcrIfNeeded_ignores_duplicate_crop_requests() {
        val recognizer = FakePlateOcrRecognizer()
        val activity = buildActivity(plateOcrRecognizer = recognizer).get()
        ReflectionHelpers.setField(activity, "lastOcrRequestedPath", "crop.jpg")

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "requestOcrIfNeeded",
            ClassParameter.from(String::class.java, "crop.jpg"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(recognizer.lastCropPath)
    }

    @Test
    fun requestOcrIfNeeded_prompts_manual_entry_for_ambiguous_assisted_result() {
        val recognizer = FakePlateOcrRecognizer()
        val activity = buildActivity(plateOcrRecognizer = recognizer).get()
        ReflectionHelpers.setField(activity, "lastCropSource", enumValue<Any>("com.andre.alprprototype.CameraActivity\$CropSource", "ASSISTED"))
        ReflectionHelpers.setField(activity, "centerCaptureArmed", true)
        recognizer.nextResult = OcrDisplayResult(
            text = "ABC123",
            sourcePath = "crop.jpg",
            confidence = 0.5f,
            agreementCount = 1,
            variantCount = 2,
            scoreMargin = 0.01f,
        )

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "requestOcrIfNeeded",
            ClassParameter.from(String::class.java, "crop.jpg"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertNotNull(requireView(dialog.window!!.decorView, EditText::class.java))
        assertFalse(ReflectionHelpers.getField(activity, "centerCaptureArmed"))
    }

    @Test
    fun requestOcrIfNeeded_marks_blank_results_unavailable() {
        val recognizer = FakePlateOcrRecognizer()
        val activity = buildActivity(plateOcrRecognizer = recognizer).get()
        recognizer.nextResult = OcrDisplayResult(
            text = "",
            sourcePath = "crop.jpg",
            confidence = 0.1f,
            agreementCount = 0,
            variantCount = 1,
            scoreMargin = null,
        )

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "requestOcrIfNeeded",
            ClassParameter.from(String::class.java, "crop.jpg"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "UNAVAILABLE",
            ReflectionHelpers.getField<Any>(activity, "latestOcrState").toString(),
        )
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun loadViolationCropImage_sets_bitmap_when_file_exists() {
        val activity = buildActivity().get()
        val imageView = android.widget.ImageView(activity)
        val file = createImageFile(activity.cacheDir, "violation-crop")

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "loadViolationCropImage",
            ClassParameter.from(android.widget.ImageView::class.java, imageView),
            ClassParameter.from(String::class.java, file.absolutePath),
        )

        assertNotNull((imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)
    }

    @Test
    fun loadViolationCropImage_sets_placeholder_when_file_is_missing() {
        val activity = buildActivity().get()
        val imageView = android.widget.ImageView(activity)

        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "loadViolationCropImage",
            ClassParameter.from(android.widget.ImageView::class.java, imageView),
            ClassParameter.from(String::class.java, "does-not-exist.jpg"),
        )

        assertNotNull(imageView.drawable)
    }

    @Test
    fun handleAnalyzedFrame_ignores_updates_when_activity_is_shutting_down() {
        val activity = buildActivity().get()
        ReflectionHelpers.setField(activity, "framesSinceSavedCrop", 7)
        ReflectionHelpers.setField(activity, "isShuttingDown", java.util.concurrent.atomic.AtomicBoolean(true))

        activity.handleAnalyzedFrame(
            AnalyzedFrame(
                state = emptyPipelineState(),
                savedCropPath = "crop.jpg",
            ),
        )

        assertEquals(7, ReflectionHelpers.getField<Int>(activity, "framesSinceSavedCrop"))
        assertNull(ReflectionHelpers.getField<AnalyzedFrame?>(activity, "latestFrame"))
    }

    @Test
    fun handleAnalyzedFrame_updates_saved_crop_and_requests_ocr() {
        val recognizer = FakePlateOcrRecognizer()
        val activity = buildActivity(plateOcrRecognizer = recognizer).get()
        ReflectionHelpers.setField(activity, "centerCaptureArmed", true)

        activity.handleAnalyzedFrame(
            AnalyzedFrame(
                state = emptyPipelineState(),
                savedCropPath = "auto-crop.jpg",
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        assertEquals("auto-crop.jpg", ReflectionHelpers.getField<String>(activity, "lastSavedCropPath"))
        assertEquals("auto-crop.jpg", recognizer.lastCropPath)
        assertFalse(ReflectionHelpers.getField(activity, "centerCaptureArmed"))
        assertEquals(activity.getString(R.string.center_capture_button), binding.centerAssistButton.text.toString())
    }

    @Test
    fun handleAnalyzedFrame_shows_assisted_prompt_after_threshold_without_saved_crop() {
        val activity = buildActivity().get()
        ReflectionHelpers.setField(activity, "framesSinceSavedCrop", 23)
        ReflectionHelpers.setField(activity, "assistedPromptShown", false)

        activity.handleAnalyzedFrame(
            AnalyzedFrame(
                state = emptyPipelineState(),
                savedCropPath = null,
            ),
        )

        assertEquals(activity.getString(R.string.center_capture_prompt), ShadowToast.getTextOfLatestToast())
        assertTrue(ReflectionHelpers.getField(activity, "assistedPromptShown"))
        assertEquals(24, ReflectionHelpers.getField<Int>(activity, "framesSinceSavedCrop"))
    }

    @Test
    fun armCenterAssistCapture_shows_preview_not_ready_toast_when_overlay_unavailable() {
        val activity = buildActivity(
            assistedCropController = FakeAssistedCropController(previewRect = null),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "armCenterAssistCapture")

        assertEquals("Preview not ready for assisted OCR", ShadowToast.getTextOfLatestToast())
        assertFalse(ReflectionHelpers.getField(activity, "centerCaptureArmed"))
    }

    @Test
    fun armCenterAssistCapture_arms_when_overlay_available() {
        val activity = buildActivity(
            assistedCropController = FakeAssistedCropController(
                previewRect = NormalizedRect(0.1f, 0.2f, 0.8f, 0.5f),
            ),
        ).get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        binding.debugOverlay.layout(0, 0, 400, 200)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "armCenterAssistCapture")

        assertTrue(ReflectionHelpers.getField(activity, "centerCaptureArmed"))
        assertEquals(activity.getString(R.string.center_capture_confirm_button), binding.centerAssistButton.text.toString())
    }

    @Test
    fun captureCenterAssistCrop_shows_preview_not_ready_when_bitmap_missing() {
        val activity = buildActivity(
            previewBitmapProvider = { null },
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "captureCenterAssistCrop")

        assertEquals("Preview not ready for assisted OCR", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun captureCenterAssistCrop_shows_failure_when_crop_save_fails() {
        val activity = buildActivity(
            previewBitmapProvider = { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) },
            assistedCropController = FakeAssistedCropController(
                previewRect = NormalizedRect(0.1f, 0.2f, 0.8f, 0.5f),
                capturedCrop = null,
            ),
        ).get()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "captureCenterAssistCrop")

        assertEquals("Unable to create assisted crop", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun captureCenterAssistCrop_updates_state_and_requests_ocr_when_crop_succeeds() {
        val recognizer = FakePlateOcrRecognizer()
        val activity = buildActivity(
            plateOcrRecognizer = recognizer,
            previewBitmapProvider = { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) },
            assistedCropController = FakeAssistedCropController(
                previewRect = NormalizedRect(0.1f, 0.2f, 0.8f, 0.5f),
                capturedCrop = AssistedCropResult(
                    path = "assisted.jpg",
                    normalizedRect = NormalizedRect(0.2f, 0.2f, 0.7f, 0.4f),
                ),
            ),
        ).get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        ReflectionHelpers.setField(activity, "centerCaptureArmed", true)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "captureCenterAssistCrop")
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(ReflectionHelpers.getField(activity, "centerCaptureArmed"))
        assertEquals("assisted.jpg", ReflectionHelpers.getField<String>(activity, "lastSavedCropPath"))
        assertEquals(activity.getString(R.string.center_capture_button), binding.centerAssistButton.text.toString())
        assertEquals("assisted.jpg", recognizer.lastCropPath)
    }

    @Test
    fun handleCenterAssistButtonClick_arms_when_not_already_armed() {
        val activity = buildActivity(
            assistedCropController = FakeAssistedCropController(
                previewRect = NormalizedRect(0.1f, 0.2f, 0.8f, 0.5f),
            ),
        ).get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        binding.debugOverlay.layout(0, 0, 400, 200)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "handleCenterAssistButtonClick")

        assertTrue(ReflectionHelpers.getField(activity, "centerCaptureArmed"))
    }

    @Test
    fun handleCenterAssistButtonClick_captures_when_already_armed() {
        val recognizer = FakePlateOcrRecognizer()
        val activity = buildActivity(
            plateOcrRecognizer = recognizer,
            previewBitmapProvider = { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) },
            assistedCropController = FakeAssistedCropController(
                previewRect = NormalizedRect(0.1f, 0.2f, 0.8f, 0.5f),
                capturedCrop = AssistedCropResult(
                    path = "click-assisted.jpg",
                    normalizedRect = NormalizedRect(0.2f, 0.2f, 0.7f, 0.4f),
                ),
            ),
        ).get()
        ReflectionHelpers.setField(activity, "centerCaptureArmed", true)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "handleCenterAssistButtonClick")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("click-assisted.jpg", recognizer.lastCropPath)
        assertFalse(ReflectionHelpers.getField(activity, "centerCaptureArmed"))
    }

    private fun buildActivity(
        registeredPlates: List<RegistryPlate> = emptyList(),
        queuedViolations: MutableList<ViolationEvent> = mutableListOf(),
        addViolationResult: Result<ViolationEvent>? = null,
        syncRegistryResult: Result<Int> = Result.success(0),
        uploadQueueResult: Result<Int> = Result.success(0),
        plateOcrRecognizer: PlateOcrRecognizer = FakePlateOcrRecognizer(),
        vehiclePhotoCaptureExecutor: VehiclePhotoCaptureExecutor = DefaultVehiclePhotoCaptureExecutor,
        previewBitmapProvider: (androidx.camera.view.PreviewView) -> Bitmap? = { null },
        assistedCropController: AssistedCropController = FakeAssistedCropController(),
        cameraRuntimeController: CameraRuntimeController = FakeCameraRuntimeController(),
        candidateGenerator: TrackingCandidateGenerator = TrackingCandidateGenerator(),
    ): ActivityController<CameraActivity> {
        CameraActivityDependencies.pipelineFactory = {
            AlprPipeline(
                candidateGenerator = candidateGenerator,
            )
        }
        CameraActivityDependencies.assistedPlateCropSaverFactory = { assistedCropController }
        CameraActivityDependencies.plateOcrRecognizerFactory = { plateOcrRecognizer }
        CameraActivityDependencies.registryManagerFactory = {
            object : PlateRegistry {
                private val registered = registeredPlates.associateBy { it.plateString.uppercase() }

                override fun isPlateValid(plateText: String): RegistryManager.PlateValidationResult {
                    val plate = registered[plateText.uppercase()]
                    return when {
                        plate == null -> RegistryManager.PlateValidationResult.NOT_FOUND
                        plate.expiryDate.isExpired() -> RegistryManager.PlateValidationResult.EXPIRED
                        else -> RegistryManager.PlateValidationResult.VALID
                    }
                }

                override suspend fun syncRegistry(): Result<Int> = syncRegistryResult
            }
        }
        CameraActivityDependencies.violationManagerFactory = {
            object : ViolationQueue {
                override fun addViolation(violation: ViolationEvent): Result<ViolationEvent> {
                    addViolationResult?.let { return it }
                    queuedViolations += violation
                    violation.localPlatePath?.let { File(it).delete() }
                    violation.localVehiclePath?.let { File(it).delete() }
                    return Result.success(violation)
                }

                override fun getQueueSize(): Int = queuedViolations.size

                override suspend fun uploadQueue(): Result<Int> {
                    val result = uploadQueueResult
                    if (result.isSuccess) {
                        queuedViolations.clear()
                    }
                    return result
                }
            }
        }
        CameraActivityDependencies.vehiclePhotoCaptureExecutor = vehiclePhotoCaptureExecutor
        CameraActivityDependencies.previewBitmapProvider = previewBitmapProvider
        CameraActivityDependencies.cameraRuntimeController = cameraRuntimeController
        return Robolectric.buildActivity(CameraActivity::class.java).setup()
    }

    private fun createImageFile(parentDir: File, prefix: String): File {
        val file = File.createTempFile(prefix, ".jpg", parentDir)
        FileOutputStream(file).use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
        return file
    }

    private fun emptyPipelineState(): PipelineDebugState {
        return PipelineDebugState(
            frameNumber = 1L,
            inputWidth = 640,
            inputHeight = 480,
            candidates = listOf(
                PlateCandidate(
                    boundingBox = RectF(0f, 0f, 1f, 1f),
                    confidence = 0.9f,
                    source = "test",
                ),
            ),
            detections = emptyList(),
            activeTrack = null,
            quality = null,
        )
    }

    private fun <T : View> requireView(root: View, type: Class<T>): T {
        if (type.isInstance(root)) {
            @Suppress("UNCHECKED_CAST")
            return root as T
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                findView(child, type)?.let { return it }
            }
        }
        throw AssertionError("Expected view of type ${type.simpleName}")
    }

    private fun <T> enumValue(className: String, constantName: String): T {
        @Suppress("UNCHECKED_CAST")
        return java.lang.Enum.valueOf(Class.forName(className) as Class<out Enum<*>>, constantName) as T
    }

    private fun <T : View> findView(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) {
            @Suppress("UNCHECKED_CAST")
            return root as T
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val result = findView(root.getChildAt(index), type)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private class FakePlateOcrRecognizer : PlateOcrRecognizer {
        var nextResult: OcrDisplayResult? = null
        var lastCropPath: String? = null
        var closed = false

        override fun recognize(cropPath: String, onResult: (OcrDisplayResult?) -> Unit) {
            lastCropPath = cropPath
            onResult(nextResult)
        }

        override fun close() {
            closed = true
        }
    }

    private fun String?.isExpired(): Boolean {
        val rawDate = this ?: return false
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.parse(rawDate)?.before(Date()) == true
    }

    private class FakeAssistedCropController(
        private val previewRect: NormalizedRect? = null,
        private val capturedCrop: AssistedCropResult? = null,
    ) : AssistedCropController {
        override fun previewCenterRect(imageWidth: Int, imageHeight: Int): NormalizedRect? = previewRect

        override fun saveFromCenter(previewBitmap: Bitmap): AssistedCropResult? = capturedCrop
    }

    private class FakeCameraRuntimeController : CameraRuntimeController {
        var startCount = 0
        var clearCount = 0

        override fun startCamera(
            activity: CameraActivity,
            binding: ActivityCameraBinding,
            mainExecutor: Executor,
            cameraExecutor: java.util.concurrent.ExecutorService,
            pipeline: AlprPipeline,
            cropSaver: com.andre.alprprototype.alpr.BestPlateCropSaver,
            canUseCamera: () -> Boolean,
            attachAnalyzer: (ImageAnalysis) -> Unit,
        ) {
            startCount += 1
        }

        override fun clearAnalyzer(imageAnalysis: ImageAnalysis?) {
            clearCount += 1
        }
    }

    private class TrackingCandidateGenerator : PlateCandidateGenerator {
        var closed = false

        override val name: String = "test"

        override fun generate(
            image: ImageProxy,
            uprightBitmapProvider: (() -> android.graphics.Bitmap?)?,
        ): List<PlateCandidate> = emptyList()

        override fun close() {
            closed = true
        }
    }
}
