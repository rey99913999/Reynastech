package io.github.nastechresearch.nastech.data.execution

import io.github.nastechresearch.nastech.data.db.AppDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ExecutionTelemetry(
    private val db: AppDatabase,
) {
    private val mutex = Mutex()

    suspend fun recordPlan(taskId: String?, stepCount: Int, llmCallsAvoided: Int) {
        update(taskId) { current ->
            current.copy(
                plannerCalls = current.plannerCalls + 1,
                plannedSteps = current.plannedSteps + stepCount,
                llmCallsAvoided = current.llmCallsAvoided + llmCallsAvoided,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun recordToolExecution(
        taskId: String?,
        outputChars: Int,
        local: Boolean,
        recoveryAttempt: Boolean = false,
    ) {
        update(taskId) { current ->
            current.copy(
                toolCalls = current.toolCalls + 1,
                localToolExecutions = current.localToolExecutions + if (local) 1 else 0,
                localRecoveryAttempts = current.localRecoveryAttempts + if (recoveryAttempt) 1 else 0,
                toolContextChars = current.toolContextChars + outputChars.toLong(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun recordPartialReplan(taskId: String?) {
        update(taskId) { current ->
            current.copy(
                partialReplans = current.partialReplans + 1,
                recoveryCalls = current.recoveryCalls + 1,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun recordRoleProviderTokens(
        taskId: String?,
        role: ExecutionAgentRole,
        inputTokens: Long?,
        outputTokens: Long?,
    ) {
        if (inputTokens == null && outputTokens == null) return
        update(taskId) { current ->
            when (role) {
                ExecutionAgentRole.PLANNER -> current.copy(
                    plannerInputTokens = current.plannerInputTokens + (inputTokens ?: 0L),
                    plannerOutputTokens = current.plannerOutputTokens + (outputTokens ?: 0L),
                )
                ExecutionAgentRole.VISION -> current.copy(
                    visionInputTokens = current.visionInputTokens + (inputTokens ?: 0L),
                    visionOutputTokens = current.visionOutputTokens + (outputTokens ?: 0L),
                )
                ExecutionAgentRole.RECOVERY -> current.copy(
                    recoveryInputTokens = current.recoveryInputTokens + (inputTokens ?: 0L),
                    recoveryOutputTokens = current.recoveryOutputTokens + (outputTokens ?: 0L),
                )
                ExecutionAgentRole.VERIFIER -> current.copy(
                    verifierInputTokens = current.verifierInputTokens + (inputTokens ?: 0L),
                    verifierOutputTokens = current.verifierOutputTokens + (outputTokens ?: 0L),
                )
            }.copy(updatedAt = System.currentTimeMillis())
        }
    }

    suspend fun snapshot(taskId: String): TaskExecutionUsageEntity? =
        db.taskExecutionUsageDao().get(taskId)

    private suspend fun update(
        taskId: String?,
        transform: (TaskExecutionUsageEntity) -> TaskExecutionUsageEntity,
    ) {
        if (taskId.isNullOrBlank()) return
        mutex.withLock {
            val dao = db.taskExecutionUsageDao()
            val current = dao.get(taskId) ?: TaskExecutionUsageEntity(taskId = taskId)
            dao.upsert(transform(current))
        }
    }
}
