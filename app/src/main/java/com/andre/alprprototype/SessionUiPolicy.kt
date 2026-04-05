package com.andre.alprprototype

internal object SessionUiPolicy {
    fun shouldShowSessionChrome(
        operatorDialogVisible: Boolean,
        evidenceFlowActive: Boolean,
    ): Boolean {
        return !operatorDialogVisible && !evidenceFlowActive
    }
}
