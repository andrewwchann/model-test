package com.andre.alprprototype

import com.andre.alprprototype.session.PendingUploadFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUploadFlowTest {
    @Test
    fun shouldFinishWithoutPrompt_only_when_session_end_and_no_pending() {
        assertTrue(PendingUploadFlow.shouldFinishWithoutPrompt(0, atSessionEnd = true))
        assertFalse(PendingUploadFlow.shouldFinishWithoutPrompt(1, atSessionEnd = true))
        assertFalse(PendingUploadFlow.shouldFinishWithoutPrompt(0, atSessionEnd = false))
    }

    @Test
    fun shouldShowPrompt_requires_pending_items() {
        assertFalse(PendingUploadFlow.shouldShowPrompt(0))
        assertTrue(PendingUploadFlow.shouldShowPrompt(2))
    }

    @Test
    fun promptSpec_changes_title_and_negative_button_by_session_mode() {
        val endSpec = PendingUploadFlow.promptSpec(atSessionEnd = true)
        val resumeSpec = PendingUploadFlow.promptSpec(atSessionEnd = false)

        assertEquals(R.string.pending_upload_before_closing_title, endSpec.titleRes)
        assertEquals(R.string.pending_upload_end_session, endSpec.negativeButtonRes)
        assertEquals(R.string.pending_upload_saved_title, resumeSpec.titleRes)
        assertEquals(R.string.pending_upload_later, resumeSpec.negativeButtonRes)
    }

    @Test
    fun syncOutcome_keeps_finish_flag_and_normalizes_null_count() {
        val success = PendingUploadFlow.syncOutcome(true, 3, atSessionEnd = true)
        val failure = PendingUploadFlow.syncOutcome(false, null, atSessionEnd = false)

        assertTrue(success.shouldShowSuccess)
        assertEquals(3, success.uploadedCount)
        assertTrue(success.shouldFinishSession)

        assertFalse(failure.shouldShowSuccess)
        assertEquals(0, failure.uploadedCount)
        assertFalse(failure.shouldFinishSession)
    }
}
