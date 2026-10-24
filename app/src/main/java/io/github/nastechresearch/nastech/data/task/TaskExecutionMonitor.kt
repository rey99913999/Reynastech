package io.github.nastechresearch.nastech.data.task

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Read-only view over the existing task and audit tables.
 *
 * The monitor never retries, cancels, or changes a task. It derives timing from persisted
 * STEP_STARTED/SUCCESS/FAILED and nested stage events. The per-second clock is collected
 * only while a caller observes this flow, so it does not poll in the background.
 */
class TaskExecutionMonitor(
    private val taskManager: TaskManager,
    private val slowAfterMs: Long = DEFAULT_SLOW_AFTER_MS,
    private val possiblyStuckAfterMs: Long = DEFAULT_POSSIBLY_STUCK_AFTER_MS,
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
) {
    init {
        require(slowAfterMs >= 0) { "slowAfterMs must be non-negative" }
        require(possiblyStuckAfterMs >= slowAfterMs) {
            "possiblyStuckAfterMs must be >= slowAfterMs"
        }
        require(refreshIntervalMs > 0) { "refreshIntervalMs must be positive" }
    }

    fun observe(taskId: String): Flow<TaskExecutionSnapshot?> =
        combine(
            taskManager.observeTask(taskId),
            taskManager.observeSteps(taskId),
            taskManager.observeAudit(taskId),
            clockTicks(),
        ) { task, steps, audit, nowMs ->
            task?.let {
                buildTaskExecutionSnapshot(
                    task = it,
                    steps = steps,
                    audit = audit,
                    nowMs = nowMs,
                    slowAfterMs = slowAfterMs,
                    possiblyStuckAfterMs = possiblyStuckAfterMs,
                )
            }
        }

    suspend fun snapshot(taskId: String, nowMs: Long = System.currentTimeMillis()): TaskExecutionSnapshot? {
        val task = taskManager.getTask(taskId) ?: return null
        val steps = taskManager.snapshotSteps(taskId)
        val audit = taskManager.observeAudit(taskId).first()
        return buildTaskExecutionSnapshot(
            task = task,
            steps = steps,
            audit = audit,
            nowMs = nowMs,
            slowAfterMs = slowAfterMs,
            possiblyStuckAfterMs = possiblyStuckAfterMs,
        )
    }

    private fun clockTicks(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis())
            delay(refreshIntervalMs)
        }
    }

    companion object {
        const val DEFAULT_SLOW_AFTER_MS = 60_000L
        const val DEFAULT_POSSIBLY_STUCK_AFTER_MS = 180_000L
        const val DEFAULT_REFRESH_INTERVAL_MS = 1_000L
    }
}

enum class TaskExecutionHealth {
    PENDING,
    RUNNING,
    SLOW,
    POSSIBLY_STUCK,
    WAITING_FOR_USER,
    PAUSED,
    FAILED,
    COMPLETED,
    CANCELLED,
}

data class TaskExecutionAttempt(
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    /** SUCCESS, FAILED, RUNNING, PAUSED, or INTERRUPTED. */
    val status: String,
    val durationMs: Long?,
)

data class TaskExecutionStepTiming(
    val stepId: String,
    val orderIndex: Int,
    val goal: String,
    val status: String,
    /** Wall-clock duration for the whole step, including pauses and retries. */
    val totalDurationMs: Long?,
    /** Duration since the latest STEP_STARTED event for this attempt. */
    val currentAttemptDurationMs: Long?,
    val attempts: List<TaskExecutionAttempt>,
    val lastError: String?,
)

data class TaskExecutionActivity(
    /** MODEL_REQUEST, ACTION, VERIFICATION, WAIT, or STEP. */
    val stage: String,
    val status: String,
    val startedAtMs: Long,
    val elapsedMs: Long,
    val description: String?,
)

data class TaskExecutionEventSummary(
    val eventType: String,
    val status: String?,
    val timestampMs: Long,
    val message: String?,
)

