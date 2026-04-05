package com.andre.alprprototype

import android.app.Application
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ViolationManagerTest {
    private lateinit var context: Application
    private lateinit var queueFile: File
    private lateinit var stagedDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        queueFile = File(context.filesDir, "violation_queue.json")
        stagedDir = File(context.filesDir, "queued-violation-images")
        queueFile.delete()
        stagedDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        queueFile.delete()
        stagedDir.deleteRecursively()
    }

    @Test
    fun addViolation_fails_when_assets_missing() {
        val manager = ViolationManager(context)

        val result = manager.addViolation(
            violation(
                platePath = File(context.cacheDir, "missing_plate.jpg").absolutePath,
                vehiclePath = File(context.cacheDir, "missing_vehicle.jpg").absolutePath,
            ),
        )

        assertTrue(result.isFailure)
        assertEquals(0, manager.getQueueSize())
    }

    @Test
    fun addViolation_stages_assets_updates_queue_and_deletes_originals() {
        val plate = File(context.cacheDir, "plate.jpg").apply { parentFile?.mkdirs(); writeText("plate") }
        val vehicle = File(context.cacheDir, "vehicle.jpg").apply { parentFile?.mkdirs(); writeText("vehicle") }
        val manager = ViolationManager(context)

        val result = manager.addViolation(
            violation(
                platePath = plate.absolutePath,
                vehiclePath = vehicle.absolutePath,
            ),
        )

        assertTrue(result.isSuccess)
        val staged = result.getOrNull()!!
        assertEquals(1, manager.getQueueSize())
        assertTrue(queueFile.exists())
        assertTrue(File(staged.localPlatePath!!).exists())
        assertTrue(File(staged.localVehiclePath!!).exists())
        assertNotEquals(plate.absolutePath, staged.localPlatePath)
        assertNotEquals(vehicle.absolutePath, staged.localVehiclePath)
        assertFalse(plate.exists())
        assertFalse(vehicle.exists())
    }

    @Test
    fun uploadQueue_returnsZero_when_empty() {
        val manager = ViolationManager(context)

        val result = kotlinx.coroutines.runBlocking {
            manager.uploadQueue()
        }

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
    }

    @Test
    fun uploadQueue_uploads_assets_and_removes_uploaded_entry() {
        val plate = File(context.cacheDir, "plate.jpg").apply { parentFile?.mkdirs(); writeText("plate") }
        val vehicle = File(context.cacheDir, "vehicle.jpg").apply { parentFile?.mkdirs(); writeText("vehicle") }
        val api = FakeViolationApi()
        val manager = ViolationManager(context) { api }

        val addResult = manager.addViolation(violation(plate.absolutePath, vehicle.absolutePath))
        assertTrue(addResult.isSuccess)

        val uploadResult = kotlinx.coroutines.runBlocking { manager.uploadQueue() }

        assertTrue(uploadResult.isSuccess)
        assertEquals(1, uploadResult.getOrNull())
        assertEquals(0, manager.getQueueSize())
        assertEquals(2, api.uploadUrlRequests.size)
        assertEquals(2, api.uploadRequests.size)
        assertEquals(1, api.batchUploads.size)
    }

    @Test
    fun uploadQueue_keeps_entry_when_asset_upload_fails() {
        val plate = File(context.cacheDir, "plate.jpg").apply { parentFile?.mkdirs(); writeText("plate") }
        val vehicle = File(context.cacheDir, "vehicle.jpg").apply { parentFile?.mkdirs(); writeText("vehicle") }
        val api = FakeViolationApi(uploadResponse = Response.error(500, "fail".toResponseBody("text/plain".toMediaType())))
        val manager = ViolationManager(context) { api }

        val addResult = manager.addViolation(violation(plate.absolutePath, vehicle.absolutePath))
        assertTrue(addResult.isSuccess)

        val uploadResult = kotlinx.coroutines.runBlocking { manager.uploadQueue() }

        assertTrue(uploadResult.isSuccess)
        assertEquals(0, uploadResult.getOrNull())
        assertEquals(1, manager.getQueueSize())
        assertTrue(api.batchUploads.isEmpty())
    }

    @Test
    fun uploadQueue_skips_missing_plate_file_and_keeps_entry() {
        val plate = File(context.cacheDir, "plate.jpg").apply { parentFile?.mkdirs(); writeText("plate") }
        val vehicle = File(context.cacheDir, "vehicle.jpg").apply { parentFile?.mkdirs(); writeText("vehicle") }
        val api = FakeViolationApi()
        val manager = ViolationManager(context) { api }

        val addResult = manager.addViolation(violation(plate.absolutePath, vehicle.absolutePath))
        val staged = addResult.getOrNull()!!
        File(staged.localPlatePath!!).delete()

        val uploadResult = kotlinx.coroutines.runBlocking { manager.uploadQueue() }

        assertTrue(uploadResult.isSuccess)
        assertEquals(0, uploadResult.getOrNull())
        assertEquals(1, manager.getQueueSize())
        assertEquals(1, api.uploadUrlRequests.size)
        assertTrue(api.batchUploads.isEmpty())
    }

    @Test
    fun addViolation_reuses_existing_staged_assets_without_deleting_them() {
        stagedDir.mkdirs()
        val stagedPlate = File(stagedDir, "plate_existing.jpg").apply { writeText("plate") }
        val stagedVehicle = File(stagedDir, "vehicle_existing.jpg").apply { writeText("vehicle") }
        val manager = ViolationManager(context)

        val result = manager.addViolation(
            violation(
                platePath = stagedPlate.absolutePath,
                vehiclePath = stagedVehicle.absolutePath,
            ),
        )

        assertTrue(result.isSuccess)
        val staged = result.getOrNull()!!
        assertEquals(stagedPlate.absolutePath, staged.localPlatePath)
        assertEquals(stagedVehicle.absolutePath, staged.localVehiclePath)
        assertTrue(stagedPlate.exists())
        assertTrue(stagedVehicle.exists())
    }

    @Test
    fun uploadQueue_skips_missing_vehicle_file_and_keeps_entry() {
        val plate = File(context.cacheDir, "plate.jpg").apply { parentFile?.mkdirs(); writeText("plate") }
        val vehicle = File(context.cacheDir, "vehicle.jpg").apply { parentFile?.mkdirs(); writeText("vehicle") }
        val api = FakeViolationApi()
        val manager = ViolationManager(context) { api }

        val staged = manager.addViolation(violation(plate.absolutePath, vehicle.absolutePath)).getOrNull()!!
        File(staged.localVehiclePath!!).delete()

        val uploadResult = kotlinx.coroutines.runBlocking { manager.uploadQueue() }

        assertTrue(uploadResult.isSuccess)
        assertEquals(0, uploadResult.getOrNull())
        assertEquals(1, manager.getQueueSize())
        assertEquals(1, api.uploadRequests.size)
        assertTrue(api.batchUploads.isEmpty())
    }

    @Test
    fun uploadQueue_returns_failure_when_api_throws() {
        val plate = File(context.cacheDir, "plate.jpg").apply { parentFile?.mkdirs(); writeText("plate") }
        val vehicle = File(context.cacheDir, "vehicle.jpg").apply { parentFile?.mkdirs(); writeText("vehicle") }
        val manager = ViolationManager(context) {
            object : ViolationApi {
                override suspend fun getUploadUrl(filename: String): GetUploadUrlResponse {
                    throw IllegalStateException("network down")
                }

                override suspend fun uploadToS3(uploadUrl: String, image: okhttp3.RequestBody): Response<Unit> {
                    error("should not run")
                }

                override suspend fun uploadViolations(violations: List<ViolationEvent>): BatchUploadResponse {
                    error("should not run")
                }
            }
        }

        val addResult = manager.addViolation(violation(plate.absolutePath, vehicle.absolutePath))
        assertTrue(addResult.isSuccess)

        val uploadResult = kotlinx.coroutines.runBlocking { manager.uploadQueue() }

        assertTrue(uploadResult.isFailure)
        assertEquals(1, manager.getQueueSize())
    }

    private fun violation(platePath: String, vehiclePath: String): ViolationEvent {
        return ViolationEvent(
            rawOcrText = "ABC123",
            confidenceScore = 0.9f,
            timestamp = "2026-01-01T00:00:00Z",
            operatorId = "Device_01",
            localPlatePath = platePath,
            localVehiclePath = vehiclePath,
        )
    }

    private class FakeViolationApi(
        private val uploadResponse: Response<Unit> = Response.success(Unit),
    ) : ViolationApi {
        val uploadUrlRequests = mutableListOf<String>()
        val uploadRequests = mutableListOf<String>()
        val batchUploads = mutableListOf<List<ViolationEvent>>()

        override suspend fun getUploadUrl(filename: String): GetUploadUrlResponse {
            uploadUrlRequests += filename
            return GetUploadUrlResponse(
                uploadUrl = "https://example.com/$filename",
                filename = filename,
                finalS3Uri = "s3://bucket/$filename",
            )
        }

        override suspend fun uploadToS3(uploadUrl: String, image: okhttp3.RequestBody): Response<Unit> {
            uploadRequests += uploadUrl
            return uploadResponse
        }

        override suspend fun uploadViolations(violations: List<ViolationEvent>): BatchUploadResponse {
            batchUploads += violations.map { it.copy() }
            return BatchUploadResponse("ok")
        }
    }
}
