package io.github.nastechresearch.nastech.data.ai

import io.github.nastechresearch.nastech.data.execution.ExecutionPlanResult
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationDeviceExecutionGuardTest {
    private val json = Json

    @Test
    fun successfulDirectDeviceToolExecutionSuppressesFalseCompletion() {
        val evidence = DeviceExecutionEvidenceTracker.recordToolResult(
            current = DeviceExecutionEvidence(),
            toolName = "open_app",
            output = listOf(UIMessagePart.Text("""{"dispatch_succeeded":true,"success":true}""")),
            invocationStarted = true,
            json = json,
        )

        assertTrue(evidence.deviceToolExecutedThisTurn)
        assertTrue(evidence.deviceToolSucceededThisTurn)
        assertTrue(
            DeviceCompletionGuard.evaluate(
                isDeviceTask = true,
                evidence = evidence,
                retryAlreadyUsed = false,
            ) == DeviceCompletionGuardDecision.ALLOW
        )
    }

    @Test
    fun explicitSuccessFalseDoesNotCountAsSuccessfulEvidence() {
        val evidence = DeviceExecutionEvidenceTracker.recordToolResult(
            current = DeviceExecutionEvidence(),
            toolName = "open_app",
            output = listOf(
                UIMessagePart.Text("""{"dispatch_succeeded":true,"success":false}""")
            ),
            invocationStarted = true,
            json = json,
        )

        assertTrue(evidence.deviceToolExecutedThisTurn)
        assertFalse(evidence.deviceToolSucceededThisTurn)
        assertTrue(
            DeviceCompletionGuard.evaluate(
                isDeviceTask = true,
                evidence = evidence,
                retryAlreadyUsed = false,
            ) == DeviceCompletionGuardDecision.RETRY
        )
    }

    @Test
    fun successfulStructuredPlanExecutionSuppressesFalseCompletion() {
        val result = ExecutionPlanResult(
            goal = "open Gemini",
            status = "SUCCESS",
            completedCount = 1,
            attemptedCount = 1,
            results = emptyList(),
        )
        val evidence = DeviceExecutionEvidenceTracker.recordToolResult(
            current = DeviceExecutionEvidence(),
            toolName = "execute_structured_plan",
            output = listOf(
                UIMessagePart.Text(json.encodeToString(ExecutionPlanResult.serializer(), result))
            ),
            invocationStarted = true,
            json = json,
        )

        assertTrue(evidence.devicePlanExecutedThisTurn)
        assertTrue(evidence.hasSuccessfulExecutionEvidence)
    }

    @Test
    fun deniedToolDoesNotCountAsSuccessfulEvidence() {
        val evidence = DeviceExecutionEvidence()
        val decision = DeviceCompletionGuard.evaluate(
            isDeviceTask = true,
            evidence = evidence,
            retryAlreadyUsed = false,
        )

        assertFalse(evidence.deviceToolSucceededThisTurn)
        assertTrue(decision == DeviceCompletionGuardDecision.RETRY)
    }

    @Test
    fun unavailableToolDoesNotCountAsSuccessfulEvidence() {
        val evidence = DeviceExecutionEvidenceTracker.recordToolFailure(
            current = DeviceExecutionEvidence(),
            toolName = "tap",
            invocationStarted = false,
        )

        assertFalse(evidence.deviceToolSucceededThisTurn)
        assertTrue(
            DeviceCompletionGuard.evaluate(
                isDeviceTask = true,
                evidence = evidence,
                retryAlreadyUsed = false,
            ) == DeviceCompletionGuardDecision.RETRY
        )
    }

    @Test
    fun thrownToolExecutionDoesNotCountAsSuccessfulEvidence() {
        val evidence = DeviceExecutionEvidenceTracker.recordToolFailure(
            current = DeviceExecutionEvidence(),
            toolName = "tap",
            invocationStarted = true,
        )

        assertTrue(evidence.deviceToolExecutedThisTurn)
        assertFalse(evidence.deviceToolSucceededThisTurn)
    }

    @Test
    fun noDeviceExecutionKeepsExistingRetryThenReplanGuard() {
        val none = DeviceExecutionEvidence()

        assertTrue(
            DeviceCompletionGuard.evaluate(
                isDeviceTask = true,
                evidence = none,
                retryAlreadyUsed = false,
            ) == DeviceCompletionGuardDecision.RETRY
        )
        assertTrue(
            DeviceCompletionGuard.evaluate(
                isDeviceTask = true,
                evidence = none,
                retryAlreadyUsed = true,
            ) == DeviceCompletionGuardDecision.REPLAN
        )
    }

    @Test
    fun partiallyExecutedStructuredPlanPreservesExecutionEvidence() {
        val result = ExecutionPlanResult(
            goal = "open Gemini and type",
            status = "REPLAN_REQUIRED",
            completedCount = 1,
            attemptedCount = 2,
            results = emptyList(),
        )
        val evidence = DeviceExecutionEvidenceTracker.recordToolResult(
            current = DeviceExecutionEvidence(),
            toolName = "execute_structured_plan",
            output = listOf(
                UIMessagePart.Text(json.encodeToString(ExecutionPlanResult.serializer(), result))
            ),
            invocationStarted = true,
            json = json,
        )

        assertTrue(evidence.devicePlanExecutedThisTurn)
        assertTrue(
            DeviceCompletionGuard.evaluate(
                isDeviceTask = true,
                evidence = evidence,
                retryAlreadyUsed = true,
            ) == DeviceCompletionGuardDecision.ALLOW
        )
    }
    @Test
    fun successfulIntermediateActionDoesNotSatisfyCopyOutcomeRequirement() {
        val evidence = DeviceExecutionEvidenceTracker.recordToolResult(
            current = DeviceExecutionEvidence(),
            toolName = "open_app",
            output = listOf(UIMessagePart.Text("""{"dispatch_succeeded":true,"success":true}""")),
            invocationStarted = true,
            json = json,
        )

        assertTrue(
            DeviceCompletionGuard.evaluate(
                isDeviceTask = true,
                evidence = evidence,
                retryAlreadyUsed = false,
                requiresVerifiedOutcome = true,
            ) == DeviceCompletionGuardDecision.RETRY
        )
    }

    @Test
    fun nonEmptyClipboardReadSatisfiesVerifiedOutcomeRequirement() {
        val evidence = DeviceExecutionEvidenceTracker.recordToolResult(
            current = DeviceExecutionEvidence(),
            toolName = "clipboard_tool",
            output = listOf(UIMessagePart.Text("""{"text":"copied response"}""")),
            invocationStarted = true,
            json = json,
        )

        assertTrue(evidence.hasVerifiedOutcomeEvidence)
        assertTrue(
            DeviceCompletionGuard.evaluate(
                isDeviceTask = true,
                evidence = evidence,
                retryAlreadyUsed = false,
                requiresVerifiedOutcome = true,
            ) == DeviceCompletionGuardDecision.ALLOW
        )
    }


}