data class TaskExecutionSnapshot(
    val taskId: String,
    val goal: String,
    val taskStatus: String,
    val health: TaskExecutionHealth,
    /** Total wall time since task creation; frozen when the task is not RUNNING. */
    val totalElapsedMs: Long,
    val capturedAtMs: Long,
    val currentStepId: String?,
    val currentStepGoal: String?,
    val currentActivity: TaskExecutionActivity?,
    val steps: List<TaskExecutionStepTiming>,
    val lastEvent: TaskExecutionEventSummary?,
    /** Informational only. Time thresholds do not prove an execution is stuck. */
    val diagnosticMessage: String?,
)

internal fun buildTaskExecutionSnapshot(
    task: TaskEntity,
    steps: List<TaskStepEntity>,
    audit: List<TaskAuditLogEntity>,
    nowMs: Long,
    slowAfterMs: Long,
    possiblyStuckAfterMs: Long,
): TaskExecutionSnapshot {
    val orderedAudit = audit.sortedBy { it.timestamp }
    val orderedSteps = steps.sortedBy { it.orderIndex }
    val currentStep = orderedSteps.firstOrNull { it.id == task.currentStepId }
        ?: orderedSteps.firstOrNull { it.status == TaskStepStatus.RUNNING.name }
    val attemptsByStep = orderedSteps.associate { step ->
        step.id to buildAttempts(step.id, orderedAudit, task.status, task.updatedAt, nowMs)
    }

    val timings = orderedSteps.map { step ->
        val attempts = attemptsByStep[step.id].orEmpty()
        val firstStart = step.startedAt ?: attempts.firstOrNull()?.startedAtMs
        val isActivelyRunning = task.status == TaskStatus.RUNNING.name &&
            step.status == TaskStepStatus.RUNNING.name
        val currentAttemptStart = attempts.lastOrNull()?.takeIf {
            it.status == "RUNNING"
        }?.startedAtMs
        val endAt = when {
            isActivelyRunning -> nowMs
            step.completedAt != null -> step.completedAt
            attempts.lastOrNull()?.finishedAtMs != null -> attempts.last().finishedAtMs
            step.status == TaskStepStatus.RUNNING.name -> task.updatedAt
            else -> null
        }
        val totalDuration = if (firstStart != null && endAt != null) {
            (endAt - firstStart).coerceAtLeast(0L)
        } else {
            null
        }
        val currentAttemptDuration = if (isActivelyRunning && currentAttemptStart != null) {
            (nowMs - currentAttemptStart).coerceAtLeast(0L)
        } else {
            null
        }
        TaskExecutionStepTiming(
            stepId = step.id,
            orderIndex = step.orderIndex,
            goal = step.logicalGoal,
            status = step.status,
            totalDurationMs = totalDuration,
            currentAttemptDurationMs = currentAttemptDuration,
            attempts = attempts,
            lastError = step.lastError,
        )
    }

    val activeActivity = if (task.status == TaskStatus.RUNNING.name) {
        findActiveActivity(orderedAudit, currentStep, nowMs)
    } else {
        null
    }
    val stepTiming = timings.firstOrNull { it.stepId == currentStep?.id }
    val ageMs = activeActivity?.elapsedMs
        ?: stepTiming?.currentAttemptDurationMs
        ?: if (task.status == TaskStatus.RUNNING.name) {
            (nowMs - task.updatedAt).coerceAtLeast(0L)
        } else {
            0L
        }
    val mostRecentEvent = orderedAudit.lastOrNull()
    val waitingForApproval = mostRecentEvent?.eventType in setOf(
        "ACTION_AWAITING_APPROVAL",
        "WAIT_AWAITING_APPROVAL",
    ) || mostRecentEvent?.status == "APPROVAL_REQUIRED"

    val health = when {
        task.status == TaskStatus.COMPLETED.name -> TaskExecutionHealth.COMPLETED
        task.status == TaskStatus.CANCELLED.name -> TaskExecutionHealth.CANCELLED
        task.status == TaskStatus.FAILED.name -> TaskExecutionHealth.FAILED
        task.status == TaskStatus.PAUSED.name -> TaskExecutionHealth.PAUSED
        task.status == TaskStatus.WAITING_FOR_USER.name || waitingForApproval ->
            TaskExecutionHealth.WAITING_FOR_USER
        task.status == TaskStatus.PENDING.name -> TaskExecutionHealth.PENDING
        task.status != TaskStatus.RUNNING.name -> TaskExecutionHealth.PENDING
        // A scheduled cooldown is an intentional wait, not a slow or stuck activity.
        activeActivity?.stage == "WAIT" -> TaskExecutionHealth.RUNNING
        ageMs >= possiblyStuckAfterMs -> TaskExecutionHealth.POSSIBLY_STUCK
        ageMs >= slowAfterMs -> TaskExecutionHealth.SLOW
        else -> TaskExecutionHealth.RUNNING
    }

    val diagnostic = when (health) {
        TaskExecutionHealth.SLOW ->
            "The current stage has been active for ${ageMs}ms without a completion event. It may still be working."
        TaskExecutionHealth.POSSIBLY_STUCK ->
            "No completion event has appeared for ${ageMs}ms. Check the current provider, network, or target app; elapsed time alone does not prove a hang."
        TaskExecutionHealth.WAITING_FOR_USER ->
            "Execution appears to be waiting for user approval or input."
        TaskExecutionHealth.PAUSED -> "Task is paused; elapsed time is frozen at the last task update."
        TaskExecutionHealth.FAILED -> "Task ended with a failure. Inspect the last error and audit events."
        TaskExecutionHealth.COMPLETED -> "Task completed."
        TaskExecutionHealth.CANCELLED -> "Task was cancelled."
        TaskExecutionHealth.PENDING -> "Task has not started running."
        TaskExecutionHealth.RUNNING -> null
    }

    val taskEndForElapsed = if (task.status == TaskStatus.RUNNING.name ||
        task.status == TaskStatus.PENDING.name
    ) nowMs else task.updatedAt
    val totalElapsed = (taskEndForElapsed - task.createdAt).coerceAtLeast(0L)

    return TaskExecutionSnapshot(
        taskId = task.taskId,
        goal = task.goal,
        taskStatus = task.status,
        health = health,
        totalElapsedMs = totalElapsed,
        capturedAtMs = nowMs,
        currentStepId = currentStep?.id,
        currentStepGoal = currentStep?.logicalGoal,
        currentActivity = activeActivity ?: currentStep?.takeIf {
            task.status == TaskStatus.RUNNING.name
        }?.let { step ->
            val start = orderedAudit.lastOrNull {
                it.stepId == step.id && it.eventType == "STEP_STARTED"
            }?.timestamp ?: step.startedAt ?: task.updatedAt
            TaskExecutionActivity(
                stage = "STEP",
                status = "RUNNING",
                startedAtMs = start,
                elapsedMs = (nowMs - start).coerceAtLeast(0L),
                description = "Step ${step.orderIndex + 1}: ${step.logicalGoal}".take(240),
            )
        },
        steps = timings,
        lastEvent = mostRecentEvent?.let {
            TaskExecutionEventSummary(
                eventType = it.eventType,
                status = it.status,
                timestampMs = it.timestamp,
                message = it.message?.take(240),
            )
        },
        diagnosticMessage = diagnostic,
    )
}

