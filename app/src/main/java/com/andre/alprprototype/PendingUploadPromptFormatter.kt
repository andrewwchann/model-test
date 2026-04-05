package com.andre.alprprototype

internal object PendingUploadPromptFormatter {
    fun buildMessage(pendingCount: Int, atSessionEnd: Boolean): String {
        val itemLabel = if (pendingCount == 1) "upload" else "uploads"
        val presentVerb = if (pendingCount == 1) "is" else "are"
        val pastVerb = if (pendingCount == 1) "was" else "were"
        val syncHint = "Sync them now if the device is online, or keep them saved for the next session."
        return if (atSessionEnd) {
            "$pendingCount queued $itemLabel $presentVerb still saved on this device. $syncHint"
        } else {
            "$pendingCount queued $itemLabel $pastVerb saved from an earlier session. $syncHint"
        }
    }
}
