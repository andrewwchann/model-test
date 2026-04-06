package com.andre.alprprototype

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RegistryManagerTest {
    private lateinit var context: Application
    private lateinit var cacheFile: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        cacheFile = File(context.filesDir, "plate_registry.json")
        cacheFile.parentFile?.mkdirs()
        cacheFile.delete()
    }

    @After
    fun tearDown() {
        cacheFile.delete()
    }

    @Test
    fun isPlateValid_returnsNotFound_when_plate_missing() {
        cacheFile.writeText("[]")

        val manager = RegistryManager(context)

        assertEquals(RegistryManager.PlateValidationResult.NOT_FOUND, manager.isPlateValid("ABC123"))
    }

    @Test
    fun isPlateValid_returnsValid_for_future_expiry() {
        cacheFile.writeText(
            """
            [{"plate_string":"ABC123","expiry_date":"2099-12-31T23:59:59.000Z"}]
            """.trimIndent(),
        )

        val manager = RegistryManager(context)

        assertEquals(RegistryManager.PlateValidationResult.VALID, manager.isPlateValid("abc123"))
    }

    @Test
    fun isPlateValid_returnsExpired_for_past_expiry() {
        cacheFile.writeText(
            """
            [{"plate_string":"ABC123","expiry_date":"2000-01-01T00:00:00.000Z"}]
            """.trimIndent(),
        )

        val manager = RegistryManager(context)

        assertEquals(RegistryManager.PlateValidationResult.EXPIRED, manager.isPlateValid("ABC123"))
    }

    @Test
    fun isPlateValid_falls_back_to_valid_when_expiry_unparseable() {
        cacheFile.writeText(
            """
            [{"plate_string":"ABC123","expiry_date":"not-a-date"}]
            """.trimIndent(),
        )

        val manager = RegistryManager(context)

        assertEquals(RegistryManager.PlateValidationResult.VALID, manager.isPlateValid("ABC123"))
    }

    @Test
    fun isPlateValid_returnsValid_when_plate_has_no_expiry() {
        cacheFile.writeText(
            """
            [{"plate_string":"ABC123","expiry_date":null}]
            """.trimIndent(),
        )

        val manager = RegistryManager(context)

        assertEquals(RegistryManager.PlateValidationResult.VALID, manager.isPlateValid(" abc123 "))
    }

    @Test
    fun constructor_tolerates_invalid_cache_json() {
        cacheFile.writeText("{not-json}")

        val manager = RegistryManager(context)

        assertEquals(RegistryManager.PlateValidationResult.NOT_FOUND, manager.isPlateValid("ABC123"))
    }

    @Test
    fun syncRegistry_updates_cache_and_inMemory_registry() {
        val plates = listOf(
            RegistryPlate(
                plateString = "SYNC123",
                permitType = "A",
                expiryDate = "2099-12-31T23:59:59.000Z",
                lotZone = "Z1",
                listVersion = 1,
            ),
        )
        val manager = RegistryManager(context) { FakeRegistryApi(result = Result.success(plates)) }

        val result = kotlinx.coroutines.runBlocking {
            manager.syncRegistry()
        }

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        assertTrue(cacheFile.readText().contains("SYNC123"))
        assertEquals(RegistryManager.PlateValidationResult.VALID, manager.isPlateValid("SYNC123"))
    }

    @Test
    fun syncRegistry_returns_failure_when_api_throws() {
        val manager = RegistryManager(context) { FakeRegistryApi(result = Result.failure(IllegalStateException("boom"))) }

        val result = kotlinx.coroutines.runBlocking {
            manager.syncRegistry()
        }

        assertTrue(result.isFailure)
    }

    private class FakeRegistryApi(
        private val result: Result<List<RegistryPlate>>,
    ) : RegistryApi {
        override suspend fun getRegistry(version: Int): List<RegistryPlate> {
            return result.getOrThrow()
        }
    }
}