private fun buildAttempts(
    stepId: String,
    audit: List<TaskAuditLogEntity>,
    taskStatus: String,
    taskUpdatedAt: Long,
    nowMs: Long,
): List<TaskExecutionAttempt> {
    val result = mutableListOf<TaskExecutionAttempt>()
    var openStart: Long? = null
    for (event in audit) {
        if (event.stepId != stepId) continue
        when (event.eventType) {
            "STEP_STARTED" -> {
                if (openStart != null) {
                    val elapsed = (event.timestamp - openStart!!).coerceAtLeast(0L)
                    result += TaskExecutionAttempt(openStart!!, event.timestamp, "INTERRUPTED", elapsed)
                }
                openStart = event.timestamp
            }
            "SUCCESS", "FAILED" -> {
                val started = openStart ?: continue
                val elapsed = (event.timestamp - started).coerceAtLeast(0L)
                result += TaskExecutionAttempt(
                    startedAtMs = started,
                    finishedAtMs = event.timestamp,
                    status = event.eventType,
                    durationMs = elapsed,
                )
                openStart = null
            }
        }
    }
    if (openStart != null) {
        val running = taskStatus == TaskStatus.RUNNING.name
        val end = if (running) nowMs else taskUpdatedAt
        result += TaskExecutionAttempt(
            startedAtMs = openStart!!,
            finishedAtMs = if (running) null else end,
            status = when {
                running -> "RUNNING"
                taskStatus == TaskStatus.PAUSED.name -> "PAUSED"
                else -> "INTERRUPTED"
            },
            durationMs = (end - openStart!!).coerceAtLeast(0L),
        )
    }
    return result
}

