package com.andre.alprprototype

import com.andre.alprprototype.session.SessionOperationFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOperationFlowTest {
    @Test
    fun registrySync_states_and_outcomes_match_expected_resources() {
        val loading = SessionOperationFlow.registrySyncLoadingState()
        val idle = SessionOperationFlow.registrySyncIdleState()
        val success = SessionOperationFlow.registrySyncOutcome(isSuccess = true, count = 4)
        val failure = SessionOperationFlow.registrySyncOutcome(isSuccess = false, count = null)

        assertFalse(loading.enabled)
        assertEquals(R.string.sync_registry_loading, loading.textRes)
        assertTrue(idle.enabled)
        assertEquals(R.string.sync_registry_button, idle.textRes)
        assertEquals(R.string.registry_sync_success, success.messageRes)
        assertEquals(4, success.formatArg)
        assertEquals(R.string.registry_sync_failed, failure.messageRes)
        assertEquals(null, failure.formatArg)
    }

    @Test
    fun queueUpload_states_and_outcomes_cover_success_failure_and_empty() {
        val empty = SessionOperationFlow.queueUploadEmpty()
        val loading = SessionOperationFlow.queueUploadLoadingState()
        val idle = SessionOperationFlow.queueUploadIdleState()
        val success = SessionOperationFlow.queueUploadOutcome(isSuccess = true, count = 3, errorMessage = null)
        val failure = SessionOperationFlow.queueUploadOutcome(isSuccess = false, count = null, errorMessage = "boom")

        assertEquals(R.string.upload_queue_empty, empty.messageRes)
        assertFalse(loading.enabled)
        assertEquals(R.string.upload_queue_loading, loading.textRes)
        assertTrue(idle.enabled)
        assertEquals(R.string.upload_queue_button_format, idle.textRes)
        assertEquals(R.string.upload_queue_success, success.messageRes)
        assertEquals("3", success.formatArg)
        assertFalse(success.longMessage)
        assertEquals(R.string.upload_queue_failed, failure.messageRes)
        assertEquals("boom", failure.formatArg)
        assertTrue(failure.longMessage)
    }

    @Test
    fun outcomes_default_missing_values_to_safe_fallbacks() {
        val syncSuccess = SessionOperationFlow.registrySyncOutcome(isSuccess = true, count = null)
        val uploadSuccess = SessionOperationFlow.queueUploadOutcome(isSuccess = true, count = null, errorMessage = null)
        val uploadFailure = SessionOperationFlow.queueUploadOutcome(isSuccess = false, count = null, errorMessage = null)

        assertEquals(0, syncSuccess.formatArg)
        assertEquals("0", uploadSuccess.formatArg)
        assertEquals("", uploadFailure.formatArg)
        assertTrue(uploadFailure.longMessage)
    }
}
