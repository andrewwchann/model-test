package com.andre.alprprototype

import com.andre.alprprototype.session.PendingUploadPromptFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingUploadPromptFormatterTest {
    @Test
    fun buildMessage_formats_single_item_at_session_end() {
        assertEquals(
            "1 queued upload is still saved on this device. Sync them now if the device is online, or keep them saved for the next session.",
            PendingUploadPromptFormatter.buildMessage(1, atSessionEnd = true),
        )
    }

    @Test
    fun buildMessage_formats_multiple_items_for_resume() {
        assertEquals(
            "2 queued uploads were saved from an earlier session. Sync them now if the device is online, or keep them saved for the next session.",
            PendingUploadPromptFormatter.buildMessage(2, atSessionEnd = false),
        )
    }
}
