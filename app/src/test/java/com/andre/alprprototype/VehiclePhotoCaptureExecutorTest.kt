package com.andre.alprprototype

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class VehiclePhotoCaptureExecutorTest {
    @After
    fun tearDown() {
        DefaultVehiclePhotoCaptureExecutor.invoker = DefaultVehiclePhotoCaptureInvoker
    }

    @Test
    fun capture_invokes_saved_callback_when_camera_succeeds() {
        var savedCalled = false
        DefaultVehiclePhotoCaptureExecutor.invoker = object : VehiclePhotoCaptureInvoker {
            override fun takePicture(
                imageCapture: ImageCapture,
                outputOptions: ImageCapture.OutputFileOptions,
                executor: Executor,
                callback: ImageCapture.OnImageSavedCallback,
            ) {
                callback.onImageSaved(ImageCapture.OutputFileResults(null))
            }
        }

        DefaultVehiclePhotoCaptureExecutor.capture(
            imageCapture = ImageCapture.Builder().build(),
            photoFile = File("vehicle.jpg"),
            executor = Executor { it.run() },
            onSaved = { savedCalled = true },
            onError = { error("should not be called") },
        )

        assertTrue(savedCalled)
    }

    @Test
    fun capture_invokes_error_callback_when_camera_fails() {
        val expected = ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "boom", null)
        var actual: ImageCaptureException? = null
        DefaultVehiclePhotoCaptureExecutor.invoker = object : VehiclePhotoCaptureInvoker {
            override fun takePicture(
                imageCapture: ImageCapture,
                outputOptions: ImageCapture.OutputFileOptions,
                executor: Executor,
                callback: ImageCapture.OnImageSavedCallback,
            ) {
                callback.onError(expected)
            }
        }

        DefaultVehiclePhotoCaptureExecutor.capture(
            imageCapture = ImageCapture.Builder().build(),
            photoFile = File("vehicle.jpg"),
            executor = Executor { it.run() },
            onSaved = { error("should not be called") },
            onError = { actual = it },
        )

        assertEquals(expected, actual)
    }
}
