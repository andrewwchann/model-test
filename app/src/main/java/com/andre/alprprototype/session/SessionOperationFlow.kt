package com.andre.alprprototype.session

import com.andre.alprprototype.R

internal data class OperationButtonState(
    val enabled: Boolean,
    val textRes: Int,
)

internal data class RegistrySyncOutcome(
    val messageRes: Int,
    val formatArg: Int? = null,
)

internal data class QueueUploadOutcome(
    val messageRes: Int,
    val formatArg: String? = null,
    val longMessage: Boolean = false,
)

internal object SessionOperationFlow {
    fun registrySyncLoadingState(): OperationButtonState {
        return OperationButtonState(
            enabled = false,
            textRes = R.string.sync_registry_loading,
        )
    }

    fun registrySyncIdleState(): OperationButtonState {
        return OperationButtonState(
            enabled = true,
            textRes = R.string.sync_registry_button,
        )
    }

    fun registrySyncOutcome(isSuccess: Boolean, count: Int?): RegistrySyncOutcome {
        return if (isSuccess) {
            RegistrySyncOutcome(
                messageRes = R.string.registry_sync_success,
                formatArg = count ?: 0,
            )
        } else {
            RegistrySyncOutcome(messageRes = R.string.registry_sync_failed)
        }
    }

    fun queueUploadEmpty(): QueueUploadOutcome {
        return QueueUploadOutcome(messageRes = R.string.upload_queue_empty)
    }

    fun queueUploadLoadingState(): OperationButtonState {
        return OperationButtonState(
            enabled = false,
            textRes = R.string.upload_queue_loading,
        )
    }

    fun queueUploadIdleState(): OperationButtonState {
        return OperationButtonState(
            enabled = true,
            textRes = R.string.upload_queue_button_format,
        )
    }

    fun queueUploadOutcome(isSuccess: Boolean, count: Int?, errorMessage: String?): QueueUploadOutcome {
        return if (isSuccess) {
            QueueUploadOutcome(
                messageRes = R.string.upload_queue_success,
                formatArg = (count ?: 0).toString(),
            )
        } else {
            QueueUploadOutcome(
                messageRes = R.string.upload_queue_failed,
                formatArg = errorMessage.orEmpty(),
                longMessage = true,
            )
        }
    }
}
