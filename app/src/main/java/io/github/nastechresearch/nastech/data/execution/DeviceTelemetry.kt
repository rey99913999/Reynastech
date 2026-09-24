package io.github.nastechresearch.nastech.data.execution

data class DeviceTelemetryEvent(
    val taskId: String?,
    val executionPath: String,
    val actionId: String? = null,
    val actionType: String,
    val targetSource: String? = null,
    val targetConfidence: Float? = null,
    val fallbackPath: List<String> = emptyList(),
    val recoveryAttempts: Int = 0,
    val verificationAttempts: Int = 0,
    val verificationOutcome: String? = null,
    val localWaitMs: Long = 0L,
    val generalLlmRequests: Int = 0,
    val visionRequests: Int = 0,
    val finalStatus: String? = null,
    val replanBoundary: Boolean = false,
    val artifactState: String? = null,
    val recordedAtMs: Long = System.currentTimeMillis(),
)
