package io.github.nastechresearch.nastech.data.execution

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_execution_usage")
data class TaskExecutionUsageEntity(
    @PrimaryKey val taskId: String,
    val plannerCalls: Long = 0,
    val recoveryCalls: Long = 0,
    val visionCalls: Long = 0,
    val verifierCalls: Long = 0,
    val plannedSteps: Long = 0,
    val toolCalls: Long = 0,
    val localToolExecutions: Long = 0,
    val localRecoveryAttempts: Long = 0,
    val partialReplans: Long = 0,
    val llmCallsAvoided: Long = 0,
    val toolContextChars: Long = 0,
    val plannerInputTokens: Long = 0,
    val plannerOutputTokens: Long = 0,
    val recoveryInputTokens: Long = 0,
    val recoveryOutputTokens: Long = 0,
    val visionInputTokens: Long = 0,
    val visionOutputTokens: Long = 0,
    val verifierInputTokens: Long = 0,
    val verifierOutputTokens: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
