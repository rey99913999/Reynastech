package io.github.nastechresearch.nastech.data.task

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TaskStatus { PENDING, RUNNING, PAUSED, WAITING_FOR_USER, FAILED, COMPLETED, CANCELLED }
enum class TaskStepStatus { PENDING, RUNNING, SUCCESS, FAILED }
enum class TaskFailureCategory { NETWORK, TOOL, PERMISSION, INVALID_RESULT, CRASH, UNKNOWN }
enum class TaskRecoveryAction { RETRY, ALTERNATIVE_TOOL, WAIT, ASK_USER, PAUSE, REPLAN }
enum class TaskToolFailurePolicy { RETRY, ALTERNATIVE_TOOL, AGENT_DECIDES, ASK_USER }
enum class TaskUnrecoverablePolicy { PAUSE, FAIL }

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["conversationId", "updatedAt"]),
        Index(value = ["status", "updatedAt"]),
    ],
)
data class TaskEntity(
    @PrimaryKey val taskId: String,
    val conversationId: String,
    val goal: String,
    val status: String = TaskStatus.PENDING.name,
    val currentStepId: String? = null,
    val progress: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val pausedReason: String? = null,
    val retryPolicy: String = "default",
    val checkpointPolicy: String = "important_steps",
    val autonomyPolicy: String = "default",
    val parentTaskId: String? = null,
)

@Entity(
    tableName = "task_steps",
    indices = [
        Index(value = ["taskId", "orderIndex"]),
        Index(value = ["taskId", "status"]),
    ],
)
data class TaskStepEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val orderIndex: Int,
    val logicalGoal: String,
    val executionInstruction: String,
    val status: String = TaskStepStatus.PENDING.name,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val expectedResult: String? = null,
    val actualResult: String? = null,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val checkpointId: String? = null,
    val requiresVerification: Boolean = false,
    val verificationHint: String? = null,
)

@Entity(
    tableName = "task_checkpoints",
    indices = [Index(value = ["taskId", "createdAt"])],
)
data class TaskCheckpointEntity(
    @PrimaryKey val checkpointId: String,
    val taskId: String,
    val stepId: String?,
    val createdAt: Long,
    val note: String? = null,
    val stateSummary: String,
)

@Entity(
    tableName = "task_audit_logs",
    indices = [Index(value = ["taskId", "timestamp"])],
)
data class TaskAuditLogEntity(
    @PrimaryKey val eventId: String,
    val taskId: String,
    val stepId: String? = null,
    val eventType: String,
    val status: String? = null,
    val message: String? = null,
    val error: String? = null,
    val inputFingerprint: String? = null,
    val outputSummary: String? = null,
    val diagnosticRef: String? = null,
    val timestamp: Long,
)

@Entity(tableName = "task_settings")
data class TaskSettingsEntity(
    @PrimaryKey val conversationId: String,
    val saveCheckpoints: Boolean = true,
    val resumeAfterAppClose: Boolean = true,
    val retryCount: Int = 2,
    val retryDelayMs: Long = 2_000L,
    val onToolFailure: String = TaskToolFailurePolicy.RETRY.name,
    val sensitiveActionsRequireApproval: Boolean = true,
    val onUnrecoverableError: String = TaskUnrecoverablePolicy.PAUSE.name,
)

data class TaskStepSpec(
    val logicalGoal: String,
    val executionInstruction: String,
    val expectedResult: String? = null,
    val requiresVerification: Boolean = false,
    val verificationHint: String? = null,
)

data class TaskResumePlan(
    val task: TaskEntity,
    val checkpoint: TaskCheckpointEntity?,
    val nextStep: TaskStepEntity?,
    val verificationRequired: Boolean,
)

data class TaskPolicy(
    val saveCheckpoints: Boolean = true,
    val resumeAfterAppClose: Boolean = true,
    val retryCount: Int = 2,
    val retryDelayMs: Long = 2_000L,
    val onToolFailure: TaskToolFailurePolicy = TaskToolFailurePolicy.RETRY,
    val sensitiveActionsRequireApproval: Boolean = true,
    val onUnrecoverableError: TaskUnrecoverablePolicy = TaskUnrecoverablePolicy.PAUSE,
) {
    fun toEntity(conversationId: String) = TaskSettingsEntity(
        conversationId = conversationId,
        saveCheckpoints = saveCheckpoints,
        resumeAfterAppClose = resumeAfterAppClose,
        retryCount = retryCount.coerceAtLeast(0),
        retryDelayMs = retryDelayMs.coerceAtLeast(0L),
        onToolFailure = onToolFailure.name,
        sensitiveActionsRequireApproval = sensitiveActionsRequireApproval,
        onUnrecoverableError = onUnrecoverableError.name,
    )
}

fun TaskSettingsEntity.toPolicy() = TaskPolicy(
    saveCheckpoints = saveCheckpoints,
    resumeAfterAppClose = resumeAfterAppClose,
    retryCount = retryCount,
    retryDelayMs = retryDelayMs,
    onToolFailure = runCatching { TaskToolFailurePolicy.valueOf(onToolFailure) }
        .getOrDefault(TaskToolFailurePolicy.RETRY),
    sensitiveActionsRequireApproval = sensitiveActionsRequireApproval,
    onUnrecoverableError = runCatching { TaskUnrecoverablePolicy.valueOf(onUnrecoverableError) }
        .getOrDefault(TaskUnrecoverablePolicy.PAUSE),
)
