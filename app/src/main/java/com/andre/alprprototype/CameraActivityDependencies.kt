package com.andre.alprprototype

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.AssistedPlateCropSaver
import com.andre.alprprototype.alpr.AssistedCropController
import com.andre.alprprototype.alpr.BestPlateCropSaver

internal object CameraActivityDependencies {
    var bestPlateCropSaverFactory: (Context) -> BestPlateCropSaver = { context -> BestPlateCropSaver(context) }
    var assistedPlateCropSaverFactory: (Context) -> AssistedCropController = { context -> AssistedPlateCropSaver(context) }
    var pipelineFactory: (Context) -> AlprPipeline = { context -> AlprPipeline.create(context) }
    var plateOcrRecognizerFactory: (Context) -> PlateOcrRecognizer = { context -> PlateOcrEngine(context) }
    var registryManagerFactory: (Context) -> PlateRegistry = { context -> RegistryManager(context) }
    var violationManagerFactory: (Context) -> ViolationQueue = { context -> ViolationManager(context) }
    var vehiclePhotoCaptureExecutor: VehiclePhotoCaptureExecutor = DefaultVehiclePhotoCaptureExecutor
    var previewBitmapProvider: (PreviewView) -> Bitmap? = { previewView -> previewView.bitmap }
    var cameraRuntimeController: CameraRuntimeController = DefaultCameraRuntimeController

    fun reset() {
        bestPlateCropSaverFactory = { context -> BestPlateCropSaver(context) }
        assistedPlateCropSaverFactory = { context -> AssistedPlateCropSaver(context) }
        pipelineFactory = { context -> AlprPipeline.create(context) }
        plateOcrRecognizerFactory = { context -> PlateOcrEngine(context) }
        registryManagerFactory = { context -> RegistryManager(context) }
        violationManagerFactory = { context -> ViolationManager(context) }
        vehiclePhotoCaptureExecutor = DefaultVehiclePhotoCaptureExecutor
        previewBitmapProvider = { previewView -> previewView.bitmap }
        cameraRuntimeController = DefaultCameraRuntimeController
    }
}
