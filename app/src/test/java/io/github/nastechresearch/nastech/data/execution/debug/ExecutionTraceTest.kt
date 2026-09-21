package io.github.nastechresearch.nastech.data.execution.debug

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTraceTest {

    @Test
    fun `trace codec round trips`() {
        val trace = ExecutionTrace(
            runId = "run-1",
            taskId = "task-1",
            workflowId = "workflow-1",
            startedAtMs = 100L,
            currentStep = 1,
            status = ExecutionTraceStatus.FAILED,
            steps = listOf(
                ExecutionTraceStep(
                    index = 0,
                    action = "tap",
                    tool = "tap",
                    status = ExecutionTraceStatus.SUCCESS,
                    checkpoint = true,
                ),
                ExecutionTraceStep(
                    index = 1,
                    action = "ocr_extract",
                    tool = "ocr_extract",
                    status = ExecutionTraceStatus.FAILED,
                    retries = 2,
                    error = "timeout",
                ),
            ),
            checkpointStep = 0,
            retryCount = 2,
        )
        val decoded = ExecutionTraceCodec.decode(ExecutionTraceCodec.encode(trace))
        assertEquals(trace, decoded)
    }

    @Test
    fun `sensitive input keys are redacted from summaries`() {
        val summary = safeJsonSummary(
            buildJsonObject {
                put("token", "secret-value")
                put("name", "wifi")
            }
        )
        assertTrue(summary.contains("<redacted>"))
        assertTrue(!summary.contains("secret-value"))
        assertTrue(summary.contains("wifi"))
    }

    @Test
    fun `human readable diagnostics cover common execution failures`() {
        assertEquals(
            "Permission was denied while executing the step.",
            humanReadableExecutionError("SecurityException: permission denied"),
        )
        assertEquals(
            "The step exceeded its allowed time.",
            humanReadableExecutionError("tap exceeded 60s"),
        )
        assertEquals(
            "A network connection failed or timed out.",
            humanReadableExecutionError("network timeout"),
        )
    }
}
