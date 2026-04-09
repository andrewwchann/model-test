package com.andre.alprprototype

import android.content.Context
import com.andre.alprprototype.ocr.PlateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PlateOcrEngineSupportTest {
    @Test
    fun loadPlateConfig_reads_expected_ocr_asset_values() {
        val config = invokeLoadPlateConfig(RuntimeEnvironment.getApplication(), "ocr/plate_config.yaml")

        assertTrue(config.maxPlateSlots > 0)
        assertTrue(config.alphabet.isNotBlank())
        assertTrue(config.imgHeight > 0)
        assertTrue(config.imgWidth > 0)
        assertEquals(config.imageColorMode, config.imageColorMode.lowercase())
    }

    @Test
    fun copyAssetToCache_copies_existing_asset_into_cache_dir() {
        val context = RuntimeEnvironment.getApplication()

        val copied = invokeCopyAssetToCache(context, "ocr/plate_config.yaml")

        assertTrue(copied.exists())
        assertEquals(context.cacheDir.absolutePath, copied.parentFile?.absolutePath)
        assertTrue(copied.readText().contains("max_plate_slots"))
    }

    private fun invokeLoadPlateConfig(context: Context, assetPath: String): PlateConfig {
        val method = Class.forName("com.andre.alprprototype.PlateOcrEngineKt")
            .getDeclaredMethod("loadPlateConfig", Context::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(null, context, assetPath) as PlateConfig
    }

    private fun invokeCopyAssetToCache(context: Context, assetPath: String): File {
        val method = Class.forName("com.andre.alprprototype.PlateOcrEngineKt")
            .getDeclaredMethod("copyAssetToCache", Context::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(null, context, assetPath) as File
    }
}
