package com.andre.alprprototype.session

import com.andre.alprprototype.R

internal data class PendingUploadPromptSpec(
    val titleRes: Int,
    val negativeButtonRes: Int,
)

internal data class PendingUploadSyncOutcome(
    val shouldShowSuccess: Boolean,
    val uploadedCount: Int,
    val shouldFinishSession: Boolean,
)

internal object PendingUploadFlow {
    fun shouldFinishWithoutPrompt(
        pendingCount: Int,
        atSessionEnd: Boolean,
    ): Boolean {
        return pendingCount <= 0 && atSessionEnd
    }

    fun shouldShowPrompt(pendingCount: Int): Boolean = pendingCount > 0

    fun promptSpec(atSessionEnd: Boolean): PendingUploadPromptSpec {
        return PendingUploadPromptSpec(
            titleRes = if (atSessionEnd) R.string.pending_upload_before_closing_title else R.string.pending_upload_saved_title,
            negativeButtonRes = if (atSessionEnd) R.string.pending_upload_end_session else R.string.pending_upload_later,
        )
    }

    fun syncOutcome(
        isSuccess: Boolean,
        uploadedCount: Int?,
        atSessionEnd: Boolean,
    ): PendingUploadSyncOutcome {
        return PendingUploadSyncOutcome(
            shouldShowSuccess = isSuccess,
            uploadedCount = uploadedCount ?: 0,
            shouldFinishSession = atSessionEnd,
        )
    }
}
