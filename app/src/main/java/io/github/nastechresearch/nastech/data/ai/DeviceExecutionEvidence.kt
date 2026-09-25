package io.github.nastechresearch.nastech.data.ai

import io.github.nastechresearch.nastech.data.execution.ExecutionPlanResult
import io.github.nastechresearch.nastech.data.execution.ExecutionToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart

/**
 * Turn-local evidence used by the device completion guard.
 *
 * This state is intentionally ephemeral and contains no persistence. It records actual runtime
 * execution rather than inferring device activity from the model's current tool list.
 */
internal data class DeviceExecutionEvidence(
    val deviceToolExecutedThisTurn: Boolean = false,
    val deviceToolSucceededThisTurn: Boolean = false,
    val devicePlanExecutedThisTurn: Boolean = false,
) {
    val hasSuccessfulExecutionEvidence: Boolean
        get() = deviceToolSucceededThisTurn || devicePlanExecutedThisTurn
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
            return if (result != null && result.completedCount > 0) {
                current.copy(devicePlanExecutedThisTurn = true)
            } else {
                current
            }
        }

        if (!ExecutionToolRegistry.isCoreDeviceTool(toolName)) return current

        val failed = outputIndicatesFailure(output, json)
        return current.copy(
            deviceToolExecutedThisTurn = true,
            deviceToolSucceededThisTurn =
                current.deviceToolSucceededThisTurn || !failed,
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
                    val successExplicitlyFalse = (obj["success"] as? JsonPrimitive)?.booleanOrNull == false
                    val dispatchExplicitlyFalse = (obj["dispatch_succeeded"] as? JsonPrimitive)?.booleanOrNull == false
                    val status = (obj["status"] as? JsonPrimitive)?.contentOrNull?.uppercase()
                    val failedStatus = status in setOf("FAILED", "FAILURE", "ERROR", "REJECTED")
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
    ): DeviceCompletionGuardDecision = when {
        !isDeviceTask -> DeviceCompletionGuardDecision.ALLOW
        evidence.hasSuccessfulExecutionEvidence -> DeviceCompletionGuardDecision.ALLOW
        !retryAlreadyUsed -> DeviceCompletionGuardDecision.RETRY
        else -> DeviceCompletionGuardDecision.REPLAN
    }
}
