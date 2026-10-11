package io.github.nastechresearch.nastech.data.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionMonitorTest {
    @Test
    fun identifiesSlowModelRequestWithoutClaimingItIsDefinitelyStuck() {
        val task = TaskEntity(
            taskId = "task-1",
            conversationId = "conversation-1",
            goal = "Send a prompt in the ChatGPT app",
            status = TaskStatus.RUNNING.name,
            currentStepId = "step-1",
            createdAt = 1_000L,
            updatedAt = 2_000L,
        )
        val step = TaskStepEntity(
            id = "step-1",
            taskId = task.taskId,
            orderIndex = 0,
            logicalGoal = "Wait for the answer",
            executionInstruction = "Wait for a visible response",
            status = TaskStepStatus.RUNNING.name,
            startedAt = 1_500L,
        )
        val audit = listOf(
            event("step-start", task.taskId, step.id, "STEP_STARTED", 1_500L),
            event("model-start", task.taskId, step.id, "MODEL_REQUEST_STARTED", 2_000L),
        )

        val result = buildTaskExecutionSnapshot(
            task = task,
            steps = listOf(step),
            audit = audit,
            nowMs = 7_000L,
            slowAfterMs = 3_000L,
            possiblyStuckAfterMs = 5_000L,
        )

        assertEquals(TaskExecutionHealth.POSSIBLY_STUCK, result.health)
        assertEquals("MODEL_REQUEST", result.currentActivity?.stage)
        assertEquals(5_000L, result.currentActivity?.elapsedMs)
        assertTrue(result.diagnosticMessage!!.contains("does not prove a hang"))
        assertEquals(6_000L, result.totalElapsedMs)
    }

    @Test
    fun completedTaskHasNoActiveActivityAndKeepsCompletedElapsedTime() {
        val task = TaskEntity(
            taskId = "task-2",
            conversationId = "conversation-1",
            goal = "Open Gemini",
            status = TaskStatus.COMPLETED.name,
            currentStepId = null,
            createdAt = 1_000L,
            updatedAt = 4_000L,
        )
        val step = TaskStepEntity(
            id = "step-2",
            taskId = task.taskId,
            orderIndex = 0,
            logicalGoal = "Open Gemini",
            executionInstruction = "Launch the app",
            status = TaskStepStatus.SUCCESS.name,
            startedAt = 1_500L,
            completedAt = 4_000L,
        )
        val audit = listOf(
            event("step-start", task.taskId, step.id, "STEP_STARTED", 1_500L),
            event("step-success", task.taskId, step.id, "SUCCESS", 4_000L),
        )

        val result = buildTaskExecutionSnapshot(
            task = task,
            steps = listOf(step),
            audit = audit,
            nowMs = 9_000L,
            slowAfterMs = 3_000L,
            possiblyStuckAfterMs = 5_000L,
        )

        assertEquals(TaskExecutionHealth.COMPLETED, result.health)
        assertNull(result.currentActivity)
        assertEquals(3_000L, result.totalElapsedMs)
        assertEquals(2_500L, result.steps.single().totalDurationMs)
    }

    private fun event(
        id: String,
        taskId: String,
        stepId: String,
        type: String,
        timestamp: Long,
    ) = TaskAuditLogEntity(
        eventId = id,
        taskId = taskId,
        stepId = stepId,
        eventType = type,
        timestamp = timestamp,
    )
}
