package io.github.nastechresearch.nastech.data.ai

import io.github.nastechresearch.nastech.data.execution.DeviceActionLifecycle
import io.github.nastechresearch.nastech.data.execution.ExecutionPlanResult
import io.github.nastechresearch.nastech.data.execution.ExecutionToolRegistry
import io.github.nastechresearch.nastech.data.execution.ExecutionStepStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart

internal data class DeviceExecutionEvidence(
    val deviceToolExecutedThisTurn: Boolean = false,
    val deviceToolSucceededThisTurn: Boolean = false,
    val devicePlanExecutedThisTurn: Boolean = false,
    val verifiedOutcomeEvidenceThisTurn: Boolean = false,
) {
    val hasSuccessfulExecutionEvidence: Boolean
        get() = deviceToolSucceededThisTurn || devicePlanExecutedThisTurn

    val hasVerifiedOutcomeEvidence: Boolean
        get() = verifiedOutcomeEvidenceThisTurn

    fun canSatisfyCompletion(requiresVerifiedOutcome: Boolean): Boolean =
        if (requiresVerifiedOutcome) hasVerifiedOutcomeEvidence else hasSuccessfulExecutionEvidence
}

internal object DeviceExecutionEvidenceTracker {
    fun recordToolResult(
        current: DeviceExecutionEvidence,
        toolName: String,
        output: List<UIMessagePart>,
        invocationStarted: Boolean,
        json: Json,
    ): DeviceExecutionEvidence {
        if (!invocationStarted) return current

        if (toolName == "execute_structured_plan") {
            val result = decodePlanResult(output, json)
            if (result != null && result.completedCount > 0) {
                val verified = result.results.any {
                    it.status == ExecutionStepStatus.SUCCESS &&
                        it.lifecycle == DeviceActionLifecycle.VERIFIED
                }
                return current.copy(
                    devicePlanExecutedThisTurn = true,
                    verifiedOutcomeEvidenceThisTurn =
                        current.verifiedOutcomeEvidenceThisTurn || verified,
                )
            }
            return current
        }

        if (!ExecutionToolRegistry.isCoreDeviceTool(toolName)) return current

        val failed = outputIndicatesFailure(output, json)
        val verifiedOutcome =
            outputContainsExplicitVerification(output, json) ||
                (toolName == "clipboard_tool" && clipboardReadContainsText(output, json))

        return current.copy(
            deviceToolExecutedThisTurn = true,
            deviceToolSucceededThisTurn =
                current.deviceToolSucceededThisTurn || !failed,
            verifiedOutcomeEvidenceThisTurn =
                current.verifiedOutcomeEvidenceThisTurn || (!failed && verifiedOutcome),
        )
    }

    fun recordToolFailure(
        current: DeviceExecutionEvidence,
        toolName: String,
        invocationStarted: Boolean,
    ): DeviceExecutionEvidence =
        if (invocationStarted && ExecutionToolRegistry.isCoreDeviceTool(toolName)) {
            current.copy(deviceToolExecutedThisTurn = true)
        } else {
            current
        }

    private fun decodePlanResult(
        output: List<UIMessagePart>,
        json: Json,
    ): ExecutionPlanResult? =
        output
            .filterIsInstance<UIMessagePart.Text>()
            .asSequence()
            .mapNotNull { part ->
                runCatching {
                    json.decodeFromString(ExecutionPlanResult.serializer(), part.text)
                }.getOrNull()
            }
            .firstOrNull()

    private fun outputContainsExplicitVerification(
        output: List<UIMessagePart>,
        json: Json,
    ): Boolean =
        output
            .filterIsInstance<UIMessagePart.Text>()
            .any { part ->
                runCatching {
                    val obj = json.parseToJsonElement(part.text).jsonObject
                    val verified = (obj["verified"] as? JsonPrimitive)?.booleanOrNull == true
                    val status = (obj["status"] as? JsonPrimitive)?.contentOrNull?.uppercase()
                    verified || status == "VERIFIED"
                }.getOrDefault(false)
            }

    private fun clipboardReadContainsText(
        output: List<UIMessagePart>,
        json: Json,
    ): Boolean =
        output
            .filterIsInstance<UIMessagePart.Text>()
            .any { part ->
                runCatching {
                    val obj = json.parseToJsonElement(part.text).jsonObject
                    val action = (obj["action"] as? JsonPrimitive)?.contentOrNull
                    val text = (obj["text"] as? JsonPrimitive)?.contentOrNull
                    action == "read" && !text.isNullOrBlank()
                }.getOrDefault(false)
            }

    private fun outputIndicatesFailure(
        output: List<UIMessagePart>,
        json: Json,
    ): Boolean =
        output
            .filterIsInstance<UIMessagePart.Text>()
            .any { part ->
                runCatching {
                    val obj = json.parseToJsonElement(part.text).jsonObject
                    val errorPresent = obj["error"] != null
                    val successExplicitlyFalse =
                        (obj["success"] as? JsonPrimitive)?.booleanOrNull == false
                    val dispatchExplicitlyFalse =
                        (obj["dispatch_succeeded"] as? JsonPrimitive)?.booleanOrNull == false
                    val status = (obj["status"] as? JsonPrimitive)?.contentOrNull?.uppercase()
                    val failedStatus =
                        status in setOf("FAILED", "FAILURE", "ERROR", "REJECTED")
                    errorPresent || successExplicitlyFalse || dispatchExplicitlyFalse || failedStatus
                }.getOrDefault(false)
            }
}

internal enum class DeviceCompletionGuardDecision {
    ALLOW,
    RETRY,
    REPLAN,
}

internal object DeviceCompletionGuard {
    fun evaluate(
        isDeviceTask: Boolean,
        evidence: DeviceExecutionEvidence,
        retryAlreadyUsed: Boolean,
        requiresVerifiedOutcome: Boolean = false,
    ): DeviceCompletionGuardDecision = when {
        !isDeviceTask -> DeviceCompletionGuardDecision.ALLOW
        evidence.canSatisfyCompletion(requiresVerifiedOutcome) -> DeviceCompletionGuardDecision.ALLOW
        !retryAlreadyUsed -> DeviceCompletionGuardDecision.RETRY
        else -> DeviceCompletionGuardDecision.REPLAN
    }
}
