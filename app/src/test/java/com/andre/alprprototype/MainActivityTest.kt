package com.andre.alprprototype

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @Test
    fun startSessionButton_launches_cameraActivity() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<android.view.View>(R.id.startSessionButton).performClick()

        val nextIntent: Intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(CameraActivity::class.java.name, nextIntent.component?.className)
    }
}
