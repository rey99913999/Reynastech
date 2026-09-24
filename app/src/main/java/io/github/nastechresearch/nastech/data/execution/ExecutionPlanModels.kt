package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ExecutionLevel {
    LOCAL_DETERMINISTIC,
    LOCAL_RULES,
    VISION,
    LLM,
}

@Serializable
enum class ExecutionStepKind {
    TOOL,
    VERIFY,
    RETURN,
    DECIDE,
    RECOVER,
}

@Serializable
enum class ExecutionStepStatus {
    SUCCESS,
    FAILED,
    SKIPPED,
    APPROVAL_REQUIRED,
    REPLAN_REQUIRED,
}

@Serializable
enum class ExecutionAgentRole {
    PLANNER,
    VISION,
    RECOVERY,
    VERIFIER,
}

@Serializable
data class StructuredExecutionPlan(
    val goal: String,
    val steps: List<StructuredExecutionStep>,
    val logicalPlan: String? = null,
    val taskId: String? = null,
    val version: Int = 1,
    val requiredCapabilities: Set<String> = emptySet(),
    val requiredConstraints: Set<String> = emptySet(),
)

@Serializable
data class StructuredExecutionStep(
    val id: String,
    val kind: ExecutionStepKind = ExecutionStepKind.TOOL,
    val level: ExecutionLevel = ExecutionLevel.LOCAL_DETERMINISTIC,
    val toolName: String? = null,
    val args: JsonObject = JsonObject(emptyMap()),
    val logicalGoal: String? = null,
    val expectedResult: String? = null,
    val requiresVerification: Boolean = false,
    val approvalRequired: Boolean = true,
    val taskStepId: String? = null,
    val alternativeToolNames: List<String> = emptyList(),
    val deviceAction: DeviceAction? = null,
    val preconditions: List<DevicePostcondition> = emptyList(),
    val postconditions: List<DevicePostcondition> = emptyList(),
    val requiredConstraints: Set<String> = emptySet(),
)

@Serializable
data class ExecutionStepResult(
    val stepId: String,
    val taskStepId: String? = null,
    val toolName: String? = null,
    val status: ExecutionStepStatus,
    val attempts: Int = 0,
    val output: String? = null,
    val error: String? = null,
    val checkpointId: String? = null,
    val lifecycle: DeviceActionLifecycle? = null,
    val observationSource: String? = null,
    val recoveryAttempts: Int = 0,
)

@Serializable
data class PartialReplanRequest(
    val goal: String,
    val completedSteps: List<ExecutionStepResult>,
    val failedStepId: String,
    val failureReason: String,
    val remainingSteps: List<StructuredExecutionStep>,
    val instruction: String =
        "Replan only the remaining steps. Do not regenerate or repeat completed steps unless verification proves they are invalid.",
)

@Serializable
data class ExecutionPlanResult(
    val goal: String,
    val status: String,
    val completedCount: Int,
    val attemptedCount: Int,
    val results: List<ExecutionStepResult>,
    val partialReplan: PartialReplanRequest? = null,
    val llmCallsAvoided: Int = 0,
)

data class ExecutionRoleBinding(
    val role: ExecutionAgentRole,
    val agentId: String? = null,
)

fun interface ExecutionRoleResolver {
    fun resolve(role: ExecutionAgentRole): ExecutionRoleBinding?
}
