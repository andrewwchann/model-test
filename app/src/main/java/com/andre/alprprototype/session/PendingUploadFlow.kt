package com.andre.alprprototype.session

import com.andre.alprprototype.R

internal data class PendingUploadPromptSpec(
    val titleRes: Int,
    val negativeButtonRes: Int,
)

internal data class PendingUploadPromptDecision(
    val shouldShowPrompt: Boolean,
    val shouldFinishSession: Boolean,
    val promptSpec: PendingUploadPromptSpec? = null,
)

internal data class PendingUploadSyncOutcome(
    val shouldShowSuccess: Boolean,
    val uploadedCount: Int,
    val shouldFinishSession: Boolean,
)

internal data class PendingUploadActionDecision(
    val shouldFinishSession: Boolean = false,
    val shouldOpenWifiSettings: Boolean = false,
    val shouldPromptOnResume: Boolean = false,
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

    fun promptDecision(
        pendingCount: Int,
        atSessionEnd: Boolean,
    ): PendingUploadPromptDecision {
        return if (shouldShowPrompt(pendingCount)) {
            PendingUploadPromptDecision(
                shouldShowPrompt = true,
                shouldFinishSession = false,
                promptSpec = promptSpec(atSessionEnd),
            )
        } else {
            PendingUploadPromptDecision(
                shouldShowPrompt = false,
                shouldFinishSession = shouldFinishWithoutPrompt(pendingCount, atSessionEnd),
            )
        }
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

    fun wifiSettingsAction(): PendingUploadActionDecision {
        return PendingUploadActionDecision(
            shouldOpenWifiSettings = true,
            shouldPromptOnResume = true,
        )
    }

    fun dismissAction(atSessionEnd: Boolean): PendingUploadActionDecision {
        return PendingUploadActionDecision(shouldFinishSession = atSessionEnd)
    }
}
