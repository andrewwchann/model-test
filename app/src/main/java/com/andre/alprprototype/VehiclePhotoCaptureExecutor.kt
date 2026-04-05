package com.andre.alprprototype

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.util.concurrent.Executor

internal interface VehiclePhotoCaptureExecutor {
    fun capture(
        imageCapture: ImageCapture,
        photoFile: File,
        executor: Executor,
        onSaved: () -> Unit,
        onError: (ImageCaptureException) -> Unit,
    )
}

internal object DefaultVehiclePhotoCaptureExecutor : VehiclePhotoCaptureExecutor {
    override fun capture(
        imageCapture: ImageCapture,
        photoFile: File,
        executor: Executor,
        onSaved: () -> Unit,
        onError: (ImageCaptureException) -> Unit,
    ) {
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSaved()
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            },
        )
    }
}