private fun findActiveActivity(
    audit: List<TaskAuditLogEntity>,
    currentStep: TaskStepEntity?,
    nowMs: Long,
): TaskExecutionActivity? {
    val stepId = currentStep?.id ?: return null
    var active: TaskAuditLogEntity? = null
    for (event in audit) {
        if (event.stepId != stepId) continue
        when (event.eventType) {
            "STEP_STARTED" -> active = null
            "SUCCESS", "FAILED" -> active = null
            "MODEL_REQUEST_STARTED", "ACTION_STARTED", "VERIFICATION_STARTED", "WAIT_STARTED",
            "MODEL_RATE_LIMIT_WAIT_STARTED" ->
                active = event
            "MODEL_RESPONSE_RECEIVED", "MODEL_REQUEST_FAILED", "MODEL_REQUEST_CANCELLED",
            "MODEL_RATE_LIMIT_WAIT_COMPLETED",
            "ACTION_COMPLETED", "ACTION_FAILED", "ACTION_AWAITING_APPROVAL",
            "VERIFICATION_COMPLETED", "WAIT_COMPLETED", "WAIT_FAILED",
            "WAIT_AWAITING_APPROVAL" -> {
                val start = active
                if (start != null && matchingEnd(start.eventType, event.eventType)) active = null
            }
        }
    }
    val event = active ?: return null
    val stage = when (event.eventType) {
        "MODEL_REQUEST_STARTED" -> "MODEL_REQUEST"
        "ACTION_STARTED" -> "ACTION"
        "VERIFICATION_STARTED" -> "VERIFICATION"
        "WAIT_STARTED", "MODEL_RATE_LIMIT_WAIT_STARTED" -> "WAIT"
        else -> "STEP"
    }
    return TaskExecutionActivity(
        stage = stage,
        status = "RUNNING",
        startedAtMs = event.timestamp,
        elapsedMs = (nowMs - event.timestamp).coerceAtLeast(0L),
        description = event.message?.take(240),
    )
}

private fun matchingEnd(startType: String, endType: String): Boolean = when (startType) {
    "MODEL_REQUEST_STARTED" -> endType in setOf(
        "MODEL_RESPONSE_RECEIVED", "MODEL_REQUEST_FAILED", "MODEL_REQUEST_CANCELLED",
    )
    "ACTION_STARTED" -> endType in setOf(
        "ACTION_COMPLETED", "ACTION_FAILED", "ACTION_AWAITING_APPROVAL",
    )
    "VERIFICATION_STARTED" -> endType == "VERIFICATION_COMPLETED"
    "WAIT_STARTED" -> endType in setOf("WAIT_COMPLETED", "WAIT_FAILED", "WAIT_AWAITING_APPROVAL")
    "MODEL_RATE_LIMIT_WAIT_STARTED" -> endType == "MODEL_RATE_LIMIT_WAIT_COMPLETED"
    else -> false
}
