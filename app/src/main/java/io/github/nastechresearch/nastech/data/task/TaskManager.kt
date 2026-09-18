package io.github.nastechresearch.nastech.data.task

import androidx.room.withTransaction
import io.github.nastechresearch.nastech.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

class TaskManager(
    private val db: AppDatabase,
) {
    val processStartAtMs: Long = System.currentTimeMillis()

    fun observeTasks(): Flow<List<TaskEntity>> = db.taskDao().observeAll()
    fun observeTask(taskId: String): Flow<TaskEntity?> = db.taskDao().observeById(taskId)
    fun observeSteps(taskId: String): Flow<List<TaskStepEntity>> = db.taskStepDao().observeForTask(taskId)
    fun observeCheckpoints(taskId: String): Flow<List<TaskCheckpointEntity>> =
        db.taskCheckpointDao().observeForTask(taskId)
    fun observeAudit(taskId: String): Flow<List<TaskAuditLogEntity>> =
        db.taskAuditLogDao().observeForTask(taskId)

    suspend fun getTask(taskId: String): TaskEntity? = db.taskDao().getById(taskId)

    suspend fun getPolicy(conversationId: String): TaskPolicy =
        db.taskSettingsDao().get(conversationId)?.toPolicy() ?: TaskPolicy()

    suspend fun savePolicy(conversationId: String, policy: TaskPolicy) {
        db.taskSettingsDao().upsert(policy.toEntity(conversationId))
    }

    suspend fun createTask(
        conversationId: String,
        goal: String,
        steps: List<TaskStepSpec>,
        parentTaskId: String? = null,
        policy: TaskPolicy = TaskPolicy(),
    ): String {
        require(goal.isNotBlank()) { "Task goal cannot be blank" }
        require(steps.isNotEmpty()) { "Task requires at least one step" }

        val now = System.currentTimeMillis()
        val taskId = Uuid.random().toString()
        val task = TaskEntity(
            taskId = taskId,
            conversationId = conversationId,
            goal = goal.trim(),
            createdAt = now,
            updatedAt = now,
            parentTaskId = parentTaskId,
        )
        val entities = steps.mapIndexed { index, spec ->
            TaskStepEntity(
                id = Uuid.random().toString(),
                taskId = taskId,
                orderIndex = index,
                logicalGoal = spec.logicalGoal.trim(),
                executionInstruction = spec.executionInstruction.trim(),
                expectedResult = spec.expectedResult?.trim()?.takeIf { it.isNotEmpty() },
                requiresVerification = spec.requiresVerification,
                verificationHint = spec.verificationHint?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        db.withTransaction {
            db.taskDao().upsert(task)
            db.taskStepDao().upsertAll(entities)
            db.taskSettingsDao().upsert(policy.toEntity(conversationId))
            audit(taskId, eventType = "START", status = TaskStatus.PENDING.name, message = "Task created")
        }
        return taskId
    }

    suspend fun startTask(taskId: String) {
        val task = db.taskDao().getById(taskId) ?: return
        val next = nextIncompleteStep(taskId)
        if (next == null) {
            updateTaskStatus(task.copy(status = TaskStatus.COMPLETED.name, progress = 100))
            return
        }
        val now = System.currentTimeMillis()
        db.taskDao().upsert(
            task.copy(
                status = TaskStatus.RUNNING.name,
                currentStepId = next.id,
                pausedReason = null,
                progress = progress(taskId),
                updatedAt = now,
            )
        )
        audit(taskId, eventType = "START", status = TaskStatus.RUNNING.name, message = "Task execution started")
    }

    suspend fun pauseTask(taskId: String, reason: String = "Paused by user") {
        val task = db.taskDao().getById(taskId) ?: return
        val now = System.currentTimeMillis()
        updateTaskStatus(
            task.copy(
                status = TaskStatus.PAUSED.name,
                pausedReason = reason.take(500),
                updatedAt = now,
            )
        )
        audit(taskId, eventType = "PAUSED", status = TaskStatus.PAUSED.name, message = reason.take(500))
    }

    suspend fun cancelTask(taskId: String) {
        val task = db.taskDao().getById(taskId) ?: return
        val now = System.currentTimeMillis()
        updateTaskStatus(task.copy(status = TaskStatus.CANCELLED.name, updatedAt = now))
        audit(taskId, eventType = "CANCELLED", status = TaskStatus.CANCELLED.name)
    }

    suspend fun failTask(taskId: String, reason: String) {
        val task = db.taskDao().getById(taskId) ?: return
        updateTaskStatus(
            task.copy(
                status = TaskStatus.FAILED.name,
                pausedReason = reason.take(500),
                updatedAt = System.currentTimeMillis(),
            )
        )
        audit(taskId, eventType = "FAILED", status = TaskStatus.FAILED.name, error = reason.take(2_000))
    }

    suspend fun beginStep(taskId: String, stepId: String) {
        val task = db.taskDao().getById(taskId) ?: return
        val step = db.taskStepDao().getById(stepId) ?: return
        require(step.taskId == taskId) { "Step does not belong to task" }
        val now = System.currentTimeMillis()
        db.taskStepDao().upsert(
            step.copy(
                status = TaskStepStatus.RUNNING.name,
                startedAt = step.startedAt ?: now,
                lastError = null,
            )
        )
        db.taskDao().upsert(
            task.copy(
                status = TaskStatus.RUNNING.name,
                currentStepId = stepId,
                updatedAt = now,
                pausedReason = null,
            )
        )
        audit(taskId, stepId, "STEP_STARTED", TaskStepStatus.RUNNING.name, "Step ${step.orderIndex + 1} started")
    }

    suspend fun completeStep(
        taskId: String,
        stepId: String,
        actualResult: String,
        checkpoint: Boolean = false,
        checkpointNote: String? = null,
    ) {
        val task = db.taskDao().getById(taskId) ?: return
        val step = db.taskStepDao().getById(stepId) ?: return
        if (step.taskId != taskId) return
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.taskStepDao().upsert(
                step.copy(
                    status = TaskStepStatus.SUCCESS.name,
                    completedAt = now,
                    actualResult = actualResult.take(4_000),
                    lastError = null,
                )
            )
            val next = nextIncompleteStep(taskId, excluding = stepId)
            val progressValue = progress(taskId, stepId)
            val nextStatus = if (next == null) TaskStatus.COMPLETED.name else TaskStatus.RUNNING.name
            db.taskDao().upsert(
                task.copy(
                    status = nextStatus,
                    currentStepId = next?.id,
                    progress = if (next == null) 100 else progressValue,
                    updatedAt = now,
                    pausedReason = null,
                )
            )
            audit(
                taskId = taskId,
                stepId = stepId,
                eventType = "SUCCESS",
                status = TaskStepStatus.SUCCESS.name,
                outputSummary = actualResult.take(600),
            )
        }
        if (checkpoint) {
            saveCheckpoint(taskId, checkpointNote)
        }
    }

    suspend fun recordStepFailure(
        taskId: String,
        stepId: String,
        error: String,
        category: TaskFailureCategory = TaskFailureCategory.UNKNOWN,
        recovery: TaskRecoveryAction = TaskRecoveryAction.PAUSE,
        inputFingerprint: String? = null,
        outputSummary: String? = null,
        diagnosticRef: String? = null,
    ) {
        val task = db.taskDao().getById(taskId) ?: return
        val step = db.taskStepDao().getById(stepId) ?: return
        if (step.taskId != taskId) return
        val now = System.currentTimeMillis()
        val nextStatus = when (recovery) {
            TaskRecoveryAction.ASK_USER -> TaskStatus.WAITING_FOR_USER.name
            TaskRecoveryAction.PAUSE,
            TaskRecoveryAction.WAIT,
            TaskRecoveryAction.REPLAN -> TaskStatus.PAUSED.name
            TaskRecoveryAction.RETRY,
            TaskRecoveryAction.ALTERNATIVE_TOOL -> TaskStatus.RUNNING.name
        }
        db.withTransaction {
            db.taskStepDao().upsert(
                step.copy(
                    status = TaskStepStatus.FAILED.name,
                    lastError = error.take(2_000),
                    retryCount = step.retryCount + if (recovery == TaskRecoveryAction.RETRY) 1 else 0,
                )
            )
            db.taskDao().upsert(
                task.copy(
                    status = nextStatus,
                    currentStepId = stepId,
                    pausedReason = if (nextStatus == TaskStatus.PAUSED.name) error.take(500) else null,
                    updatedAt = now,
                )
            )
            audit(
                taskId = taskId,
                stepId = stepId,
                eventType = "FAILED",
                status = category.name,
                message = "Recovery: ${recovery.name}",
                error = error.take(2_000),
                inputFingerprint = inputFingerprint,
                outputSummary = outputSummary?.take(600),
                diagnosticRef = diagnosticRef,
            )
        }
    }

    /**
     * Critical resume guard. A failed/interrupted step is never blindly retried.
     *
     * existingResultIsValid = true  -> mark the step successful and skip the retry.
     * existingResultIsValid = false -> make the step executable again.
     * existingResultIsValid = null  -> pause when a verification predicate is required.
     */
    suspend fun prepareRetry(
        taskId: String,
        stepId: String,
        existingResultIsValid: Boolean?,
    ) {
        val task = db.taskDao().getById(taskId) ?: return
        val step = db.taskStepDao().getById(stepId) ?: return
        if (step.taskId != taskId) return

        if (step.requiresVerification && existingResultIsValid == null) {
            pauseTask(
                taskId,
                "Verification required before retry: ${step.verificationHint.orEmpty()}",
            )
            audit(
                taskId = taskId,
                stepId = stepId,
                eventType = "WAITING_FOR_VERIFICATION",
                status = TaskStatus.WAITING_FOR_USER.name,
                message = "A verification predicate is required before retry.",
            )
            return
        }

        if (existingResultIsValid == true) {
            completeStep(
                taskId = taskId,
                stepId = stepId,
                actualResult = step.actualResult ?: "Verified existing result",
            )
            audit(
                taskId = taskId,
                stepId = stepId,
                eventType = "VERIFIED_EXISTING_RESULT",
                status = TaskStepStatus.SUCCESS.name,
                message = "Skipped retry because the expected result already exists.",
            )
            return
        }

        val now = System.currentTimeMillis()
        db.taskStepDao().upsert(
            step.copy(status = TaskStepStatus.PENDING.name, lastError = null)
        )
        db.taskDao().upsert(
            task.copy(
                status = TaskStatus.RUNNING.name,
                currentStepId = stepId,
                pausedReason = null,
                updatedAt = now,
            )
        )
        audit(
            taskId = taskId,
            stepId = stepId,
            eventType = "RETRY_PREPARED",
            status = TaskStatus.RUNNING.name,
            message = "Verification found no valid existing result.",
        )
    }

    suspend fun saveCheckpoint(taskId: String, note: String? = null): String? {
        val task = db.taskDao().getById(taskId) ?: return null
        val steps = db.taskStepDao().listForTask(taskId)
        val checkpointId = Uuid.random().toString()
        val summary = buildString {
            append("status=${task.status};progress=${task.progress};currentStep=${task.currentStepId};")
            append(steps.joinToString(",") { "${it.orderIndex}:${it.status}" })
        }
        val checkpoint = TaskCheckpointEntity(
            checkpointId = checkpointId,
            taskId = taskId,
            stepId = task.currentStepId,
            createdAt = System.currentTimeMillis(),
            note = note?.take(500),
            stateSummary = summary,
        )
        db.taskCheckpointDao().insert(checkpoint)
        db.taskDao().upsert(task.copy(updatedAt = checkpoint.createdAt))
        audit(
            taskId = taskId,
            eventType = "CHECKPOINT_SAVED",
            status = task.status,
            message = note?.take(500),
        )
        return checkpointId
    }

    suspend fun recoverStaleRunningTasks() {
        val running = db.taskDao().listByStatus(TaskStatus.RUNNING.name)
        for (task in running.filter { it.updatedAt < processStartAtMs }) {
            val policy = getPolicy(task.conversationId)
            if (policy.resumeAfterAppClose) {
                pauseTask(task.taskId, "Application restarted; the task can be resumed.")
                audit(
                    taskId = task.taskId,
                    eventType = "PROCESS_RESTART",
                    status = TaskStatus.PAUSED.name,
                    message = "Recovered a previously running task after application restart.",
                )
            }
        }
    }

    suspend fun resumeTask(taskId: String): TaskResumePlan? {
        val task = db.taskDao().getById(taskId) ?: return null
        val next = nextIncompleteStep(taskId)
        if (next == null) {
            updateTaskStatus(task.copy(status = TaskStatus.COMPLETED.name, progress = 100))
            return TaskResumePlan(
                task = task.copy(status = TaskStatus.COMPLETED.name, progress = 100),
                checkpoint = db.taskCheckpointDao().latestForTask(taskId),
                nextStep = null,
                verificationRequired = false,
            )
        }
        val checkpoint = db.taskCheckpointDao().latestForTask(taskId)
        val now = System.currentTimeMillis()
        val newProgress = progress(taskId)
        val resumedTask = task.copy(
            status = TaskStatus.RUNNING.name,
            currentStepId = next.id,
            pausedReason = null,
            progress = newProgress,
            updatedAt = now,
        )
        db.taskDao().upsert(resumedTask)
        audit(
            taskId = taskId,
            eventType = "RESUME",
            status = TaskStatus.RUNNING.name,
            message = "Resuming from the last successful step.",
        )
        return TaskResumePlan(
            task = resumedTask,
            checkpoint = checkpoint,
            nextStep = next,
            verificationRequired = next.requiresVerification || next.status == TaskStepStatus.FAILED.name,
        )
    }

    private suspend fun nextIncompleteStep(
        taskId: String,
        excluding: String? = null,
    ): TaskStepEntity? =
        db.taskStepDao().listForTask(taskId)
            .firstOrNull { it.id != excluding && it.status != TaskStepStatus.SUCCESS.name }

    private suspend fun progress(
        taskId: String,
        justCompletedStepId: String? = null,
    ): Int {
        val steps = db.taskStepDao().listForTask(taskId)
        if (steps.isEmpty()) return 0
        val completed = steps.count {
            it.status == TaskStepStatus.SUCCESS.name || it.id == justCompletedStepId
        }
        return ((completed * 100f) / steps.size).toInt().coerceIn(0, 100)
    }

    private suspend fun updateTaskStatus(task: TaskEntity) {
        db.taskDao().upsert(task)
    }

    private suspend fun audit(
        taskId: String,
        stepId: String? = null,
        eventType: String,
        status: String? = null,
        message: String? = null,
        error: String? = null,
        inputFingerprint: String? = null,
        outputSummary: String? = null,
        diagnosticRef: String? = null,
    ) {
        db.taskAuditLogDao().insert(
            TaskAuditLogEntity(
                eventId = Uuid.random().toString(),
                taskId = taskId,
                stepId = stepId,
                eventType = eventType,
                status = status,
                message = message?.take(1_000),
                error = error?.take(2_000),
                inputFingerprint = inputFingerprint?.take(500),
                outputSummary = outputSummary?.take(600),
                diagnosticRef = diagnosticRef?.take(500),
                timestamp = System.currentTimeMillis(),
            )
        )
    }
}
